(ns hyper.async-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]))

(defn- wait-for
  "Poll `pred` until truthy or ~1s elapses.  Returns the truthy value or false."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (loop []
      (or (pred)
          (if (> (System/currentTimeMillis) deadline)
            false
            (do (Thread/sleep 5) (recur)))))))

(defn- cell [app-state* tab-id component-id]
  (get-in @app-state* [:tabs tab-id :async component-id :cell]))

(defmacro ^:private rendering
  "Evaluate body as a render of `tab-id` (so *action-idx* keys async by call
   order, as in production)."
  [app-state* tab-id & body]
  `(binding [context/*request*    {:hyper/session-id "s"
                                   :hyper/tab-id     ~tab-id
                                   :hyper/app-state  ~app-state*
                                   :hyper/router     nil}
             context/*action-idx* (atom 0)]
     ~@body))

(deftest test-partial-render-has-route-and-env
  (testing "a reactive region's partial re-render sees :hyper/route and
            :hyper/env, matching the full render (regression: partial-render
            used to omit them, so h/route/h/env returned nil on re-render)"
    (let [app-state* (atom (state/init-state))
          dep        (atom 0)]
      (state/get-or-create-tab! app-state* "s1" "t1")
      (state/set-tab-route! app-state* "t1"
                            {:name :dash :path "/dash" :path-params {} :query-params {}})
      (swap! app-state* assoc-in [:tabs "t1" :env] {:db :prod})
      (swap! app-state* assoc :router :some-router)
      (swap! app-state* assoc-in [:tabs "t1" :subviews "r_t1_x"]
             {:render-fn   (fn [] [:div (str "route=" (:name (h/route))
                                             " env=" (pr-str (h/env)))])
              :deps        [dep]                                         :dep-vals  [0]      :html-id "r_t1_x"
              :region-path []                                            :on-change :partial})
      (let [html (subview/partial-render app-state* "t1" "r_t1_x")]
        (is (re-find #"route=:dash" html) "h/route resolves during partial render")
        (is (re-find #"env=\{:db :prod\}" html) "h/env resolves during partial render")))))

(deftest test-async-loading-then-ready
  (testing "first render is :loading; the worker landing flips the cell to
            :ready and a partial re-render shows the result"
    (let [app-state* (atom (state/init-state))
          gate       (promise)
          fetched    (atom 0)]
      (state/get-or-create-tab! app-state* "s" "t")
      (let [render! (fn []
                      (rendering app-state* "t"
                                 (h/async []
                                          (do @gate (swap! fetched inc) :rows)
                                          {:keys [status result]}
                                          [:div.region (str status "|" (pr-str result))])))
            html    (render!)]
        (testing "registers a render-scoped, partial-on-change region + store"
          (let [sv (get-in @app-state* [:tabs "t" :subviews "async_t_1"])]
            (is (= [:div.region {:id "async_t_1"} ":loading|nil"] html)
                "placeholder rendered with the region id injected")
            (is (= :render (:scope sv)))
            (is (= :partial (:on-change sv)))
            (is (some #{(cell app-state* "t" "async_t_1")} (:deps sv))
                "the status cell is a dep of the region")
            (is (= :loading (:status @(cell app-state* "t" "async_t_1"))))))

        ;; Let the fetch complete.
        (deliver gate true)
        (is (wait-for #(= :ready (:status @(cell app-state* "t" "async_t_1"))))
            "cell flips to :ready when the worker lands")
        (is (= {:status :ready :result :rows} @(cell app-state* "t" "async_t_1")))
        (is (= 1 @fetched) "fetched exactly once")
        (is (= "<div data-hyper-render-version=\"1\" id=\"async_t_1\" class=\"region\">:ready|:rows</div>"
               (subview/partial-render app-state* "t" "async_t_1"))
            "a partial re-render renders the ready result")
        (subview/teardown-all! app-state* "t")))))

(deftest test-async-completion-enqueues-partial
  (testing "the status cell is a wired dep — completion enqueues a partial
            re-render of the region"
    (let [app-state* (atom (state/init-state))
          gate       (promise)
          enqueued   (atom [])]
      (state/get-or-create-tab! app-state* "s" "t")
      (rendering app-state* "t"
                 (h/async [] (do @gate :v)
                          {:keys [status]} [:div (str status)]))
      ;; Wire dep watches as the first SSE full render does.
      (subview/setup-new-watches! app-state* "t" nil #(swap! enqueued conj %))
      (deliver gate true)
      (is (wait-for #(some #{"async_t_1"} @enqueued))
          "writing the cell enqueues a partial render of the region")
      (subview/teardown-all! app-state* "t"))))

(deftest test-async-error
  (testing ":error carries the throwable; :result stays nil"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-tab! app-state* "s" "t")
      (rendering app-state* "t"
                 (h/async [] (throw (ex-info "boom" {:x 1}))
                          {:keys [status]} [:div (str status)]))
      (is (wait-for #(= :error (:status @(cell app-state* "t" "async_t_1")))))
      (let [c @(cell app-state* "t" "async_t_1")]
        (is (= :error (:status c)))
        (is (nil? (:result c)))
        (is (= "boom" (ex-message (:error c)))))
      (subview/teardown-all! app-state* "t"))))

(deftest test-async-nil-result-distinct-from-loading
  (testing "a fetch resolving to nil is {:status :ready :result nil} — not
            confusable with loading"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-tab! app-state* "s" "t")
      (rendering app-state* "t"
                 (h/async [] nil {:keys [status result]} [:div (str status result)]))
      (is (wait-for #(= :ready (:status @(cell app-state* "t" "async_t_1")))))
      (is (= {:status :ready :result nil} @(cell app-state* "t" "async_t_1")))
      (subview/teardown-all! app-state* "t"))))

(deftest test-async-reloading-on-dep-change
  (testing "a dep change refetches stale-while-revalidate: :reloading keeps the
            prior result, then :ready with the new value; unchanged deps do not
            refetch"
    (let [app-state* (atom (state/init-state))
          dep*       (atom :a)
          fetched    (atom 0)
          render!    (fn []
                       (rendering app-state* "t"
                                  (h/async [dep*]
                                           (do (swap! fetched inc) (Thread/sleep 30)
                                               (keyword (str "rows-" (name @dep*))))
                                           {:keys [status result]}
                                           [:div (str status "|" (pr-str result))])))]
      (state/get-or-create-tab! app-state* "s" "t")
      (render!)
      (is (wait-for #(= :ready (:status @(cell app-state* "t" "async_t_1")))))
      (is (= :rows-a (:result @(cell app-state* "t" "async_t_1"))))

      ;; Change the dep and re-render: coordination sets :reloading synchronously,
      ;; keeping the stale result, and kicks off a fresh fetch.
      (reset! dep* :b)
      (let [reloading-html (render!)]
        (is (= [:div {:id "async_t_1"} ":reloading|:rows-a"] reloading-html)
            ":reloading render keeps the stale result"))
      (is (wait-for #(= :rows-b (:result @(cell app-state* "t" "async_t_1"))))
          "refetch lands the new value")
      (is (= :ready (:status @(cell app-state* "t" "async_t_1"))))
      (is (= 2 @fetched) "refetched exactly once on the dep change")

      ;; Re-render with the dep unchanged — no refetch.
      (render!) (render!)
      (is (= 2 @fetched) "unchanged deps do not refetch")
      (subview/teardown-all! app-state* "t"))))

(deftest test-async-sweep-interrupts-and-clears
  (testing "when the region leaves the view tree it is swept: in-flight fetch
            interrupted, store dropped, subview removed"
    (let [app-state*  (atom (state/init-state))
          interrupted (promise)]
      (state/get-or-create-tab! app-state* "s" "t")
      (rendering app-state* "t"
                 (h/async []
                          (try (Thread/sleep 60000)
                               (catch InterruptedException _
                                 (deliver interrupted true)
                                 (throw (InterruptedException.))))
                          {:keys [status]} [:div (str status)]))
      (is (some? (get-in @app-state* [:tabs "t" :async "async_t_1"])))
      ;; Region absent from this render's live set -> swept.
      (subview/sweep-stale! app-state* "t" #{})
      (is (true? (deref interrupted 1000 :timeout)) "in-flight fetch interrupted")
      (is (nil? (get-in @app-state* [:tabs "t" :async "async_t_1"])) "store cleared")
      (is (nil? (get-in @app-state* [:tabs "t" :subviews "async_t_1"])) "subview removed"))))

(deftest test-async-disconnect-interrupts
  (testing "tab disconnect (cleanup-tab!) interrupts an in-flight fetch"
    (let [app-state*  (atom (state/init-state))
          interrupted (promise)]
      (state/get-or-create-tab! app-state* "s" "t")
      (rendering app-state* "t"
                 (h/async []
                          (try (Thread/sleep 60000)
                               (catch InterruptedException _
                                 (deliver interrupted true)
                                 (throw (InterruptedException.))))
                          {:keys [status]} [:div (str status)]))
      (server/cleanup-tab! app-state* "t")
      (is (true? (deref interrupted 1000 :timeout)))
      (is (not (contains? (:tabs @app-state*) "t"))))))
