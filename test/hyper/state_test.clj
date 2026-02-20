(ns hyper.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.state :as state]))

(deftest normalize-path-test
  (testing "converts keyword to vector"
    (is (= [:user] (state/normalize-path :user))))

  (testing "converts vector path to vector (idempotent)"
    (is (= [:user :name] (state/normalize-path [:user :name]))))

  (testing "handles single keyword"
    (is (= [:count] (state/normalize-path :count)))))

(deftest init-state-test
  (testing "creates initial state structure"
    (let [state (state/init-state)]
      (is (map? state))
      (is (contains? state :sessions))
      (is (contains? state :tabs))
      (is (contains? state :actions))
      (is (= {} (:sessions state)))
      (is (= {} (:tabs state)))
      (is (= {} (:actions state))))))

(deftest session-management-test
  (testing "creates new session"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"]
      (state/get-or-create-session! app-state* session-id)
      (is (contains? (:sessions @app-state*) session-id))
      (is (= {:data {} :tabs #{}}
             (get-in @app-state* [:sessions session-id])))))

  (testing "doesn't overwrite existing session"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-2"]
      (state/get-or-create-session! app-state* session-id)
      (swap! app-state* assoc-in [:sessions session-id :data :foo] :bar)
      (state/get-or-create-session! app-state* session-id)
      (is (= :bar (get-in @app-state* [:sessions session-id :data :foo]))))))

(deftest tab-management-test
  (testing "creates new tab and links to session"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id     "test-tab-1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (contains? (:tabs @app-state*) tab-id))
      (is (contains? (get-in @app-state* [:sessions session-id :tabs]) tab-id))
      (is (= session-id (get-in @app-state* [:tabs tab-id :session-id])))))

  (testing "doesn't overwrite existing tab data"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-4"
          tab-id     "test-tab-2"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :data :count] 42)
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (= 42 (get-in @app-state* [:tabs tab-id :data :count]))))))

(deftest cursor-deref-test
  (testing "deref returns nil for missing path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-5"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :user)]
        (is (nil? @cursor)))))

  (testing "deref returns value at path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-6"]
      (state/get-or-create-session! app-state* session-id)
      (swap! app-state* assoc-in [:sessions session-id :data :user :name] "Alice")
      (let [cursor (state/session-cursor app-state* session-id [:user :name])]
        (is (= "Alice" @cursor)))))

  (testing "deref with vector path"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-tab-3"
          session-id "test-session-7"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :data :todos :list] [1 2 3])
      (let [cursor (state/tab-cursor app-state* tab-id [:todos :list])]
        (is (= [1 2 3] @cursor))))))

(deftest cursor-reset-test
  (testing "reset! sets value at cursor path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-8"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :count)]
        (reset! cursor 42)
        (is (= 42 @cursor))
        (is (= 42 (get-in @app-state* [:sessions session-id :data :count]))))))

  (testing "reset! with nested path"
    (let [app-state* (atom (state/init-state))
          tab-id     "test-tab-4"
          session-id "test-session-9"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (let [cursor (state/tab-cursor app-state* tab-id [:user :email])]
        (reset! cursor "test@example.com")
        (is (= "test@example.com" @cursor))
        (is (= "test@example.com" (get-in @app-state* [:tabs tab-id :data :user :email])))))))

(deftest cursor-swap-test
  (testing "swap! with single arg function"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-10"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :count)]
        (reset! cursor 10)
        (swap! cursor inc)
        (is (= 11 @cursor))
        (is (= 11 (get-in @app-state* [:sessions session-id :data :count]))))))

  (testing "swap! with function and args"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-11"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :count)]
        (reset! cursor 5)
        (swap! cursor + 10)
        (is (= 15 @cursor))
        (swap! cursor + 3 2)
        (is (= 20 @cursor))))))

(deftest cursor-watch-test
  (testing "cursor watchers fire on change"
    (let [app-state*  (atom (state/init-state))
          session-id  "test-session-12"
          watch-calls (atom [])]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :count)]
        (add-watch cursor :test-watch
                   (fn [_k _r old-val new-val]
                     (swap! watch-calls conj {:old old-val :new new-val})))
        (reset! cursor 1)
        (reset! cursor 2)
        (Thread/sleep 10) ;; Give watch time to fire
        (is (= 2 (count @watch-calls)))
        (is (= nil (get-in @watch-calls [0 :old])))
        (is (= 1 (get-in @watch-calls [0 :new])))
        (is (= 1 (get-in @watch-calls [1 :old])))
        (is (= 2 (get-in @watch-calls [1 :new]))))))

  (testing "cursor watchers can be removed"
    (let [app-state*  (atom (state/init-state))
          session-id  "test-session-13"
          watch-calls (atom 0)]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :count)]
        (add-watch cursor :test-watch
                   (fn [_k _r _old _new] (swap! watch-calls inc)))
        (reset! cursor 1)
        (remove-watch cursor :test-watch)
        (reset! cursor 2)
        (Thread/sleep 10)
        (is (= 1 @watch-calls)))))) ;; Only fired once

(deftest cleanup-test
  (testing "cleanup-tab! removes tab and unlinks from session"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-14"
          tab-id     "test-tab-5"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (contains? (:tabs @app-state*) tab-id))
      (state/cleanup-tab! app-state* tab-id)
      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is (not (contains? (get-in @app-state* [:sessions session-id :tabs]) tab-id)))))

  (testing "cleanup-session! removes session and all tabs"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-15"
          tab-id-1   "test-tab-6"
          tab-id-2   "test-tab-7"]
      (state/get-or-create-tab! app-state* session-id tab-id-1)
      (state/get-or-create-tab! app-state* session-id tab-id-2)
      (is (contains? (:sessions @app-state*) session-id))
      (is (contains? (:tabs @app-state*) tab-id-1))
      (is (contains? (:tabs @app-state*) tab-id-2))
      (state/cleanup-session! app-state* session-id)
      (is (not (contains? (:sessions @app-state*) session-id)))
      (is (not (contains? (:tabs @app-state*) tab-id-1)))
      (is (not (contains? (:tabs @app-state*) tab-id-2))))))

(deftest cursor-default-value-test
  (testing "session-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-16"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id :counter 0)]
        (is (= 0 @cursor))
        (is (= 0 (get-in @app-state* [:sessions session-id :data :counter]))))))

  (testing "session-cursor with default value doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-17"]
      (state/get-or-create-session! app-state* session-id)
      (swap! app-state* assoc-in [:sessions session-id :data :counter] 42)
      (let [cursor (state/session-cursor app-state* session-id :counter 0)]
        (is (= 42 @cursor)))))

  (testing "tab-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-18"
          tab-id     "test-tab-8"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (let [cursor (state/tab-cursor app-state* tab-id :items [])]
        (is (= [] @cursor))
        (is (= [] (get-in @app-state* [:tabs tab-id :data :items]))))))

  (testing "tab-cursor with default value doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-19"
          tab-id     "test-tab-9"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :data :items] [1 2 3])
      (let [cursor (state/tab-cursor app-state* tab-id :items [])]
        (is (= [1 2 3] @cursor)))))

  (testing "cursor with nested path and default value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-20"]
      (state/get-or-create-session! app-state* session-id)
      (let [cursor (state/session-cursor app-state* session-id [:user :preferences] {})]
        (is (= {} @cursor))
        (is (= {} (get-in @app-state* [:sessions session-id :data :user :preferences])))))))

(deftest global-cursor-test
  (testing "global-cursor reads/writes to global state"
    (let [app-state* (atom (state/init-state))
          cursor     (state/global-cursor app-state* :theme)]
      (is (nil? @cursor))
      (reset! cursor "dark")
      (is (= "dark" @cursor))
      (is (= "dark" (get-in @app-state* [:global :theme])))))

  (testing "global-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          cursor     (state/global-cursor app-state* :user-count 0)]
      (is (= 0 @cursor))
      (is (= 0 (get-in @app-state* [:global :user-count])))))

  (testing "global-cursor with default value doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))]
      (swap! app-state* assoc-in [:global :user-count] 42)
      (let [cursor (state/global-cursor app-state* :user-count 0)]
        (is (= 42 @cursor)))))

  (testing "global-cursor swap! works"
    (let [app-state* (atom (state/init-state))
          cursor     (state/global-cursor app-state* :counter 0)]
      (swap! cursor inc)
      (is (= 1 @cursor))
      (swap! cursor + 10)
      (is (= 11 @cursor))))

  (testing "global-cursor with nested path"
    (let [app-state* (atom (state/init-state))
          cursor     (state/global-cursor app-state* [:config :feature-flags] #{})]
      (is (= #{} @cursor))
      (swap! cursor conj :dark-mode)
      (is (= #{:dark-mode} @cursor))
      (is (= #{:dark-mode} (get-in @app-state* [:global :config :feature-flags])))))

  (testing "global-cursor is shared across sessions and tabs"
    (let [app-state*   (atom (state/init-state))
          session-id-1 "session-g1"
          session-id-2 "session-g2"
          tab-id-1     "tab-g1"
          tab-id-2     "tab-g2"]
      (state/get-or-create-tab! app-state* session-id-1 tab-id-1)
      (state/get-or-create-tab! app-state* session-id-2 tab-id-2)
      ;; Both cursors point to the same global state
      (let [cursor-a (state/global-cursor app-state* :shared-count 0)
            cursor-b (state/global-cursor app-state* :shared-count 0)]
        (swap! cursor-a inc)
        (is (= 1 @cursor-a))
        (is (= 1 @cursor-b))
        (swap! cursor-b + 5)
        (is (= 6 @cursor-a))
        (is (= 6 @cursor-b))))))
