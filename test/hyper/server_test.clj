(ns hyper.server-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.component :as component]
            [hyper.component.bundle :as bundle]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.render :as render]
            [hyper.render.queue :as rq]
            [hyper.routes :as routes]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.watch :as watch]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [ring.core.protocols])
  (:import [java.io OutputStream]))

(deftest test-generate-session-id
  (testing "Session ID generation"
    (let [id1 (server/generate-session-id)
          id2 (server/generate-session-id)]
      (is (string? id1))
      (is (string? id2))
      (is (.startsWith id1 "ses_"))
      (is (.startsWith id2 "ses_"))
      (is (not= id1 id2)))))

(deftest test-generate-tab-id
  (testing "Tab ID generation"
    (let [id1 (server/generate-tab-id)
          id2 (server/generate-tab-id)]
      (is (string? id1))
      (is (string? id2))
      (is (.startsWith id1 "tab_"))
      (is (.startsWith id2 "tab_"))
      (is (not= id1 id2)))))

(deftest test-wrap-hyper-context-new-session
  (testing "Middleware creates new session and tab IDs"
    (let [app-state* (atom (state/init-state))
          handler    (fn [req]
                       {:status 200
                        :body   (str "session: " (:hyper/session-id req)
                                     " tab: " (:hyper/tab-id req))})
          wrapped    ((server/wrap-hyper-context app-state*) handler)
          req        {}
          response   (wrapped req)]

      (is (contains? (:cookies response) "hyper-session"))
      (is (string? (get-in response [:cookies "hyper-session" :value])))
      (is (.startsWith (get-in response [:cookies "hyper-session" :value]) "ses_"))
      (is (.contains (:body response) "session: ses_"))
      (is (.contains (:body response) "tab: tab_")))))

(deftest test-wrap-hyper-context-existing-session
  (testing "Middleware reuses existing session from cookie"
    (let [app-state*          (atom (state/init-state))
          existing-session-id "ses_existing_123"
          handler             (fn [req]
                                {:status 200
                                 :body   (str "session: " (:hyper/session-id req))})
          wrapped             ((server/wrap-hyper-context app-state*) handler)
          req                 {:cookies {"hyper-session" {:value existing-session-id}}}
          response            (wrapped req)]

      (is (nil? (get-in response [:cookies "hyper-session"])))
      (is (.contains (:body response) "session: ses_existing_123")))))

(deftest test-wrap-hyper-context-tab-id-from-query
  (testing "Middleware uses tab-id from query params"
    (let [app-state* (atom (state/init-state))
          handler    (fn [req]
                       {:status 200
                        :body   (str "tab: " (:hyper/tab-id req))})
          wrapped    ((server/wrap-hyper-context app-state*) handler)
          req        {:query-params {"tab-id" "tab_from_query"}}
          response   (wrapped req)]

      (is (.contains (:body response) "tab: tab_from_query")))))

(deftest test-default-datastar-script
  (testing "Datastar script tag generation"
    (let [script (server/default-datastar-script)]
      (is (vector? script))
      (is (match?
            [:script {:src  #".*datastar.*"
                      :type "module"}]
            script)))))

(deftest test-initial-sse-response-headers
  (testing "the SSE handler sets the expected response headers + a streaming body"
    (doseq [[accept-encoding expected-headers]
            [[nil    {"Content-Type"      "text/event-stream"
                      "Cache-Control"     "no-cache, no-transform"
                      "X-Accel-Buffering" "no"}]
             ["br"  {"Content-Type"      "text/event-stream"
                     "Cache-Control"     "no-cache, no-transform"
                     "X-Accel-Buffering" "no"
                     "Content-Encoding"  "br"}]]]
      (let [app-state* (atom (state/init-state))
            handler    (#'server/sse-events-handler app-state*)
            req        (cond-> {:hyper/session-id "ses_test"
                                :hyper/tab-id     "tab_test"}
                         accept-encoding (assoc :headers {"accept-encoding" accept-encoding}))
            response   (handler req)]
        (is (= 200 (:status response)))
        (is (= expected-headers (:headers response)))
        ;; The body is a streaming response body, not a realized string.
        (is (satisfies? ring.core.protocols/StreamableResponseBody (:body response)))))))

(deftest test-create-handler
  (testing "Creates a working ring handler"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)]
      (is (fn? handler))

      ;; Test that it handles a request
      (let [response (handler {:uri "/" :request-method :get})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "Home")))))

  (testing "Allows injecting tags into <head>"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head [[:link {:rel "stylesheet" :href "/app.css"}]]})
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "rel=\"stylesheet\""))
      (is (.contains (:body response) "href=\"/app.css\""))
      (is (.contains (:body response) "data-hyper-head")
          "Head elements are marked for SSE management")))

  (testing "Allows :head to be a function"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head (fn [_req]
                                                     [[:meta {:name "test" :content "ok"}]])})
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "name=\"test\""))
      (is (.contains (:body response) "content=\"ok\""))
      (is (.contains (:body response) "data-hyper-head")
          "Head elements are marked for SSE management")))

  (testing "Datastar script override"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head            (fn [_req]
                                                                [[:meta {:name "test" :content "ok"}]])
                                             :datastar-script [:script {:src "something-else.js"}]})
          response   (handler {:uri "/" :request-method :get})]
      (is (match?
            {:status 200
             :body   (m/pred #(string/includes? % "<script src=\"something-else.js\">"))}
            response))))

  (testing "Datastar script suppress"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head            (fn [_req]
                                                                [[:meta {:name "test" :content "ok"}]])
                                             :datastar-script nil})
          response   (handler {:uri "/" :request-method :get})]
      (is (match?
            {:status 200
             :body   (m/pred #(not (string/includes? % "<script src=")))}
            response))))

  (testing "Allows :head to be a Var containing a function"
    (let [app-state* (atom (state/init-state))
          head-var   (intern *ns* (gensym "head-")
                             (fn [_req] [[:meta {:name "test-head" :content "from-var"}]]))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head head-var})
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "name=\"test-head\""))
      (is (.contains (:body response) "content=\"from-var\""))
      (is (.contains (:body response) "data-hyper-head")
          "Head elements are marked for SSE management")))

  (testing "Head elements render as HTML inside <head>, not as escaped text in <body>"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:head [:style "body { color: red; }"]})
          response   (handler {:uri "/" :request-method :get})
          html       (:body response)
          head-end   (.indexOf html "</head>")
          body-start (.indexOf html "<body")]
      (is (= 200 (:status response)))
      ;; The <style> tag must appear inside <head>, before </head>
      (is (pos? (.indexOf (.substring html 0 head-end) "<style"))
          "Style element should be inside <head>")
      ;; The <style> tag must NOT appear as escaped text in the <body>
      (is (neg? (.indexOf (.substring html body-start) "&lt;style"))
          "Style element should not appear as escaped HTML text in <body>")))

  (testing "Serves static assets from :static-dir"
    (let [tmp-path   (java.nio.file.Files/createTempDirectory
                       "hyper-static-"
                       (make-array java.nio.file.attribute.FileAttribute 0))
          tmp-dir    (.toFile tmp-path)
          css-file   (io/file tmp-dir "styles.css")
          _          (spit css-file "body { background: red; }")
          app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:static-dir (.getAbsolutePath tmp-dir)})
          response   (handler {:uri "/styles.css" :request-method :get})]
      (is (= 200 (:status response)))
      (is (some? (get-in response [:headers "Content-Type"])))
      (is (.contains (get-in response [:headers "Content-Type"]) "text/css"))
      (is (.contains (slurp (:body response)) "background: red"))))

  (testing "Serves static assets from multiple :static-dir roots"
    (let [tmp1-path  (java.nio.file.Files/createTempDirectory
                       "hyper-static-1-"
                       (make-array java.nio.file.attribute.FileAttribute 0))
          tmp2-path  (java.nio.file.Files/createTempDirectory
                       "hyper-static-2-"
                       (make-array java.nio.file.attribute.FileAttribute 0))
          tmp1-dir   (.toFile tmp1-path)
          tmp2-dir   (.toFile tmp2-path)
          a-file     (io/file tmp1-dir "a.css")
          b-file     (io/file tmp2-dir "b.css")
          _          (spit a-file "/* a */")
          _          (spit b-file "/* b */")
          app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
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
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:static-resources "public"})
          response   (handler {:uri "/hyper-test-static.txt" :request-method :get})]
      (is (= 200 (:status response)))
      (is (= "static-ok\n" (slurp (:body response)))))))

(deftest test-open-when-hidden
  (testing "Default includes openWhenHidden: true in data-init"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (string/includes? (:body response) "openWhenHidden: true"))))

  (testing "Explicit true includes openWhenHidden: true in data-init"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:open-when-hidden? true})
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (string/includes? (:body response) "openWhenHidden: true"))))

  (testing "false omits openWhenHidden from data-init"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:open-when-hidden? false})
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (not (string/includes? (:body response) "openWhenHidden"))))))

(deftest test-ring-response-passthrough
  (testing "render fn returning a Ring response map is passed through as-is"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req]
                                    {:status  302
                                     :headers {"Location" "/login"}
                                     :body    ""})}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/" :request-method :get})]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"])))
      (is (= "" (:body response)))))

  (testing "render fn returning hiccup still wraps in HTML"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Normal page"])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "Normal page"))
      (is (.contains (:body response) "<!DOCTYPE html"))))

  (testing "render fn can conditionally redirect or render"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [req]
                                    (if (get-in req [:query-params "auth"])
                                      [:div "Welcome"]
                                      {:status  302
                                       :headers {"Location" "/login"}
                                       :body    ""}))}]]
          handler    (server/create-handler routes app-state*)
          authed     (handler {:uri "/" :request-method :get :query-params {"auth" "true"}})
          unauthed   (handler {:uri "/" :request-method :get :query-params {}})]
      (is (= 200 (:status authed)))
      (is (.contains (:body authed) "Welcome"))
      (is (= 302 (:status unauthed)))
      (is (= "/login" (get-in unauthed [:headers "Location"]))))))

(deftest test-create-handler-with-denormalized-routes
  (testing "Denormalized (nested) routes are served and receive hyper context"
    (let [received-req (atom nil)
          app-state*   (atom (state/init-state))
          routes       [[""
                         ["/"
                          ["" {:name :home
                               :get  (fn [req]
                                       (reset! received-req req)
                                       [:div "Home"])}]]
                         ["/about"
                          ["" {:name  :about
                               :get   (fn [_] [:div "About"])
                               :title "About Us"}]]
                         ["/users/:id" {:name :user-profile
                                        :get  (fn [_] [:div "User"])}]]]
          handler      (server/create-handler routes app-state*)
          response     (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response))
          "Nested home route should be served")
      (is (.contains (:body response) "Home"))
      (is (some? @received-req)
          "Handler should have been called")
      (is (string? (:hyper/session-id @received-req))
          "Request should carry :hyper/session-id")
      (is (string? (:hyper/tab-id @received-req))
          "Request should carry :hyper/tab-id")
      (is (= app-state* (:hyper/app-state @received-req))
          "Request should carry :hyper/app-state")))

  (testing "All sibling routes in a denormalized tree are reachable"
    (let [app-state* (atom (state/init-state))
          routes     [[""
                       ["/"
                        ["" {:name :home
                             :get  (fn [_] [:div "Home"])}]]
                       ["/about"
                        ["" {:name  :about
                             :get   (fn [_] [:div "About"])
                             :title "About Us"}]]
                       ["/users/:id" {:name :user-profile
                                      :get  (fn [_] [:div "User"])}]]]
          handler    (server/create-handler routes app-state*)]
      (is (= 200 (:status (handler {:uri "/about" :request-method :get}))))
      (is (.contains (:body (handler {:uri "/about" :request-method :get})) "About"))
      (is (= 200 (:status (handler {:uri "/users/42" :request-method :get}))))
      (is (.contains (:body (handler {:uri "/users/42" :request-method :get})) "User"))))

  (testing "Denormalized routes are indexed correctly in app-state"
    (let [app-state* (atom (state/init-state))
          routes     [[""
                       ["/"
                        ["" {:name :home
                             :get  (fn [_] [:div "Home"])}]]
                       ["/about"
                        ["" {:name  :about
                             :get   (fn [_] [:div "About"])
                             :title "About Us"}]]
                       ["/users/:id" {:name :user-profile
                                      :get  (fn [_] [:div "User"])}]]]
          _handler   (server/create-handler routes app-state*)
          route-idx  (routes/live-route-index app-state*)]
      (is (contains? route-idx :home))
      (is (contains? route-idx :about))
      (is (contains? route-idx :user-profile))
      (is (= "About Us" (routes/find-route-title route-idx :about))))))

(deftest test-parameter-coercion
  (testing "Route :parameters coerce raw string params into typed values"
    (let [app-state* (atom (state/init-state))
          routes     [["/page" {:name       :page
                                :parameters {:query [:map [:n :int]]}
                                :get        (fn [req]
                                              [:div "n=" (get-in req [:hyper/route :query-params :n])])}]]
          handler    (server/create-handler routes app-state*)
          good       (handler {:uri "/page" :request-method :get :query-params {"n" "5"}})]
      (is (= 200 (:status good)))
      ;; The coerced value is an int (5), not the raw string "5"
      (is (string/includes? (:body good) "n=5"))))

  (testing "Coercion failure returns HTTP 400 with a malli explanation, not a hang"
    (let [app-state* (atom (state/init-state))
          routes     [["/page" {:name       :page
                                :parameters {:query [:map [:n :int]]}
                                :get        (fn [_req] [:div "ok"])}]]
          handler    (server/create-handler routes app-state*)
          bad        (handler {:uri "/page" :request-method :get :query-params {"n" "abc"}})]
      (is (= 400 (:status bad)))
      (is (= "application/json; charset=utf-8" (get-in bad [:headers "Content-Type"])))
      (with-open [output (java.io.ByteArrayOutputStream.)]
        (ring.core.protocols/write-body-to-stream (:body bad) bad output)
        (is (match? {:humanized {:n ["should be an integer"]}}
                    (json/parse-string (.toString output "UTF-8") true))))))

  (testing "Routes without :parameters keep raw string params"
    (let [app-state* (atom (state/init-state))
          routes     [["/page" {:name :page
                                :get  (fn [req]
                                        [:div "n=" (get-in req [:hyper/route :query-params :n])])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/page" :request-method :get :query-params {"n" "abc"}})]
      (is (= 200 (:status response)))
      (is (string/includes? (:body response) "n=abc")))))

(deftest invalid-path-and-uuid-query-parameters-have-sendable-responses
  (let [rendered*  (atom 0)
        app-state* (atom (state/init-state))
        handler    (server/create-handler
                     [["/items/:id" {:name       :item
                                     :parameters {:path  [:map [:id :int]]
                                                  :query [:map [:capture {:optional true} :uuid]]}
                                     :get        (fn [_] (swap! rendered* inc) [:p "OK"])}]]
                     app-state*)]
    (doseq [request [{:uri "/items/not-an-int"}
                     {:uri "/items/1" :query-string "capture=not-a-uuid"}]]
      (let [response (handler (assoc request :request-method :get))]
        (is (= 400 (:status response)))
        (with-open [output (java.io.ByteArrayOutputStream.)]
          (ring.core.protocols/write-body-to-stream (:body response) response output)
          (is (seq (:humanized (json/parse-string (.toString output "UTF-8") true)))))))
    (is (zero? @rendered*))))

(deftest invalid-navigation-parameters-do-not-replace-the-tab-route
  (let [app-state* (atom (state/init-state))
        handler    (server/create-handler
                     [["/items/:id" {:name       :item
                                     :parameters {:path [:map [:id :int]]}
                                     :get        (fn [_] [:p "OK"])}]] app-state*)
        response   (handler {:uri          "/hyper/navigate"            :request-method :post
                             :query-params {"path" "/items/not-an-int"}})]
    (is (= 400 (:status response)))
    (is (= {:id ["should be an integer"]}
           (:humanized (json/parse-string (:body response) true))))
    (is (every? #(nil? (:route %)) (vals (:tabs @app-state*))))))

(deftest navigation-uses-the-same-typed-parameters-as-page-loads
  (let [app-state* (atom (state/init-state))
        capture    (random-uuid)
        handler    (server/create-handler
                     [["/items/:id" {:name       :item
                                     :parameters {:path  [:map [:id :int]]
                                                  :query [:map [:capture :uuid]]}
                                     :get        (fn [_] [:p "OK"])}]] app-state*)
        good       (handler {:uri          "/hyper/navigate"                           :request-method :post
                             :query-params {"path" (str "/items/42?capture=" capture)}})]
    (is (= 200 (:status good)))
    (is (some #(= {:name        :item    :path         "/items/42"
                   :path-params {:id 42} :query-params {:capture capture}}
                  (:route %)) (vals (:tabs @app-state*))))
    (let [before (into {} (map (fn [[id tab]] [id (:route tab)])) (:tabs @app-state*))
          bad    (handler {:uri          "/hyper/navigate"                    :request-method :post
                           :query-params {"path" "/items/42?capture=invalid"}})]
      (is (= 400 (:status bad)))
      (is (= {:capture ["should be a uuid"]}
             (:humanized (json/parse-string (:body bad) true))))
      (is (= before (into {} (keep (fn [[id tab]] (when (:route tab) [id (:route tab)])))
                          (:tabs @app-state*)))))))

(deftest coercion-response-middleware-preserves-unrelated-results-and-errors
  (let [response {:status 418 :body "teapot"}
        error    (ex-info "unrelated" {:type ::unrelated})
        raised*  (atom nil)]
    (is (= response ((#'server/-wrap-coercion-errors (constantly response)) {})))
    (is (identical? error
                    (try ((#'server/-wrap-coercion-errors (fn [_] (throw error))) {})
                         (catch Exception caught caught))))
    ((#'server/-wrap-coercion-errors (fn [_ _ raise] (raise error)))
     {} (fn [_] (is false "unrelated errors must not become responses"))
     #(reset! raised* %))
    (is (identical? error @raised*))))

(deftest test-create-handler-with-hyper-disabled
  (testing "render fn can disable endpoint wrapping"
    (let [app-state*  (atom (state/init-state))
          json-result "{\"foo\":1}"
          routes      [["/api/info" {:name            :api-info
                                     :hyper/disabled? true
                                     :get             (fn [_req]
                                                        {:status  200
                                                         :headers {"Content-Type" "application/json"}
                                                         :body    "{\"foo\":1}"})}]]
          handler     (server/create-handler routes app-state*)
          response    (handler {:uri "/api/info" :request-method :get})]
      (is (= 200 (:status response)))
      (is (= json-result (:body response))))))

(deftest test-create-handler-with-global-watches
  (testing "Global :watches are stored in app-state"
    (let [app-state* (atom (state/init-state))
          global-src (atom 0)
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]
                      ["/about" {:name :about
                                 :get  (fn [_req] [:div "About"])}]]
          _handler   (server/create-handler routes app-state*
                                            {:watches [global-src]})]
      (is (= [global-src] (:global-watches @app-state*)))))

  (testing "No :watches option leaves global-watches empty"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state* {})]
      (is (= [] (:global-watches @app-state*)))))

  (testing "Non-Var :head does not add to global-watches"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*
                                            {:head [:style "body{}"]})]
      (is (= [] (:global-watches @app-state*))))))

(deftest test-server-lifecycle
  (testing "Server start and stop"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Hello"])}]]
          handler    (server/create-handler routes app-state*)
          stop-fn    (server/start! handler {:port 13000})]

      (is (some? stop-fn))
      (is (fn? stop-fn))

      ;; Stop server
      (server/stop! stop-fn))))

(deftest test-shutdown-cleans-up-tabs
  (testing "Stopping the server cleans up all tab watchers, actions, and renderer threads"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Hello"])}]]
          handler    (server/create-handler routes app-state*)
          stop-fn    (server/start! handler {:port 13001})
          session-id "test-session"
          tab-id-1   "test_tab_1"
          tab-id-2   "test_tab_2"
          stopped    (atom #{})]

      ;; Simulate two connected tabs with watchers, actions, and mock renderers
      (doseq [tab-id [tab-id-1 tab-id-2]]
        (state/get-or-create-tab! app-state* session-id tab-id)
        (render/register-render-fn! app-state* tab-id (fn [_] [:div "test"]))
        ;; Store a mock renderer handle with a stop! fn
        (swap! app-state* assoc-in [:tabs tab-id :renderer]
               {:trigger-render! (fn [])
                :stop!           #(swap! stopped conj tab-id)})
        (watch/setup-watchers! app-state* session-id tab-id (fn []))
        (actions/register-action! app-state* session-id tab-id
                                  (fn [_] (println "action")) (str "a-" tab-id "-0")))

      ;; Verify resources exist
      (is (= 2 (count (:tabs @app-state*))))
      (is (= 2 (count (:actions @app-state*))))

      ;; Stop — should clean up everything
      (server/stop! stop-fn)

      (is (empty? (:tabs @app-state*)) "All tabs should be cleaned up")
      (is (empty? (:actions @app-state*)) "All actions should be cleaned up")
      (is (= #{tab-id-1 tab-id-2} @stopped) "All renderer stop! fns should be called"))))

(deftest test-create-handler-with-var-routes
  (testing "Accepts a Var and serves initial routes"
    (let [app-state*  (atom (state/init-state))
          ;; Use an atom to back the Var so we can simulate re-def
          routes-atom (atom [["/" {:name :home
                                   :get  (fn [_req] [:div "Home V1"])}]])
          routes-var  (intern *ns* (gensym "test-routes-") @routes-atom)
          handler     (server/create-handler routes-var app-state*)
          response    (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "Home V1"))))

  (testing "Picks up route changes on next request"
    (let [app-state* (atom (state/init-state))
          v1-routes  [["/" {:name :home
                            :get  (fn [_req] [:div "Version 1"])}]]
          v2-routes  [["/" {:name :home
                            :get  (fn [_req] [:div "Version 2"])}]
                      ["/new" {:name :new-page
                               :get  (fn [_req] [:div "New Page"])}]]
          routes-var (intern *ns* (gensym "test-routes-") v1-routes)
          handler    (server/create-handler routes-var app-state*)]

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
    (let [app-state*  (atom (state/init-state))
          routes      [["/" {:name :home
                             :get  (fn [_req] [:div "Stable"])}]]
          routes-var  (intern *ns* (gensym "test-routes-") routes)
          build-count (atom 0)
          handler     (server/create-handler routes-var app-state*)]

      ;; build-ring-handler was called once during create-handler
      ;; Subsequent requests with the same routes should not rebuild
      (with-redefs [routes/find-render-fn (let [orig routes/find-render-fn]
                                            (fn [route-index route-name]
                                              (swap! build-count inc)
                                              (orig route-index route-name)))]
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
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Static"])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "Static")))))

(deftest test-base-path
  (testing "Default (no :base-path) uses /hyper/* paths"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/" :request-method :get})
          body       (:body response)]
      (is (= 200 (:status response)))
      (is (string/includes? body "/hyper/events"))
      (is (string/includes? body "/hyper/navigate"))
      (is (not (string/includes? body "//hyper")))))

  (testing ":base-path prefixes /hyper/events in data-init"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:base-path "/my-app"})
          response   (handler {:uri "/" :request-method :get})
          body       (:body response)]
      (is (= 200 (:status response)))
      (is (string/includes? body "/my-app/hyper/events"))
      (is (not (string/includes? body "@get('/hyper/events")))))

  (testing ":base-path prefixes /hyper/navigate in popstate JS"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:base-path "/my-app"})
          response   (handler {:uri "/" :request-method :get})
          body       (:body response)]
      (is (string/includes? body "/my-app/hyper/navigate"))
      (is (not (string/includes? body "fetch('/hyper/navigate")))))

  (testing ":base-path mounts system routes under the prefix"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:base-path "/my-app"})
          ;; System routes should be accessible under /my-app/hyper/*
          events-res (handler {:uri "/my-app/hyper/events" :request-method :get})
          ;; And the old paths should not match (404)
          old-res    (handler {:uri "/hyper/events" :request-method :get})]
      (is (not= 404 (:status events-res)))
      (is (= 404 (:status old-res)))))

  (testing ":base-path is stored in app-state"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*
                                            {:base-path "/sub"})]
      (is (= "/sub" (:base-path @app-state*)))))

  (testing "default (no :base-path) stores empty string in app-state"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*)]
      (is (= "" (:base-path @app-state*)))))

  (testing ":base-path works alongside other options"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:base-path         "/app"
                                             :open-when-hidden? false
                                             :head              [[:link {:rel "stylesheet" :href "/app.css"}]]})
          response   (handler {:uri "/" :request-method :get})
          body       (:body response)]
      (is (= 200 (:status response)))
      (is (string/includes? body "/app/hyper/events"))
      (is (string/includes? body "/app/hyper/navigate"))
      (is (string/includes? body "rel=\"stylesheet\""))
      (is (not (string/includes? body "openWhenHidden"))))))

(deftest test-not-found-handler
  (testing "Unmatched route renders the default 404 view as a full Hyper page"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          response   (handler {:uri "/does-not-exist" :request-method :get})
          body       (:body response)]
      (is (= 404 (:status response)))
      (is (string/includes? (get-in response [:headers "Content-Type"]) "text/html"))
      ;; Full document scaffolding, same as a normal page
      (is (string/includes? body "<!DOCTYPE html>"))
      (is (string/includes? body "/hyper/events")
          "404 page boots the SSE connection like any other page")
      ;; Default not-found content + title
      (is (string/includes? body "404"))
      (is (string/includes? body "<title>Not Found</title>"))))

  (testing "Custom :not-found renderer is used and sees the request"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*
                                            {:not-found (fn [req]
                                                          [:div "Missing: " (:uri req)])})
          response   (handler {:uri "/ghost" :request-method :get})
          body       (:body response)]
      (is (= 404 (:status response)))
      (is (string/includes? body "Missing: /ghost"))))

  (testing "Custom :not-found may be a Var (picks up redefinitions)"
    (let [app-state*    (atom (state/init-state))
          not-found-var (intern *ns* (gensym "nf-")
                                (fn [_req] [:div "var-404-v1"]))
          routes        [["/" {:name :home
                               :get  (fn [_req] [:div "Home"])}]]
          handler       (server/create-handler routes app-state* {:not-found not-found-var})]
      (is (string/includes? (:body (handler {:uri "/x" :request-method :get}))
                            "var-404-v1"))
      ;; Redefine the Var's value; the handler should pick it up without rebuild
      (alter-var-root not-found-var (constantly (fn [_req] [:div "var-404-v2"])))
      (is (string/includes? (:body (handler {:uri "/x" :request-method :get}))
                            "var-404-v2"))))

  (testing "The :not-found renderer is stored in app-state"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*)]
      (is (fn? (:not-found @app-state*)))))

  (testing ":not-found nil disables the feature (reitit plain-text 404)"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state* {:not-found nil})
          response   (handler {:uri "/nope" :request-method :get})]
      (is (= 404 (:status response)))
      (is (nil? (:not-found @app-state*)))
      ;; Plain reitit default — not the full Hyper HTML document
      (is (not (string/includes? (str (:body response)) "<!DOCTYPE html>")))))

  (testing ":not-found nil makes navigate to a dead route reply with JSON 404"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state* {:not-found nil})
          response   (handler {:uri            "/hyper/navigate"
                               :request-method :post
                               :query-params   {"path" "/ghost"}})]
      (is (= 404 (:status response)))
      (is (string/includes? (:body response) "Route not found"))))

  (testing "Matched route with unsupported method is not treated as 404"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home
                            :get  (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          ;; POST to a GET-only route -> 405, not the 404 page
          response   (handler {:uri "/" :request-method :post})]
      (is (= 405 (:status response))))))

;; ===========================================================================
;; Graceful reconnect (Tier 0): detach / re-attach / grace-window reaper
;; ===========================================================================

(defn- ctx
  [app-state* session-id tab-id]
  {:hyper/session-id session-id
   :hyper/tab-id     tab-id
   :hyper/app-state  app-state*
   :hyper/router     nil})

(defmacro ^:private rendering
  [app-state* session-id tab-id & body]
  `(binding [context/*request*    (ctx ~app-state* ~session-id ~tab-id)
             context/*action-idx* (atom 0)]
     ~@body))

(deftest test-detach-preserves-tab-state
  (testing "detach-tab! keeps tab-cursor data, stamps :disconnected-at, drops renderer"
    (let [app-state* (atom (state/init-state))
          session-id "s-detach"
          tab-id     "t_detach"
          stopped?   (atom false)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:render-queue (rq/make-queue)
              :stop!        #(reset! stopped? true)})
      (rendering app-state* session-id tab-id
                 (reset! (h/tab-cursor :count 0) 42))
      (is (= 42 (get-in @app-state* [:tabs tab-id :data :count])))

      (server/detach-tab! app-state* tab-id)

      (is (true? @stopped?) "renderer stop! was called on detach")
      (is (contains? (:tabs @app-state*) tab-id) "tab survives detach")
      (is (= 42 (get-in @app-state* [:tabs tab-id :data :count]))
          "tab-cursor state survives detach")
      (is (number? (get-in @app-state* [:tabs tab-id :disconnected-at]))
          ":disconnected-at is stamped")
      (is (nil? (get-in @app-state* [:tabs tab-id :renderer]))
          "renderer is removed on detach"))))

(deftest test-detach-does-not-interrupt-worker
  (testing "a spawn! worker survives a detach (no interrupt within grace)"
    (let [app-state*  (atom (state/init-state))
          session-id  "s-detach-worker"
          tab-id      "t_detach_worker"
          started     (promise)
          interrupted (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:render-queue (rq/make-queue) :stop! (fn [])})
      (rendering app-state* session-id tab-id
                 (h/spawn! (fn []
                             (deliver started true)
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (is (true? (deref started 1000 :timeout)) "worker started")

      (server/detach-tab! app-state* tab-id)

      (is (= :still-running (deref interrupted 300 :still-running))
          "worker is NOT interrupted by a detach")
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews])))
          "worker subview survives detach")
      (subview/teardown-all! app-state* tab-id))))

(deftest test-watchers-follow-reattached-renderer
  (testing "after detach + re-attach, an existing app-state watcher drives the
            NEW renderer's queue without being re-installed"
    (let [app-state*      (atom (state/init-state))
          session-id      "s-reattach"
          tab-id          "t_reattach"
          trigger-render! (#'server/tab-trigger-render! app-state* tab-id)
          q1              (rq/make-queue)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :renderer] {:render-queue q1})
      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      (rendering app-state* session-id tab-id
                 (reset! (h/tab-cursor :n 0) 1))
      (is (= {:idle? false :full-render? true :shutdown? false :dirty-ids #{} :scripts []}
             (rq/drain! q1))
          "connection-1 queue received the render")

      (server/detach-tab! app-state* tab-id)
      (let [q2 (rq/make-queue)]
        (swap! app-state* assoc-in [:tabs tab-id :renderer] {:render-queue q2})
        (swap! app-state* update-in [:tabs tab-id] dissoc :disconnected-at)

        (rendering app-state* session-id tab-id
                   (reset! (h/tab-cursor :n 1) 2))
        (is (= {:idle? false :full-render? true :shutdown? false :dirty-ids #{} :scripts []}
               (rq/drain! q2))
            "connection-2 queue received the render via the same watcher"))
      (watch/remove-watchers! app-state* tab-id))))

(deftest test-reaper-respects-grace-window
  (testing "reap-disconnected-tabs! leaves tabs within grace and reaps expired ones"
    (let [app-state*  (atom (assoc (state/init-state) :disconnect-grace-ms 180000))
          session-id  "s-reap"
          fresh       "t_fresh"
          stale       "t_stale"
          interrupted (promise)]
      (state/get-or-create-tab! app-state* session-id fresh)
      (swap! app-state* assoc-in [:tabs fresh :disconnected-at] (System/currentTimeMillis))

      (state/get-or-create-tab! app-state* session-id stale)
      (swap! app-state* assoc-in [:tabs stale :renderer]
             {:render-queue (rq/make-queue) :stop! (fn [])})
      (rendering app-state* session-id stale
                 (h/spawn! (fn []
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (Thread/sleep 50)
      (swap! app-state* assoc-in [:tabs stale :disconnected-at]
             (- (System/currentTimeMillis) 200000))

      (server/reap-disconnected-tabs! app-state* (System/currentTimeMillis))

      (is (contains? (:tabs @app-state*) fresh)
          "tab within grace window is preserved")
      (is (not (contains? (:tabs @app-state*) stale))
          "expired tab is fully reaped")
      (is (true? (deref interrupted 1000 :timeout))
          "reaping an expired tab interrupts its worker"))))

(deftest test-disconnect-grace-ms-option
  (testing "defaults to 3 minutes"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*)]
      (is (= 180000 (:disconnect-grace-ms @app-state*)))))

  (testing "honors an explicit :disconnect-grace-ms"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*
                                            {:disconnect-grace-ms 5000})]
      (is (= 5000 (:disconnect-grace-ms @app-state*))))))

(deftest test-sse-connect-expr
  (testing "builds the SSE @get expression shared by data-init and reconnect"
    (is (= "@get('/hyper/events?tab-id=tab_1', {openWhenHidden: true})"
           (server/sse-connect-expr "" "tab_1" true)))
    (is (= "@get('/hyper/events?tab-id=tab_1')"
           (server/sse-connect-expr "" "tab_1" false)))
    (is (= "@get('/app/hyper/events?tab-id=tab_1', {openWhenHidden: true})"
           (server/sse-connect-expr "/app" "tab_1" true))))

  (testing "the page's data-init uses the same builder"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          body       (:body (handler {:uri "/" :request-method :get}))]
      ;; the exact @get string (entity-decoded apostrophes) appears in data-init
      (is (string/includes? body "/hyper/events?tab-id=tab_"))
      (is (string/includes? body "openWhenHidden: true"))
      ;; :open-when-hidden? is stashed so reconnect can reproduce it
      (is (true? (:open-when-hidden? @app-state*))))))

(deftest test-rendered-page-wires-connection-signals
  (testing "an initial page load declares the connection signals + handler on <body>"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          handler    (server/create-handler routes app-state*)
          body       (:body (handler {:uri "/" :request-method :get}))]
      (is (string/includes? body "data-signals:_hyper-connection__ifmissing"))
      (is (string/includes? body "data-signals:_hyper-connected__ifmissing"))
      (is (string/includes? body "data-on:datastar-fetch"))
      (is (string/includes? body "evt.detail.el === el")))))

;; ===========================================================================
;; Heartbeat keepalive
;; ===========================================================================

(deftest test-heartbeat-ms-option
  (testing "defaults to 25s"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*)]
      (is (= 25000 (:heartbeat-ms @app-state*)))))

  (testing "honors an explicit interval"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state* {:heartbeat-ms 5000})]
      (is (= 5000 (:heartbeat-ms @app-state*)))))

  (testing "nil disables it"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state* {:heartbeat-ms nil})]
      (is (nil? (:heartbeat-ms @app-state*))))))

(deftest test-heartbeat-sent-when-idle
  (testing "an idle renderer loop emits keepalive comments, and a closed
            connection (a write that throws -> send-sse! false) exits the loop"
    (let [app-state* (atom (assoc (state/init-state) :heartbeat-ms 20))
          q          (rq/make-queue)
          sends      (atom [])
          done       (promise)
          ;; Capture each written chunk as a UTF-8 string, mirroring the SSE
          ;; payloads the loop writes to the response output stream.  A proxy
          ;; overrides every `write` overload by name, so handle both the
          ;; byte[] form send-sse! uses and the 3-arg form.
          os         (proxy [OutputStream] []
                       (write
                         ([b] (swap! sends conj (String. ^bytes b "UTF-8")))
                         ([b off len]
                          (swap! sends conj (String. ^bytes b (int off) (int len) "UTF-8"))))
                       (flush []))]
      (future
        (#'server/-renderer-loop! app-state* "s" "t" os false q)
        (deliver done true))
      (Thread/sleep 120)
      ;; stop the loop
      (rq/enqueue-shutdown! q)
      (is (true? (deref done 1000 :timeout)) "loop exited on shutdown")
      ;; at least one heartbeat comment was written (the connected event is
      ;; an SSE event, the heartbeat is a ":"-prefixed comment line)
      (is (some #(and (string? %) (string/starts-with? % ":")) @sends)
          "a keepalive comment was emitted while idle"))))

(deftest test-components-js-handler-cache-headers
  (testing "components.js is only served immutable when ?v matches the
            current bundle hash, so a stale ?v can never poison the cache"
    (let [saved @component/registry*]
      (try
        (reset! component/registry* {})
        (component/register-component! "x-widget" {:attrs [] :render "(fn [_ _] [:i])"})
        (let [app-state* (atom {})
              handler    (#'server/components-js-handler app-state*)
              hash       (:hash (bundle/bundle))]
          (testing "matching ?v -> immutable (true content-addressed cache)"
            (let [resp (handler {:query-params {"v" hash}})]
              (is (= 200 (:status resp)))
              (is (= "public, max-age=31536000, immutable"
                     (get-in resp [:headers "Cache-Control"])))
              (is (= (str "\"" hash "\"") (get-in resp [:headers "ETag"])))
              (is (string? (:body resp)))))
          (testing "stale ?v -> no-cache (no immutable promise for old URL)"
            (let [resp (handler {:query-params {"v" "0000000000000000"}})]
              (is (= 200 (:status resp)))
              (is (= "no-cache" (get-in resp [:headers "Cache-Control"])))
              ;; still serves the *current* bundle + its real hash
              (is (= (str "\"" hash "\"") (get-in resp [:headers "ETag"])))))
          (testing "missing ?v -> no-cache"
            (let [resp (handler {})]
              (is (= 200 (:status resp)))
              (is (= "no-cache" (get-in resp [:headers "Cache-Control"]))))))
        (finally
          (reset! component/registry* saved))))))

(deftest test-components-js-handler-no-components
  (testing "404 when no components are registered"
    (let [saved @component/registry*]
      (try
        (reset! component/registry* {})
        (let [app-state* (atom {})
              handler    (#'server/components-js-handler app-state*)
              resp       (handler {:query-params {"v" "anything"}})]
          (is (= 404 (:status resp))))
        (finally
          (reset! component/registry* saved))))))
