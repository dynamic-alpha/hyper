(ns hyper.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hyper.server :as server]
            [hyper.core :as hy]))

(defn stop-server-fixture [f]
  (try
    (f)
    (finally
      ;; Always stop server after tests
      (try (hy/stop!) (catch Exception _e nil)))))

(use-fixtures :each stop-server-fixture)

(deftest test-generate-session-id
  (testing "Session ID generation"
    (let [id1 (server/generate-session-id)
          id2 (server/generate-session-id)]
      ;; IDs should be strings
      (is (string? id1))
      (is (string? id2))
      ;; IDs should start with sess-
      (is (.startsWith id1 "sess-"))
      (is (.startsWith id2 "sess-"))
      ;; IDs should be unique
      (is (not= id1 id2)))))

(deftest test-generate-tab-id
  (testing "Tab ID generation"
    (let [id1 (server/generate-tab-id)
          id2 (server/generate-tab-id)]
      ;; IDs should be strings
      (is (string? id1))
      (is (string? id2))
      ;; IDs should start with tab-
      (is (.startsWith id1 "tab-"))
      (is (.startsWith id2 "tab-"))
      ;; IDs should be unique
      (is (not= id1 id2)))))

(deftest test-wrap-hyper-context-new-session
  (testing "Middleware creates new session and tab IDs"
    (let [handler (fn [req]
                    {:status 200
                     :body (str "session: " (:hyper/session-id req)
                                " tab: " (:hyper/tab-id req))})
          wrapped (server/wrap-hyper-context handler)
          req {}
          response (wrapped req)]

      ;; Response should have session cookie
      (is (contains? (:cookies response) "hyper-session"))
      (is (string? (get-in response [:cookies "hyper-session" :value])))
      (is (.startsWith (get-in response [:cookies "hyper-session" :value]) "sess-"))

      ;; Response body should contain both IDs
      (is (.contains (:body response) "session: sess-"))
      (is (.contains (:body response) "tab: tab-")))))

(deftest test-wrap-hyper-context-existing-session
  (testing "Middleware reuses existing session from cookie"
    (let [existing-session-id "sess-existing-123"
          handler (fn [req]
                    {:status 200
                     :body (str "session: " (:hyper/session-id req))})
          wrapped (server/wrap-hyper-context handler)
          req {:cookies {"hyper-session" {:value existing-session-id}}}
          response (wrapped req)]

      ;; Response should not add new session cookie
      (is (nil? (get-in response [:cookies "hyper-session"])))

      ;; Should use existing session ID
      (is (.contains (:body response) "session: sess-existing-123")))))

(deftest test-wrap-hyper-context-tab-id-from-query
  (testing "Middleware uses tab-id from query params"
    (let [handler (fn [req]
                    {:status 200
                     :body (str "tab: " (:hyper/tab-id req))})
          wrapped (server/wrap-hyper-context handler)
          req {:query-params {"tab-id" "tab-from-query"}}
          response (wrapped req)]

      ;; Should use provided tab-id
      (is (.contains (:body response) "tab: tab-from-query")))))

(deftest test-datastar-script
  (testing "Datastar script tag generation"
    (let [script (server/datastar-script)]
      ;; Should be a hiccup vector
      (is (vector? script))
      ;; Should be a script tag
      (is (= :script (first script)))
      ;; Should have CDN src
      (is (contains? (second script) :src))
      (is (.contains (get (second script) :src) "datastar")))))

(deftest test-server-lifecycle
  (testing "Server start and stop"
    (let [view-fn (fn [_req] [:div "Hello"])]

      ;; Server should not be running initially
      (is (nil? @server/server-instance))

      ;; Start server
      (hy/start! {:render-fn view-fn :port 13000})

      ;; Server should be running
      (is (some? @server/server-instance))

      ;; Stop server
      (hy/stop!)

      ;; Server should be stopped
      (is (nil? @server/server-instance)))))

(deftest test-server-requires-render-fn
  (testing "Server start requires render-fn"
    (is (thrown? Exception
                 (hy/start! {:port 3000})))))

(deftest test-server-already-running-error
  (testing "Starting server twice throws error"
    (let [view-fn (fn [_req] [:div "Hello"])]
      ;; Start server
      (hy/start! {:render-fn view-fn :port 13001})

      ;; Try to start again
      (is (thrown? Exception
                   (hy/start! {:render-fn view-fn :port 13002})))

      ;; Clean up
      (hy/stop!))))
