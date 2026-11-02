(ns hyper.action-order-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context :as context]
            [hyper.core :as h]
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
   JSON signals body and extra query params (client params)."
  ([handler action-id]
   (post! handler action-id nil))
  ([handler action-id body]
   (post! handler action-id body nil))
  ([handler action-id body params]
   (future (handler (cond-> {:query-params (merge {"action-id" action-id} params)}
                      body (assoc :body body))))))

(defn- slow-action
  "An action fn that records its start and end in `log` around a sleep."
  [log label ms]
  (fn [_]
    (swap! log conj [label :start])
    (Thread/sleep (long ms))
    (swap! log conj [label :end])))

(defn- blocker!
  "Register an action \"block\" on tab \"t\" that holds the queue until
   `release` is delivered.  Returns a promise delivered once it is running."
  [app-state* release]
  (let [started (promise)]
    (actions/register-action! app-state* "s" "t"
                              (fn [_] (deliver started true) (deref release 2000 nil))
                              "block")
    started))

(defn- wait-queued!
  "Wait until `n` requests are waiting in tab \"t\"'s action queue."
  [app-state* n]
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (while (and (< (count (get-in @app-state* [:tabs "t" :action-queue :queue])) n)
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 2))))

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

(deftest test-unknown-action-409s
  (testing "an unknown action id returns 409, so a click from a stale page
            fails quietly"
    (let [app-state* (new-app "t")
          handler    (server/action-handler app-state*)]
      (is (= 409 (:status (handler {:query-params {"action-id" "nope"}})))))))

(deftest test-action-made-stale-while-waiting-still-runs
  (testing "an action dropped from the page while its request waited in the
            queue still runs the handler the click was rendered with"
    (let [app-state* (new-app "t")
          release    (promise)
          ran        (atom 0)
          handler    (server/action-handler app-state*)
          started    (blocker! app-state* release)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "gone")
      (let [fb (post! handler "block")
            _  (deref started 1000 :timeout)
            fg (post! handler "gone")]
        (wait-queued! app-state* 1)
        (actions/sweep-stale-tab-actions! app-state* "t" #{"block"})
        (deliver release true)
        @fb
        (is (= 204 (:status (deref fg 1000 :timeout))))
        (is (= 1 @ran))))))

(deftest test-action-expired-while-waiting-409s
  (testing "an action removed for good while its request waited returns 409"
    (let [app-state* (new-app "t")
          _          (swap! app-state* assoc :keep-stale-actions-ms 0)
          release    (promise)
          handler    (server/action-handler app-state*)
          started    (blocker! app-state* release)]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "gone")
      (let [fb (post! handler "block")
            _  (deref started 1000 :timeout)
            fg (post! handler "gone")]
        (wait-queued! app-state* 1)
        (actions/sweep-stale-tab-actions! app-state* "t" #{"block"})
        (deliver release true)
        @fb
        (is (= 409 (:status (deref fg 1000 :timeout))))))))

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

(deftest test-navigation-skips-the-queue
  (testing "a navigation action runs immediately, without waiting behind a
            running action"
    (let [app-state* (new-app "t")
          release    (promise)
          started    (promise)
          navigated  (promise)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [_] (deliver started true) (deref release 2000 nil))
                                "a_t_1")
      (actions/register-action! app-state* "s" "t" (fn [_] (deliver navigated true))
                                "a_t_2" {:navigation? true})
      (let [fa (post! handler "a_t_1")
            _  (deref started 1000 :timeout)
            fb (post! handler "a_t_2")]
        (is (= 204 (:status (deref fb 500 :timeout))) "navigation answered while the action runs")
        (is (true? (deref navigated 0 :pending)))
        (deliver release true)
        (is (= 204 (:status @fa)))))))

(deftest test-drop-ignores-repeats-while-in-flight
  (testing ":drop ignores a request for the same action while one is waiting
            or running, and accepts it again once that one finishes"
    (let [app-state* (new-app "t")
          release    (promise)
          ran        (atom 0)
          handler    (server/action-handler app-state*)
          started    (blocker! app-state* release)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc))
                                "save" {:concurrency :drop})
      (let [fb (post! handler "block")
            _  (deref started 1000 :timeout)
            f1 (post! handler "save")
            _  (wait-queued! app-state* 1)
            f2 (post! handler "save")]
        (is (= 204 (:status (deref f2 500 :timeout)))
            "the repeat is answered at once, without waiting its turn")
        (deliver release true)
        @fb @f1)
      (is (= 1 @ran) "only the first of the two ran")
      @(post! handler "save")
      (is (= 2 @ran) "accepted again once nothing is in flight"))))

(deftest test-drop-applies-signals-of-dropped-requests
  (testing "a dropped request still applies its signals, in order"
    (let [app-state* (new-app "t")
          release    (promise)
          handler    (server/action-handler app-state*)
          started    (blocker! app-state* release)]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "save" {:concurrency :drop})
      (let [fb (post! handler "block")
            _  (deref started 1000 :timeout)
            f1 (post! handler "save" "{\"x\": 1}")
            _  (wait-queued! app-state* 1)
            f2 (post! handler "save" "{\"x\": 2}")]
        (is (nil? (get-in @app-state* [:tabs "t" :signals :x]))
            "not applied ahead of the requests before it")
        (deliver release true)
        @fb @f1 @f2)
      ;; The dropped request is answered before its signals are applied, so
      ;; send one more action through the queue to wait for them.
      @(post! handler "block")
      (is (= 2 (get-in @app-state* [:tabs "t" :signals :x]))))))

(deftest test-latest-replaces-waiting-request
  (testing ":latest replaces a waiting request for the same action with the
            newest one, which moves to the back of the queue"
    (let [app-state* (new-app "t")
          release    (promise)
          log        (atom [])
          handler    (server/action-handler app-state*)
          started    (blocker! app-state* release)]
      (actions/register-action! app-state* "s" "t" (fn [{:keys [q]}] (swap! log conj [:search q]))
                                "search" {:concurrency :latest})
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! log conj :other)) "other")
      (let [fb (post! handler "block")
            _  (deref started 1000 :timeout)
            f1 (post! handler "search" nil {"q" "1"})
            _  (wait-queued! app-state* 1)
            fo (post! handler "other")
            _  (wait-queued! app-state* 2)
            f2 (post! handler "search" nil {"q" "2"})]
        (is (= 204 (:status (deref f1 500 :timeout)))
            "the replaced request is answered at once")
        (deliver release true)
        @fb @fo @f2)
      (is (= [:other [:search 2]] @log)
          "only the newest search ran, after the action queued before it"))))

(deftest test-latest-does-not-cancel-running-request
  (testing ":latest leaves a running request alone; the new one runs after it"
    (let [app-state* (new-app "t")
          release    (promise)
          started    (promise)
          log        (atom [])
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t"
                                (fn [{:keys [q]}]
                                  (when (= 1 q) (deliver started true) (deref release 2000 nil))
                                  (swap! log conj q))
                                "search" {:concurrency :latest})
      (let [f1 (post! handler "search" nil {"q" "1"})
            _  (deref started 1000 :timeout)
            f2 (post! handler "search" nil {"q" "2"})]
        (deliver release true)
        @f1 @f2)
      (is (= [1 2] @log)))))

(deftest test-invalid-concurrency-throws
  (testing "an unknown :concurrency value is rejected when registering"
    (let [app-state* (new-app "t")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":concurrency"
                            (actions/register-action! app-state* "s" "t" (fn [_] nil)
                                                      "x" {:concurrency :sometimes}))))))

(deftest test-action-macro-passes-concurrency
  (testing "(h/action {:concurrency ...} ...) records it on the action"
    (let [app-state* (new-app "t")
          expr       (binding [context/*request*    {:hyper/session-id "s"        :hyper/tab-id "t"
                                                     :hyper/app-state  app-state* :hyper/router nil}
                               context/*action-idx* (atom 0)]
                       (h/action {:concurrency :drop} nil))
          id         (second (re-find #"action-id=([^'&]+)" (str expr)))]
      (is (= :drop (get-in @app-state* [:actions id :concurrency]))))))

(defn- post-seq!
  "POST action `action-id` with sequence number `n` and JSON `body`; returns
   the response."
  [handler action-id n body]
  (handler (cond-> {:query-params {"action-id" action-id "hyper-seq" (str n)}}
             body (assoc :body body))))

(deftest test-older-signals-are-ignored
  (testing "signals from a request sent before ones already applied are
            ignored, while its action still runs"
    (let [app-state* (new-app "t")
          ran        (atom 0)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] (swap! ran inc)) "a")
      (post-seq! handler "a" 2 "{\"x\": 2}")
      (post-seq! handler "a" 1 "{\"x\": 1}")
      (is (= 2 (get-in @app-state* [:tabs "t" :signals :x])) "the newer value stays")
      (is (= 2 @ran) "both clicks ran")
      (post-seq! handler "a" 3 "{\"x\": 3}")
      (is (= 3 (get-in @app-state* [:tabs "t" :signals :x]))))))

(deftest test-requests-without-seq-apply-signals
  (testing "a request without a sequence number applies its signals"
    (let [app-state* (new-app "t")
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] nil) "a")
      (post-seq! handler "a" 5 "{\"x\": 5}")
      @(post! handler "a" "{\"x\": 6}")
      (is (= 6 (get-in @app-state* [:tabs "t" :signals :x]))))))

(deftest test-seq-is-not-a-client-param
  (testing "the sequence number is not passed to the action as a client param"
    (let [app-state* (new-app "t")
          seen       (promise)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [cp] (deliver seen cp)) "a")
      (post-seq! handler "a" 1 nil)
      (is (nil? (:hyper-seq (deref seen 0 :none)))))))

(deftest test-older-navigation-is-skipped
  (testing "a navigation action sent before one already applied does not run"
    (let [app-state* (new-app "t")
          route      (atom nil)
          handler    (server/action-handler app-state*)]
      (actions/register-action! app-state* "s" "t" (fn [_] (reset! route :a)) "nav-a"
                                {:navigation? true})
      (actions/register-action! app-state* "s" "t" (fn [_] (reset! route :b)) "nav-b"
                                {:navigation? true})
      (is (= 204 (:status (post-seq! handler "nav-b" 3 nil))))
      (is (= 204 (:status (post-seq! handler "nav-a" 2 nil))))
      (is (= :b @route)))))

(deftest test-older-back-forward-navigation-is-skipped
  (testing "a back/forward navigation sent before one already applied does not
            change the route"
    (let [app-state* (atom (state/init-state))
          handler    (server/create-handler [["/a" {:name :a :get (fn [_] [:div "A"])}]
                                             ["/b" {:name :b :get (fn [_] [:div "B"])}]]
                                            app-state*)
          navigate!  (fn [path n]
                       (handler {:uri            "/hyper/navigate"
                                 :request-method :post
                                 :query-params   {"path" path "tab-id" "t" "hyper-seq" (str n)}}))]
      (state/get-or-create-tab! app-state* "s" "t")
      (navigate! "/b" 3)
      (navigate! "/a" 2)
      (is (= :b (get-in @app-state* [:tabs "t" :route :name]))))))

(deftest test-client-sends-seq
  (testing "action and navigation requests carry the page's next sequence number"
    (is (str/includes? (actions/build-action-expr "a_t_1" {} nil "")
                       "hyper-seq=' + hyper.nextSeq()"))
    (is (str/includes? (actions/build-action-expr "a_t_1" {'$value {:js "evt.target.value" :key "value"}} nil "")
                       "hyper-seq=' + hyper.nextSeq()"))
    (let [app-state* (atom (state/init-state))
          handler    (server/create-handler [["/" {:name :home :get (fn [_] [:div "Home"])}]]
                                            app-state*)
          body       (str (:body (handler {:uri "/" :request-method :get})))]
      (is (str/includes? body "window.hyper.nextSeq"))
      (is (str/includes? body "hyper-seq=' + window.hyper.nextSeq()")
          "back/forward navigation sends it too"))))
