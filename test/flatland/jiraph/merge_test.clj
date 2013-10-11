(ns flatland.jiraph.merge-test
  (:use clojure.test)
  (:refer-clojure :exclude [merge])
  (:require [flatland.jiraph.layer.masai :as masai]
            [flatland.jiraph.graph :as graph :refer [txn get-node get-in-node update-node update-in-node child open close]]
            [flatland.jiraph.merge :as merge :refer [merge unmerge]]
            [flatland.jiraph.debug :refer [?rev]]
            [flatland.jiraph.parent :as parent]
            [flatland.jiraph.ruminate :as ruminate]
            [flatland.jiraph.resettable :as resettable]
            [flatland.retro.core :as retro :refer [at-revision]]
            [flatland.useful.utils :refer [adjoin]]
            [flatland.useful.map :refer [update filter-vals]]))

(defn make-merged []
  (letfn [(layer []
            (resettable/make (masai/make-temp)
                             (masai/make-temp)
                             {}))]
    (let [[P E N M] (repeatedly layer)]
      (merge/make (ruminate/incoming M (layer))
                  [(-> (ruminate/incoming E (layer))
                       (parent/make {:without-edge-merging (parent/make N {:phantom P})}))]))))

(deftest basic-writing
  (let [[m [e]] (make-merged)
        n (child e :without-edge-merging)]
    (is m)
    (is e)
    (open m e)
    (testing "writing a node"
      (txn (update-in-node e ["a"] adjoin {:size 10}))
      (is (= {:size 10} (get-node e "a")))
      (is (= {:size 10} (get-node n "a"))))
    (testing "writing some edges"
      (txn (update-in-node e ["a" :edges] adjoin {"b" {:exists true :foo 1}}))
      (is (= {:foo 1 :exists true} (get-in-node e ["a" :edges "b"])))
      (is (= {:foo 1 :exists true} (get-in-node n ["a" :edges "b"]))))
    (testing "merge node data"
      (txn (update-in-node e ["a1"] adjoin {:edges {"c" {:x 8 :bar "win" :exists true}
                                                                "b" {:x 1 :foo "lose" :exists true}}
                                                        :size 5, :data "sam"}))
      (txn (update-node m "a" merge "a1" "p1"))
      (is (= {:size 10 :data "sam" :edges {"c" {:x 8 :bar "win" :exists true}
                                           "b" {:x 1 :foo 1 :exists true}}}
             (get-node e "a")))
      (is (= {:size 10 :data "sam" :edges {"c" {:x 8 :bar "win" :exists true}
                                           "b" {:x 1 :foo 1 :exists true}}}
             (get-node n "a")))
      (is (not (get-node e "a1"))))
    (testing "write after merge"
      (txn (update-in-node e ["a"] adjoin {:data "blah"}))
      (is (= {:size 10 :data "blah" :edges {"c" {:x 8 :bar "win" :exists true}
                                            "b" {:x 1 :foo 1 :exists true}}}
             (get-node e "a")))
      (is (= {:size 10 :data "blah" :edges {"c" {:x 8 :bar "win" :exists true}
                                            "b" {:x 1 :foo 1 :exists true}}}
             (get-node n "a")))
      (is (not (get-node e "a1"))))
    (testing "merge edges"
      (txn (update-in-node m ["b"] merge "c" "p2"))
      (let [{:keys [edges] :as node} (get-node e "a")]
        (is (= {:size 10 :data "blah"} (dissoc node :edges)))
        (is (not (:exists (get edges "c"))))
        (is (= {:x 1 :foo 1 :bar "win" :exists true}
               (get edges "b"))))
      (is (= {:size 10 :data "blah" :edges {"b" {:x 1 :foo 1 :exists true}
                                            "c" {:bar "win" :x 8 :exists true}}}
             (get-node n "a"))))
    (testing "write edges after merge"
      (txn (update-in-node e ["a" :edges "b"] adjoin {:x 30}))
      (is (= {:size 10 :data "blah" :edges {"b" {:bar "win" :foo 1 :x 30 :exists true}}}
             (-> (get-node e "a")
                 (update :edges filter-vals :exists)))))
    (close m e)))

(deftest crisscross-edges
  (testing "merge from-ids first"
    (let [[m [e]] (make-merged)
          n (child e :without-edge-merging)]
      (open m e)
      (txn (update-node e "a" adjoin {:edges {"b'" {:type "head->tail" :exists true}}}))
      (txn (update-node e "a'" adjoin {:edges {"b" {:type "tail->head" :exists true}}}))
      (testing "merge from-ids"
        (txn (update-node m "a" merge "a'" "p1"))
        (is (= {:edges {"b'" {:exists true, :type "head->tail"}
                        "b" {:exists true, :type "tail->head"}}}
               (get-node e "a")))
        (is (not (get-node e "a'"))))
      (testing "merge to-ids"
        (txn (update-node m "b" merge "b'" "p2"))
        (is (= {:edges {"b" {:exists true, :type "tail->head"}}}
               (-> (get-node e "a")
                   (update :edges filter-vals :exists)))))
      (close m e)))

  (testing "merge to-ids first"
    (let [[m [e]] (make-merged)
          n (child e :without-edge-merging)]
      (open m e)
      (txn (update-node e "a" adjoin {:edges {"b'" {:type "head->tail" :exists true}}}))
      (txn (update-node e "a'" adjoin {:edges {"b" {:type "tail->head" :exists true}}}))
      (testing "merge to-ids"
        (txn (update-node m "b" merge "b'" "p2"))
        (is (= {:edges {"b" {:exists true :type "head->tail"}}}
               (-> (get-node e "a")
                   (update :edges filter-vals :exists))))
        (is (= {:edges {"b" {:exists true :type "tail->head"}}}
               (get-node e "a'"))))
      (testing "merge from-ids"
        (txn (update-node m "a" merge "a'" "p1"))
        (is (= {:edges {"b" {:exists true, :type "tail->head"}}}
               (-> (get-node e "a")
                   (update :edges filter-vals :exists))))
        (is (not (get-node e "a'"))))
      (close m e))))

(deftest basic-unmerging
  (let [[m [e]] (make-merged)
        n (child e :without-edge-merging)]
    (open m e)
    (txn (update-in-node (at-revision e 0) ["a"] adjoin {:size 10}))
    (txn (update-in-node (at-revision e 1) ["b"] adjoin {:size 5 :foo 1}))
    (txn (update-in-node (at-revision e 2) ["a"] adjoin {:size 100}))
    (txn (update-in-node (at-revision m 3) ["a"] merge "b" "p1"))
    (txn (update-in-node (at-revision m 4) ["a"] unmerge "b"))
    (is (= {:size 100} (get-node e "a")))
    (is (= {:size 5 :foo 1} (get-node e "b")))
    (close m e)))

(deftest edge-unmerging
  (let [[m [e]] (make-merged)
        n (child e :without-edge-merging)]
    (open m e)
    (txn (update-in-node (at-revision e 0) ["a" :edges]
                                     adjoin {"x" {:foo 1 :exists true}
                                             "y" {:foo 2 :exists true}}))
    (txn (update-in-node (at-revision e 1) ["b" :edges]
                                     adjoin {"z" {:foo 10 :exists true}
                                             "y" {:foo 20 :exists true}}))
    (txn (update-in-node (at-revision m 2) ["y"] merge "x" "p1"))
    (txn (update-in-node (at-revision m 3) ["a"] merge "b" "p2"))
    (is (= {"y" {:foo 2 :exists true}
            "z" {:foo 10 :exists true}}
           (get-in-node e ["a" :edges])))
    (txn (update-in-node (at-revision e 4) ["a" :edges "y"] adjoin {:exists false}))
    (txn (update-in-node (at-revision m 5) ["y"] unmerge "x"))
    (txn (update-in-node (at-revision m 6) ["a"] unmerge "b"))
    (is (= {"x" {:foo 1 :exists true}
            "y" {:foo 2 :exists false}}
           (get-in-node e ["a" :edges])))
    (is (= {"z" {:foo 10 :exists true}
            "y" {:foo 20 :exists true}}
           (get-in-node e ["b" :edges])))
    (close m e)))

(defn apply* [start-at & revisions]
  (reduce (fn [revision [layer keyseq f & args]]
            (do (txn (apply update-in-node (at-revision layer revision) keyseq f args))
                (inc revision)))
          start-at, (apply concat revisions)))

(deftest multiple-merges
  (let [[m [e]] (make-merged)
        n (child e :without-edge-merging)]
    (open m e)
    (letfn [(writes [ids f]
              (for [c ids :let [id (str c)]]
                [e [id] adjoin (f id)]))
            (merges [pairs]
              (for [[head tail phantom] pairs]
                [m [(str head)] merge (str tail) (str phantom)]))
            (unmerges [pairs]
              (for [[head tail] pairs]
                [m [(str head)] unmerge (str tail)]))
            (edges [ids]
              (into {}
                    (for [c ids :let [id (str c)]]
                      [id {:exists true :data id}])))]
      (let [r (apply* 0
                      (writes "abcdef" (fn [id]
                                         (let [upper-id (.toUpperCase id)]
                                           {:node id
                                            :edges {upper-id {:exists true :data upper-id}}})))
                      (merges ["ab1" "cd2" "ef4"])
                      (writes "ace" (fn [id]
                                      {:node (.toUpperCase id)}))
                      (merges ["ac3"])
                      (writes "a" (constantly {:node "QQ"}))
                      (merges ["ae5"])
                      (writes "a" (constantly {:node "God"})))
            _ (is (= {:node "God" :edges (edges "ABCDEF")}
                     (get-node e "a")))
            r (apply* r
                      (unmerges ["af"]))
            _ (is (= {:node "God" :edges (edges "ABCDE")}))]))
    (close m e)))

(comment
  (defn empty-graph [f]
    (let [[id-base id-incoming people-base people-incoming] (repeatedly masai/make-temp)
          id-with-incoming (merge-layer id-base id-incoming)
          people-with-incoming (ruminate/incoming people-base people-incoming)]
      (with-graph {:id     id-with-incoming
                   :people (make people-with-incoming id-with-incoming)}
        (f))))

  (use-fixtures :each empty-graph)

  (deftest merging
    (testing "nothing is merged initially"
      (is (empty? (merged-into "A")))
      (is (empty? (merged-into "B")))
      (is (= ["A"] (merge-ids "A")))
      (is (= ["B"] (merge-ids "B")))
      (is (= nil (merge-head "A")))
      (is (= nil (merge-head "B")))
      (is (= nil (merge-position "A")))
      (is (= nil (merge-position "B"))))

    (testing "merge two nodes"
      (at-revision 1 (merge-node! "A" "B"))
      (is (= #{"B"}    (merged-into "A")))
      (is (= ["A" "B"] (merge-ids "A")))
      (is (= ["A" "B"] (merge-ids "B")))
      (is (= "A" (merge-head "A")))
      (is (= "A" (merge-head "B")))
      (is (= 0 (merge-position "A")))
      (is (= 1 (merge-position "B"))))

    (testing "cannot re-merge tail"
      (is (thrown-with-msg? Exception #"already merged"
            (merge-node! "C" "B"))))

    (testing "cannot merge into non-head"
      (is (thrown-with-msg? Exception #"already merged"
            (merge-node! "B" "C"))))

    (testing "merge multiple nodes into a single head"
      (at-revision 2 (merge-node! "A" "C"))
      (at-revision 3 (merge-node! "A" "D"))
      (is (= #{"B" "C" "D"} (merged-into "A")))
      (is (= "A" (merge-head "C")))
      (is (= "A" (merge-head "D")))
      (is (= 0 (merge-position "A")))
      (is (= 1 (merge-position "B")))
      (is (= 2 (merge-position "C")))
      (is (= 3 (merge-position "D"))))

    (testing "can view previous merge data with at-revision"
      (at-revision 1
        (is (= #{"B"} (merged-into "A")))
        (is (= nil (merge-head "C")))
        (is (= nil (merge-head "D")))))

    (testing "merge two chains together"
      (at-revision 4 (merge-node! "E" "F"))
      (at-revision 5 (merge-node! "E" "G"))
      (is (= #{"F" "G"} (merged-into "E")))
      (is (= 0 (merge-position "E")))
      (is (= 1 (merge-position "F")))
      (is (= 2 (merge-position "G")))
      (at-revision 6 (merge-node! "A" "E"))
      (is (= #{"F" "G"} (merged-into "E")))
      (is (= #{"B" "C" "D" "E" "F" "G"} (merged-into "A")))
      (is (= 0 (merge-position "A")))
      (is (= 1 (merge-position "B")))
      (is (= 2 (merge-position "C")))
      (is (= 3 (merge-position "D")))
      (is (= 4 (merge-position "E")))
      (is (= 5 (merge-position "F")))
      (is (= 6 (merge-position "G"))))

    (testing "unmerge latest merge"
      (at-revision 7 (unmerge-node! "A" "E"))
      (is (= nil (merge-head "E")))
      (is (= #{"F" "G"} (merged-into "E")))
      (is (= #{"B" "C" "D"} (merged-into "A")))))

  (deftest readable-merge-update
    (let [val (promise)]
      (at-revision 1
        (txn (flatland.jiraph.graph/compose
              (merge-node (layer :id) "A" "B")
              (update-in-node :people ["A"] adjoin {:foo 1})
              (fn [read]
                (do (deliver val (read (layer :people) ["B" :foo]))
                    [])))))
      (is (= 1 @val))
      (is (= 1 (get-in-node :people ["A" :foo])))
      (is (= 1 (get-in-node :people ["B" :foo])))))

  (deftest readable-update-merge
    (let [val (promise)]
      (at-revision 1
        (txn (flatland.jiraph.graph/compose
              (update-in-node :people ["A"] adjoin {:foo 1})
              (merge-node (layer :id) "A" "B")
              (fn [read]
                (do (deliver val (read (layer :people) ["B" :foo]))
                    [])))))
      (is (= 1 @val))
      (is (= 1 (get-in-node :people ["A" :foo])))
      (is (= 1 (get-in-node :people ["B" :foo])))))

  (deftest readable-merge-update-unmerge
    (let [val (promise)]
      (at-revision 1
        (txn (flatland.jiraph.graph/compose
              (merge-node (layer :id) "A" "B")
              (update-in-node :people ["A"] adjoin {:foo 1})
              (unmerge-node (layer :id) "A" "B")
              (fn [read]
                (do (deliver val (read (layer :people) ["B" :foo]))
                    [])))))
      (is (= nil @val))
      (is (= 1   (get-in-node :people ["A" :foo])))
      (is (= nil (get-in-node :people ["B" :foo])))))

  (deftest readable-merge-unmerge-merge
    (let [val1 (promise)
          val2 (promise)
          val3 (promise)]
      (at-revision 1
        (txn (flatland.jiraph.graph/compose (merge-node (layer :id) "A" "B")
                                            (fn [read]
                                              (do (deliver val1 (merged-into read (layer :id) "A"))
                                                  []))
                                            (unmerge-node (layer :id) "A" "B")
                                            (fn [read]
                                              (do (deliver val2 (merged-into read (layer :id) "A"))
                                                  [])))))
      (at-revision 2
        (txn (flatland.jiraph.graph/compose (merge-node (layer :id) "A" "B")
                                            (fn [read]
                                              (do (deliver val3 (merged-into read (layer :id) "A"))
                                                  [])))))
      (is (= #{"B"} @val1))
      (is (= #{}    @val2))
      (is (= #{"B"} @val3))
      (is (= #{"B"} (merged-into (layer :id) "A")))))

  (deftest edge-merging
    (at-revision 1 (assoc-in-node! :people ["A" :edges] {"B" {:foo 1 :exists true}
                                                         "C" {:foo 2 :exists true}}))
    (at-revision 2 (merge-node! "C" "B"))

    (is (= {"C" {:foo 2 :exists true}} (get-in-node :people ["A" :edges])))
    (is (= #{"A"} (get-incoming :people "C")))
    (is (= #{"A"} (get-incoming :people "B")))

    (at-revision 3 (unmerge-node! "C" "B"))
    (at-revision 4 (merge-node! "B" "C"))

    (is (= {"B" {:foo 1 :exists true}} (get-in-node :people ["A" :edges])))
    (is (= #{"A"} (get-incoming :people "C")))

    (at-revision 5 (assoc-node! :people "D" {:a 1 :edges {"F" {:foo 3 :exists true :baz nil}}}))
    (at-revision 6 (assoc-node! :people "E" {:a 2 :edges {"G" {:foo 1 :exists true :bar 2 :baz 3}}}))
    (at-revision 7 (merge-node! "D" "E"))
    (at-revision 8 (merge-node! "G" "F"))

    (is (= {:a 1 :edges {"G" {:foo 3 :exists true :bar 2 :baz nil}}} (get-node :people "D")))
    (is (= {:a 1 :edges {"G" {:foo 3 :exists true :bar 2 :baz nil}}} (get-node :people "E")))
    (is (= #{"D"} (get-incoming :people "G")))
    (is (= #{"D"} (get-incoming :people "F")))

    (at-revision 9  (unmerge-node! "D" "E"))
    (at-revision 10 (unmerge-node! "G" "F"))

    (is (= {:a 1 :edges {"F" {:foo 3 :exists true :baz nil}}}      (get-node :people "D")))
    (is (= {:a 2 :edges {"G" {:foo 1 :exists true :bar 2 :baz 3}}} (get-node :people "E")))
    (is (= #{"E"} (get-incoming :people "G")))
    (is (= #{"D"} (get-incoming :people "F"))))

  (deftest deleted-edge-merging-opposite-direction
    (at-revision 1 (assoc-node! :people "A" {:edges {"C" {:exists false}}}))
    (at-revision 2 (assoc-node! :people "B" {:edges {"D" {:exists true}}}))
    (at-revision 3 (merge-node! "A" "B"))
    (at-revision 4 (merge-node! "D" "C"))

    (is (= {:edges {"D" {:exists true}}} (get-node :people "A")))
    (is (= {:edges {"D" {:exists true}}} (get-node :people "B")))
    (is (= {"A" true} (get-incoming-map :people "C")))
    (is (= {"A" true} (get-incoming-map :people "D"))))

  (deftest deleted-edge-merging-same-direction
    (at-revision 1 (assoc-node! :people "A" {:edges {"C" {:exists false}}}))
    (at-revision 2 (assoc-node! :people "B" {:edges {"D" {:exists true}}}))
    (at-revision 3 (merge-node! "A" "B"))
    (at-revision 4 (merge-node! "C" "D"))

    (is (= {:edges {"C" {:exists true}}} (get-node :people "A")))
    (is (= {:edges {"C" {:exists true}}} (get-node :people "B")))
    (is (= {"A" true} (get-incoming-map :people "C")))
    (is (= {"A" true} (get-incoming-map :people "D"))))

  (deftest delete-edges-on-all-merged-nodes
    (at-revision 1 (assoc-node! :people "A"  {:edges {"B1" {:exists false}}}))
    (at-revision 2 (assoc-node! :people "A1" {:edges {"B"  {:exists true}}}))
    (at-revision 2 (assoc-node! :people "A2" {:edges {"B2" {:exists true}}}))
    (at-revision 3 (merge-node! "A" "A1"))
    (at-revision 4 (merge-node! "A" "A2"))
    (at-revision 5 (merge-node! "B" "B1"))
    (at-revision 6 (merge-node! "B" "B2"))

    (is (= {:edges {"B" {:exists true}}} (get-node :people "A")))
    (is (= {:edges {"B" {:exists true}}} (get-node :people "A1")))
    (is (= {:edges {"B" {:exists true}}} (get-node :people "A2")))
    (is (= {"A" true} (get-incoming-map :people "B")))
    (is (= {"A" true} (get-incoming-map :people "B1")))
    (is (= {"A" true} (get-incoming-map :people "B2")))

    (at-revision 7 (assoc-node! :people "A"  {:edges {"B" {:exists false}}}))

    (is (= {:edges {"B" {:exists false}}} (get-node :people "A")))
    (is (= {"A" false} (get-incoming-map :people "B")))))
