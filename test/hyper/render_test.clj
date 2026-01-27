(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.render :as render]
            [hyper.core :as hy]
            [hyper.state :as state]))

(deftest test-sse-channel-registration
  (testing "SSE channel registration and retrieval"
    (let [tab-id "test-tab-1"
          channel {:mock true}]
      ;; Register channel
      (render/register-sse-channel! tab-id channel)

      ;; Retrieve channel
      (is (= channel (render/get-sse-channel tab-id)))

      ;; Unregister channel
      (render/unregister-sse-channel! tab-id)

      ;; Verify it's gone
      (is (nil? (render/get-sse-channel tab-id))))))

(deftest test-render-fn-registration
  (testing "Render function registration and retrieval"
    (let [tab-id "test-tab-2"
          render-fn (fn [_req] [:div "test"])]
      ;; Register render function
      (render/register-render-fn! tab-id render-fn)

      ;; Retrieve render function
      (is (= render-fn (render/get-render-fn tab-id)))

      ;; Clean up
      (swap! render/render-fns dissoc tab-id))))

(deftest test-datastar-fragment-format
  (testing "Datastar fragment formatting"
    (let [html "<div><h1>Hello, Datastar!</h1></div>"
          selector "body"
          fragment (render/format-datastar-fragment html selector)]

      ;; Check format components
      (is (.contains fragment "event: datastar-fragment"))
      (is (.contains fragment "data: fragment"))
      (is (.contains fragment html))
      (is (.contains fragment selector))
      (is (.endsWith fragment "\n\n")))))

(deftest test-connected-tabs
  (testing "Get connected tabs and count"
    (let [tab-id-1 "test-tab-3"
          tab-id-2 "test-tab-4"
          channel-1 {:mock 1}
          channel-2 {:mock 2}]

      ;; Start fresh
      (reset! render/sse-channels {})

      ;; Register channels
      (render/register-sse-channel! tab-id-1 channel-1)
      (render/register-sse-channel! tab-id-2 channel-2)

      ;; Check count
      (is (= 2 (render/tab-count)))

      ;; Check tab IDs
      (let [tab-ids (set (render/get-connected-tabs))]
        (is (contains? tab-ids tab-id-1))
        (is (contains? tab-ids tab-id-2)))

      ;; Clean up
      (render/unregister-sse-channel! tab-id-1)
      (render/unregister-sse-channel! tab-id-2)

      ;; Verify cleanup
      (is (= 0 (render/tab-count))))))

(deftest test-watchers-trigger-rerenders
  (testing "State changes trigger re-renders via watchers"
    (let [session-id "test-session-watch"
          tab-id "test-tab-watch"
          render-count (atom 0)
          rendered-value (atom nil)]

      ;; Initialize state
      (state/get-or-create-session-state! session-id)
      (state/get-or-create-tab-state! tab-id)

      ;; Set initial tab state
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id}]
        (reset! (hy/tab-cursor :count) 0))

      ;; Register render function that tracks calls
      (render/register-render-fn!
       tab-id
       (fn [_req]
         (swap! render-count inc)
         (binding [hy/*request* {:hyper/session-id session-id
                                 :hyper/tab-id tab-id}]
           (let [count @(hy/tab-cursor :count)]
             (reset! rendered-value count)
             [:div [:h1 "Count: " count]]))))

      ;; Setup watchers
      (render/setup-watchers! session-id tab-id)

      ;; Change state and wait for watcher to fire
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id}]
        (swap! (hy/tab-cursor :count) inc))

      ;; Wait for future to complete
      (Thread/sleep 100)

      ;; Verify render was called
      (is (= 1 @render-count))
      (is (= 1 @rendered-value))

      ;; Change state again
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id}]
        (reset! (hy/tab-cursor :count) 42))

      ;; Wait for future to complete
      (Thread/sleep 100)

      ;; Verify render was called again
      (is (= 2 @render-count))
      (is (= 42 @rendered-value))

      ;; Clean up
      (render/cleanup-tab! session-id tab-id))))

(deftest test-watchers-no-trigger-on-same-value
  (testing "Watchers don't trigger on identity updates"
    (let [session-id "test-session-same"
          tab-id "test-tab-same"
          render-count (atom 0)]

      ;; Initialize state
      (state/get-or-create-session-state! session-id)
      (state/get-or-create-tab-state! tab-id)

      ;; Set initial value
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id}]
        (reset! (hy/tab-cursor :count) 42))

      ;; Register render function
      (render/register-render-fn!
       tab-id
       (fn [_req]
         (swap! render-count inc)
         [:div "test"]))

      ;; Setup watchers
      (render/setup-watchers! session-id tab-id)

      ;; Reset to same value
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id}]
        (reset! (hy/tab-cursor :count) 42))

      ;; Wait a bit
      (Thread/sleep 100)

      ;; Verify render was NOT called (value didn't change)
      (is (= 0 @render-count))

      ;; Clean up
      (render/cleanup-tab! session-id tab-id))))

(deftest test-cleanup-tab
  (testing "cleanup-tab! removes all resources"
    (let [session-id "test-session-cleanup"
          tab-id "test-tab-cleanup"
          channel {:mock true}
          render-fn (fn [_req] [:div "test"])]

      ;; Setup everything
      (state/get-or-create-session-state! session-id)
      (state/get-or-create-tab-state! tab-id)
      (render/register-sse-channel! tab-id channel)
      (render/register-render-fn! tab-id render-fn)
      (render/setup-watchers! session-id tab-id)

      ;; Verify setup
      (is (= channel (render/get-sse-channel tab-id)))
      (is (= render-fn (render/get-render-fn tab-id)))

      ;; Clean up
      (render/cleanup-tab! session-id tab-id)

      ;; Verify cleanup
      (is (nil? (render/get-sse-channel tab-id)))
      (is (nil? (render/get-render-fn tab-id)))
      (is (nil? (state/get-tab-atom tab-id))))))
