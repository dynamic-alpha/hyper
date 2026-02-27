(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.core :as hy]
            [hyper.render :as render]
            [hyper.state :as state]))

(deftest test-sse-channel-registration
  (testing "SSE channel registration and retrieval"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"
          tab-id     "test-tab-1"
          channel    {:mock true}]
      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Register channel
      (render/register-sse-channel! app-state* tab-id channel false)

      ;; Retrieve channel
      (is (= channel (render/get-sse-channel app-state* tab-id)))

      ;; Unregister channel
      (render/unregister-sse-channel! app-state* tab-id)

      ;; Verify it's gone
      (is (nil? (render/get-sse-channel app-state* tab-id))))))

(deftest test-send-sse-does-not-block-on-brotli
  (testing "send-sse! should not invoke streaming brotli compression on the caller thread"
    (let [executor   (java.util.concurrent.Executors/newSingleThreadExecutor)
          app-state* (atom (assoc (state/init-state) :executor executor))
          session-id "test-session-sse-async"
          tab-id     "test-tab-sse-async"
          channel    {:mock true}
          allow      (promise)
          sent       (java.util.concurrent.CountDownLatch. 1)]
      (try
        (state/get-or-create-tab! app-state* session-id tab-id)

        (with-redefs [hyper.brotli/byte-array-out-stream (fn [] ::out)
                      hyper.brotli/compress-out-stream   (fn [_out & _] ::stream)
                      hyper.brotli/compress-stream       (fn [_out _stream _message]
                                                         ;; Block until the test releases us.
                                                           @allow
                                                           (.getBytes "ok" "UTF-8"))
                      org.httpkit.server/send!           (fn [_ch _data _close?]
                                                           (.countDown sent)
                                                           true)]
          (render/register-sse-channel! app-state* tab-id channel true)

          ;; If send-sse! calls brotli inline (current behavior), this future will block.
          ;; Under the writer-actor implementation it should return immediately.
          (let [f (future (render/send-sse! app-state* tab-id "hello"))]
            (is (not= ::timeout (deref f 50 ::timeout))
                "send-sse! blocked on brotli compression; expected it to enqueue and return")

            ;; Always release the compressor so we don't leak a stuck thread if the assertion fails.
            (deliver allow true)

            ;; Drain should eventually send.
            (is (.await sent 2 java.util.concurrent.TimeUnit/SECONDS)
                "expected an SSE send after unblocking brotli")))
        (finally
          (render/unregister-sse-channel! app-state* tab-id)
          (.shutdownNow executor))))))

(deftest test-sse-brotli-stream-is-single-writer
  (testing "streaming brotli compression must never be invoked concurrently for a tab"
    (let [app-executor   (java.util.concurrent.Executors/newSingleThreadExecutor)
          send-executor  (java.util.concurrent.Executors/newFixedThreadPool 2)
          app-state*     (atom (assoc (state/init-state) :executor app-executor))
          session-id     "test-session-sse-single-writer"
          tab-id         "test-tab-sse-single-writer"
          channel        {:mock true}
          in-flight*     (atom 0)
          max-in-flight* (atom 0)
          sent*          (atom [])
          sent-any       (java.util.concurrent.CountDownLatch. 1)
          start          (java.util.concurrent.CountDownLatch. 1)
          done           (java.util.concurrent.CountDownLatch. 2)]
      (try
        (state/get-or-create-tab! app-state* session-id tab-id)

        (with-redefs [hyper.brotli/byte-array-out-stream (fn [] ::out)
                      hyper.brotli/compress-out-stream   (fn [_out & _] ::stream)
                      hyper.brotli/compress-stream       (fn [_out _stream message]
                                                           (let [n (swap! in-flight* inc)]
                                                             (swap! max-in-flight* max n)
                                                            ;; Make the call long-lived so overlaps are detectable.
                                                             (Thread/sleep 50)
                                                             (swap! in-flight* dec))
                                                           (.getBytes (str message) "UTF-8"))
                      org.httpkit.server/send!           (fn [_ch data _close?]
                                                           (let [payload (if (map? data) (:body data) data)
                                                                 s       (if (bytes? payload)
                                                                           (String. ^bytes payload "UTF-8")
                                                                           (str payload))]
                                                             (swap! sent* conj s))
                                                           (.countDown sent-any)
                                                           true)]
          (render/register-sse-channel! app-state* tab-id channel true)

          (doseq [i (range 2)]
            (.submit send-executor
                     ^Runnable
                     (fn []
                       (.await start)
                       (render/send-sse! app-state* tab-id (str "msg-" i))
                       (.countDown done))))

          (.countDown start)

          (is (.await done 2 java.util.concurrent.TimeUnit/SECONDS)
              "timed out waiting for send-sse! calls to return")

          ;; The actor may batch multiple messages into a single send.
          (is (.await sent-any 2 java.util.concurrent.TimeUnit/SECONDS)
              "timed out waiting for SSE sends")

          ;; Wait until we observe both messages (either batched or separate).
          (is (let [deadline (+ (System/currentTimeMillis) 2000)]
                (loop []
                  (let [combined (apply str @sent*)]
                    (cond
                      (and (.contains combined "msg-0")
                           (.contains combined "msg-1")) true
                      (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
                      :else false))))
              "timed out waiting for both SSE messages to be sent")

          (is (<= @max-in-flight* 1)
              (str "brotli compress-stream was invoked concurrently (max in-flight = "
                   @max-in-flight* ")"))

          ;; Stop the actor so it doesn't leak a thread across the test suite.
          (render/unregister-sse-channel! app-state* tab-id))
        (finally
          (.shutdownNow send-executor)
          (.shutdownNow app-executor))))))

(deftest test-render-fn-registration
  (testing "Render function registration and retrieval"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-2"
          tab-id     "test-tab-2"
          render-fn  (fn [_req] [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Register render function
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Retrieve render function
      (is (= render-fn (render/get-render-fn app-state* tab-id))))))

(deftest test-datastar-fragment-format
  (testing "Datastar patch-elements formatting"
    (let [html     "<div><h1>Hello, Datastar!</h1></div>"
          fragment (render/format-datastar-fragment html)]
      ;; Should start with event type
      (is (.startsWith fragment "event: datastar-patch-elements\n"))
      ;; Should include data line with elements prefix
      (is (.contains fragment "data: elements "))
      ;; Should include html content
      (is (.contains fragment html))
      ;; Should end with double newline
      (is (.endsWith fragment "\n\n"))))

  (testing "Formats different HTML content"
    (let [html     "<span>test</span>"
          fragment (render/format-datastar-fragment html)]
      (is (.contains fragment html))
      (is (.startsWith fragment "event: datastar-patch-elements\n")))))

(deftest test-render-and-send
  (testing "Renders and formats content for SSE"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-3"
          tab-id        "test-tab-3"
          sent-messages (atom [])
          ;; Use a simple map instead of reify since AsyncChannel is a class not interface
          _mock-channel {:send (fn [data _close?]
                                 (swap! sent-messages conj data)
                                 true)}
          render-fn     (fn [_req] [:div "Hello World"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Manually test formatting since we can't actually send with mock
      (let [hiccup-result (render-fn {})
            html-str      (c/html hiccup-result)
            fragment      (render/format-datastar-fragment html-str)]
        (is (.contains fragment "event: datastar-patch-elements"))
        (is (.contains fragment "Hello World"))))))

(deftest test-watchers
  (testing "Watchers trigger re-renders on state change"
    (let [app-state*    (atom (assoc (state/init-state)
                                     :executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)))
          session-id    "test-session-4"
          tab-id        "test-tab-4"
          sent-messages (atom [])
          ;; Create a mock that tracks sends
          mock-send!    (fn [_state* _tid msg]
                          (swap! sent-messages conj msg)
                          true)
          render-fn     (fn [_req]
                          [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id {:mock true} false)

      ;; Temporarily override send-sse! for testing
      (with-redefs [render/send-sse! mock-send!]
        (render/setup-watchers! app-state* session-id tab-id #'context/*request*)

        ;; Change tab state
        (swap! app-state* assoc-in [:tabs tab-id :data :count] 1)
        (Thread/sleep 50) ;; Wait for async render

        (is (>= (count @sent-messages) 1))

        ;; Change session state
        (swap! app-state* assoc-in [:sessions session-id :data :user] "Alice")
        (Thread/sleep 50)

        (is (>= (count @sent-messages) 2))

        ;; Change global state
        (swap! app-state* assoc-in [:global :theme] "dark")
        (Thread/sleep 50)

        (is (>= (count @sent-messages) 3))

        ;; Clean up watchers
        (render/remove-watchers! app-state* tab-id)
        (render/unregister-sse-channel! app-state* tab-id)))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state*   (atom (assoc (state/init-state)
                                    :executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)))
          session-id   "test-session-5"
          tab-id       "test-tab-5"
          mock-channel {:mock true}
          render-fn    (fn [_req] [:div "test"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id mock-channel false)
      (render/setup-watchers! app-state* session-id tab-id #'context/*request*)

      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (some? (render/get-sse-channel app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))

      ;; Cleanup
      (render/cleanup-tab! app-state* tab-id)

      (is (nil? (render/get-sse-channel app-state* tab-id)))
      (is (not (contains? (:tabs @app-state*) tab-id))))))

(deftest test-error-boundary
  (testing "safe-render catches errors and renders error fragment"
    (let [failing-render-fn (fn [_req] (throw (ex-info "Test error" {})))
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test-tab"}
          result            (render/safe-render failing-render-fn req)]
      ;; Should return HTML string or hiccup, not throw
      (is (or (string? result) (vector? result)))
      ;; Should contain error information
      (is (re-find #"Render Error" (str result)))))

  (testing "safe-render returns result when render succeeds"
    (let [working-render-fn (fn [_req] [:div [:h1 "Success"]])
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test-tab"}
          result            (render/safe-render working-render-fn req)]
      (is (= [:div [:h1 "Success"]] result)))))

(deftest test-render-throttling
  (testing "should-render? respects throttle timing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-6"
          tab-id     "test-tab-6"]
      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; First render should succeed
      (is (true? (render/should-render? app-state* tab-id)))

      ;; Immediate second render should be throttled (returns nil/falsey)
      (is (not (render/should-render? app-state* tab-id)))

      ;; After waiting longer than throttle period, should render again
      (Thread/sleep 20) ;; Default throttle is 16ms
      (is (true? (render/should-render? app-state* tab-id)))))

  (testing "throttled-render-and-send! respects throttling"
    (let [app-state*   (atom (state/init-state))
          session-id   "test-session-7"
          tab-id       "test-tab-7"
          render-count (atom 0)
          render-fn    (fn [_req]
                         (swap! render-count inc)
                         [:div "Render " @render-count])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id {:mock true} false)

      (with-redefs [render/send-sse! (fn [_state* _tid _msg] true)]
        ;; First render succeeds
        (render/throttled-render-and-send! app-state* session-id tab-id #'context/*request*)
        (is (= 1 @render-count))

        ;; Immediate second render is throttled
        (render/throttled-render-and-send! app-state* session-id tab-id #'context/*request*)
        (is (= 1 @render-count))

        ;; After throttle period, render succeeds
        (Thread/sleep 20)
        (render/throttled-render-and-send! app-state* session-id tab-id #'context/*request*)
        (is (= 2 @render-count)))

      (render/unregister-sse-channel! app-state* tab-id)))

  (testing "cleanup removes last-render-ms tracking"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-8"
          tab-id     "test-tab-8"]
      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Trigger render to set last-render-ms
      (render/should-render? app-state* tab-id)
      (is (some? (get-in @app-state* [:tabs tab-id :last-render-ms])))

      ;; Cleanup should remove entire tab including last-render-ms
      (render/cleanup-tab! app-state* tab-id)
      (is (nil? (get-in @app-state* [:tabs tab-id]))))))

(deftest test-mark-head-elements
  (testing "Single element gets marked"
    (is (= [:style {:data-hyper-head true} "body{}"]
           (render/mark-head-elements [:style "body{}"]))))

  (testing "Single element with existing attrs gets marked"
    (is (= [:link {:rel "stylesheet" :href "/a.css" :data-hyper-head true}]
           (render/mark-head-elements [:link {:rel "stylesheet" :href "/a.css"}]))))

  (testing "Sequence of elements all get marked"
    (is (= [[:style {:data-hyper-head true} "body{}"]
            [:link {:rel "stylesheet" :href "/b.css" :data-hyper-head true}]]
           (render/mark-head-elements
             [[:style "body{}"]
              [:link {:rel "stylesheet" :href "/b.css"}]]))))

  (testing "nil returns nil"
    (is (nil? (render/mark-head-elements nil)))))

(deftest test-head-update-format
  (testing "Head update sends a self-removing script event"
    (let [event (render/format-head-update "My Page" "<style data-hyper-head>body{}</style>")]
      ;; Should be a patch-elements event
      (is (.startsWith event "event: datastar-patch-elements\n"))
      ;; Should append to body
      (is (.contains event "data: mode append\n"))
      (is (.contains event "data: selector body\n"))
      ;; Should contain a self-removing script tag
      (is (.contains event "data: elements <script data-effect=\"el.remove()\">"))
      ;; Should set document.title
      (is (.contains event "document.title='My Page'"))
      ;; Should include head element swap logic
      (is (.contains event "[data-hyper-head]"))
      ;; Should end with double newline
      (is (.endsWith event "\n\n"))))
  (testing "Head update without extra head content only sets title"
    (let [event (render/format-head-update "Title Only" nil)]
      (is (.contains event "document.title='Title Only'"))
      ;; Should NOT contain head element removal/insertion JS
      (is (not (.contains event "[data-hyper-head]"))))))

(deftest test-actions-cleaned-between-renders
  (testing "Stale actions from a previous render are cleaned up when the next render produces fewer"
    (let [app-state*    (atom (assoc (state/init-state)
                                     :executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)))
          session-id    "test-session-actions"
          tab-id        "test-tab-actions"
          sent-messages (atom [])
          mock-send!    (fn [_state* _tid msg]
                          (swap! sent-messages conj msg)
                          true)
          ;; Render fn whose action count depends on state
          render-fn     (fn [_req]
                          (let [n (or (get-in @app-state* [:tabs tab-id :data :item-count]) 3)]
                            (into [:div]
                                  (for [i (range n)]
                                    [:button {:data-on:click (hy/action #(println "action" i))}
                                     (str "Button " i)]))))]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id {:mock true} false)

      (with-redefs [render/send-sse! mock-send!]
        (render/setup-watchers! app-state* session-id tab-id #'context/*request*)

        ;; Initial render: 3 items → 3 actions
        (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 3)
        (Thread/sleep 50)

        (let [tab-actions (fn []
                            (->> (:actions @app-state*)
                                 (filter (fn [[_k v]] (= (:tab-id v) tab-id)))
                                 count))]

          (is (= 3 (tab-actions)) "Should have 3 actions after first render")

          ;; Shrink to 1 item → should have exactly 1 action, not 3
          (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 1)
          (Thread/sleep 50)

          (is (= 1 (tab-actions)) "Stale actions should be cleaned up, only 1 remaining"))

        (render/remove-watchers! app-state* tab-id)
        (render/unregister-sse-channel! app-state* tab-id)))))
