(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.render :as render]
            [hyper.state :as state]
            [hyper.core]
            [hiccup.core]))

(deftest test-sse-channel-registration
  (testing "SSE channel registration and retrieval"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"
          tab-id "test-tab-1"
          channel {:mock true}]
      (state/get-or-create-tab! app-state* session-id tab-id)
      
      ;; Register channel
      (render/register-sse-channel! app-state* tab-id channel)

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
          tab-id "test-tab-2"
          render-fn (fn [_req] [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      
      ;; Register render function
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Retrieve render function
      (is (= render-fn (render/get-render-fn app-state* tab-id))))))

(deftest test-datastar-fragment-format
  (testing "Datastar fragment formatting"
    (let [html "<div><h1>Hello, Datastar!</h1></div>"
          selector "body"
          fragment (render/format-datastar-fragment html selector)]
      ;; Should start with event type
      (is (.startsWith fragment "event: datastar-fragment\n"))
      ;; Should include data line
      (is (.contains fragment "data: fragment"))
      ;; Should include html content
      (is (.contains fragment html))
      ;; Should include selector
      (is (.contains fragment selector))
      ;; Should end with double newline
      (is (.endsWith fragment "\n\n"))))

  (testing "Formats with different selectors"
    (let [html "<span>test</span>"
          selector "#my-id"
          fragment (render/format-datastar-fragment html selector)]
      (is (.contains fragment selector))
      (is (.contains fragment html)))))

(deftest test-render-and-send
  (testing "Renders and formats content for SSE"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id "test-tab-3"
          sent-messages (atom [])
          ;; Use a simple map instead of reify since AsyncChannel is a class not interface
          _mock-channel {:send (fn [data _close?]
                                 (swap! sent-messages conj data)
                                 true)}
          render-fn (fn [_req] [:div "Hello World"])]
      
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      
      ;; Manually test formatting since we can't actually send with mock
      (let [hiccup-result (render-fn {})
            html-str (hiccup.core/html hiccup-result)
            fragment (render/format-datastar-fragment html-str "body innerHTML")]
        (is (.contains fragment "event: datastar-fragment"))
        (is (.contains fragment "Hello World"))))))

(deftest test-watchers
  (testing "Watchers trigger re-renders on state change"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-4"
          tab-id "test-tab-4"
          sent-messages (atom [])
          ;; Create a mock that tracks sends
          mock-send! (fn [_state* _tid msg]
                       (swap! sent-messages conj msg)
                       true)
          render-fn (fn [_req]
                      [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]
      
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id {:mock true})
      
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
        
        ;; Clean up watchers
        (render/remove-watchers! app-state* tab-id)))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-5"
          tab-id "test-tab-5"
          mock-channel {:mock true}
          render-fn (fn [_req] [:div "test"])]
      
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (render/register-sse-channel! app-state* tab-id mock-channel)
      (render/setup-watchers! app-state* session-id tab-id #'hyper.core/*request*)
      
      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (some? (render/get-sse-channel app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))
      
      ;; Cleanup
      (render/cleanup-tab! app-state* tab-id)
      
      (is (nil? (render/get-sse-channel app-state* tab-id)))
      (is (not (contains? (:tabs @app-state*) tab-id))))))
