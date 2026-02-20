(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.core]
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
        (render/setup-watchers! app-state* session-id tab-id #'hyper.core/*request*)

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
        (render/remove-watchers! app-state* tab-id)))))

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
      (render/setup-watchers! app-state* session-id tab-id #'hyper.core/*request*)

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
        (render/throttled-render-and-send! app-state* session-id tab-id #'hyper.core/*request*)
        (is (= 1 @render-count))

        ;; Immediate second render is throttled
        (render/throttled-render-and-send! app-state* session-id tab-id #'hyper.core/*request*)
        (is (= 1 @render-count))

        ;; After throttle period, render succeeds
        (Thread/sleep 20)
        (render/throttled-render-and-send! app-state* session-id tab-id #'hyper.core/*request*)
        (is (= 2 @render-count)))))

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
