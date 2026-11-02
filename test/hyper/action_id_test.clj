(ns hyper.action-id-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [reitit.ring :as ring]))

(defn- new-app []
  (let [app-state* (atom (state/init-state))]
    (state/get-or-create-tab! app-state* "s" "t")
    app-state*))

(defmacro ^:private rendering
  "Evaluate body as a full render of tab \"t\"."
  [app-state* & body]
  `(binding [context/*request*                {:hyper/session-id "s"
                                               :hyper/tab-id     "t"
                                               :hyper/app-state  ~app-state*
                                               :hyper/router     nil}
             context/*action-idx*             (atom 0)
             context/*region-path*            []
             context/*registered-subview-ids* (atom #{})
             context/*registered-action-ids*  (atom #{})
             context/*action-id-claims*       (atom {})]
     ~@body))

(defn- id-of
  "The action id inside an action expression."
  [expr]
  (second (re-find #"action-id=([^'&\"]+)" (str expr))))

(defn- click! [app-state* id]
  ((get-in @app-state* [:actions id :fn]) nil))

(deftest test-earlier-action-does-not-shift-ids
  (testing "an action appearing earlier on the page leaves later ids alone
            (the Save/Undo double-click repro)"
    (let [app-state* (new-app)
          log        (atom [])
          render!    (fn [show-undo?]
                       (rendering app-state*
                                  {:undo (when show-undo? (id-of (h/action (swap! log conj :undo))))
                                   :save (id-of (h/action (swap! log conj :save)))}))
          before     (render! false)
          after      (render! true)]
      (is (= (:save before) (:save after)) "Save keeps its id")
      (is (not= (:save after) (:undo after)))
      (click! app-state* (:save before))
      (is (= [:save] @log) "the old Save id still runs Save"))))

(deftest test-list-row-ids-follow-the-row
  (testing "each row's action id comes from its captured value, so removing a
            row does not hand its id to the next one (the delete-row repro)"
    (let [app-state* (new-app)
          render!    (fn [rows]
                       (rendering app-state*
                                  (into {} (for [row rows]
                                             [row (id-of (h/action (prn :delete row)))]))))
          before     (render! [:a :b :c])
          after      (render! [:b :c])]
      (is (= 3 (count (set (vals before)))) "rows get distinct ids")
      (is (= (:b before) (:b after)))
      (is (= (:c before) (:c after)))
      (is (not (contains? (set (vals after)) (:a before)))
          "the removed row's id is not reused"))))

(deftest test-captured-data-changes-the-id
  (testing "an action closing over a value gets a new id when the value changes"
    (let [app-state* (new-app)
          render!    (fn [draft] (rendering app-state* (id-of (h/action (prn :save draft)))))]
      (is (= (render! "a") (render! "a")))
      (is (not= (render! "a") (render! "b"))))))

(deftest test-captured-cursor-keeps-the-id
  (testing "a fresh cursor object for the same path each render keeps the id"
    (let [app-state* (new-app)
          render!    (fn []
                       (rendering app-state*
                                  (let [count* (h/tab-cursor :count 0)]
                                    (id-of (h/action (swap! count* inc))))))]
      (is (= (render!) (render!))))))

(deftest test-captured-signal-keeps-the-id
  (testing "a fresh signal object for the same signal each render keeps the id"
    (let [app-state* (new-app)
          render!    (fn []
                       (rendering app-state*
                                  (let [name* (h/signal :user-name "")]
                                    (id-of (h/action (reset! name* "x"))))))]
      (is (= (render!) (render!))))))

(deftest test-unhashable-captures-are-told-apart
  (testing "actions from one site capturing values without a stable identity
            (fns, atoms) get distinct, stable ids by occurrence"
    (let [app-state* (new-app)
          render!    (fn []
                       (rendering app-state*
                                  (vec (for [f [inc dec]]
                                         (let [a (atom 0)]
                                           (id-of (h/action (swap! a f))))))))
          r1         (render!)
          r2         (render!)]
      (is (= 2 (count (set r1))))
      (is (= r1 r2)))))

(deftest test-identical-actions-share-an-id
  (testing "two actions with the same site and captured values are the same
            action, so they share an id"
    (let [app-state* (new-app)
          ids        (rendering app-state*
                                (vec (for [_ (range 2)]
                                       (id-of (h/action (prn :same))))))]
      (is (apply = ids)))))

(deftest test-hash-collisions-are-told-apart
  (testing "two different actions whose ids collide in one render get distinct
            ids, in render order"
    (let [claim! #'hyper.actions/claim-id!]
      (binding [context/*action-id-claims* (atom {})]
        (is (= "a_t_1" (claim! "a_t_1" [:x])))
        (is (= "a_t_1" (claim! "a_t_1" [:x])) "the same action keeps the id")
        (is (= "a_t_1_2" (claim! "a_t_1" [:y])) "a different action gets a suffix")
        (is (= "a_t_1_3" (claim! "a_t_1" [:z])))))))

(deftest test-partial-render-keeps-other-actions
  (testing "re-rendering a region does not reassign an action outside it (the
            partial-render repro)"
    (let [app-state* (new-app)
          calls      (atom [])
          dep        (atom 0)
          header-id  (rendering app-state*
                                (let [header (id-of (h/action (swap! calls conj :header)))]
                                  (h/reactive [dep]
                                              [:div [:button {:data-on:click (h/action (swap! calls conj :region))} "x"]])
                                  header))
          region-id  (first (keys (get-in @app-state* [:tabs "t" :subviews])))
          frag       (subview/partial-render app-state* "t" region-id)]
      (is (not (re-find (re-pattern header-id) frag))
          "the region's action did not take the header's id")
      (click! app-state* header-id)
      (is (= [:header] @calls)))))

(deftest test-navigate-ids-follow-the-route
  (testing "navigate ids come from the route and params, not render position"
    (let [routes     [["/" {:name :home :get (fn [_] [:div])}]
                      ["/users/:id" {:name :user :get (fn [_] [:div])}]]
          router     (ring/router routes {:conflicts nil})
          app-state* (new-app)
          _          (swap! app-state* assoc :router router :routes routes)
          render!    (fn [extra?]
                       (binding [context/*request*          {:hyper/session-id "s"        :hyper/tab-id "t"
                                                             :hyper/app-state  app-state* :hyper/router router}
                                 context/*action-idx*       (atom 0)
                                 context/*action-id-claims* (atom {})]
                         (when extra? (h/navigate :home))
                         [(id-of (:data-on:click__prevent (h/navigate :user {:id "1"})))
                          (id-of (:data-on:click__prevent (h/navigate :user {:id "2"})))]))
          [u1 u2]    (render! false)
          [v1 v2]    (render! true)]
      (is (not= u1 u2))
      (is (= [u1 u2] [v1 v2])))))
