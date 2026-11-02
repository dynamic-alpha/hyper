(ns hyper.stale-action-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context :as context]
            [hyper.server :as server]
            [hyper.state :as state]))

(defn- new-app
  "App state with tab \"t\" and `opts` merged into the root."
  ([] (new-app {}))
  ([opts]
   (let [app-state* (atom (merge (state/init-state) opts))]
     (state/get-or-create-tab! app-state* "s" "t")
     app-state*)))

(defn- post [handler action-id]
  (handler {:query-params {"action-id" action-id}}))

(deftest test-stale-action-still-runs
  (testing "an action missing from the latest render still runs a click sent
            from the page that showed it"
    (let [app-state* (new-app)
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (number? (get-in @app-state* [:actions "a" :stale-at])) "kept and marked stale")
      (is (= 204 (:status (post handler "a"))))
      (is (= 1 @ran)))))

(deftest test-expired-stale-action-409s
  (testing "once :keep-stale-actions-ms has passed, a stale action is gone"
    (let [app-state* (new-app {:keep-stale-actions-ms 30})
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (Thread/sleep 60)
      (is (= 409 (:status (post handler "a"))))
      (is (zero? @ran))
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (not (contains? (:actions @app-state*) "a")) "pruned by the next sweep"))))

(deftest test-zero-keeps-nothing
  (testing ":keep-stale-actions-ms 0 removes actions at the sweep"
    (let [app-state* (new-app {:keep-stale-actions-ms 0})
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (not (contains? (:actions @app-state*) "a")))
      (is (= 409 (:status (post handler "a")))))))

(deftest test-rerendered-action-is-current-again
  (testing "an action rendered again after going stale is current and is not
            pruned when its stale time would have run out"
    (let [app-state* (new-app {:keep-stale-actions-ms 30})]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{"a"})
      (is (nil? (get-in @app-state* [:actions "a" :stale-at])))
      (Thread/sleep 60)
      (actions/sweep-stale-tab-actions! app-state* "t" #{"a"})
      (is (some? (actions/find-action app-state* "a"))))))

(deftest test-stale-actions-are-capped-per-tab
  (testing "beyond the per-tab cap, the longest-stale actions are removed"
    (with-redefs [actions/max-stale-actions-per-tab 2]
      (let [app-state* (new-app)]
        (doseq [id ["a" "b" "c"]]
          (actions/register-action! app-state* "s" "t" (fn [_] nil) id))
        (doseq [live [#{"b" "c"} #{"c"} #{}]]
          (actions/sweep-stale-tab-actions! app-state* "t" live)
          (Thread/sleep 2))
        (is (nil? (actions/find-action app-state* "a")) "the oldest went")
        (is (some? (actions/find-action app-state* "b")))
        (is (some? (actions/find-action app-state* "c")))))))

(deftest test-cleanup-removes-stale-actions
  (testing "tab cleanup removes the tab's stale actions too"
    (let [app-state* (new-app)]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (actions/cleanup-tab-actions! app-state* "t")
      (is (not (contains? (:actions @app-state*) "a"))))))

(deftest test-new-action-does-not-take-a-kept-id
  (testing "a different action whose id collides with a kept stale action gets
            a suffix, so a click on the old page still reaches the old action"
    (let [app-state* (new-app)
          claim!     #'hyper.actions/claim-id!
          render!    (fn [identity-key]
                       (binding [context/*request*          {:hyper/app-state app-state* :hyper/tab-id "t"}
                                 context/*action-id-claims* (atom {})]
                         (let [id (claim! "a_t_1" identity-key)]
                           (actions/register-action! app-state* "s" "t" (fn [_] identity-key) id)
                           id)))]
      (is (= "a_t_1" (render! [:old])))
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (= "a_t_1_2" (render! [:new])) "the kept id is not reused")
      (is (= [:old] ((:fn (actions/find-action app-state* "a_t_1")) nil)))
      (is (= "a_t_1" (render! [:old])) "the same action keeps its id"))))

(deftest test-create-handler-stores-keep-stale-actions-ms
  (testing "create-handler stores :keep-stale-actions-ms, defaulting to 60s"
    (let [routes [["/" {:name :home :get (fn [_] [:div])}]]
          a*     (atom (state/init-state))
          b*     (atom (state/init-state))]
      (server/create-handler routes a* {:keep-stale-actions-ms 5})
      (server/create-handler routes b*)
      (is (= 5 (:keep-stale-actions-ms @a*)))
      (is (= 60000 (:keep-stale-actions-ms @b*))))))
