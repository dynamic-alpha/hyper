(ns hyper.state-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [hyper.state :as state]
            [malli.core :as m]
            [malli.transform :as mt]))

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

(deftest cursor-scope-metadata-test
  (let [app-state* (atom (state/init-state))]
    (testing "scoped constructors stamp :hyper/scope and :hyper/path"
      (is (= {:hyper/scope :global :hyper/path [:theme]}
             (meta (state/global-cursor app-state* :theme))))
      (is (= {:hyper/scope :session :hyper/path [:user :name]}
             (meta (state/session-cursor app-state* "s1" [:user :name]))))
      (is (= {:hyper/scope :tab :hyper/path [:count]}
             (meta (state/tab-cursor app-state* "t1" :count)))))

    (testing "default-value arities stamp the same metadata"
      (is (= {:hyper/scope :global :hyper/path [:n]}
             (meta (state/global-cursor app-state* :n 0))))
      (is (= {:hyper/scope :session :hyper/path [:cols 0 :width]}
             (meta (state/session-cursor app-state* "s1" [:cols 0 :width] 240))))
      (is (= {:hyper/scope :tab :hyper/path [:draft]}
             (meta (state/tab-cursor app-state* "t1" :draft "")))))

    (testing "raw create-cursor carries no scope metadata"
      (is (= {} (meta (state/create-cursor app-state* [:custom] :x)))))))

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
          tab-id     "test_tab_1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (contains? (:tabs @app-state*) tab-id))
      (is (contains? (get-in @app-state* [:sessions session-id :tabs]) tab-id))
      (is (= session-id (get-in @app-state* [:tabs tab-id :session-id])))))

  (testing "doesn't overwrite existing tab data"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-4"
          tab-id     "test_tab_2"]
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
          tab-id     "test_tab_3"
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
          tab-id     "test_tab_4"
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
          tab-id     "test_tab_5"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (contains? (:tabs @app-state*) tab-id))
      (state/cleanup-tab! app-state* tab-id)
      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is (not (contains? (get-in @app-state* [:sessions session-id :tabs]) tab-id)))))

  (testing "cleanup-session! removes session and all tabs"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-15"
          tab-id-1   "test_tab_6"
          tab-id-2   "test_tab_7"]
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
          tab-id     "test_tab_8"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (let [cursor (state/tab-cursor app-state* tab-id :items [])]
        (is (= [] @cursor))
        (is (= [] (get-in @app-state* [:tabs tab-id :data :items]))))))

  (testing "tab-cursor with default value doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-19"
          tab-id     "test_tab_9"]
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

(deftest cursor-default-value-preserves-explicit-nil-test
  (testing "session-cursor default does not overwrite an explicit nil"
    (let [app-state* (atom (state/init-state))
          session-id "sess-nil"]
      (state/get-or-create-session! app-state* session-id)
      ;; first render initializes an absent path
      (is (= "default" @(state/session-cursor app-state* session-id :sel "default")))
      ;; a component writes nil (e.g. "nothing selected")
      (reset! (state/session-cursor app-state* session-id :sel) nil)
      (is (contains? (get-in @app-state* [:sessions session-id :data]) :sel)
          "the key is present with a nil value, not absent")
      ;; next render re-creates the cursor with the same default — nil must survive
      (is (nil? @(state/session-cursor app-state* session-id :sel "default")))))

  (testing "tab-cursor default does not overwrite an explicit nil"
    (let [app-state* (atom (state/init-state))
          session-id "sess-nil-2"
          tab-id     "tab_nil"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (= "default" @(state/tab-cursor app-state* tab-id :sel "default")))
      (reset! (state/tab-cursor app-state* tab-id :sel) nil)
      (is (nil? @(state/tab-cursor app-state* tab-id :sel "default")))))

  (testing "global-cursor default does not overwrite an explicit nil"
    (let [app-state* (atom (state/init-state))]
      (is (= "default" @(state/global-cursor app-state* :sel "default")))
      (reset! (state/global-cursor app-state* :sel) nil)
      (is (nil? @(state/global-cursor app-state* :sel "default")))))

  (testing "nested-path default does not overwrite an explicit nil"
    (let [app-state* (atom (state/init-state))
          session-id "sess-nil-3"]
      (state/get-or-create-session! app-state* session-id)
      (is (= 0 @(state/session-cursor app-state* session-id [:a :b] 0)))
      (reset! (state/session-cursor app-state* session-id [:a :b]) nil)
      (is (nil? @(state/session-cursor app-state* session-id [:a :b] 0))))))

(deftest init-default!-test
  (testing "initializes an absent path"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-session! app-state* "s")
      (let [cursor (state/session-cursor app-state* "s" :k)]
        (state/init-default! cursor 42)
        (is (= 42 @cursor)))))

  (testing "leaves an explicit nil in place"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-session! app-state* "s")
      (swap! app-state* assoc-in [:sessions "s" :data :k] nil)
      (let [cursor (state/session-cursor app-state* "s" :k)]
        (state/init-default! cursor 42)
        (is (nil? @cursor)))))

  (testing "leaves an existing non-nil value in place"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-session! app-state* "s")
      (swap! app-state* assoc-in [:sessions "s" :data :k] 7)
      (let [cursor (state/session-cursor app-state* "s" :k)]
        (state/init-default! cursor 42)
        (is (= 7 @cursor)))))

  (testing "returns the cursor"
    (let [app-state* (atom (state/init-state))]
      (state/get-or-create-session! app-state* "s")
      (let [cursor (state/session-cursor app-state* "s" :k)]
        (is (identical? cursor (state/init-default! cursor 1)))))))

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
          tab-id-1     "tab_g1"
          tab-id-2     "tab_g2"]
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

(deftest build-url-test
  (testing "returns path when query params are nil or empty"
    (is (= "/search" (state/build-url "/search" nil)))
    (is (= "/search" (state/build-url "/search" {}))))

  (testing "omits query params with nil values"
    (is (= "/search" (state/build-url "/search" {:q nil})))
    (is (= "/search?q=clojure"
           (state/build-url "/search" {:q "clojure" :page nil}))))

  (testing "preserves empty query param values"
    (is (= "/search?q="
           (state/build-url "/search" {:q ""}))))

  (testing "encodes keyword values without the leading colon"
    (is (= "/items?view=grid"
           (state/build-url "/items" {:view :grid})))
    (is (= "/items?view=a%2Fgrid"
           (state/build-url "/items" {:view :a/grid}))))

  (testing "writes collection values as repeated query params, skipping nils"
    (is (= "/items?tags=a&tags=b"
           (state/build-url "/items" {:tags [:a nil "b"]})))
    (is (= "/items"
           (state/build-url "/items" {:tags []}))))

  (testing "keyword values round-trip through malli string coercion"
    (let [schema [:map [:view [:enum :grid :list]]]
          url    (state/build-url "/items" {:view :grid})
          parsed (state/parse-query-string (second (clojure.string/split url #"\?")))]
      (is (= {:view :grid}
             (m/decode schema parsed (mt/string-transformer)))))))

(deftest parse-query-string-test
  (testing "returns nil for a nil query string"
    (is (nil? (state/parse-query-string nil))))

  (testing "decodes keys and values"
    (is (= {:q "a b" :empty ""}
           (state/parse-query-string "q=a+b&empty="))))

  (testing "collects repeated keys into a vector"
    (is (= {:tags ["a" "b" "c"] :view "grid"}
           (state/parse-query-string "tags=a&view=grid&tags=b&tags=c"))))

  (testing "round-trips with build-url"
    (let [url (state/build-url "/items" {:tags ["a" "b"] :q "x&y"})]
      (is (= {:tags ["a" "b"] :q "x&y"}
             (state/parse-query-string (second (clojure.string/split url #"\?"))))))))
