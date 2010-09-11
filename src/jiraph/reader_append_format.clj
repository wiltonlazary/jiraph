(ns jiraph.reader-append-format
  (:require jiraph.byte-append-format))

(defn- read-seq []
  (lazy-seq
   (let [form (read *in* false ::EOF)]
     (when-not (= ::EOF form)
       (cons form (read-seq))))))

(defn- append [left right]
  (cond (map? left)
        (merge-with append left right)
        
        (and (set? left) (map? right))
        (reduce (fn [set [k v]] ((if v conj disj) set k))
                left right)

        (coll? left)
        ((if (coll? right) into conj) left right)
        
        :else right))

(defn- read-append [str defaults]
  (with-in-str str
    (apply merge-with append (read-seq))))

(deftype ReaderAppendFormat [defaults]
  jiraph.byte-append-format/ByteAppendFormat
  
  (load [format data]
    (if data
      (read-append (String. data) defaults)
      defaults))
  
  (load [format data len]
    (if data
      (read-append (String. data 0 (inc len)) defaults)
      defaults))
  
  (dump [format node]
    (.getBytes (pr-str node))))

(defn make [& [defaults]]
  (ReaderAppendFormat. defaults))