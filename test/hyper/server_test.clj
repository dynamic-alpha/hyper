(ns hyper.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.core]))

(deftest test-generate-session-id
  (testing "Session ID generation"
    (let [id1 (server/generate-session-id)
          id2 (server/generate-session-id)]
      (is (string? id1))
      (is (string? id2))
      (is (.startsWith id1 "sess-"))
      (is (.startsWith id2 "sess-"))
      (is (not= id1 id2)))))

(deftest test-generate-tab-id
  (testing "Tab ID generation"
    (let [id1 (server/generate-tab-id)
          id2 (server/generate-tab-id)]
      (is (string? id1))
      (is (string? id2))
      (is (.startsWith id1 "tab-"))
      (is (.startsWith id2 "tab-"))
      (is (not= id1 id2)))))

(deftest test-wrap-hyper-context-new-session
  (testing "Middleware creates new session and tab IDs"
    (let [app-state* (atom (state/init-state))
          handler (fn [req]
                    {:status 200
                     :body (str "session: " (:hyper/session-id req)
                                " tab: " (:hyper/tab-id req))})
          wrapped ((server/wrap-hyper-context app-state*) handler)
          req {}
          response (wrapped req)]

      (is (contains? (:cookies response) "hyper-session"))
      (is (string? (get-in response [:cookies "hyper-session" :value])))
      (is (.startsWith (get-in response [:cookies "hyper-session" :value]) "sess-"))
      (is (.contains (:body response) "session: sess-"))
      (is (.contains (:body response) "tab: tab-")))))

(deftest test-wrap-hyper-context-existing-session
  (testing "Middleware reuses existing session from cookie"
    (let [app-state* (atom (state/init-state))
          existing-session-id "sess-existing-123"
          handler (fn [req]
                    {:status 200
                     :body (str "session: " (:hyper/session-id req))})
          wrapped ((server/wrap-hyper-context app-state*) handler)
          req {:cookies {"hyper-session" {:value existing-session-id}}}
          response (wrapped req)]

      (is (nil? (get-in response [:cookies "hyper-session"])))
      (is (.contains (:body response) "session: sess-existing-123")))))

(deftest test-wrap-hyper-context-tab-id-from-query
  (testing "Middleware uses tab-id from query params"
    (let [app-state* (atom (state/init-state))
          handler (fn [req]
                    {:status 200
                     :body (str "tab: " (:hyper/tab-id req))})
          wrapped ((server/wrap-hyper-context app-state*) handler)
          req {:query-params {"tab-id" "tab-from-query"}}
          response (wrapped req)]

      (is (.contains (:body response) "tab: tab-from-query")))))

(deftest test-datastar-script
  (testing "Datastar script tag generation"
    (let [script (server/datastar-script)]
      (is (vector? script))
      (is (= :script (first script)))
      (is (contains? (second script) :src))
      (is (.contains (get (second script) :src) "datastar")))))

(deftest test-create-handler
  (testing "Creates a working ring handler"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          handler (server/create-handler routes app-state* request-var)]
      (is (fn? handler))
      
      ;; Test that it handles a request
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "Home"))))))

(deftest test-server-lifecycle
  (testing "Server start and stop"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Hello"])}]]
          request-var #'hyper.core/*request*
          handler (server/create-handler routes app-state* request-var)
          server (server/start! handler {:port 13000})]

      (is (some? server))
      (is (fn? server))

      ;; Stop server
      (server/stop! server))))
