(ns jiraph.core
  (:use [useful.utils :only [returning memoize-deref map-entry adjoin invoke]]
        [useful.map :only [update into-map]]
        [useful.macro :only [macro-do]]
        slingshot.slingshot)
  (:require [jiraph.graph :as graph]
            [jiraph.layer :as layer]
            [clojure.string :as s]
            [retro.core :as retro])
  (:import java.io.IOException))

(def ^{:dynamic true} *graph*    nil)
(def ^{:dynamic true} *revision* nil)

(defn get-layer [layer-name]
  (when-let [layer (get *graph* layer-name)]
    (retro/at-revision layer *revision*)))

(defn layer
  "Return the layer for a given name from *graph*."
  [layer-name]
  (if *graph*
    (or (get-layer layer-name)
        (throw (IOException.
                (format "cannot find layer %s in open graph" layer-name))))
    (throw (IOException. (format "attempt to use a layer without an open graph")))))

(defn layer-entries
  ([] *graph*)
  ([type] (for [[name layer] *graph*
                :when (seq (graph/schema layer type))]
            (map-entry name (retro/at-revision layer *revision*)))))

(defn layer-names
  "Return the names of all layers in the current graph."
  ([]     (keys *graph*))
  ([type] (map key (layer-entries type))))
(defn layers
  "Return all layers in the current graph."
  ([]     (map layer (layer-names)))
  ([type] (map val (layer-entries type))))

(defn as-layer-map
  "Create a map of {layer-name, layer} pairs from the input. A keyword yields a
   single-entry map, an empty sequence operates on all layers, and a sequence of
   keywords causes each to be resolved to its actual layer."
  [layers]
  (if (keyword? layers)
    {layers (layer layers)}
    (let [names (if (empty? layers)
                  (keys *graph*)
                  (filter (set layers) (keys *graph*)))]
      (into {} (for [name names]
                 [name (layer name)])))))

(defmacro dotxn [layer-name & forms]
  `(graph/dotxn [(layer ~layer-name)]
     (do ~@forms)))

(macro-do [fname]
  (let [impl (symbol "jiraph.graph" (name fname))]
    `(defmacro ~fname ~'[actions]
       (list '~impl ~'actions)))
  txn* txn
  unsafe-txn* unsafe-txn)

(defmacro txn-> [layer-name & forms]
  (let [name (gensym 'name)]
    `(let [~name ~layer-name]
       (do (graph/txn (graph/compose ~@(for [form forms]
                                         `(-> ~name ~form))))
           ~name))))

(letfn [(symbol [& args]
          (apply clojure.core/symbol (map name args)))]
  (defn- graph-impl [name]
    (let [impl-name (symbol 'jiraph.graph name)
          var (resolve impl-name)
          meta (-> (meta var)
                   (select-keys [:arglists :doc :macro :dynamic]))]
          {:varname impl-name
           :var var
           :meta meta ;; for use by functions
           :func @var})))

(defn- fix-meta [meta]
  (update meta :arglists (partial list 'quote)))

;; define forwarders to resolve keyword layer-names in *graph*
(macro-do [name]
  (let [{:keys [varname meta]} (graph-impl name)]
    `(def ~(with-meta name (fix-meta meta))
       (fn ~name [layer-name# & args#]
         (apply ~varname (layer layer-name#) args#))))
  update-in-node  update-node  dissoc-node  assoc-node  assoc-in-node
  update-in-node! update-node! dissoc-node! assoc-node! assoc-in-node!
  node-id-seq node-seq node-id-subseq node-id-rsubseq node-subseq node-rsubseq
  fields node-valid? verify-node
  get-node find-node query-in-node get-in-node get-edges get-edge
  get-revisions node-history get-incoming get-incoming-map)

;; these point directly at jiraph.graph functions, without layer-name resolution
;; or any indirection, because they can't meaningfully work with layer names but
;; we don't want to make the "simple" uses of jiraph.core have to mention
;; jiraph.graph at all
(doseq [name '[wrap-caching with-caching]]
  (let [{:keys [func meta]} (graph-impl name)]
    (intern *ns* (with-meta name meta) func)))

;; operations on a list of layers
(macro-do [name]
  (let [{:keys [varname meta]} (graph-impl name)]
    `(def ~(with-meta name (fix-meta meta))
       (fn ~name [& layers#]
         (apply ~varname (vals (as-layer-map layers#))))))
  open close touch sync! optimize! truncate!)

(defmacro at-revision
  "Execute the given forms with the current revision set to rev. Can be used to mark changes with a
   given revision, or read the state at a given revision."
  [rev & forms]
  `(binding [*revision* ~rev]
      ~@forms))

(defn set-graph! [graph]
  (alter-var-root #'*graph* (constantly graph)))

(defmacro with-graph [graph & forms]
  `(let [graph# ~graph]
     (binding [*graph* graph#]
       (try (open)
            ~@forms
            (finally (close))))))

(defmacro with-graph! [graph & forms]
  `(let [graph# *graph*]
     (set-graph! ~graph)
     (try (open)
          ~@forms
          (finally (close)
                   (set-graph! graph#)))))

(letfn [(all-revisions [layers]
          (or (seq (remove #{Double/POSITIVE_INFINITY}
                           (map retro/max-revision
                                (vals (as-layer-map layers)))))
              [0]))]
  (defn current-revision
    "The minimum revision on all specified layers, or all layers if none are specified."
    [& layers]
    (apply min (all-revisions layers)))
  (defn uncommitted-revision
    "The maximum revision on all specified layers, or all layers if none are specified."
    [& layers]
    (apply max (all-revisions layers))))

(defn layer-exists?
  "Does the named layer exist in the current graph?"
  [layer-name]
  (contains? *graph* layer-name))

(defn schema-by-layer
  "Get the schema for a node-type across all layers, indexed by layer.

   Optionally you may pass in a graph to use instead of *graph*, to allow
   things like filtering which layers are included in the schema."
  ([type] (schema-by-layer type *graph*))
  ([type graph]
     (into {}
           (for [[layer-name layer] graph
                 :let [schema (graph/schema layer type)]
                 :when (seq schema)]
             [layer-name (:fields schema)]))))

(defn schema-by-attr
  "Get the schema for a node-type across all layers, indexed by attribute name.

   Optionally you may pass in a graph to use instead of *graph*, to allow
   things like filtering which layers are included in the schema."
  ([type] (schema-by-attr type *graph*))
  ([type graph]
     (apply merge-with conj {}
            (for [[layer-name attrs] (schema-by-layer type graph)
                  [attr type] attrs]
              {attr {layer-name type}}))))
