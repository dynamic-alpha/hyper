(ns hyper.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.state :as state]
            [taoensso.telemere :as t]))

(deftest require-context-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? Exception #"global-cursor called outside request context"
                          (context/require-context! "global-cursor"))))

  (testing "throws when app-state is missing"
    (binding [context/*request* {:hyper/session-id "s1"
                                 :hyper/tab-id     "t1"}]
      (is (thrown-with-msg? Exception #"No app-state"
                            (context/require-context! "test")))))

  (testing "returns context map with all keys"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*
                                   :hyper/router     :mock-router}]
        (let [ctx (context/require-context! "test")]
          (is (= "s1" (:session-id ctx)))
          (is (= "t1" (:tab-id ctx)))
          (is (= app-state* (:app-state* ctx)))
          (is (= :mock-router (:router ctx)))))))

  (testing "router is nil when not in request"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (is (nil? (:router (context/require-context! "test"))))))))

(deftest current-overlay-test
  (testing "returns nil when no overlay is bound"
    (is (nil? (context/current-overlay))))

  (testing "returns the overlay on the thread that owns it"
    (let [overlay {:state* (atom {})
                   :ops*   (atom [])
                   :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        (is (identical? overlay (context/current-overlay))))))

  (testing "returns nil on a thread that merely inherited the binding"
    (let [overlay {:state* (atom {})
                   :ops*   (atom [])
                   :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        ;; future conveys dynamic bindings, but the overlay is not ours
        (is (nil? (deref (future (context/current-overlay)) 2000 ::timeout))))))

  (testing "render-bindings stamps the creating thread as owner"
    (let [app-state* (atom {})
          bindings   (context/render-bindings {} app-state*)
          overlay    (get bindings #'context/*state-overlay*)]
      (is (identical? (Thread/currentThread) (:owner overlay))))))

(deftest flush-overlay-ownership-test
  (testing "flush replays the op-log on the owner thread only"
    (let [app-state* (atom {:x 0})
          overlay    {:state* (atom {:x 1})
                      :ops*   (atom [{:kind :reset :path [:x] :value 1}])
                      :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        ;; Non-owner thread flush is a no-op
        (deref (future (context/flush-overlay! app-state*)) 2000 ::timeout)
        (is (= 0 (:x @app-state*)) "foreign-thread flush should be a no-op")
        ;; Owner flush replays the recorded op
        (context/flush-overlay! app-state*)
        (is (= 1 (:x @app-state*)))))))

(deftest apply-ops-test
  (testing ":update composes its fn with the current value at the path"
    (is (= {:m {:rows [:a :b] :status :ok}}
           (context/apply-ops {:m {:rows [:a :b]}}
                              [{:kind :update :path [:m] :f #(assoc % :status :ok)}]))))

  (testing ":reset overwrites the exact path (overwrite-this-value semantics)"
    (is (= {:m {:status :new}}
           (context/apply-ops {:m {:rows [:a]}}
                              [{:kind :reset :path [:m] :value {:status :new}}]))))

  (testing ":cas writes only when the live value still equals the expected old"
    (is (= {:x 2} (context/apply-ops {:x 1} [{:kind :cas :path [:x] :old 1 :new 2}]))
        "matching old → applies")
    (is (= {:x 9} (context/apply-ops {:x 9} [{:kind :cas :path [:x] :old 1 :new 2}]))
        "diverged old → no-op (yields to the concurrent value)"))

  (testing ":init writes only when the path is still absent"
    (is (= {:x 5} (context/apply-ops {} [{:kind :init :path [:x] :value 5}]))
        "absent path → applies the default")
    (is (= {:x nil} (context/apply-ops {:x nil} [{:kind :init :path [:x] :value 5}]))
        "explicit nil present → no-op (nil is a real value, not absent)")
    (is (= {:x 7} (context/apply-ops {:x 7} [{:kind :init :path [:x] :value 5}]))
        "existing value present → no-op"))

  (testing "an op in a session with no entry does nothing"
    (let [state {:sessions {"s" {:data {}}}}]
      (doseq [op [{:kind :update :f (constantly 1)}
                  {:kind :reset :value 1}
                  {:kind :cas :old nil :new 1}
                  {:kind :init :value 1}]]
        (is (= state (context/apply-ops state [(assoc op :path [:sessions "gone" :data :x])])))
        (is (= {:sessions {"s" {:data {:x 1}}}}
               (context/apply-ops state [(assoc op :path [:sessions "s" :data :x])]))))))

  (testing "ops are replayed in recorded order"
    (is (= {:x 3}
           (context/apply-ops {:x 0}
                              [{:kind :reset :path [:x] :value 1}
                               {:kind :update :path [:x] :f inc}
                               {:kind :update :path [:x] :f inc}])))))

;; ---------------------------------------------------------------------------
;; Overlay flush — buffering, atomicity, and reactive phases
;; ---------------------------------------------------------------------------

(def ^:private tab "t1")

(defn- fresh-app []
  (atom (assoc-in (state/init-state) [:tabs tab :data] {})))

(defn- live [app path]
  (get-in @app (into [:tabs tab :data] (if (vector? path) path [path]))))

(defn- overlay-for [app]
  {:state* (atom @app)
   :ops*   (atom [])
   :owner  (Thread/currentThread)})

(defmacro ^:private with-overlay
  "Run body under a batch-style overlay owned by this thread (no guard)."
  [app & body]
  `(binding [context/*state-overlay* (overlay-for ~app)]
     ~@body))

(defmacro ^:private with-render-context
  "Run body under a full render context — overlay plus an ACTIVE render
   guard, exactly like render-tab after guard-discard!.  Combined with
   {:render-guard :error} on the app, any effect judged as in-render throws."
  [app & body]
  `(with-bindings (context/render-bindings {:hyper/app-state ~app} ~app)
     (context/guard-discard!)
     ~@body))

(deftest overlay-buffers-writes-until-flush
  (testing "cursor writes under an overlay are invisible in live state until flush"
    (let [app (fresh-app)]
      (with-overlay app
        (let [x* (state/tab-cursor app tab :x)]
          (reset! x* 1)
          (is (nil? (live app :x)) "write is buffered, not live")
          (context/flush-overlay! app)
          (is (= 1 (live app :x)) "write lands at flush"))))))

(deftest overlay-read-your-writes
  (testing "reads under the overlay see buffered defaults and writes"
    (let [app (fresh-app)]
      (with-overlay app
        (let [x* (state/tab-cursor app tab :x 42)]
          (is (= 42 @x*) "default visible via shadow")
          (swap! x* inc)
          (is (= 43 @x*) "write visible via shadow")
          (is (nil? (live app :x)) "still not live"))))))

(deftest default-init-yields-to-concurrent-live-write
  (testing "a default-value init is a set-if-absent op that yields to a real
            write landing before flush, rather than clobbering it"
    (let [app (fresh-app)]
      (with-overlay app
        (state/tab-cursor app tab :x 42)
        ;; concurrent writer commits a real value before the flush
        (swap! app assoc-in [:tabs tab :data :x] 99)
        (context/flush-overlay! app)
        (is (= 99 (live app :x)))))))

(deftest default-init-preserves-explicit-nil-at-flush
  (testing "a default-value init yields even to a concurrent write of nil —
            an explicit nil is a real value, not 'unset', so it is not
            clobbered by the default at flush"
    (let [app (fresh-app)]
      (with-overlay app
        (state/tab-cursor app tab :x 42)
        ;; concurrent writer sets an explicit nil before the flush
        (swap! app assoc-in [:tabs tab :data :x] nil)
        (context/flush-overlay! app)
        (is (contains? (get-in @app [:tabs tab :data]) :x)
            "the key is present with a nil value")
        (is (nil? (live app :x)) "explicit nil survived the default init"))))

  (testing "a default-value init leaves an explicit nil already in live state
            untouched"
    (let [app (fresh-app)]
      (swap! app assoc-in [:tabs tab :data :x] nil)
      (with-overlay app
        (state/tab-cursor app tab :x 42)
        (context/flush-overlay! app))
      (is (nil? (live app :x))))))

(deftest flush-drops-writes-to-a-removed-session
  (testing "writes and defaults buffered for a session removed before the flush
            do not bring it back; the writes warn"
    (let [app (atom (assoc-in (state/init-state) [:sessions "s"] {:data {} :tabs #{tab}}))]
      (with-overlay app
        (state/session-cursor app "s" :theme "dark")
        (reset! (state/session-cursor app "s" :user) "u")
        (swap! app update :sessions dissoc "s")
        (let [{:keys [signals]} (t/with-signals (context/flush-overlay! app))]
          (is (not (contains? (:sessions @app) "s")))
          (is (= [[:sessions "s" :data :user]]
                 (keep #(when (= :hyper.warn/session-cursor-write-dropped (:id %))
                          (get-in % [:data :hyper/path]))
                       signals))))))))

(deftest pre-flush-writes-land-in-one-transition
  (testing "all user writes buffered before the flush commit in a single
            atomic swap — a watcher never observes one without the other"
    (let [app         (fresh-app)
          transitions (atom [])]
      (add-watch app ::obs
                 (fn [_ _ old new]
                   (swap! transitions conj
                          {:a [(get-in old [:tabs tab :data :a])
                               (get-in new [:tabs tab :data :a])]
                           :b [(get-in old [:tabs tab :data :b])
                               (get-in new [:tabs tab :data :b])]})))
      (with-overlay app
        (reset! (state/tab-cursor app tab :a) 1)
        (reset! (state/tab-cursor app tab :b) 2)
        (context/flush-overlay! app))
      (remove-watch app ::obs)
      (is (= 1 (count @transitions)) "exactly one live transition")
      (is (= {:a [nil 1] :b [nil 2]} (first @transitions))
          "both writes visible in the same transition"))))

(deftest reaction-write-during-flush-lands
  (testing "a cursor written by a watch callback during the flush is applied
            (next phase), not dropped — and raises no render-guard error even
            at :render-guard :error (the missionary m/watch-into-cursor case)"
    (let [app (doto (fresh-app) (swap! assoc :render-guard :error))]
      (with-render-context app
        (let [_entry* (state/tab-cursor app tab :entry {:side :buy})
              alloc*  (state/tab-cursor app tab :alloc)]
          (add-watch app ::derive
                     (fn [_ _ old new]
                       (let [p [:tabs tab :data :entry]]
                         (when (not= (get-in old p) (get-in new p))
                           (reset! alloc* :computed)))))
          (context/flush-overlay! app)))
      (remove-watch app ::derive)
      (is (= {:side :buy} (live app :entry)) "source default landed")
      (is (= :computed (live app :alloc)) "derived write landed, not dropped"))))

(deftest reaction-cascade-converges
  (testing "chained reactions (a -> b -> c) converge across flush phases"
    (let [app (fresh-app)]
      (with-overlay app
        (let [a* (state/tab-cursor app tab :a)
              b* (state/tab-cursor app tab :b)
              c* (state/tab-cursor app tab :c)]
          (add-watch app ::a->b
                     (fn [_ _ old new]
                       (let [p [:tabs tab :data :a]]
                         (when (not= (get-in old p) (get-in new p))
                           (reset! b* (inc (get-in new p)))))))
          (add-watch app ::b->c
                     (fn [_ _ old new]
                       (let [p [:tabs tab :data :b]]
                         (when (not= (get-in old p) (get-in new p))
                           (reset! c* (inc (get-in new p)))))))
          (reset! a* 1)
          (context/flush-overlay! app)))
      (remove-watch app ::a->b)
      (remove-watch app ::b->c)
      (is (= 1 (live app :a)))
      (is (= 2 (live app :b)))
      (is (= 3 (live app :c))))))

(deftest ops-apply-exactly-once-across-phases
  (testing "a buffered swap! op is applied exactly once even when reactions
            force additional flush phases"
    (let [app (fresh-app)]
      (with-overlay app
        (let [n*     (state/tab-cursor app tab :n 0)
              other* (state/tab-cursor app tab :other)]
          (add-watch app ::second-phase
                     (fn [_ _ old new]
                       (let [p [:tabs tab :data :n]]
                         (when (and (not= (get-in old p) (get-in new p))
                                    (nil? (get-in new [:tabs tab :data :other])))
                           (reset! other* :done)))))
          (swap! n* inc)
          (context/flush-overlay! app)))
      (remove-watch app ::second-phase)
      (is (= 1 (live app :n)) "inc applied exactly once, not re-applied per phase")
      (is (= :done (live app :other)) "second phase ran"))))

(deftest non-converging-cycle-throws
  (testing "a watch that keeps writing on every phase (value never reaches a
            fixpoint) fails loudly instead of looping or silently dropping"
    (let [app (fresh-app)]
      (with-overlay app
        (let [x* (state/tab-cursor app tab :x 0)]
          (add-watch app ::cycle
                     (fn [_ _ old new]
                       (let [p [:tabs tab :data :x]]
                         (when (not= (get-in old p) (get-in new p))
                           (swap! x* inc)))))
          (swap! x* inc)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not converge"
                                (context/flush-overlay! app)))))
      (remove-watch app ::cycle))))
