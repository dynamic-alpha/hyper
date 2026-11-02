(ns hyper.stale-action-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context :as context]
            [hyper.core :as h]
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

(defn- register-stale!
  "Register action `id` on tab \"t\" with `opts`, then sweep it stale."
  [app-state* id f opts]
  (actions/register-action! app-state* "s" "t" f id opts)
  (actions/sweep-stale-tab-actions! app-state* "t" #{}))

(deftest test-on-stale-reject
  (testing ":on-stale :reject refuses a stale action with 409 but runs a
            current one"
    (let [app-state* (new-app)
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "a" {:on-stale :reject})
      (is (= 204 (:status (post handler "a"))) "current: runs")
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (= 409 (:status (post handler "a"))) "stale: refused")
      (is (= 1 @ran)))))

(deftest test-on-stale-run
  (testing ":on-stale :run (the default) runs a stale action"
    (let [app-state* (new-app)
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (register-stale! app-state* "a" (fn [_] (swap! ran inc)) {:on-stale :run})
      (is (= 204 (:status (post handler "a"))))
      (is (= 1 @ran)))))

(deftest test-on-stale-fn
  (testing "an :on-stale fn runs instead of a stale action, and the request
            is refused"
    (let [app-state* (new-app)
          log        (atom [])
          handler    (server/action-handler app-state*)]
      (register-stale! app-state* "a" (fn [_] (swap! log conj :action))
                       {:on-stale (fn [] (swap! log conj :on-stale))})
      (is (= 409 (:status (post handler "a"))))
      (is (= [:on-stale] @log)))))

(deftest test-on-stale-reject-while-queued
  (testing "an :on-stale :reject action that goes stale while its request
            waits is refused when its turn comes"
    (let [app-state* (new-app)
          release    (promise)
          started    (promise)
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [_] (deliver started true) (deref release 2000 nil))
                                "block")
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "a" {:on-stale :reject})
      (let [fb (future (post handler "block"))
            _  (deref started 1000 :timeout)
            fa (future (post handler "a"))]
        (Thread/sleep 50)
        (actions/sweep-stale-tab-actions! app-state* "t" #{"block"})
        (deliver release true)
        @fb
        (is (= 409 (:status (deref fa 1000 :timeout))))
        (is (zero? @ran))))))

(deftest test-invalid-on-stale-throws
  (testing "an :on-stale value other than :run, :reject or a fn is rejected"
    (let [app-state* (new-app)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":on-stale"
                            (actions/register-action! app-state* "s" "t" (fn [_] nil)
                                                      "a" {:on-stale :sometimes}))))))

(deftest test-action-macro-on-stale
  (testing "(h/action {:on-stale f} ...) runs f in the tab's context when the
            action is stale"
    (let [app-state* (new-app)
          handler    (server/action-handler app-state*)
          expr       (binding [context/*request*          {:hyper/session-id "s"        :hyper/tab-id "t"
                                                           :hyper/app-state  app-state* :hyper/router nil}
                               context/*action-idx*       (atom 0)
                               context/*action-id-claims* (atom {})]
                       (h/action {:on-stale #(reset! (h/tab-cursor :notice) "changed")}
                                 (reset! (h/tab-cursor :done) true)))
          id         (second (re-find #"action-id=([^'&]+)" (str expr)))]
      (actions/sweep-stale-tab-actions! app-state* "t" #{})
      (is (= 409 (:status (post handler id))))
      (is (= "changed" (get-in @app-state* [:tabs "t" :data :notice])))
      (is (nil? (get-in @app-state* [:tabs "t" :data :done]))))))
