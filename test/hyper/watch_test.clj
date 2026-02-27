(ns hyper.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.render :as render]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.watch :as watch]))

(deftest test-watchers
  (testing "Watchers trigger callback on state change"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-4"
          tab-id          "test-tab-4"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          render-fn       (fn [_req]
                            [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      ;; Change tab state
      (swap! app-state* assoc-in [:tabs tab-id :data :count] 1)
      (Thread/sleep 50)

      (is (>= @trigger-count 1))

      ;; Change session state
      (swap! app-state* assoc-in [:sessions session-id :data :user] "Alice")
      (Thread/sleep 50)

      (is (>= @trigger-count 2))

      ;; Change global state
      (swap! app-state* assoc-in [:global :theme] "dark")
      (Thread/sleep 50)

      (is (>= @trigger-count 3))

      ;; Clean up watchers
      (watch/remove-watchers! app-state* tab-id))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-5"
          tab-id          "test-tab-5"
          stopped?        (atom false)
          trigger-render! (fn [])
          render-fn       (fn [_req] [:div "test"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Store a mock renderer handle with a stop! fn
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!
              :stop!           #(reset! stopped? true)})

      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))

      ;; Cleanup
      (server/cleanup-tab! app-state* tab-id)

      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is @stopped? "Renderer stop! should have been called"))))

(deftest test-external-watch
  (testing "watch-source! triggers callback when source changes"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-ext"
          tab-id          "test-tab-ext"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (watch/watch-source! app-state* tab-id trigger-render! external-atom)

      ;; Mutate the external atom
      (swap! external-atom inc)
      (Thread/sleep 50)

      (is (= 1 @trigger-count) "trigger-render! should have been called")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))
