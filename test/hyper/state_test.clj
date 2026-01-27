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

(deftest session-state-test
  (testing "creates new session state"
    (let [session-id "test-session-1"
          state-atom (state/get-or-create-session-state! session-id)]
      (is (some? state-atom))
      (is (= {} @state-atom))))

  (testing "returns existing session state"
    (let [session-id "test-session-2"
          state-atom-1 (state/get-or-create-session-state! session-id)
          _ (reset! state-atom-1 {:foo :bar})
          state-atom-2 (state/get-or-create-session-state! session-id)]
      (is (identical? state-atom-1 state-atom-2))
      (is (= {:foo :bar} @state-atom-2)))))

(deftest tab-state-test
  (testing "creates new tab state"
    (let [tab-id "test-tab-1"
          state-atom (state/get-or-create-tab-state! tab-id)]
      (is (some? state-atom))
      (is (= {} @state-atom))))

  (testing "returns existing tab state"
    (let [tab-id "test-tab-2"
          state-atom-1 (state/get-or-create-tab-state! tab-id)
          _ (reset! state-atom-1 {:count 5})
          state-atom-2 (state/get-or-create-tab-state! tab-id)]
      (is (identical? state-atom-1 state-atom-2))
      (is (= {:count 5} @state-atom-2)))))

(deftest cursor-deref-test
  (testing "deref returns nil for missing path"
    (let [cursor (state/session-cursor "test-session-3" :user)]
      (is (nil? @cursor))))

  (testing "deref returns value at path"
    (let [session-id "test-session-4"
          cursor (state/session-cursor session-id :user)
          _ (reset! cursor {:name "Alice"})]
      (is (= {:name "Alice"} @cursor))))

  (testing "deref works with nested paths"
    (let [session-id "test-session-5"
          user-cursor (state/session-cursor session-id :user)
          name-cursor (state/session-cursor session-id [:user :name])
          _ (reset! user-cursor {:name "Bob" :age 30})]
      (is (= "Bob" @name-cursor)))))

(deftest cursor-reset-test
  (testing "reset! updates cursor value"
    (let [cursor (state/tab-cursor "test-tab-3" :count)]
      (reset! cursor 10)
      (is (= 10 @cursor))))

  (testing "reset! on nested path updates parent"
    (let [session-id "test-session-6"
          user-cursor (state/session-cursor session-id :user)
          name-cursor (state/session-cursor session-id [:user :name])
          _ (reset! user-cursor {:name "Alice" :email "alice@example.com"})]
      (reset! name-cursor "Bob")
      (is (= {:name "Bob" :email "alice@example.com"} @user-cursor)))))

(deftest cursor-swap-test
  (testing "swap! with 1-arity function"
    (let [cursor (state/tab-cursor "test-tab-4" :count)]
      (reset! cursor 0)
      (swap! cursor inc)
      (is (= 1 @cursor))))

  (testing "swap! with 2-arity function"
    (let [cursor (state/tab-cursor "test-tab-5" :count)]
      (reset! cursor 10)
      (swap! cursor + 5)
      (is (= 15 @cursor))))

  (testing "swap! with 3-arity function"
    (let [cursor (state/tab-cursor "test-tab-6" :count)]
      (reset! cursor 10)
      (swap! cursor + 2 3)
      (is (= 15 @cursor))))

  (testing "swap! with variadic function"
    (let [cursor (state/tab-cursor "test-tab-7" :count)]
      (reset! cursor 10)
      (swap! cursor + 1 2 3 4)
      (is (= 20 @cursor)))))

(deftest cursor-watchers-test
  (testing "watchers trigger on cursor changes"
    (let [cursor (state/tab-cursor "test-tab-8" :count)
          changes (atom [])]
      (add-watch cursor :test-watch
                 (fn [_k _ref old-val new-val]
                   (swap! changes conj {:old old-val :new new-val})))
      (reset! cursor 1)
      (swap! cursor inc)
      (is (= [{:old nil :new 1}
              {:old 1 :new 2}]
             @changes))))

  (testing "watchers only trigger on specific path changes"
    (let [session-id "test-session-7"
          name-cursor (state/session-cursor session-id [:user :name])
          age-cursor (state/session-cursor session-id [:user :age])
          name-changes (atom [])
          age-changes (atom [])]
      (add-watch name-cursor :name-watch
                 (fn [_k _ref old-val new-val]
                   (swap! name-changes conj {:old old-val :new new-val})))
      (add-watch age-cursor :age-watch
                 (fn [_k _ref old-val new-val]
                   (swap! age-changes conj {:old old-val :new new-val})))
      ;; Change name - should only trigger name watcher
      (reset! name-cursor "Alice")
      ;; Change age - should only trigger age watcher
      (reset! age-cursor 30)
      (is (= [{:old nil :new "Alice"}] @name-changes))
      (is (= [{:old nil :new 30}] @age-changes))))

  (testing "remove-watch stops watcher notifications"
    (let [cursor (state/tab-cursor "test-tab-9" :count)
          changes (atom [])]
      (add-watch cursor :test-watch
                 (fn [_k _ref old-val new-val]
                   (swap! changes conj {:old old-val :new new-val})))
      (reset! cursor 1)
      (remove-watch cursor :test-watch)
      (reset! cursor 2)
      ;; Only the first change should be recorded
      (is (= [{:old nil :new 1}] @changes)))))

(deftest cursor-iref-compliance-test
  (testing "cursor implements IRef"
    (let [cursor (state/tab-cursor "test-tab-10" :value)]
      (is (instance? clojure.lang.IRef cursor))))

  (testing "cursor implements IAtom"
    (let [cursor (state/tab-cursor "test-tab-11" :value)]
      (is (instance? clojure.lang.IAtom cursor)))))

(deftest cleanup-test
  (testing "cleanup-session! removes session state"
    (let [session-id "test-session-cleanup"
          _ (state/get-or-create-session-state! session-id)
          _ (state/cleanup-session! session-id)]
      (is (nil? (state/get-session-atom session-id)))))

  (testing "cleanup-tab! removes tab state"
    (let [tab-id "test-tab-cleanup"
          _ (state/get-or-create-tab-state! tab-id)
          _ (state/cleanup-tab! tab-id)]
      (is (nil? (state/get-tab-atom tab-id))))))
