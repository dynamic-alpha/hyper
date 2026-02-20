(ns hyper.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.core :as hy]
            [hyper.state :as state]
            [reitit.ring :as ring]))

(deftest test-global-cursor
  (testing "global-cursor requires request context"
    (is (thrown? Exception
                 (hy/global-cursor :theme))))

  (testing "global-cursor creates cursor to global state"
    (let [app-state* (atom (state/init-state))]
      (binding [hy/*request* {:hyper/session-id "s1"
                              :hyper/tab-id "t1"
                              :hyper/app-state app-state*}]
        (let [cursor (hy/global-cursor :theme)]
          (reset! cursor "dark")
          (is (= "dark" @cursor))
          (is (= "dark" (get-in @app-state* [:global :theme])))))))

  (testing "global-cursor with default value"
    (let [app-state* (atom (state/init-state))]
      (binding [hy/*request* {:hyper/session-id "s1"
                              :hyper/tab-id "t1"
                              :hyper/app-state app-state*}]
        (let [cursor (hy/global-cursor :counter 0)]
          (is (= 0 @cursor))
          (swap! cursor inc)
          (is (= 1 @cursor))))))

  (testing "global-cursor is shared across different tab contexts"
    (let [app-state* (atom (state/init-state))]
      ;; Write from tab 1
      (binding [hy/*request* {:hyper/session-id "s1"
                              :hyper/tab-id "t1"
                              :hyper/app-state app-state*}]
        (reset! (hy/global-cursor :shared 0) 42))
      ;; Read from tab 2 in a different session
      (binding [hy/*request* {:hyper/session-id "s2"
                              :hyper/tab-id "t2"
                              :hyper/app-state app-state*}]
        (is (= 42 @(hy/global-cursor :shared 0)))))))

(deftest test-session-cursor
  (testing "session-cursor requires request context"
    (is (thrown? Exception
                 (hy/session-cursor :user))))

  (testing "session-cursor creates cursor to session state"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"]
      (state/get-or-create-session! app-state* session-id)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/session-cursor :user)]
          (reset! cursor {:name "Alice"})
          (is (= {:name "Alice"} @cursor))
          (is (= {:name "Alice"} (get-in @app-state* [:sessions session-id :data :user]))))))))

(deftest test-tab-cursor
  (testing "tab-cursor requires request context"
    (is (thrown? Exception
                 (hy/tab-cursor :count))))

  (testing "tab-cursor creates cursor to tab state"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-2"
          tab-id "test-tab-1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/tab-cursor :count)]
          (reset! cursor 42)
          (is (= 42 @cursor))
          (is (= 42 (get-in @app-state* [:tabs tab-id :data :count]))))))))

(deftest test-action-macro
  (testing "action requires request context"
    (is (thrown? Exception
                 (hy/action (println "test")))))

  (testing "action registers and returns click handler"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id "test-tab-2"
          executed (atom false)]
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [action-attrs (hy/action (reset! executed true))]
          (is (map? action-attrs))
          (is (contains? action-attrs :data-on:click))
          (is (.contains (str (:data-on:click action-attrs)) "@post"))
          (is (.contains (str (:data-on:click action-attrs)) "/hyper/actions"))
          (is (.contains (str (:data-on:click action-attrs)) "action-id="))

          ;; Extract action ID and execute it
          (let [action-id (second (re-find #"action-id=([^']+)" (str (:data-on:click action-attrs))))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]))
            (is @executed)))))))

(defn- make-test-context
  "Create a test request context with a router for navigate tests."
  [routes]
  (let [app-state* (atom (state/init-state))
        session-id "test-session-nav"
        tab-id "test-tab-nav"
        router (ring/router (mapv (fn [[path data]] [path data]) routes)
                            {:conflicts nil})]
    (state/get-or-create-tab! app-state* session-id tab-id)
    (swap! app-state* assoc :router router :routes routes)
    {:hyper/session-id session-id
     :hyper/tab-id tab-id
     :hyper/app-state app-state*
     :hyper/router router}))

(deftest test-navigate
  (testing "navigate generates href and action-based click handler with pushState"
    (let [routes [["/" {:name :home :get (fn [_] [:div "Home"])}]
                  ["/about" {:name :about :get (fn [_] [:div "About"])}]
                  ["/users/:id" {:name :user-profile :get (fn [_] [:div "User"])}]]
          ctx (make-test-context routes)]

      (testing "without params"
        (binding [hy/*request* ctx]
          (let [nav-attrs (hy/navigate :home)]
            (is (map? nav-attrs))
            (is (= "/" (:href nav-attrs)))
            (is (contains? nav-attrs :data-on:click__prevent))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "@post"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "/hyper/actions"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState")))))

      (testing "with path params"
        (binding [hy/*request* ctx]
          (let [nav-attrs (hy/navigate :user-profile {:id "123"})]
            (is (= "/users/123" (:href nav-attrs)))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "/users/123")))))

      (testing "with query params"
        (binding [hy/*request* ctx]
          (let [nav-attrs (hy/navigate :home nil {:q "clojure"})]
            (is (= "/?q=clojure" (:href nav-attrs)))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState")))))

      (testing "returns nil for unknown route"
        (binding [hy/*request* ctx]
          (is (nil? (hy/navigate :nonexistent)))))))

  (testing "navigate action updates render fn and route state"
    (let [home-fn (fn [_] [:div "Home"])
          about-fn (fn [_] [:div "About"])
          routes [["/" {:name :home :get home-fn}]
                  ["/about" {:name :about :get about-fn}]]
          ctx (make-test-context routes)
          app-state* (:hyper/app-state ctx)
          tab-id (:hyper/tab-id ctx)]

      (binding [hy/*request* ctx]
        (let [nav-attrs (hy/navigate :about)]
          ;; Extract and execute the action
          (let [action-id (second (re-find #"action-id=([^']+)"
                                           (str (:data-on:click__prevent nav-attrs))))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]))
            ;; Route state should be updated
            (let [route (state/get-tab-route app-state* tab-id)]
              (is (= :about (:name route)))
              (is (= "/about" (:path route))))
            ;; Render fn should be swapped
            (is (= about-fn (get-in @app-state* [:tabs tab-id :render-fn])))))))))

(deftest test-create-handler
  (testing "creates handler with default app-state"
    (let [routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          handler (hy/create-handler routes)]
      (is (fn? handler))))

  (testing "creates handler with provided app-state"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          handler (hy/create-handler routes :app-state app-state*)]
      (is (fn? handler))
      (is (= app-state* app-state*)))))

(deftest test-server-lifecycle
  (testing "start! and stop! work together"
    (let [routes [["/" {:name :home
                        :get (fn [_req] [:div "Test"])}]]
          handler (hy/create-handler routes)
          server (hy/start! handler {:port 13010})]
      (is (some? server))
      (is (fn? server))
      (hy/stop! server))))

(deftest test-cursor-with-default-values
  (testing "session-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-4"]
      (state/get-or-create-session! app-state* session-id)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/session-cursor :counter 0)]
          (is (= 0 @cursor))
          (is (= 0 (get-in @app-state* [:sessions session-id :data :counter])))))))

  (testing "session-cursor with default doesn't overwrite existing value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-5"]
      (state/get-or-create-session! app-state* session-id)
      (swap! app-state* assoc-in [:sessions session-id :data :counter] 99)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/session-cursor :counter 0)]
          (is (= 99 @cursor))))))

  (testing "tab-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-6"
          tab-id "test-tab-3"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/tab-cursor :items [])]
          (is (= [] @cursor))
          (is (= [] (get-in @app-state* [:tabs tab-id :data :items])))))))

  (testing "tab-cursor with default doesn't overwrite existing value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-7"
          tab-id "test-tab-4"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :data :items] [1 2 3])
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/tab-cursor :items [])]
          (is (= [1 2 3] @cursor))))))

  (testing "nested path with default value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-8"
          tab-id "test-tab-5"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/tab-cursor [:config :theme] "light")]
          (is (= "light" @cursor))
          (is (= "light" (get-in @app-state* [:tabs tab-id :data :config :theme]))))))))

(deftest test-path-cursor
  (testing "path-cursor requires request context"
    (is (thrown? Exception
                 (hy/path-cursor :count))))

  (testing "path-cursor reads/writes to route query params"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-1"
          tab-id "test-tab-path-1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; Seed route state
      (state/set-tab-route! app-state* tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/path-cursor :count 0)]
          (is (= 0 @cursor))
          ;; Write updates route query params
          (reset! cursor 5)
          (is (= 5 @cursor))
          (is (= 5 (get-in @app-state* [:tabs tab-id :route :query-params :count])))))))

  (testing "path-cursor with default doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-2"
          tab-id "test-tab-path-2"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name :search :path "/search"
                             :path-params {} :query-params {:q "clojure"}})
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/path-cursor :q "")]
          (is (= "clojure" @cursor))))))

  (testing "path-cursor swap! works"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-3"
          tab-id "test-tab-path-3"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (binding [hy/*request* {:hyper/session-id session-id
                              :hyper/tab-id tab-id
                              :hyper/app-state app-state*}]
        (let [cursor (hy/path-cursor :count 0)]
          (swap! cursor inc)
          (is (= 1 @cursor))
          (swap! cursor + 10)
          (is (= 11 @cursor)))))))
