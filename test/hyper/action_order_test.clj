(ns hyper.action-order-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.server :as server]
            [hyper.state :as state]))

(defn- new-app
  "App state with session \"s\" and the given tabs."
  [& tab-ids]
  (let [app-state* (atom (state/init-state))]
    (doseq [tab-id tab-ids]
      (state/get-or-create-tab! app-state* "s" tab-id))
    app-state*))

(defn- post!
  "Invoke the action handler for `action-id` on a future, optionally with a
   JSON signals body."
  ([handler action-id]
   (post! handler action-id nil))
  ([handler action-id body]
   (future (handler (cond-> {:query-params {"action-id" action-id}}
                      body (assoc :body body))))))

(defn- slow-action
  "An action fn that records its start and end in `log` around a sleep."
  [log label ms]
  (fn [_]
    (swap! log conj [label :start])
    (Thread/sleep (long ms))
    (swap! log conj [label :end])))

(deftest test-same-tab-actions-run-one-at-a-time
  (testing "two actions on the same tab do not overlap"
    (let [app-state* (new-app "t")
          log        (atom [])
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (slow-action log :A 150) "a_t_1")
      (actions/register-action! app-state* "s" "t" (slow-action log :B 150) "a_t_2")
      (let [fa (post! handler "a_t_1")
            _  (Thread/sleep 20)
            fb (post! handler "a_t_2")]
        (is (= 204 (:status @fa)))
        (is (= 204 (:status @fb))))
      (is (= [[:A :start] [:A :end] [:B :start] [:B :end]] @log)
          "B starts only after A ends, in arrival order"))))

(deftest test-different-tabs-run-concurrently
  (testing "actions on different tabs are not ordered against each other"
    (let [app-state* (new-app "t1" "t2")
          log        (atom [])
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t1" (slow-action log :A 150) "a_t1_1")
      (actions/register-action! app-state* "s" "t2" (slow-action log :B 150) "a_t2_1")
      (let [fa (post! handler "a_t1_1")
            fb (post! handler "a_t2_1")]
        @fa @fb)
      (is (= #{[:A :start] [:B :start]} (set (take 2 @log)))
          "both started before either finished"))))

(deftest test-failing-action-does-not-block-later-actions
  (testing "an action that throws does not stop the tab's later actions"
    (let [app-state* (new-app "t")
          ran        (atom [])
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [_] (swap! ran conj :boom) (throw (ex-info "boom" {})))
                                "a_t_1")
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran conj :ok)) "a_t_2")
      @(post! handler "a_t_1")
      (is (= 204 (:status (deref (post! handler "a_t_2") 1000 :timeout))))
      (is (= [:boom :ok] @ran)))))

(deftest test-signals-applied-when-action-runs
  (testing "a waiting request's signals are not applied while an earlier action
            is still running"
    (let [app-state* (new-app "t")
          seen       (promise)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [_]
                                  (Thread/sleep 150)
                                  (deliver seen (get-in @app-state* [:tabs "t" :signals :x])))
                                "a_t_1")
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a_t_2")
      (let [fa (post! handler "a_t_1" "{\"x\": 1}")
            _  (Thread/sleep 20)
            fb (post! handler "a_t_2" "{\"x\": 2}")]
        @fa @fb)
      (is (= 1 (deref seen 1000 :timeout))
          "the first action sees its own signals, not the later request's")
      (is (= 2 (get-in @app-state* [:tabs "t" :signals :x]))
          "the later request's signals are applied once it runs"))))

(deftest test-unknown-action-still-500s
  (testing "an unknown action id still returns 500"
    (let [app-state* (new-app "t")
          handler    (server/action-handler app-state*)]
      (is (= 500 (:status (handler {:query-params {"action-id" "nope"}})))))))

(deftest test-cleanup-answers-waiting-requests
  (testing "cleaning up a tab interrupts its running action and fails the
            requests waiting behind it instead of leaving them hanging"
    (let [app-state*  (new-app "t")
          started     (promise)
          interrupted (promise)
          ran-second? (atom false)
          handler     (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [_]
                                  (deliver started true)
                                  (try (Thread/sleep 60000)
                                       (catch InterruptedException _
                                         (deliver interrupted true))))
                                "a_t_1")
      (actions/register-action! app-state* "s" "t" (fn [_] (reset! ran-second? true)) "a_t_2")
      (let [fa (post! handler "a_t_1")
            _  (deref started 1000 :timeout)
            fb (post! handler "a_t_2")]
        (Thread/sleep 20)
        (server/cleanup-tab! app-state* "t")
        (is (true? (deref interrupted 1000 :timeout)) "running action interrupted")
        (is (some? (:status (deref fa 1000 nil))) "running request answered")
        (is (= 500 (:status (deref fb 1000 nil))) "waiting request failed")
        (is (false? @ran-second?) "waiting action never ran")))))
