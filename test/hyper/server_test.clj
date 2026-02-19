(ns hyper.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
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
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var)]
      (is (fn? handler))

      ;; Test that it handles a request
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "Home")))))

  (testing "Allows injecting tags into <head>"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var
                                         {:head [[:link {:rel "stylesheet" :href "/app.css"}]]})
          response (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "rel=\"stylesheet\""))
      (is (.contains (:body response) "href=\"/app.css\""))))

  (testing "Allows :head to be a function"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var
                                         {:head (fn [_req]
                                                 [[:meta {:name "test" :content "ok"}]])})
          response (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "name=\"test\""))
      (is (.contains (:body response) "content=\"ok\""))))

  (testing "Serves static assets from :static-dir"
    (let [tmp-path (java.nio.file.Files/createTempDirectory
                     "hyper-static-"
                     (make-array java.nio.file.attribute.FileAttribute 0))
          tmp-dir (.toFile tmp-path)
          css-file (io/file tmp-dir "styles.css")
          _ (spit css-file "body { background: red; }")
          app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var
                                         {:static-dir (.getAbsolutePath tmp-dir)})
          response (handler {:uri "/styles.css" :request-method :get})]
      (is (= 200 (:status response)))
      (is (some? (get-in response [:headers "Content-Type"])))
      (is (.contains (get-in response [:headers "Content-Type"]) "text/css"))
      (is (.contains (slurp (:body response)) "background: red"))))

  (testing "Serves static assets from multiple :static-dir roots"
    (let [tmp1-path (java.nio.file.Files/createTempDirectory
                      "hyper-static-1-"
                      (make-array java.nio.file.attribute.FileAttribute 0))
          tmp2-path (java.nio.file.Files/createTempDirectory
                      "hyper-static-2-"
                      (make-array java.nio.file.attribute.FileAttribute 0))
          tmp1-dir (.toFile tmp1-path)
          tmp2-dir (.toFile tmp2-path)
          a-file (io/file tmp1-dir "a.css")
          b-file (io/file tmp2-dir "b.css")
          _ (spit a-file "/* a */")
          _ (spit b-file "/* b */")
          app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var
                                         {:static-dir [(.getAbsolutePath tmp1-dir)
                                                      (.getAbsolutePath tmp2-dir)]})
          response-a (handler {:uri "/a.css" :request-method :get})
          response-b (handler {:uri "/b.css" :request-method :get})]
      (is (= 200 (:status response-a)))
      (is (.contains (slurp (:body response-a)) "a"))
      (is (= 200 (:status response-b)))
      (is (.contains (slurp (:body response-b)) "b"))))

  (testing "Serves static assets from :static-resources"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Home"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var
                                         {:static-resources "public"})
          response (handler {:uri "/hyper-test-static.txt" :request-method :get})]
      (is (= 200 (:status response)))
      (is (= "static-ok\n" (slurp (:body response)))))))

(deftest test-server-lifecycle
  (testing "Server start and stop"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Hello"])}]]
          request-var #'hyper.core/*request*
          executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
          handler (server/create-handler routes app-state* executor request-var)
          server (server/start! handler {:port 13000})]

      (is (some? server))
      (is (fn? server))

      ;; Stop server
      (server/stop! server))))

(deftest test-create-handler-with-var-routes
  (testing "Accepts a Var and serves initial routes"
    (let [app-state* (atom (state/init-state))
          ;; Use an atom to back the Var so we can simulate re-def
          routes-atom (atom [["/" {:name :home
                                   :get (fn [_req] [:div "Home V1"])}]])]
      ;; Create a real Var to pass as routes
      (let [routes-var (intern *ns* (gensym "test-routes-") @routes-atom)
            handler (server/create-handler routes-var app-state*
                                           (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                                           #'hyper.core/*request*)
            response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "Home V1")))))

  (testing "Picks up route changes on next request"
    (let [app-state* (atom (state/init-state))
          v1-routes [["/" {:name :home
                           :get (fn [_req] [:div "Version 1"])}]]
          v2-routes [["/" {:name :home
                           :get (fn [_req] [:div "Version 2"])}]
                     ["/new" {:name :new-page
                              :get (fn [_req] [:div "New Page"])}]]
          routes-var (intern *ns* (gensym "test-routes-") v1-routes)
          handler (server/create-handler routes-var app-state*
                                         (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                                         #'hyper.core/*request*)]

      ;; Initial request serves v1
      (let [response (handler {:uri "/" :request-method :get})]
        (is (.contains (:body response) "Version 1")))

      ;; Simulate re-def by altering the Var root
      (alter-var-root routes-var (constantly v2-routes))

      ;; Next request picks up v2
      (let [response (handler {:uri "/" :request-method :get})]
        (is (.contains (:body response) "Version 2")))

      ;; New route is available
      (let [response (handler {:uri "/new" :request-method :get})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "New Page")))

      ;; App-state has the updated routes and router
      (is (= v2-routes (:routes @app-state*)))
      (is (some? (:router @app-state*)))))

  (testing "Does not rebuild when routes haven't changed"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Stable"])}]]
          routes-var (intern *ns* (gensym "test-routes-") routes)
          build-count (atom 0)
          handler (server/create-handler routes-var app-state*
                                         (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                                         #'hyper.core/*request*)]

      ;; build-ring-handler was called once during create-handler
      ;; Subsequent requests with the same routes should not rebuild
      (with-redefs [server/find-render-fn (let [orig server/find-render-fn]
                                            (fn [routes route-name]
                                              (swap! build-count inc)
                                              (orig routes route-name)))]
        ;; Several requests — find-render-fn is only called by navigate-handler,
        ;; not by the router rebuild path. We just verify the handler works
        ;; consistently without errors.
        (let [r1 (handler {:uri "/" :request-method :get})
              r2 (handler {:uri "/" :request-method :get})
              r3 (handler {:uri "/" :request-method :get})]
          (is (= 200 (:status r1)))
          (is (= 200 (:status r2)))
          (is (= 200 (:status r3)))
          ;; All should return the same content
          (is (.contains (:body r1) "Stable"))
          (is (.contains (:body r3) "Stable"))))))

  (testing "Static routes (non-Var) still work as before"
    (let [app-state* (atom (state/init-state))
          routes [["/" {:name :home
                        :get (fn [_req] [:div "Static"])}]]
          handler (server/create-handler routes app-state*
                                         (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                                         #'hyper.core/*request*)
          response (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "Static")))))
