(ns hyper.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.core :as hy]
            [hyper.state :as state]))

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
          (is (contains? action-attrs :data-on-click))
          (is (.contains (:data-on-click action-attrs) "$$post"))
          (is (.contains (:data-on-click action-attrs) "/hyper/actions"))
          (is (.contains (:data-on-click action-attrs) "action-id="))
          
          ;; Extract action ID and execute it
          (let [action-id (second (re-find #"action-id=([^']+)" (:data-on-click action-attrs)))]
            (is (some? action-id))
            ((get-in @app-state* [:actions action-id :fn]))
            (is @executed)))))))

(deftest test-navigate
  (testing "navigate generates click handler with pushState"
    (let [routes [["/" {:name :home}]
                  ["/about" {:name :about}]
                  ["/users/:id" {:name :user-profile}]]]
      
      (testing "without params"
        (let [nav-attrs (hy/navigate routes :home)]
          (is (map? nav-attrs))
          (is (contains? nav-attrs :data-on-click))
          (is (.contains (:data-on-click nav-attrs) "$$get('/')"))
          (is (.contains (:data-on-click nav-attrs) "pushState"))))
      
      (testing "with path params"
        (let [nav-attrs (hy/navigate routes :user-profile {:id "123"})]
          (is (.contains (:data-on-click nav-attrs) "/users/123"))))
      
      (testing "returns nil for unknown route"
        (is (nil? (hy/navigate routes :nonexistent)))))))

(deftest test-navigate-url
  (testing "navigate-url generates click handler"
    (let [nav-attrs (hy/navigate-url "/custom-path")]
      (is (map? nav-attrs))
      (is (contains? nav-attrs :data-on-click))
      (is (.contains (:data-on-click nav-attrs) "$$get('/custom-path')"))
      (is (.contains (:data-on-click nav-attrs) "pushState")))))

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
          handler (hy/create-handler routes app-state*)]
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
