(ns jiraph.stm-layer
  (:use jiraph.layer
        retro.core
        [useful :only [update adjoin]]))

(defn- conj-set
  "Conj onto collection ensuring it is a set."
  [coll item]
  (conj (set coll) item))

(defn- update-incoming [meta layer to-id from-id doing]
  (let [f (case doing :add conj-set :drop disj)]
    (adjoin
     (when *revision*
       (update-in
        meta
        [to-id :revs *revision* :in]
        (fn [old] (into (f old from-id) (get-in meta [to-id :in])))))
     (update-in meta [to-id :in] f from-id))))

(defn- get-meta [layer id] (get @(:meta layer) id))

(def reverse-compare (comp #(- %) compare))

(def reverse-sorted-map
     ^{:doc "Returns a sorted-map that is sorted by its keys from highest to lowest."}
     (partial sorted-map-by reverse-compare))

(defn- create-rev
  "Create a revision."
  [layer id node]
  (alter (:meta layer) adjoin {id {:all-revs [*revision*]}})
  (get-in
   (alter (:meta layer) assoc-in [id :revs *revision*] node)
   [id :revs *revision*]))

(defn- append-rev
  "Modify a revision."
  [layer id node]
  (get-in
   (alter (:meta layer)
          adjoin
          (adjoin
           {id {:revs {*revision*
                       (binding [*revision* nil]
                         (dissoc (get-node layer id) :id))}
                :all-revs [*revision*]}}
           {id {:revs {*revision* node}}}))
   [id :revs *revision*]))

(defn- wipe-revs
  "Collapse all of an id's revisions."
  [layer id] (alter (:meta layer) assoc-in [id :revs] (reverse-sorted-map)))

(defn- initiate-revs
  "Create an initial sorted map for :revs if one doesn't yet exist."
  [layer id]
  (when-not (:revs (get-meta layer id)) (wipe-revs layer id)))

(defn- get-rev
  "Fetch a particular revision of an id."
  [layer id]
  (->> *revision* (subseq (:revs (get-meta layer id)) >=) first second))

(defn- make-node [attrs]
  (let [attrs (dissoc attrs :id)]
    (if *revision*
      (assoc attrs :rev *revision*)
      (dissoc attrs :rev))))

(defrecord STMLayer [data meta properties]
  jiraph.layer/Layer

  (open [layer] nil)
  (close [layer] nil)
  (sync! [layer] nil)

  (truncate! [layer]
    (dosync (ref-set data {})
            (ref-set meta {})
            (ref-set properties {})))

  (node-count [layer] (count @data))

  (node-ids [layer] (keys @data))

  (get-property [layer key] (get @properties key))

  (set-property! [layer key val] (dosync ((alter properties assoc key val) key)))

  (get-node [layer id]
    (when-let [rev (if *revision* (get-rev layer id) (get @data id))]
      (assoc rev :id id)))

  (node-exists? [layer id] (contains? @data id))

  (add-node! [layer id attrs]
    (let [node (make-node attrs)]
      (when-not (node-exists? layer id)
        (dosync
         (initiate-revs layer id)
         (let [id-meta (get-meta layer id)]
           (when-not (:in id-meta)
             (alter meta assoc id (assoc id-meta :in #{}))))
         (when *revision* (append-rev layer id node))
         (alter data into {id node}))
        node)))

  (update-node! [layer id f args]
    (dosync
     (initiate-revs layer id)
     (let [old (get-node layer id)
           new (make-node (apply f old args))]
       (when *revision* (create-rev layer id new))
       (alter data assoc id new)
       (map #(dissoc % :id) [old new]))))

  (append-node! [layer id attrs]
    (update-node! layer id adjoin [attrs])
    (make-node attrs))

  (get-revisions [layer id] (-> layer (get-meta id) :all-revs))

  (get-incoming [layer id]
    (get-in
     (get-meta layer id)
     (if *revision*
       [:revs *revision* :in]
       [:in])))

  (add-incoming! [layer id from-id]
    (dosync (alter meta update-incoming layer id from-id :add)))

  (drop-incoming! [layer id from-id]
    (dosync (alter meta update-incoming layer id from-id :drop)))

  retro.core/Revisioned

  (get-revision [layer] (get-property layer :rev))

  (set-revision! [layer rev] (set-property! layer :rev rev))

  retro.core/WrappedTransactional

  (txn-wrap [layer f] #(dosync (f))))

(defn make []
  (STMLayer. (ref {}) (ref {}) (ref {})))