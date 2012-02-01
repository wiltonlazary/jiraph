(ns jiraph.core
  (:require [jiraph.graph :as graph]
            [jiraph.layer :as layer]
            [clojure.string :as s]
            [retro.core :as retro])
  (:use     [useful.utils :only [returning memoize-deref map-entry adjoin]]
            [useful.map :only [update into-map]]
            [useful.macro :only [macro-do]])
  (:import java.io.IOException))

(def ^{:dynamic true} *graph*           nil)
(def ^{:dynamic true} *verbose*         nil)
(def ^{:dynamic true} *revision*        nil)

(defn layer
  "Return the layer for a given name from *graph*."
  [layer-name]
  (if *graph*
    (retro/at-revision (or (get *graph* layer-name)
                           (throw (IOException.
                                   (format "cannot find layer %s in open graph" layer-name))))
                       *revision*)
    (throw (IOException. (format "attempt to use a layer without an open graph")))))

(letfn [(layer-entries
          ([] *graph*)
          ([type] (for [[name layer :as e] *graph*
                        :let [meta (meta layer)]
                        :when (and (contains? (:types meta) type)
                                   (not (:hidden meta)))]
                    (map-entry name (retro/at-revision layer *revision*)))))]
  (defn layer-names
    "Return the names of all layers in the current graph."
    ([]     (keys *graph*))
    ([type] (map key (layer-entries type))))
  (defn layers
    "Return all layers in the current graph."
    ([]     (map val (layer-entries)))
    ([type] (map val (layer-entries type)))))

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

(defmacro with-each-layer
  "Execute forms with layer bound to each layer specified or all layers if layers is empty."
  [layers & forms]
  `(doseq [[~'layer-name ~'layer] (as-layer-map ~layers)]
     (when *verbose*
       (println (format "%-20s %s"
                        ~'layer-name
                        (s/join " " (map pr-str '~forms)))))
     ~@forms))

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
           :fixed-meta (update meta :arglists (partial list 'quote)) ; for macros
           :func @var})))

;; define forwarders to resolve keyword layer-names in *graph*
(macro-do [name]
  (let [{:keys [varname fixed-meta]} (graph-impl name)]
    `(def ~(with-meta name fixed-meta)
       (fn ~name [layer-name# & args#]
         (apply ~varname (layer layer-name#)
                args#))))
  node-id-seq get-node find-node query-in-node get-in-node get-edges get-edge
  update-in-node! update-node! dissoc-node! assoc-node! assoc-in-node!
  fields node-valid? verify-node
  get-all-revisions get-revisions
  get-incoming get-incoming-map)

(defn append-node!
  "Deprecated: a shortcut for update-in-node! with useful.utils/adjoin."
  [layer-name & attrs]
  (graph/update-in-node! (layer layer-name) adjoin (into-map attrs)))

;; these point directly at jiraph.graph functions, without layer-name resolution
;; or any indirection, because they can't meaningfully work with layer names but
;; we don't want to make the "simple" uses of jiraph.core have to mention
;; jiraph.graph at all
(doseq [name '[update-in-node update-node dissoc-node assoc-node assoc-in-node
               wrap-caching with-caching]]
  (let [{:keys [func meta]} (graph-impl name)]
    (intern *ns* (with-meta name meta) func)))

;; operations on a list of layers
(macro-do [name]
  (let [{:keys [varname fixed-meta]} (graph-impl name)]
    `(def ~(with-meta name fixed-meta)
       (fn ~name [& layers#]
         (apply ~varname (vals (as-layer-map layers#))))))
  sync! optimize! truncate!)

(defmacro at-revision
  "Execute the given forms with the curren revision set to rev. Can be used to mark changes with a given
   revision, or read the state at a given revision."
  [rev & forms]
  `(binding [*revision* ~rev]
      ~@forms))

(defn open! []
  (with-each-layer []
    (layer/open layer)))

(defn close! []
  (with-each-layer []
    (layer/close layer)))

(defn set-graph! [graph]
  (alter-var-root #'*graph* (constantly graph)))

(defmacro with-graph [graph & forms]
  `(binding [*graph* ~graph]
     (try (open!)
          ~@forms
          (finally (close!)))))

(defmacro with-graph! [graph & forms]
  `(let [graph# *graph*]
     (set-graph! ~graph)
     (try (open!)
          ~@forms
          (finally (close!)
                   (set-graph! graph#)))))

(defmacro with-transaction
  "Execute forms within a transaction on the named layer/layers."
  [layer & forms]
  `(graph/with-transaction (layer ~layer)
     ~@forms))

(defn current-revision
  "The maximum revision on all specified layers, or all layers if none are specified."
  [& layers]
  (apply max 0 (for [layer (vals (as-layer-map layers))]
                 (graph/get-in-node layer [:meta :rev] 0))))

(defn schema
  "Return a map of fields for a given type to the metadata for each layer. If a subfield is
  provided, then the schema returned is for the nested type within that subfield."
  ([type]
     (apply merge-with conj
            (for [layer        (layers type)
                  [field meta] (layer/fields layer type)]
              {field {layer meta}})))
  ([type subfield]
     (apply merge-with conj
            (for [layer        (keys (get (schema type) subfield))
                  [field meta] (layer/fields layer type [subfield])]
              {field {layer meta}}))))

(alter-var-root #'schema #(with-meta (memoize-deref [#'*graph*] %) (meta %)))

(defn layer-exists?
  "Does the named layer exist in the current graph?"
  [layer-name]
  (contains? *graph* layer-name))

(defmacro txn-> [layer-name & actions]
  `(let [layer-name# ~layer-name
         layer# (layer layer-name#)]
     (retro/dotxn layer#
                  (-> layer#
                      ~@actions))
     layer-name#))
