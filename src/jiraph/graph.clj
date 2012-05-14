(ns jiraph.graph
  (:use [useful.map :only [filter-keys-by-val map-vals-with-keys update dissoc-in* assoc-in* update-in*]]
        [useful.utils :only [memoize-deref adjoin into-set map-entry verify]]
        [useful.fn :only [any fix to-fix fixing given]]
        [useful.seq :only [merge-sorted indexed]]
        [useful.macro :only [with-altered-var]]
        [clojure.string :only [split join]]
        [ego.core :only [type-key]]
        [jiraph.wrapper :only [*read-wrappers* *write-wrappers*]]
        [useful.experimental :only [wrap-multiple]]
        [clojure.core.match :only [match]]
        (ordered [set :only [ordered-set]]
                 [map :only [ordered-map]]))
  (:require [jiraph.layer :as layer]
            [retro.core :as retro]))

(def ^{:dynamic true} *skip-writes* false)
(def ^{:dynamic true, :doc "All layers that are currently in read-only mode. You should probably not modify this directly; prefer using with-readonly instead."}
  *read-only* #{})

(def ^{:private true :dynamic true} *compacting* false)
(def ^{:private true :dynamic true} *use-outer-cache* nil)
(def ^{:private true}               write-count (atom 0))

(defn edges
  "Gets edges from a node. Returns all edges, including deleted ones."
  [node]
  (:edges node))

(defn filter-edge-ids [pred node]
  (filter-keys-by-val pred (edges node)))

(defn filter-edges [pred node]
  (select-keys (edges node) (filter-edge-ids pred node)))

(defn read-only? [layer]
  (*read-only* layer))

(defmacro with-readonly [layers & body]
  `(binding [*read-only* (into *read-only* ~layers)]
     ~@body))

(defn- refuse-readonly [layers]
  (doseq [layer layers]
    (if (read-only? layer)
      (throw (IllegalStateException.
              (format "Can't write to %s in read-only mode" layer)))
      (retro/modify! layer))))

(defmacro with-transaction
  "Execute forms within a transaction on the specified layers."
  [layer & forms]
  `(let [layer# ~layer]
     (retro/with-transaction layer#
       (do ~@forms
           layer#))))

(def abort-transaction retro/abort-transaction)

(defn sync!
  "Flush changes for the specified layers to the storage medium."
  [& layers]
  (doseq [layer layers]
    (layer/sync! layer)))

(defn optimize!
  "Optimize the underlying storage for the specified layers."
  [& layers]
  (doseq [layer layers]
    (layer/optimize! layer)))

(defn truncate!
  "Remove all nodes from the specified layers."
  [& layers]
  (refuse-readonly layers)
  (doseq [layer layers]
    (layer/truncate! layer)))

(defn node-id-seq
  "Return a lazy sequence of all node ids in this layer."
  [layer]
  (layer/node-id-seq layer))

(defn node-seq
  "Return a lazy sequence of all nodes in the layer."
  [layer]
  (layer/node-seq layer))

(defn- meta-keyseq [layer keys]
  (let [[first & more] keys]
    (if (= :meta first)
      (if-let [[second & more] (seq more)]
        (if (keyword? second)
          (list* (layer/meta-key layer "_layer")
                 (name second)
                 more)
          (cons (layer/meta-key layer second)
                more))
        [(layer/meta-key layer "_layer")])
      keys)))

(declare merge-ids merge-head merge-position node-deleted?)

(defn- edges-keyseq [keyseq]
  (match keyseq
    [:meta (k :guard keyword?)] nil ; layer-meta
    [_]                         [:edges]
    [_ :edges]                  []
    [:meta _]                   [:incoming]
    [:meta _ :incoming]         []))

(defn- merge-edges [id-layer keyseq edges-seq]
  (let [incoming? (= :meta (first keyseq))]
    (letfn [(edge-sort-order [i id edge]
              (let [pos (merge-position id-layer id)]
                (if incoming?
                  [pos i]
                  [(:deleted edge) i pos])))]
      (->> (for [[i edges] (indexed (reverse edges-seq))
                 [id edge] edges]
             (let [head-id (or (merge-head id-layer id) id)]
               [(edge-sort-order i id edge)
                [head-id edge]]))
           (sort-by first #(compare %2 %1))
           (map second)
           (reduce (if incoming?
                     (fn [edges [id exists?]]
                       (assoc edges id exists?))
                     (fn [edges [id edge]]
                       (if (and (:deleted edge)
                                (get edges id))
                         edges
                         (adjoin edges {id edge}))))
                   {})))))

(defn merge-nodes [id-layer keyseq nodes]
  (if-let [ks (edges-keyseq keyseq)]
    (let [edges (merge-edges id-layer
                             keyseq
                             (map #(get-in % ks) nodes))]
      (-> (reduce adjoin nil
                  (map #(dissoc-in* % ks) nodes))
          (given (seq edges)
                 (assoc-in* ks edges))))
    (reduce adjoin nil nodes)))

(defn- deleted-edge-keyseq [keyseq]
  (match keyseq
    [:meta (k :guard keyword?)] nil ; layer-meta
    [_ :edges _]                [:deleted]
    [_ :edges _ :deleted]       []
    [:meta _ :incoming]         []))

(defn- deleted-node-keyseq [keyseq]
  (match keyseq
    [:meta (k :guard keyword?)] nil ; layer-meta
    [_]                         [:deleted]
    [_ :deleted]                []))

(letfn [(exists?  [id-layer id exists]  (and exists (not (node-deleted? id-layer id))))
        (deleted? [id-layer id deleted] (or deleted (node-deleted? id-layer id) deleted))]

  (defn mark-edges-deleted [id-layer incoming? keyseq node]
    (if-let [ks (edges-keyseq keyseq)]
      (update-in* node ks fixing map? map-vals-with-keys
                  (if incoming?
                    (partial exists? id-layer)
                    (fn [id edge]
                      (update edge :deleted (partial deleted? id-layer id)))))
      (if-let [ks (deleted-edge-keyseq keyseq)]
        (update-in* node ks
                    (if (= :meta (first keyseq))
                      (partial exists?  id-layer (second keyseq))
                      (partial deleted? id-layer (first  keyseq))))
        node)))

  (defn mark-deleted [id-layer keyseq node]
    (->> (if-let [ks (deleted-node-keyseq keyseq)]
           (update-in* node ks (partial deleted? id-layer (first keyseq)))
           node)
         (mark-edges-deleted id-layer (= :meta (first keyseq)) keyseq))))

(let [sentinel (Object.)]
  (defn ^{:dynamic true} get-node
    "Fetch a node's data from this layer."
    [layer id & [not-found]]
    (if-let [id-layer (layer/id-layer layer)]
      (let [nodes (->> (reverse (merge-ids id-layer id))
                       (map #(layer/get-node layer % sentinel))
                       (remove #(identical? % sentinel)))]
        (if (empty? nodes)
          not-found
          (->> nodes
               (merge-nodes  id-layer [id])
               (mark-deleted id-layer [id]))))
      (layer/get-node layer id not-found)))

  (defn find-node
    "Get a node's data along with its id."
    [layer id]
    (let [node (get-node layer id sentinel)]
      (when-not (identical? node sentinel)
        (map-entry id node)))))

(defn- query [layer keyseq not-found f args]
  (let [keyseq (meta-keyseq layer keyseq)]
    (if-let [query-fn (layer/query-fn layer keyseq not-found f)]
      (apply query-fn args)
      (if-let [query-fn (layer/query-fn layer keyseq not-found identity)]
        (apply f (query-fn) args)
        (let [[id & keys] keyseq
              node (layer/get-node layer id nil)]
          (apply f (get-in node keys) args))))))

(defn- expand-keyseq-merges
  ([id-layer keyseq]
     (if (= :meta (first keyseq))
       (map (partial cons (first keyseq))
            (expand-keyseq-merges id-layer (rest keyseq) :incoming))
       (expand-keyseq-merges id-layer keyseq :edges)))
  ([id-layer keyseq edge-key]
     (let [[from-id attr to-id & tail] keyseq
           from-ids (reverse (merge-ids id-layer from-id))]
       (if (and to-id (= edge-key attr))
         (for [from-id from-ids
               to-id   (reverse (merge-ids id-layer to-id))]
           `(~from-id edge-key ~to-id ~@tail))
         (for [from-id from-ids]
           (cons from-id (rest keyseq)))))))

(defn- merge-fn [id-layer keyseq f]
  (when (seq keyseq)
    (condp contains? f
      #{seq  subseq}  (partial apply merge-sorted <)
      #{rseq rsubseq} (partial apply merge-sorted >)
      (when (= identity f)
        (partial merge-nodes id-layer keyseq)))))

(defn- query-merged [layer keyseq not-found f args]
  (let [id-layer (layer/id-layer layer)]
    (if-let [merge-data (merge-fn id-layer keyseq f)]
      (let [keyseqs (expand-keyseq-merges id-layer keyseq)]
        (merge-data (for [keyseq keyseqs]
                      (query layer keyseq not-found f args))))
      (apply f (query-merged layer keyseq not-found identity []) args))))

(defn query-in-node*
  "Fetch data from inside a node, replacing it with not-found if it is missing,
   and immediately call a function on it."
  [layer keyseq not-found f & args]
  (let [keyseq (vec keyseq)]
    (if-let [id-layer (layer/id-layer layer)]
      (->> (query-merged layer keyseq not-found f args)
           (mark-deleted id-layer keyseq))
      (query layer keyseq not-found f args))))

(defn query-in-node
  "Fetch data from inside a node and immediately call a function on it."
  [layer keyseq f & args]
  (apply query-in-node* layer keyseq nil f args))

(defn get-in-node
  "Fetch data from inside a node."
  [layer keyseq & [not-found]]
  (query-in-node* layer keyseq not-found identity))

(defn get-edges
  "Fetch the edges for a node on this layer."
  [layer id]
  (get-in-node layer [id :edges] nil))

(defn get-in-edge
  "Fetch data from inside an edge."
  [layer [id to-id & keys] & [not-found]]
  (get-in-node layer (list* id :edges to-id keys) not-found))

(defn get-edge
  "Fetch an edge from node with id to to-id."
  [layer id to-id & [not-found]]
  (get-in-edge layer [id to-id] not-found))

(declare update-in-node!)

(defn- changed-edges [old-edges new-edges]
  (reduce (fn [edges [edge-id edge]]
            (let [was-present (not (:deleted edge))
                  is-present  (when-let [e (get edges edge-id)] (not (:deleted e)))]
              (if (= was-present is-present)
                (dissoc edges edge-id)
                (assoc-in edges [edge-id :deleted] was-present))))
          new-edges old-edges))

(defn- update-edges! [layer [id & keys] old new]
  (when (layer/manage-incoming? layer)
    (doseq [[edge-id {:keys [deleted]}]
            (apply changed-edges ;; TODO does this to-fix work? could it just be an if?
                   (map (comp :edges (partial assoc-in* {} keys))
                        [old new]))]
      ((if deleted layer/drop-incoming! layer/add-incoming!)
       layer edge-id id))))

(defn- update-changelog! [layer node-id]
  (when (layer/manage-changelog? layer)
    (when-let [rev-id (retro/current-revision layer)]
      (update-in-node! layer [:meta :revision-id] (constantly rev-id))
      (when node-id
        (update-in-node! layer [:meta node-id "affected-by"] conj rev-id)
        (update-in-node! layer [:meta :changed-ids rev-id] conj node-id)))))

(defn update-in-node!
  "Update the subnode at keyseq by calling function f with the old value and any supplied args."
  [layer keyseq f & args]
  (refuse-readonly [layer])
  (with-transaction layer
    (when-not *skip-writes*
      (let [keys (meta-keyseq layer keyseq)
            update-meta! (if (identical? keys keyseq) ; not a meta
                           (fn [keys old new]
                             (update-edges! layer keys old new)
                             (update-changelog! layer (first keys)))
                           (constantly nil))]
        (let [updater (partial layer/update-fn layer)
              keys (seq keys)]
          (if-let [update! (updater keys f)]
            ;; maximally-optimized; the layer can do this exact thing well
            (let [{:keys [old new]} (apply update! args)]
              (update-meta! keys old new))
            (if-let [update! (and keys ;; don't look for assoc-in of empty keys
                                  (updater (butlast keys) assoc))]
              ;; they can replace this sub-node efficiently, at least
              (let [old (get-in-node layer keys)
                    new (apply f old args)]
                (update! (last keys) new)
                (update-meta! keys old new))

              ;; need some special logic for unoptimized top-level assoc/dissoc
              (if-let [update! (and (not keys) ({assoc  layer/assoc-node!
                                                 dissoc layer/dissoc-node!} f))]
                (let [[id new] args
                      old (when (layer/manage-incoming? layer)
                            (get-node layer id))]
                  (apply update! layer args)
                  (update-meta! [id] old new))
                (let [[id & keys] keys
                      old (get-node layer id)
                      new (if keys
                            (apply update-in old keys f args)
                            (apply f old args))]
                  (layer/assoc-node! layer id new)
                  (update-meta! [id] old new))))))
        (swap! write-count inc)))))

(defn update-in-node
  "Functional version of update-in-node! for use in a transaction."
  [layer keys f & args]
  (retro/enqueue layer #(apply update-in-node! % keys f args)))

(do (defn update-node!
      "Update a node by calling function f with the old value and any supplied args."
      [layer id f & args]
      (apply update-in-node! layer [id] f args))
    (defn update-node
      "Functional version of update-node! for use in a transaction."
      [layer id f & args]
      (retro/enqueue layer #(apply update-node! % id f args))))

(do (defn dissoc-node!
      "Remove a node from a layer (incoming links remain)."
      [layer id]
      (update-in-node! layer [] dissoc id))
    (defn dissoc-node
      "Functional version of update-node! for use in a transaction."
      [layer id]
      (retro/enqueue layer #(dissoc-node! % id))))

(do (defn assoc-node!
      "Create or set a node with the given id and value."
      [layer id value]
      (update-in-node! layer [] assoc id value))
    (defn assoc-node
      "Functional version of assoc-node! for use in a transaction."
      [layer id value]
      (retro/enqueue layer #(assoc-node! % id value))))

(do (defn assoc-in-node!
      "Set attributes inside of a node."
      [layer keys value]
      (let [keyseq (vec (meta-keyseq layer keys))]
        (update-in-node! layer (pop keyseq) assoc (peek keyseq) value)))
    (defn assoc-in-node
      "Functional version of assoc-in-node! for use in a transaction."
      [layer keys value]
      (retro/enqueue layer #(assoc-in-node! % keys value))))

(defn schema
  [layer id-or-type]
  (let [id (fix id-or-type
                keyword? #(str (name %) "-1"))]
    (layer/schema layer id)))

(defn fields
  "Return a map of fields to their metadata for the given layer."
  ([layer id-or-type]
     (keys (:fields (schema layer id-or-type)))))

(defn edges-valid? [layer edges]
  (or (not (layer/single-edge? layer))
      (> 2 (count edges))))

(defn types-valid? [layer id node]
  (let [types (get-in-node layer [:meta :types])]
    (or (not types)
        (let [node-type (type-key id)]
          (and (contains? types node-type)
               (every? (partial contains? (types node-type))
                       (map type-key (keys (edges node)))))))))

(defn node-valid?
  "Check if the given node is valid for the specified layer."
  [layer id attrs]
  (and (or (nil? id) (types-valid? layer id attrs))
       (edges-valid? layer attrs)
       (layer/node-valid? layer attrs)))

(defn verify-node
  "Assert that the given node is valid for the specified layer."
  [layer id attrs]
  (when id
    (assert (types-valid? layer id attrs)))
  (assert (edges-valid? layer attrs))
  (layer/verify-node layer id attrs)
  nil)

(defn ^{:dynamic true} get-revisions
  "Return a seq of all revisions with data for this node."
  [layer id]
  (layer/get-revisions layer id))

(extend-type Object
  layer/Meta
  (meta-key [layer key]
    (str "_" key))
  (meta-key? [layer key]
    (.startsWith ^String key "_"))

  layer/ChangeLog
  (get-revisions [layer id]
    (get-in-node layer [:meta id "affected-by"]))
  (get-changed-ids [layer rev]
    (get-in-node layer [:meta :changed-ids rev]))
  (max-revision [layer]
    (-> layer
        (retro/at-revision nil)
        (get-in-node [:meta :revision-id])
        (or 0)))

  layer/Incoming
  ;; default behavior: use node meta with special prefix to track incoming edges
  (get-incoming [layer id]
    (get-in-node layer [:meta id :incoming]))
  (add-incoming! [layer id from-id]
    (update-in-node! layer [:meta id :incoming] adjoin (ordered-map from-id true)))
  (drop-incoming! [layer id from-id]
    (update-in-node! layer [:meta id :incoming] adjoin (ordered-map from-id false))))

(defn ^{:dynamic true} get-incoming
  "Return the ids of all nodes that have incoming edges on this layer to this node (excludes edges marked :deleted)."
  [layer id]
  (let [incoming (layer/get-incoming layer id)]
    (if (set? incoming)
      incoming
      (into (ordered-set)
            (for [[k v] incoming
                  :when v]
              k)))))

(defn ^{:dynamic true} get-incoming-map
  "Return a map of incoming edges, where the value for each key indicates whether an edge is
   incoming from that node."
  [layer id]
  (let [incoming (layer/get-incoming layer id)]
    (if (map? incoming)
      incoming
      (into (ordered-map)
            (for [node incoming]
                 [node true])))))

(defn wrap-bindings
  "Wrap the given function with the current graph context."
  [f]
  (bound-fn ([& args] (apply f args))))

(defn wrap-caching
  "Wrap the given function with a new function that memoizes read methods. Nested wrap-caching calls
   are collapsed so only the outer cache is used."
  [f]
  (fn []
    (if *use-outer-cache*
      (f)
      (binding [*use-outer-cache* true
                get-node          (memoize-deref [write-count] get-node)
                get-incoming      (memoize-deref [write-count] get-incoming)
                get-revisions     (memoize-deref [write-count] get-revisions)]
        (f)))))

(defmacro with-caching
  "Enable caching for the given forms. See wrap-caching."
  ([form]
     `((wrap-caching (fn [] ~form))))
  ([cache form]
     `((fix (fn [] ~form) (boolean ~cache) wrap-caching))))

(defn merge-head
  "Returns the head-id that a specific node is merged into."
  [id-layer id]
  (:head (get-node id-layer id)))

(def ^{:doc
   "Returns the list of node ids that are merged into a specific node. Provided only for convenience,
   identical to calling get-incoming on the identity-layer."}
  merged-into get-incoming)

(defn node-deleted?
  "Returns true if the specified node has been deleted."
  [id-layer id]
  (:deleted (get-node id-layer id)))

(defn merge-ids
  "Returns a list of node ids in the merge chain of id. Always starts with the head-id,
   followed by tail-ids in the reverse order they were merged."
  [id-layer id]
  (if-let [head-id (merge-head id-layer id)]
    `[~head-id ~@(merged-into id-layer head-id)]
    [id]))

(defn merge-position
  "Returns the position in the merge chain for a given id, 0 for the head."
  [id-layer id]
  (let [node (get-node id-layer id)]
    (when-let [head (:head node)]
      (if (= head id)
        0
        (get-in node [:edges head :position])))))

(defn merge-node
  "Functional version of merge-node!"
  [id-layer head-id tail-id]
  (verify (not= head-id tail-id)
          (format "cannot merge %s into itself" tail-id))
  (verify (= (type-key head-id) (type-key tail-id))
          (format "cannot merge %s into %s because they are not the same type" tail-id head-id))
  (let [head        (get-node id-layer head-id)
        merge-count (or (:merge-count head) 0)
        head-merged (fix (:head head)               #{head-id} nil)
        tail-merged (fix (merge-head id-layer tail-id) #{tail-id} nil)]
    (if (= head-id tail-merged)
      (printf "warning: %s is already merged into %s\n" tail-id head-id)
      (do
        (verify (not head-merged)
                (format "cannot merge %s into %s because %2$s is already merged into %s"
                        tail-id head-id head-merged))
        (verify (not tail-merged)
                (format "cannot merge %s into %s because %1$s is already merged into %s"
                        tail-id head-id tail-merged))
        (let [revision (retro/current-revision id-layer)
              tail-ids (cons tail-id (merged-into id-layer tail-id))]
          (reduce (fn [layer [pos id]]
                    (update-node layer id adjoin
                                 {:head head-id
                                  :edges {head-id {:revision revision
                                                   :position (+ 1 pos merge-count)}}}))
                  (update-node id-layer head-id adjoin
                               {:head head-id
                                :merge-count (+ merge-count (count tail-ids))})
                  (indexed tail-ids)))))))

(defn merge-node!
  "Merge tail node into head node, merging all nodes that are currently merged into tail as well."
  [id-layer head-id tail-id]
  (retro/dotxn id-layer
    (merge-node id-layer head-id tail-id)))

(defn- delete-merges-after
  "Delete all merges from node that happened at or after revision."
  [id-layer revision id node]
  (update-node id-layer id adjoin
               {:edges
                (into {} (for [[to-id edge] (:edges node)
                               :when (and (not (:deleted edge))
                                          (<= revision (:revision edge)))]
                           [to-id {:deleted true}]))}))

(defn unmerge-node
  "Functional version of unmerge-node!"
  [id-layer head-id tail-id]
  (let [tail      (get-node id-layer tail-id)
        merge-rev (get-in tail [:edges head-id :revision])
        tail-ids  (merged-into id-layer tail-id)
        head-ids  (merged-into id-layer head-id)]
    (verify merge-rev ;; revision of the original merge of tail into head
            (format "cannot unmerge %s from %s because they aren't merged" tail-id head-id))
    (reduce (fn [layer id]
              (-> layer
                  (delete-merges-after merge-rev id (get-node id-layer id))
                  (update-node id adjoin {:head tail-id})))
            (-> id-layer
                (delete-merges-after merge-rev tail-id tail)
                (update-node tail-id adjoin {:head nil})
                (given (= (count head-ids) (inc (count tail-ids)))
                       (update-node head-id adjoin {:head nil})))
            tail-ids)))

(defn unmerge-node!
  "Unmerge tail node from head node, taking all nodes that were previously merged into tail with it."
  [id-layer head-id tail-id]
  (retro/dotxn id-layer
    (unmerge-node id-layer head-id tail-id)))

(defn delete-node
  "Functional version of delete-node!"
  [id-layer id]
  (update-node id-layer id adjoin {:deleted true}))

(defn delete-node!
  "Mark the specified node as deleted."
  [id-layer id]
  (retro/dotxn id-layer
    (delete-node id-layer id)))

(defn undelete-node
  "Functional version of undelete-node!"
  [id-layer id]
  (update-node id-layer id adjoin {:deleted false}))

(defn undelete-node!
  "Mark the specified node as not deleted."
  [id-layer id]
  (retro/dotxn id-layer
    (undelete-node id-layer id)))

(comment these guys need to be updated and split up once we've figured out our
         plan for wrapping, and also the split between graph and core.
  (wrap-multiple #'*read-wrappers*
                 node-id-seq get-property current-revision
                 get-node node-exists? get-incoming)
  (wrap-multiple #'*write-wrappers*
                 update-node! optimize! truncate! set-property! update-property!
                 sync! add-node! append-node! assoc-node! compact-node! delete-node!))
