(ns hyper.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
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

(deftest test-pending-watches-stash
  (testing "watch! with no trigger-render! stashes source under :pending-watches"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-pending"
          tab-id        "test-tab-pending"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; No renderer set up — trigger-render! will be nil
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Should be stashed under :pending-watches
      (let [pending (get-in @app-state* [:tabs tab-id :pending-watches])]
        (is (some? pending) "pending-watches should exist")
        (is (= 1 (count pending)) "should have exactly one pending watch")
        (is (= external-atom (first (vals pending)))
            "pending watch value should be the external atom"))

      ;; Should NOT have a real watch on the atom yet
      (is (empty? (.getWatches external-atom))
          "external atom should have no real watches yet"))))

(deftest test-pending-watches-stash-idempotent
  (testing "watch! stashing the same source twice is idempotent"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-idem"
          tab-id        "test-tab-idem"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom)
        (h/watch! external-atom))

      (let [pending (get-in @app-state* [:tabs tab-id :pending-watches])]
        (is (= 1 (count pending))
            "duplicate watch! calls should not create multiple entries")))))

(deftest test-promote-pending-watches
  (testing "promote-pending-watches! creates real watches and clears pending"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-promote"
          tab-id          "test-tab-promote"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Stash via watch! with no renderer
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Verify stashed
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :pending-watches]))))

      ;; Promote
      (watch/promote-pending-watches! app-state* tab-id trigger-render!)

      ;; :pending-watches should be cleared
      (is (nil? (get-in @app-state* [:tabs tab-id :pending-watches]))
          "pending-watches should be cleared after promotion")

      ;; Real watch should exist on the atom
      (is (= 1 (count (.getWatches external-atom)))
          "external atom should have a real watch after promotion")

      ;; Watch should be tracked under :watches in tab state
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :watches])))
          "promoted watch should be tracked under :watches")

      ;; Mutating the atom should trigger render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count) "trigger-render! should fire when source changes")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))

(deftest test-watch-with-trigger-render-still-works
  (testing "watch! with trigger-render! present still creates real watches directly"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-direct"
          tab-id          "test-tab-direct"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Set up a renderer so trigger-render! is available
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!})

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Should NOT use pending-watches path
      (is (nil? (get-in @app-state* [:tabs tab-id :pending-watches]))
          "should not stash when trigger-render! is present")

      ;; Real watch should exist directly
      (is (= 1 (count (.getWatches external-atom)))
          "real watch should be on the atom")

      ;; Mutating the atom should trigger render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count))

      ;; Calling watch! again with the same source is idempotent
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (= 1 (count (.getWatches external-atom)))
          "duplicate watch! should not create additional watches")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))

(deftest test-cleanup-clears-pending-watches
  (testing "cleanup-tab! clears pending-watches along with the whole tab"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-cleanup-pw"
          tab-id        "test-tab-cleanup-pw"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Stash a pending watch
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (some? (get-in @app-state* [:tabs tab-id :pending-watches])))

      ;; Cleanup the tab
      (server/cleanup-tab! app-state* tab-id)

      ;; Tab should be completely gone
      (is (not (contains? (:tabs @app-state*) tab-id))))))
