(ns hyper.core-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as hy]
            [hyper.datastar :as datastar]
            [hyper.render :as render]
            [hyper.render.error :as render.error]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.test :as ht]
            [reitit.ring :as ring]))

(deftest test-global-cursor
  (testing "global-cursor requires request context"
    (is (thrown? Exception
                 (hy/global-cursor :theme))))

  (testing "global-cursor creates cursor to global state"
    (let [app-state* (atom (state/init-state))]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/global-cursor :theme)]
          (reset! cursor "dark")
          (is (= "dark" @cursor))
          (is (= "dark" (get-in @app-state* [:global :theme])))))))

  (testing "global-cursor with default value"
    (let [app-state* (atom (state/init-state))]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/global-cursor :counter 0)]
          (is (= 0 @cursor))
          (swap! cursor inc)
          (is (= 1 @cursor))))))

  (testing "global-cursor is shared across different tab contexts"
    (let [app-state* (atom (state/init-state))]
      ;; Write from tab 1
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (reset! (hy/global-cursor :shared 0) 42))
      ;; Read from tab 2 in a different session
      (binding [context/*request* {:hyper/session-id "s2"
                                   :hyper/tab-id     "t2"
                                   :hyper/app-state  app-state*}]
        (is (= 42 @(hy/global-cursor :shared 0)))))))

(deftest test-session-cursor
  (testing "session-cursor requires request context"
    (is (thrown? Exception
                 (hy/session-cursor :user))))

  (testing "session-cursor creates cursor to session state"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"]
      (state/get-or-create-session! app-state* session-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/app-state  app-state*}]
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
          tab-id     "test_tab_1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/tab-cursor :count)]
          (reset! cursor 42)
          (is (= 42 @cursor))
          (is (= 42 (get-in @app-state* [:tabs tab-id :data :count]))))))))

(deftest test-action-macro
  (testing "action requires request context"
    (is (thrown? Exception
                 (hy/action (println "test")))))

  (testing "action registers and returns a Datastar expression"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id     "test_tab_2"
          executed   (atom false)]
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (hy/action (reset! executed true))
              action-js   (str action-expr)]
          (is (datastar/datastar-expr? action-expr))
          (is (.contains action-js "@post"))
          (is (.contains action-js "/hyper/actions"))
          (is (.contains action-js "action-id="))

          ;; Extract action ID and execute it
          (let [action-id (second (re-find #"action-id=([^'&]+)" action-js))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) nil)
            (is @executed)))))))

(defn- make-test-context
  "Create a test request context with a router for navigate tests."
  [routes]
  (let [app-state* (atom (state/init-state))
        session-id "test-session-nav"
        tab-id     "test_tab_nav"
        router     (ring/router (mapv (fn [[path data]] [path data]) routes)
                                {:conflicts nil})]
    (state/get-or-create-tab! app-state* session-id tab-id)
    (swap! app-state* assoc :router router :routes routes)
    {:hyper/session-id session-id
     :hyper/tab-id     tab-id
     :hyper/app-state  app-state*
     :hyper/router     router}))

(deftest test-navigate
  (testing "navigate generates href and action-based click handler with pushState"
    (let [routes [["/" {:name :home :get (fn [_] [:div "Home"])}]
                  ["/about" {:name :about :get (fn [_] [:div "About"])}]
                  ["/users/:id" {:name :user-profile :get (fn [_] [:div "User"])}]]
          ctx    (make-test-context routes)]

      (testing "without params"
        (binding [context/*request* ctx]
          (let [nav-attrs (hy/navigate :home)]
            (is (map? nav-attrs))
            (is (= "/" (:href nav-attrs)))
            (is (contains? nav-attrs :data-on:click__prevent))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "@post"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "/hyper/actions"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState"))
            (is (.contains (str (:data-on:click__prevent nav-attrs))
                           "window.hyper.startNavigation('/');")))))

      (testing "with path params"
        (binding [context/*request* ctx]
          (let [nav-attrs (hy/navigate :user-profile {:id "123"})]
            (is (= "/users/123" (:href nav-attrs)))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState"))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "/users/123")))))

      (testing "with query params"
        (binding [context/*request* ctx]
          (let [nav-attrs (hy/navigate :home nil {:q "clojure"})]
            (is (= "/?q=clojure" (:href nav-attrs)))
            (is (.contains (str (:data-on:click__prevent nav-attrs)) "pushState")))))

      (testing "returns nil for unknown route"
        (binding [context/*request* ctx]
          (is (nil? (hy/navigate :nonexistent)))))))

  (testing "navigate action updates render fn and route state"
    (let [home-fn    (fn [_] [:div "Home"])
          about-fn   (fn [_] [:div "About"])
          routes     [["/" {:name :home :get home-fn}]
                      ["/about" {:name :about :get about-fn}]]
          ctx        (make-test-context routes)
          app-state* (:hyper/app-state ctx)
          tab-id     (:hyper/tab-id ctx)]

      (binding [context/*request* ctx]
        (let [nav-attrs (hy/navigate :about)
              action-id (second (re-find #"action-id=([^'&]+)"
                                         (str (:data-on:click__prevent nav-attrs))))
              _         (do (is (some? action-id))
                            ((get-in @app-state* [:actions action-id :fn]) nil))
              route     (state/get-tab-route app-state* tab-id)]
          (is (true? (get-in @app-state* [:actions action-id :navigation?]))
              "navigate marks its action so it skips the action queue")
          ;; Route state should be updated
          (is (= :about (:name route)))
          (is (= "/about" (:path route)))
          ;; Render fn should be swapped
          (is (= about-fn (get-in @app-state* [:tabs tab-id :render-fn]))))))))

(deftest test-create-handler
  (testing "creates handler with default app-state"
    (let [routes  [["/" {:name :home
                         :get  (fn [_req] [:div "Home"])}]]
          handler (hy/create-handler routes)]
      (is (fn? handler))))

  (testing "creates handler with provided app-state"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (hy/create-handler routes :app-state app-state*)]
      (is (fn? handler))
      (is (= app-state* app-state*))))

  (testing ":base-path is passed through to server and stored in app-state"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (hy/create-handler routes
                                        :app-state app-state*
                                        :base-path "/sub")]
      (is (= "/sub" (:base-path @app-state*)))))

  (testing ":not-found defaults to render.error/not-found when not supplied"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (hy/create-handler routes :app-state app-state*)]
      (is (= render.error/not-found (:not-found @app-state*)))))

  (testing ":not-found is passed through to server and stored in app-state"
    (let [app-state* (atom (state/init-state))
          not-found  (fn [_req] [:div "Nope"])
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (hy/create-handler routes
                                        :app-state app-state*
                                        :not-found not-found)]
      (is (= not-found (:not-found @app-state*)))))

  (testing "explicit :not-found nil disables the feature"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (hy/create-handler routes
                                        :app-state app-state*
                                        :not-found nil)]
      (is (nil? (:not-found @app-state*))))))

(deftest test-server-lifecycle
  (testing "start! and stop! work together"
    (let [routes  [["/" {:name :home
                         :get  (fn [_req] [:div "Test"])}]]
          handler (hy/create-handler routes)
          server  (hy/start! handler {:port 13010})]
      (is (some? server))
      (is (fn? server))
      (hy/stop! server))))

(deftest test-cursor-with-default-values
  (testing "session-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-4"]
      (state/get-or-create-session! app-state* session-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/session-cursor :counter 0)]
          (is (= 0 @cursor))
          (is (= 0 (get-in @app-state* [:sessions session-id :data :counter])))))))

  (testing "session-cursor with default doesn't overwrite existing value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-5"]
      (state/get-or-create-session! app-state* session-id)
      (swap! app-state* assoc-in [:sessions session-id :data :counter] 99)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/session-cursor :counter 0)]
          (is (= 99 @cursor))))))

  (testing "tab-cursor with default value initializes nil path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-6"
          tab-id     "test_tab_3"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/tab-cursor :items [])]
          (is (= [] @cursor))
          (is (= [] (get-in @app-state* [:tabs tab-id :data :items])))))))

  (testing "tab-cursor with default doesn't overwrite existing value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-7"
          tab-id     "test_tab_4"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :data :items] [1 2 3])
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/tab-cursor :items [])]
          (is (= [1 2 3] @cursor))))))

  (testing "nested path with default value"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-8"
          tab-id     "test_tab_5"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
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
          tab-id     "test_tab_path_1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; Seed route state
      (state/set-tab-route! app-state* tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/path-cursor :count 0)]
          (is (= 0 @cursor))
          ;; Write updates route query params
          (reset! cursor 5)
          (is (= 5 @cursor))
          (is (= 5 (get-in @app-state* [:tabs tab-id :route :query-params :count])))))))

  (testing "path-cursor with default doesn't overwrite existing"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-2"
          tab-id     "test_tab_path_2"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name        :search :path         "/search"
                             :path-params {}      :query-params {:q "clojure"}})
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/path-cursor :q "")]
          (is (= "clojure" @cursor))))))

  (testing "path-cursor stamps :hyper/scope and :hyper/path metadata"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-meta"
          tab-id     "test_tab_path_meta"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (is (= {:hyper/scope :path :hyper/path [:page]}
               (meta (hy/path-cursor :page 1)))))))

  (testing "path-cursor swap! works"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-path-3"
          tab-id     "test_tab_path_3"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [cursor (hy/path-cursor :count 0)]
          (swap! cursor inc)
          (is (= 1 @cursor))
          (swap! cursor + 10)
          (is (= 11 @cursor)))))))

(deftest test-action-with-client-params
  (testing "$value client param generates @post expression with hyper.encodeClientParams"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (reset! (hy/tab-cursor :query) $value)))]
          (is (string? action-expr))
          (is (.contains action-expr "@post("))
          (is (.contains action-expr "hyper.encodeClientParams"))
          (is (.contains action-expr "value:evt.target.value"))
          (let [action-id (second (re-find #"action-id=([^'&\"]+)" action-expr))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) {:value "hello"})
            (is (= "hello" (get-in @app-state* [:tabs tab-id :data :query]))))))))

  (testing "$checked client param generates @post expression"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (reset! (hy/tab-cursor :dark?) $checked)))]
          (is (.contains action-expr "checked:evt.target.checked"))
          (let [action-id (second (re-find #"action-id=([^'&\"]+)" action-expr))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) {:checked true})
            (is (= true (get-in @app-state* [:tabs tab-id :data :dark?]))))))))

  (testing "$key client param generates @post expression"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (reset! (hy/tab-cursor :last-key) $key)))]
          (is (.contains action-expr "key:evt.key"))
          (let [action-id (second (re-find #"action-id=([^'&\"]+)" action-expr))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) {:key "Enter"})
            (is (= "Enter" (get-in @app-state* [:tabs tab-id :data :last-key]))))))))

  (testing "$form-data client param generates @post expression"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (reset! (hy/tab-cursor :form) $form-data)))]
          (is (.contains action-expr "formData:Object.fromEntries"))
          (let [action-id (second (re-find #"action-id=([^'&\"]+)" action-expr))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) {:formData {:email "a@b.com" :name "Alice"}})
            (is (= {:email "a@b.com" :name "Alice"}
                   (get-in @app-state* [:tabs tab-id :data :form]))))))))

  (testing "no client params uses simple @post expression"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (swap! (hy/tab-cursor :count 0) inc)))]
          (is (.contains action-expr "@post("))
          (is (not (.contains action-expr "hyper.encodeClientParams")))))))

  (testing "multiple client params in single action"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-cp"
          tab-id     "test_tab_cp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (do (reset! (hy/tab-cursor :val) $value)
                                              (reset! (hy/tab-cursor :k) $key))))]
          (is (.contains action-expr "@post("))
          (is (.contains action-expr "hyper.encodeClientParams"))
          (is (.contains action-expr "value:evt.target.value"))
          (is (.contains action-expr "key:evt.key"))))))

  (testing "JS string with client params injects guard before @post"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-js"
          tab-id     "test_tab_js"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action {:when "evt.key === 'Enter'"}
                                          (reset! (hy/tab-cursor :val) $value)))]
          (is (string? action-expr))
          (is (.contains action-expr "evt.key === 'Enter'"))
          (is (.contains action-expr "@post("))
          (is (.contains action-expr "hyper.encodeClientParams"))
          (is (.contains action-expr "value:evt.target.value"))
          (is (.startsWith action-expr "evt.key === 'Enter' && "))
          ;; Verify action executes correctly with client params
          (let [action-id (second (re-find #"action-id=([^'&\"]+)" action-expr))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]) {:value "hello"})
            (is (= "hello" (get-in @app-state* [:tabs tab-id :data :val]))))))))

  (testing "empty :when string treated as no JS injection"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-js"
          tab-id     "test_tab_js"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action {:when ""} (reset! (hy/tab-cursor :val) $value)))]
          (is (.contains action-expr "@post("))
          (is (.contains action-expr "hyper.encodeClientParams"))
          (is (.contains action-expr "value:evt.target.value"))
          (is (not (.contains action-expr " && ")))))))

  (testing ":when accepts a runtime-evaluated form, e.g. (hy/expr ...)"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-js"
          tab-id     "test_tab_js"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action {:when (hy/expr (= evt.key "Enter"))}
                                          (reset! (hy/tab-cursor :val) $value)))]
          (is (.startsWith action-expr "(evt.key === \"Enter\") && "))
          (is (.contains action-expr "@post("))))))

  (testing ":when guard evaluating to a non-string throws"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-js"
          tab-id     "test_tab_js"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (is (thrown-with-msg?
              Exception #"must evaluate to a string"
              (hy/action {:when 42} (reset! (hy/tab-cursor :val) $value))))))))

(deftest test-base-path-action
  (testing "action macro uses /hyper/actions without :base-path"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-bp"
          tab-id     "test_tab_bp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (swap! (hy/tab-cursor :n 0) inc)))]
          (is (string/includes? action-expr "@post('/hyper/actions?"))))))

  (testing "action macro prefixes /hyper/actions with :base-path"
    (let [app-state* (atom (assoc (state/init-state) :base-path "/my-app"))
          session-id "test-session-bp"
          tab-id     "test_tab_bp"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (swap! (hy/tab-cursor :n 0) inc)))]
          (is (string/includes? action-expr "@post('/my-app/hyper/actions?"))
          (is (not (string/includes? action-expr "@post('/hyper/actions?")))))))

  (testing "action macro with client params also prefixes the URL"
    (let [app-state* (atom (assoc (state/init-state) :base-path "/sub"))
          session-id "test-session-bp2"
          tab-id     "test_tab_bp2"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action (reset! (hy/tab-cursor :v) $value)))]
          (is (string/includes? action-expr "@post('/sub/hyper/actions?"))
          (is (string/includes? action-expr "hyper.encodeClientParams"))))))

  (testing "action macro with :when guard and :base-path"
    (let [app-state* (atom (assoc (state/init-state) :base-path "/app"))
          session-id "test-session-bp3"
          tab-id     "test_tab_bp3"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (let [action-expr (str (hy/action {:when "evt.key === 'Enter'"}
                                          (swap! (hy/tab-cursor :n 0) inc)))]
          (is (string/includes? action-expr "@post('/app/hyper/actions?"))
          (is (string/starts-with? action-expr "evt.key === 'Enter' && ")))))))

(deftest test-base-path-navigate
  (testing "navigate uses /hyper/actions without :base-path"
    (let [routes [["/" {:name :home :get (fn [_] [:div "Home"])}]
                  ["/about" {:name :about :get (fn [_] [:div "About"])}]]
          ctx    (make-test-context routes)]
      (binding [context/*request* ctx]
        (let [nav-attrs (hy/navigate :about)]
          (is (string/includes? (str (:data-on:click__prevent nav-attrs))
                                "@post('/hyper/actions?"))))))

  (testing "navigate prefixes /hyper/actions with :base-path"
    (let [routes     [["/" {:name :home :get (fn [_] [:div "Home"])}]
                      ["/about" {:name :about :get (fn [_] [:div "About"])}]]
          app-state* (atom (assoc (state/init-state) :base-path "/my-app"))
          session-id "test-session-nav-bp"
          tab-id     "test_tab_nav_bp"
          router     (ring/router routes {:conflicts nil})]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc :router router :routes routes)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*
                                   :hyper/router     router}]
        (let [nav-attrs (hy/navigate :about)]
          (is (string/includes? (str (:data-on:click__prevent nav-attrs))
                                "@post('/my-app/hyper/actions?"))
          (is (not (string/includes? (str (:data-on:click__prevent nav-attrs))
                                     "@post('/hyper/actions?"))))))))

;; ---------------------------------------------------------------------------
;; Batch
;; ---------------------------------------------------------------------------

(deftest batch-atomic-update-test
  (testing "batch flushes all cursor writes in a single swap"
    (let [swap-count* (atom 0)
          app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")]
      ;; Watch the atom to count how many times it's swapped AFTER batch
      (add-watch app-state* :counter (fn [_ _ _ _] (swap! swap-count* inc)))
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        ;; Reset counter after setup swaps
        (reset! swap-count* 0)
        (hy/batch
          (reset! (hy/tab-cursor :a) 1)
          (reset! (hy/tab-cursor :b) 2)
          (reset! (hy/tab-cursor :c) 3))
        ;; All three writes should have been flushed in a single swap
        (is (= 1 @swap-count*) "batch should flush all writes in a single swap!")
        ;; All values should be present in the live atom
        (is (= 1 (get-in @app-state* [:tabs "t1" :data :a])))
        (is (= 2 (get-in @app-state* [:tabs "t1" :data :b])))
        (is (= 3 (get-in @app-state* [:tabs "t1" :data :c]))))
      (remove-watch app-state* :counter))))

(deftest batch-read-your-writes-test
  (testing "cursor reads inside batch see accumulated writes"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (hy/batch
          (reset! (hy/tab-cursor :x) 10)
          ;; Should read the value we just set, even though it hasn't been flushed
          (is (= 10 @(hy/tab-cursor :x)))
          (swap! (hy/tab-cursor :x) + 5)
          (is (= 15 @(hy/tab-cursor :x))))
        ;; After flush, live atom should have the final value
        (is (= 15 (get-in @app-state* [:tabs "t1" :data :x])))))))

(deftest batch-nested-transparency-test
  (testing "nested batch executes within outer overlay — no double flush"
    (let [swap-count* (atom 0)
          app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")]
      (add-watch app-state* :counter (fn [_ _ _ _] (swap! swap-count* inc)))
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (reset! swap-count* 0)
        (hy/batch
          (reset! (hy/tab-cursor :a) 1)
          ;; Nested batch — should NOT create a separate overlay
          (hy/batch
            (reset! (hy/tab-cursor :b) 2))
          (reset! (hy/tab-cursor :c) 3)
          ;; Nested batch's write should be visible
          (is (= 2 @(hy/tab-cursor :b))))
        ;; Still a single flush
        (is (= 1 @swap-count*))
        (is (= 1 (get-in @app-state* [:tabs "t1" :data :a])))
        (is (= 2 (get-in @app-state* [:tabs "t1" :data :b])))
        (is (= 3 (get-in @app-state* [:tabs "t1" :data :c]))))
      (remove-watch app-state* :counter))))

(deftest batch-reaction-transparency-test
  (testing "a watch reacting to the batch flush with a batch-wrapped cursor
            write does not re-enter the flush — ops apply exactly once and
            the reaction write lands as a follow-up update"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (let [n*     (hy/tab-cursor :n 0)
              other* (hy/tab-cursor :other)]
          (add-watch app-state* :batched-reaction
                     (fn [_ _ old new]
                       (let [p [:tabs "t1" :data :n]]
                         (when (not= (get-in old p) (get-in new p))
                           (hy/batch
                             (reset! other* :from-batch))))))
          (hy/batch
            (swap! n* inc))
          (is (= 1 (get-in @app-state* [:tabs "t1" :data :n]))
              "op applied exactly once — no re-entrant flush")
          (is (= :from-batch (get-in @app-state* [:tabs "t1" :data :other]))
              "batched reaction write landed")))
      (remove-watch app-state* :batched-reaction))))

(deftest batch-return-value-test
  (testing "batch returns the value of the last expression"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (let [result (hy/batch
                       (reset! (hy/tab-cursor :x) 42)
                       :done)]
          (is (= :done result)))))))

(deftest batch-no-writes-no-flush-test
  (testing "batch with no cursor writes does not swap app-state"
    (let [swap-count* (atom 0)
          app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")]
      (add-watch app-state* :counter (fn [_ _ _ _] (swap! swap-count* inc)))
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (reset! swap-count* 0)
        (hy/batch
          (+ 1 2 3))
        (is (= 0 @swap-count*)))
      (remove-watch app-state* :counter))))

(deftest batch-without-batch-writes-directly-test
  (testing "without batch, each cursor write goes to live atom immediately"
    (let [swap-count* (atom 0)
          app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")]
      (add-watch app-state* :counter (fn [_ _ _ _] (swap! swap-count* inc)))
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (reset! swap-count* 0)
        (reset! (hy/tab-cursor :a) 1)
        (reset! (hy/tab-cursor :b) 2)
        ;; Each write should have hit the live atom separately
        (is (< 1 @swap-count*) "without batch, writes should hit live atom individually"))
      (remove-watch app-state* :counter))))

(deftest batch-preserves-concurrent-writes-test
  (testing "batch flush only overwrites paths it wrote — other paths preserved"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      ;; Pre-seed a value
      (swap! app-state* assoc-in [:tabs "t1" :data :existing] "untouched")
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (hy/batch
          (reset! (hy/tab-cursor :new-key) "hello")
          ;; Simulate a concurrent write to a different path
          ;; (another action handler on a different tab)
          (swap! app-state* assoc-in [:tabs "t1" :data :concurrent] "written-during-batch")))
      ;; Batch's write should be present
      (is (= "hello" (get-in @app-state* [:tabs "t1" :data :new-key])))
      ;; Pre-existing value should be preserved
      (is (= "untouched" (get-in @app-state* [:tabs "t1" :data :existing])))
      ;; Concurrent write should be preserved (not overwritten by flush)
      (is (= "written-during-batch" (get-in @app-state* [:tabs "t1" :data :concurrent]))))))

(deftest batch-compare-and-set-test
  (testing "compareAndSet works inside batch"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      (swap! app-state* assoc-in [:tabs "t1" :data :x] 10)
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (hy/batch
          (let [c (hy/tab-cursor :x)]
            ;; CAS with correct old value should succeed
            (is (true? (compare-and-set! c 10 20)))
            (is (= 20 @c))
            ;; CAS with wrong old value should fail
            (is (false? (compare-and-set! c 10 30)))
            (is (= 20 @c))))
        ;; Flushed value should be 20
        (is (= 20 (get-in @app-state* [:tabs "t1" :data :x])))))))

(deftest batch-inside-test-action-test
  (testing "batch works end-to-end inside a test-page/test-action workflow"
    (let [page-fn (fn [_req]
                    (let [a* (hy/tab-cursor :a 0)
                          b* (hy/tab-cursor :b 0)]
                      [:div
                       [:p (str @a* " " @b*)]
                       [:button {:data-on:click
                                 (hy/action {:as "update-both"}
                                            (hy/batch
                                              (reset! (hy/tab-cursor :a) 10)
                                              (reset! (hy/tab-cursor :b) 20)))}
                        "Update"]]))
          result  (ht/test-page page-fn)
          after   (ht/test-action result "update-both")]
      (is (= 10 (get-in after [:cursors :tab :a])))
      (is (= 20 (get-in after [:cursors :tab :b]))))))

(deftest render-overlay-flush-test
  (testing "render overlay flushes default-value inits to live atom"
    (let [page-fn (fn [_req]
                    (let [c* (hy/tab-cursor :auto-init 42)]
                      [:div [:p (str @c*)]]))
          result  (ht/test-page page-fn)]
      ;; Default value should be flushed to the live atom after render
      (is (= 42 (get-in @(:app-state result) [:tabs "test-tab" :data :auto-init]))))))

(deftest render-overlay-read-consistency-test
  (testing "cursor reads during render see a consistent snapshot"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          ;; Pre-seed state
          _          (swap! app-state* assoc-in [:tabs "t1" :data :x] 100)
          captured   (atom nil)
          page-fn    (fn [_req]
                       (let [x* (hy/tab-cursor :x)]
                         ;; Capture the value seen during render
                         (reset! captured @x*)
                         [:div (str @x*)]))]
      (ht/test-page page-fn {:app-state  app-state*
                             :tab-id     "t1"
                             :session-id "s1"})
      ;; Should see the snapshot value
      (is (= 100 @captured)))))

;; ---------------------------------------------------------------------------
;; Overlay thread ownership — async work spawned from render/batch
;;
;; future/send-off/fibers convey dynamic bindings, so background work
;; launched inside a render inherits *state-overlay*.  Overlays are owned
;; by their creating thread: conveyed copies must be ignored so worker
;; reads/writes hit the live app-state (writes fire watchers → SSE frames),
;; instead of silently landing in a snapshot that is dead after the flush.
;; ---------------------------------------------------------------------------

(deftest render-spawned-future-writes-live-test
  (testing "future launched from render writes to live app-state after render flushes"
    (let [app-state*     (atom (state/init-state))
          _              (state/get-or-create-tab! app-state* "s1" "t1")
          release        (promise)
          done           (promise)
          watcher-fired* (atom false)
          page-fn        (fn [_req]
                           (future
                             @release ;; wait until render completed and flushed
                             (reset! (hy/tab-cursor :worker-result) :done)
                             (deliver done true))
                           [:div "page"])]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      ;; Render is done, overlay flushed and dead.  Now let the worker write.
      (add-watch app-state* :ownership-test (fn [_ _ _ _] (reset! watcher-fired* true)))
      (deliver release true)
      (is (true? (deref done 2000 false)) "worker should complete")
      (is (= :done (get-in @app-state* [:tabs "t1" :data :worker-result]))
          "worker write must land in live app-state, not the dead render overlay")
      (is (true? @watcher-fired*)
          "worker write must fire app-state watchers (drives re-render/SSE)")
      (remove-watch app-state* :ownership-test))))

(deftest render-spawned-future-reads-live-test
  (testing "owner thread reads its snapshot; conveyed worker thread reads live state"
    (let [app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")
          _           (swap! app-state* assoc-in [:tabs "t1" :data :x] 100)
          owner-saw*  (atom nil)
          worker-saw* (atom nil)
          page-fn     (fn [_req]
                        (let [x* (hy/tab-cursor :x)]
                          ;; Mutate live state out-of-band after the snapshot
                          (swap! app-state* assoc-in [:tabs "t1" :data :x] 200)
                          ;; Conveyed worker bypasses the overlay → sees live value
                          (reset! worker-saw* (deref (future @(hy/tab-cursor :x)) 2000 ::timeout))
                          ;; Owner still reads the consistent snapshot
                          (reset! owner-saw* @x*)
                          [:div]))]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (is (= 100 @owner-saw*) "render thread keeps snapshot read-consistency")
      (is (= 200 @worker-saw*) "worker reads live state, not the conveyed snapshot"))))

(deftest render-spawned-future-writes-during-render-test
  (testing "worker write during an in-flight render goes live and survives the flush"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          mid-render (atom nil)
          page-fn    (fn [_req]
                       ;; Owner write — lands in the overlay, flushed at render end
                       (reset! (hy/tab-cursor :owner-key) :from-owner)
                       ;; Worker write — different path, goes live immediately
                       (deref (future (reset! (hy/tab-cursor :worker-key) :from-worker)) 2000 nil)
                       (reset! mid-render (get-in @app-state* [:tabs "t1" :data :worker-key]))
                       [:div])]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (is (= :from-worker @mid-render)
          "worker write visible in live atom before render completes")
      (is (= :from-owner (get-in @app-state* [:tabs "t1" :data :owner-key]))
          "owner overlay write flushed normally")
      (is (= :from-worker (get-in @app-state* [:tabs "t1" :data :worker-key]))
          "overlay flush must not clobber the worker's live write"))))

(deftest batch-spawned-future-writes-live-test
  (testing "future launched inside batch writes directly to the live atom"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          mid-batch  (atom nil)]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (hy/batch
          (reset! (hy/tab-cursor :a) 1)
          (deref (future (reset! (hy/tab-cursor :from-worker) :live)) 2000 nil)
          ;; Worker's write is already in the live atom, mid-batch
          (reset! mid-batch (get-in @app-state* [:tabs "t1" :data :from-worker])))
        (is (= :live @mid-batch)
            "worker write visible in live atom before the batch flushes")
        (is (= 1 (get-in @app-state* [:tabs "t1" :data :a])))
        (is (= :live (get-in @app-state* [:tabs "t1" :data :from-worker]))
            "batch flush must preserve the worker's live write")))))

(deftest batch-inside-conveyed-future-test
  (testing "batch inside a render-spawned future creates its own overlay and flushes live"
    (let [app-state*  (atom (state/init-state))
          _           (state/get-or-create-tab! app-state* "s1" "t1")
          release     (promise)
          done        (promise)
          worker-saw* (atom nil)
          page-fn     (fn [_req]
                        (future
                          @release ;; render's overlay is dead by now
                          (hy/batch
                            (reset! (hy/tab-cursor :a) 1)
                            (reset! (hy/tab-cursor :b) 2)
                            ;; read-your-writes inside the worker's own batch
                            (reset! worker-saw* @(hy/tab-cursor :a)))
                          (deliver done true))
                        [:div])]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (deliver release true)
      (is (true? (deref done 2000 false)) "worker should complete")
      (is (= 1 @worker-saw*) "worker's batch supports read-your-writes")
      (is (= 1 (get-in @app-state* [:tabs "t1" :data :a])))
      (is (= 2 (get-in @app-state* [:tabs "t1" :data :b]))
          "worker's batch flushes to live app-state despite the conveyed dead overlay"))))

;; ---------------------------------------------------------------------------
;; Op-log overlay — writes recorded as composable ops, not values.  Flush
;; replays them against live state, so swap!/update merges with concurrent
;; same-path writes; reset! keeps overwrite semantics.
;; ---------------------------------------------------------------------------

(deftest overlay-swap-composes-with-concurrent-live-write-test
  (testing "a render's swap! claim composes with a worker's concurrent live write"
    ;; Streaming foot-gun: render claims :status on the cursor a worker streams
    ;; rows into.  Value-replay wiped the rows; op-log merges the claim on top.
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          page-fn    (fn [_req]
                       (let [results* (hy/tab-cursor :results)]
                         ;; Owner stakes its claim on a sub-key (compositional swap)
                         (swap! results* assoc :status :starting)
                         ;; Worker streams rows into the SAME cursor on live state
                         ;; (a conveyed future bypasses the overlay), finishing
                         ;; before the render returns and flushes.
                         (deref (future
                                  (swap! (hy/tab-cursor :results)
                                         update :rows (fnil into []) [:row1 :row2]))
                                2000 nil)
                         [:div]))]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (let [results (get-in @app-state* [:tabs "t1" :data :results])]
        (is (= :starting (:status results))
            "render's claim is applied")
        (is (= [:row1 :row2] (:rows results))
            "worker's streamed rows survive the render flush (no clobber)")))))

(deftest overlay-reset-overwrites-concurrent-write-test
  (testing "reset! keeps overwrite semantics: a whole-value reset replays absolutely"
    ;; Two whole-cursor resets are a genuine conflict; the render's reset wins.
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          page-fn    (fn [_req]
                       (let [results* (hy/tab-cursor :results)]
                         (reset! results* {:status :starting})
                         (deref (future (reset! (hy/tab-cursor :results) {:rows [:row1]}))
                                2000 nil)
                         [:div]))]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (is (= {:status :starting} (get-in @app-state* [:tabs "t1" :data :results]))
          "render's reset op replays as an absolute write, overwriting the worker"))))

(deftest overlay-read-your-writes-test
  (testing "@cursor after a swap! returns the written value within the overlay"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          seen       (atom nil)
          page-fn    (fn [_req]
                       (let [s* (hy/tab-cursor :s)]
                         (swap! s* assoc :rows [:a :b :c])
                         ;; read-your-writes: shadow reflects the write immediately
                         (reset! seen (:rows @s*))
                         [:p (str (:rows @s*))]))
          result     (ht/test-page page-fn {:app-state  app-state*
                                            :tab-id     "t1"
                                            :session-id "s1"})]
      (is (= [:a :b :c] @seen)
          "the body sees its own write synchronously via the shadow")
      (is (string/includes? (:body-html result) "[:a :b :c]")
          "the rendered HTML reflects the written value")
      (is (= [:a :b :c] (get-in @app-state* [:tabs "t1" :data :s :rows]))
          "the write is committed to live state after flush"))))

(deftest batch-swap-composes-with-live-write-test
  (testing "batch swap! ops compose with a concurrent worker's live write to the same path"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (hy/batch
          (swap! (hy/tab-cursor :results) assoc :status :done)
          ;; worker appends rows to the same cursor on live, mid-batch
          (deref (future (swap! (hy/tab-cursor :results)
                                update :rows (fnil conj []) :r1))
                 2000 nil))
        (let [results (get-in @app-state* [:tabs "t1" :data :results])]
          (is (= :done (:status results)))
          (is (= [:r1] (:rows results))
              "batch flush replays ops, preserving the worker's rows"))))))

(deftest default-init-does-not-clobber-concurrent-write-test
  (testing "first-render default init yields to a concurrent worker write (cas op)"
    (let [app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          page-fn    (fn [_req]
                       ;; Worker initializes :count on live before the default
                       ;; init's cas op flushes.
                       (deref (future (reset! (hy/tab-cursor :count) 41)) 2000 nil)
                       ;; Render asks for the cursor with a default of 0.
                       (let [c* (hy/tab-cursor :count 0)]
                         [:div (str @c*)]))]
      (ht/test-page page-fn {:app-state app-state* :tab-id "t1" :session-id "s1"})
      (is (= 41 (get-in @app-state* [:tabs "t1" :data :count]))
          "default init must not overwrite the worker's value"))))

;; ---------------------------------------------------------------------------
;; Env
;; ---------------------------------------------------------------------------

(deftest env-helper-test
  (testing "env returns nil outside request context"
    (binding [context/*request* nil]
      (is (nil? (hy/env)))))

  (testing "env returns the full env map"
    (binding [context/*request* {:hyper/env {:db :test-db :user {:name "Alice"}}}]
      (is (= {:db :test-db :user {:name "Alice"}} (hy/env)))))

  (testing "env with key returns a specific value"
    (binding [context/*request* {:hyper/env {:db :test-db}}]
      (is (= :test-db (hy/env :db)))
      (is (nil? (hy/env :missing)))))

  (testing "env with key and default"
    (binding [context/*request* {:hyper/env {:db :test-db}}]
      (is (= :test-db (hy/env :db :fallback)))
      (is (= :fallback (hy/env :missing :fallback))))))

(deftest env-in-render-test
  (testing "env is available in render functions via test-page :req"
    (let [captured (atom nil)
          page-fn  (fn [_req]
                     (reset! captured (hy/env))
                     [:div "db: " (str (hy/env :db))])]
      (ht/test-page page-fn {:req {:hyper/env {:db :test-db}}})
      (is (= {:db :test-db} @captured)))))

(deftest env-stashed-for-sse-renders-test
  (testing "env stashed in tab state is available on SSE re-renders (no base-req)"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-env"
          tab-id     "test_tab_env"
          captured   (atom nil)
          render-fn  (fn [_req]
                       (reset! captured (hy/env))
                       [:div "env"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Simulate stashing env (as page-handler would do)
      (swap! app-state* assoc-in [:tabs tab-id :env] {:db :prod-db :user {:id 1}})

      ;; SSE re-render — no base-req, env comes from stashed tab state
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (some? result))
        (is (= {:db :prod-db :user {:id 1}} @captured))))))

(deftest env-base-req-takes-precedence-test
  (testing "env from base-req (initial page load) takes precedence over stashed env"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-env2"
          tab-id     "test_tab_env2"
          captured   (atom nil)
          render-fn  (fn [_req]
                       (reset! captured (hy/env))
                       [:div "env"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Stash old env
      (swap! app-state* assoc-in [:tabs tab-id :env] {:db :old-db})

      ;; Initial page load with fresh env on the request
      (let [base-req {:hyper/env {:db :fresh-db}}
            result   (render/render-tab app-state* session-id tab-id base-req)]
        (is (some? result))
        (is (= {:db :fresh-db} @captured))))))

(deftest env-nil-does-not-break-test
  (testing "missing env does not break renders"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-env3"
          tab-id     "test_tab_env3"
          captured   (atom :sentinel)
          render-fn  (fn [_req]
                       (reset! captured (hy/env))
                       [:div "no env"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      ;; No env stashed, no base-req — should be nil, not throw
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (some? result))
        (is (nil? @captured))))))

(deftest env-in-action-test
  (testing "env is available inside action handlers"
    (let [captured   (atom nil)
          page-fn    (fn [_req]
                       [:div
                        [:button {:data-on:click
                                  (hy/action {:as "check-env"}
                                             (reset! captured (hy/env)))}
                         "Go"]])
          app-state* (atom (state/init-state))
          _          (state/get-or-create-tab! app-state* "s1" "t1")
          ;; Stash env directly (simulating what page-handler does)
          _          (swap! app-state* assoc-in [:tabs "t1" :env]
                            {:db :action-db :user {:role :admin}})
          result     (ht/test-page page-fn {:app-state  app-state*
                                            :session-id "s1"
                                            :tab-id     "t1"
                                            :req        {:hyper/env {:db   :action-db
                                                                     :user {:role :admin}}}})]
      (ht/test-action result "check-env")
      (is (= {:db :action-db :user {:role :admin}} @captured)))))

(deftest env-replaced-on-action-test
  (testing "env is fully replaced (not merged) when action refreshes it"
    (let [app-state* (atom (state/init-state))
          session-id "s-env-replace"
          tab-id     "t-env-replace"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; Initial env
      (swap! app-state* assoc-in [:tabs tab-id :env]
             {:db :db :user {:name "Alice"} :feature-flags #{:beta}})
      ;; Simulate action-handler stashing a completely new env (replace semantics)
      (swap! app-state* assoc-in [:tabs tab-id :env]
             {:db :db :user {:name "Bob"}})
      ;; :feature-flags should be gone — full replace, not merge
      (is (= {:db :db :user {:name "Bob"}}
             (get-in @app-state* [:tabs tab-id :env]))))))

(deftest test-connection-signal-reexports
  (testing "core re-exports the connection signals and token set from hyper.signal"
    (is (identical? signal/connected?* hy/connected?*))
    (is (identical? signal/connection* hy/connection*))
    (is (identical? signal/navigating?* hy/navigating?*))
    (is (= signal/connection-states hy/connection-states))))

(deftest test-reconnect
  (testing "reconnect returns the SSE @get expression for this tab"
    (let [app-state* (atom (assoc (state/init-state)
                                  :base-path "" :open-when-hidden? true))]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "tab_abc"
                                   :hyper/app-state  app-state*}]
        (is (= "@get('/hyper/events?tab-id=tab_abc', {openWhenHidden: true})"
               (hy/reconnect))))))

  (testing "reconnect honors :base-path and :open-when-hidden? false"
    (let [app-state* (atom (assoc (state/init-state)
                                  :base-path "/app" :open-when-hidden? false))]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "tab_xyz"
                                   :hyper/app-state  app-state*}]
        (is (= "@get('/app/hyper/events?tab-id=tab_xyz')"
               (hy/reconnect))))))

  (testing "reconnect throws outside request context"
    (is (thrown? Exception (hy/reconnect)))))
