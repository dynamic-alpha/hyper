(ns hyper.region-identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.state :as state]
            [hyper.subview :as subview]))

(defmacro ^:private rendering
  "Evaluate body as a full render of `tab-id` (positional ids key off call
   order; the subview-id accumulator tracks live regions)."
  [app-state* tab-id & body]
  `(binding [context/*request*                {:hyper/session-id "s"
                                               :hyper/tab-id     ~tab-id
                                               :hyper/app-state  ~app-state*
                                               :hyper/router     nil}
             context/*action-idx*             (atom 0)
             context/*region-path*            []
             context/*registered-subview-ids* (atom #{})]
     ~@body))

(defn- new-tab []
  (let [app-state* (atom (state/init-state))]
    (state/get-or-create-tab! app-state* "s" "t")
    app-state*))

(defn- async-cell-id [app-state* id]
  (System/identityHashCode (get-in @app-state* [:tabs "t" :async id :cell])))

(deftest test-anonymous-regions-stay-positional
  (testing "without :key or a root :id, region ids are the positional fallback"
    (let [app-state* (new-tab)]
      (rendering app-state* "t"
                 (h/reactive [(atom 0)] [:p "a"])
                 (h/async [] :v {:keys [status]} [:div (str status)]))
      (is (= #{"r_t_1" "async_t_2"}
             (set (keys (get-in @app-state* [:tabs "t" :subviews]))))))))

(deftest test-reactive-key-drives-registry-id
  (testing ":key keys the subview registry and the morph anchor alike"
    (let [app-state* (new-tab)
          html       (rendering app-state* "t"
                                (h/reactive {:key (str "node-" 42)} [(atom 0)]
                                            [:div "grid"]))]
      (is (= [:div {:id "r_t_node-42"} "grid"] html))
      (is (some? (get-in @app-state* [:tabs "t" :subviews "r_t_node-42"]))))))

(deftest test-reactive-root-id-drives-registry-id
  (testing "a stable root :id is adopted as the registry key (no positional id)"
    (let [app-state* (new-tab)]
      (rendering app-state* "t"
                 (h/reactive [(atom 0)] [:div {:id "node-42"} "grid"]))
      (is (some? (get-in @app-state* [:tabs "t" :subviews "node-42"])))
      (is (nil? (get-in @app-state* [:tabs "t" :subviews "r_t_1"]))))))

(deftest test-async-key-survives-reorder
  (testing "a keyed async region's in-flight fetch follows the item across a
            re-sort; positional ids would reattach it to a different item"
    (let [app-state* (new-tab)
          render!    (fn [order]
                       (rendering app-state* "t"
                                  (doseq [node order]
                                    (h/async {:key node}
                                             [] (do (Thread/sleep 60000) :rows)
                                             {:keys [status]} [:div (str status)])))
                       (into {} (for [node order]
                                  [node (async-cell-id app-state* (str "async_t_" (name node)))])))
          r1         (render! [:A :B :C])
          r2         (render! [:C :A :B])]
      (is (= #{"async_t_A" "async_t_B" "async_t_C"}
             (set (keys (get-in @app-state* [:tabs "t" :async])))))
      (is (= (:A r1) (:A r2)) "node A keeps its fetch cell")
      (is (= (:B r1) (:B r2)) "node B keeps its fetch cell")
      (is (= (:C r1) (:C r2)) "node C keeps its fetch cell")
      (subview/teardown-all! app-state* "t"))))

(deftest test-nested-keys-are-scoped
  (testing "an inner :key is namespaced by its enclosing keyed region, so a
            locally-unique key does not collide across sibling parents, and
            every id is a valid CSS id selector (no '/')"
    (let [app-state* (new-tab)]
      (rendering app-state* "t"
                 (doall
                   (for [node [:A :B]]
                     (h/reactive {:key node} [(atom 0)]
                                 [:div
                                  (h/reactive {:key :body} [(atom 0)]
                                              [:span "inner"])]))))
      (let [ids (set (keys (get-in @app-state* [:tabs "t" :subviews])))]
        (is (= 4 (count ids)) "no collision across sibling parents")
        (is (contains? ids "r_t_A"))
        (is (contains? ids "r_t_B"))
        (is (= 2 (count (filter #(re-find #"_body$" %) ids)))
            "the two inner regions get distinct, scoped ids")
        (is (every? #(re-matches #"[A-Za-z0-9_-]+" %) ids)
            "all ids are valid CSS id selectors (Datastar morphs by #id)")))))

(deftest test-partial-render-restores-nested-path
  (testing "partially re-rendering a nested region restores its path, so a
            keyed descendant keeps the same id (the morph anchor matches)"
    (let [app-state* (new-tab)]
      (rendering app-state* "t"
                 (h/reactive {:key :A} [(atom 0)]
                             [:div (h/reactive {:key :mid} [(atom 0)]
                                               [:div (h/reactive {:key :leaf} [(atom 0)] [:span "x"])])]))
      (let [subviews (get-in @app-state* [:tabs "t" :subviews])
            mid-id   (first (filter #(re-find #"_mid$" %) (keys subviews)))
            leaf-id  (first (filter #(re-find #"_leaf$" %) (keys subviews)))]
        (is (= ["A" "mid"] (:region-path (subviews mid-id))))
        (let [frag (subview/partial-render app-state* "t" mid-id)]
          (is (re-find (re-pattern (str "id=\"" leaf-id "\"")) frag)
              "the keyed descendant keeps its full-render id"))))))

(deftest test-region-whose-body-is-a-region
  (testing "a region whose body is only another region gets its own element,
            and the inner region keeps its id"
    (doseq [[label render! outer-re inner-re]
            [["positional"
              #(h/reactive [(atom 1)] (h/reactive [(atom 2)] [:p "x"]))
              #"_1$" #"_2$"]
             ["keyed"
              #(h/reactive {:key :outer} [(atom 1)]
                           (h/reactive {:key :inner} [(atom 2)] [:p "x"]))
              #"_outer$" #"_inner$"]]]
      (testing label
        (let [app-state* (new-tab)
              hiccup     (rendering app-state* "t" (render!))
              subviews   (get-in @app-state* [:tabs "t" :subviews])
              outer-id   (first (filter #(re-find outer-re %) (keys subviews)))
              inner-id   (first (filter #(re-find inner-re %) (keys subviews)))]
          (is (= [:div {:id outer-id :style "display: contents"} [:p {:id inner-id} "x"]] hiccup))
          (is (re-find (re-pattern (str "^<div [^>]*id=\"" outer-id "\" style=\"display: contents\"><p id=\"" inner-id "\">"))
                       (subview/partial-render app-state* "t" outer-id))
              "a partial render of the outer region keeps both ids")
          (is (re-find (re-pattern (str "^<p [^>]*id=\"" inner-id "\""))
                       (subview/partial-render app-state* "t" inner-id))
              "a partial render of the inner region targets its own element"))))))

(deftest test-duplicate-key-throws
  (testing "two regions resolving to the same id in one render throw"
    (let [app-state* (new-tab)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate region id"
            (rendering app-state* "t"
                       (h/reactive {:key :dup} [(atom 0)] [:div "a"])
                       (h/reactive {:key :dup} [(atom 0)] [:div "b"]))))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate region id"
            (rendering app-state* "t"
                       (h/async {:key :dup} [] :x {:keys [status]} [:div (str status)])
                       (h/async {:key :dup} [] :y {:keys [status]} [:div (str status)]))))
      (subview/teardown-all! app-state* "t"))))

(deftest test-action-key-drives-action-id
  (testing ":key gives an action a stable id independent of render order"
    (let [app-state* (new-tab)]
      (rendering app-state* "t"
                 (h/action {:key "save"} (constantly :ok)))
      (is (some? (get-in @app-state* [:actions "a_t_save"]))))))

(deftest test-key->token
  (testing "simple values pass through id-safe; complex values hash"
    (is (= "save" (subview/key->token "save")))
    (is (= "grid" (subview/key->token :grid)))
    (is (= "42" (subview/key->token 42)))
    (is (re-matches #"[0-9a-f]{8}" (subview/key->token :ns/qualified)))
    (is (re-matches #"[0-9a-f]{8}" (subview/key->token [:a 1])))))
