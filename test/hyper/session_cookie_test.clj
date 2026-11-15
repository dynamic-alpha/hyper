(ns hyper.session-cookie-test
  "The session cookie: its default attributes, the `:session` option's
   overrides, which responses set it, and renewal on page loads."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.state :as state]))

(defn- app
  "A hyper handler with a page at / holding an action, a page at /other, a
   route at /api returning a Ring map, and `opts`; and its app-state."
  [& opts]
  (let [app* (atom (state/init-state))]
    [(apply h/create-handler
            [["/" {:name :home
                   :get  (fn [_]
                           [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))} "+"])}]
             ["/other" {:name :other :get (fn [_] [:div "other"])}]
             ["/api" {:get (fn [_] {:status 200 :body "{}"})}]]
            :app-state app*
            opts)
     app*]))

(defn- request
  "`req` with a cookie header of `cookie` (\"name=value\") when it is given."
  [req cookie]
  (cond-> (merge {:request-method :get :headers {}} req)
    cookie (assoc-in [:headers "cookie"] cookie)))

(defn- set-cookies
  "The `Set-Cookie` headers of `response`."
  [response]
  (let [header (get-in response [:headers "Set-Cookie"])]
    (if (string? header) [header] (vec header))))

(defn- session-cookie
  "The `Set-Cookie` header of `response` for cookie `cookie-name`."
  [response cookie-name]
  (some #(when (str/starts-with? % (str cookie-name "=")) %) (set-cookies response)))

(defn- attributes
  "The attributes of `Set-Cookie` header `header`, lower-cased."
  [header]
  (set (map (comp str/lower-case str/trim) (rest (str/split header #";")))))

(defn- load-page!
  "Load / into tab T, with cookie `cookie` and `req` merged in."
  ([handler cookie] (load-page! handler cookie {}))
  ([handler cookie req]
   (handler (request (merge {:uri "/" :query-string "tab-id=T"} req) cookie))))

(deftest test-default-attributes
  (let [[handler] (app)]
    (testing "over HTTP"
      (let [header (session-cookie (load-page! handler nil) "hyper-session")]
        (is (re-find #"^hyper-session=ses_" header))
        (is (= #{"path=/" "httponly" "max-age=604800" "samesite=lax"} (attributes header)))))
    (testing "over HTTPS it is Secure"
      (is (contains? (attributes (session-cookie (load-page! handler nil {:scheme :https}) "hyper-session"))
                     "secure")))
    (testing "behind a proxy that forwards HTTPS it is Secure"
      (is (contains? (attributes (session-cookie (load-page! handler nil {:headers {"x-forwarded-proto" "https"}})
                                                 "hyper-session"))
                     "secure"))
      (is (contains? (attributes (session-cookie (load-page! handler nil {:headers {"x-forwarded-proto" "HTTPS, http"}})
                                                 "hyper-session"))
                     "secure")))
    (testing "behind a proxy that forwards HTTP it is not Secure"
      (is (not (contains? (attributes (session-cookie (load-page! handler nil {:headers {"x-forwarded-proto" "http"}})
                                                      "hyper-session"))
                          "secure"))))))

(deftest test-pages-loaded-at-once-follow-the-last-cookie
  (let [[handler app*] (app)
        start          (promise)
        loads          (mapv (fn [tab-id]
                               (future @start (load-page! handler nil {:query-string (str "tab-id=" tab-id)})))
                             ["A" "B"])
        _              (deliver start true)
        [a b]          (mapv deref loads)
        cookie-of      #(re-find #"^hyper-session=[^;]+" (session-cookie % "hyper-session"))
        session-of     #(get-in @app* [:tabs % :session-id])
        former-id      (session-of "A")
        kept-id        (session-of "B")
        ;; the browser keeps the cookie set last
        kept           (cookie-of b)
        events         (fn [tab-id cookie]
                         (handler (request {:uri "/hyper/events" :query-string (str "tab-id=" tab-id)} cookie)))]
    (testing "each page load without a cookie starts its own session"
      (is (not= (cookie-of a) kept))
      (is (not= former-id kept-id))
      (is (= #{former-id kept-id} (set (keys (:sessions @app*))))))
    (testing "both pages' streams are served with the cookie the browser kept"
      (doseq [tab-id ["A" "B"]]
        (let [response (events tab-id kept)]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "Content-Type"])))
          (is (not (string? (:body response))) "a stream, not a short answer"))))
    (testing "both tabs are then of that session, and the session left without a tab is removed"
      (is (= kept-id (session-of "A") (session-of "B")))
      (is (= #{"A" "B"} (get-in @app* [:sessions kept-id :tabs])))
      (is (= #{kept-id} (set (keys (:sessions @app*))))))
    (testing "the moved tab's actions work with the cookie"
      (let [action-id (second (re-find #"hyperAction\(&apos;(a_A_\d+)" (:body a)))
            response  (handler (request {:request-method :post
                                         :uri            "/hyper/actions"
                                         :query-string   "tab-id=A"
                                         :headers        {"content-type" "application/json"}
                                         :body           (json/generate-string
                                                           {:base    nil
                                                            :actions [{:id action-id :seq 1 :v nil :signals "{}" :params nil}]})}
                                        kept))]
        (is (= 204 (:status response)))
        (is (= 1 (get-in @app* [:tabs "A" :data :clicks])))))))

(deftest test-a-tab-follows-the-cookie-only-before-it-connects-and-to-a-held-session
  (let [[handler app*] (app)
        cookie-of      #(re-find #"^hyper-session=[^;]+" (session-cookie % "hyper-session"))
        kept           (cookie-of (load-page! handler nil {:query-string "tab-id=K"}))
        _              (load-page! handler nil)
        former-id      (get-in @app* [:tabs "T" :session-id])
        events         #(handler (request {:uri "/hyper/events" :query-string "tab-id=T"} %))]
    (testing "a stream without a cookie, as from a browser that drops it, is refused"
      (let [response (events nil)]
        (is (= 404 (:status response)))
        (is (string? (:body response)) "no stream is opened"))
      (is (= former-id (get-in @app* [:tabs "T" :session-id])) "and the tab stays in its session"))
    (testing "a tab that connected before reloads instead"
      ;; as the tab's first stream connection does
      (swap! app* assoc-in [:tabs "T" :connection-initialized?] true)
      (let [response (events kept)]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "window.location.reload()")))
      (is (= former-id (get-in @app* [:tabs "T" :session-id])) "and stays in its session")
      (is (= #{"T"} (get-in @app* [:sessions former-id :tabs]))))))

(deftest test-cookie-overrides
  (testing "attributes and name are overridden"
    (let [[handler app*] (app :session {:cookie {:name      "sid"
                                                 :same-site :strict
                                                 :max-age   60
                                                 :secure    false}})
          response       (load-page! handler nil {:scheme :https})
          header         (session-cookie response "sid")]
      (is (nil? (session-cookie response "hyper-session")))
      (is (= #{"path=/" "httponly" "max-age=60" "samesite=strict"} (attributes header)))
      (testing "and the session is read from the named cookie"
        (load-page! handler "sid=S" {:query-string "tab-id=T2"})
        (is (= "S" (get-in @app* [:tabs "T2" :session-id]))))
      (testing "and not from hyper-session"
        (let [[handler app*] (app :session {:cookie {:name "sid"}})]
          (load-page! handler "hyper-session=S")
          (is (not= "S" (get-in @app* [:tabs "T" :session-id])))))))
  (testing ":secure true makes it Secure over HTTP"
    (let [[handler] (app :session {:cookie {:secure true}})]
      (is (contains? (attributes (session-cookie (load-page! handler nil) "hyper-session"))
                     "secure"))))
  (testing "an empty :session keeps the defaults"
    (let [[handler] (app :session {})]
      (is (= #{"path=/" "httponly" "max-age=604800" "samesite=lax"}
             (attributes (session-cookie (load-page! handler nil) "hyper-session")))))))

(deftest test-invalid-session-option
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":session must be a map"
                        (app :session "sid")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cookie of :session must be a map"
                        (app :session {:cookie "sid"}))))

(deftest test-a-page-load-renews-the-cookie
  (let [[handler] (app)]
    (testing "a page load with the cookie sets it again, for the same session"
      (let [header (session-cookie (load-page! handler "hyper-session=S") "hyper-session")]
        (is (str/starts-with? header "hyper-session=S;"))
        (is (contains? (attributes header) "max-age=604800"))))
    (testing "so does the not-found page"
      (let [response (handler (request {:uri "/nowhere"} "hyper-session=S"))]
        (is (= 404 (:status response)))
        (is (str/starts-with? (session-cookie response "hyper-session") "hyper-session=S;"))))))

(deftest test-other-requests-do-not-set-the-cookie
  (let [[handler] (app)
        html      (:body (load-page! handler "hyper-session=S"))
        action-id (second (re-find #"hyperAction\(&apos;(a_T_\d+)" html))
        action    (fn [cookie]
                    (handler (request {:request-method :post
                                       :uri            "/hyper/actions"
                                       :query-string   "tab-id=T"
                                       :headers        {"content-type" "application/json"}
                                       :body           (json/generate-string
                                                         {:base    nil
                                                          :actions [{:id action-id :seq 1 :v nil :signals "{}" :params nil}]})}
                                      cookie)))
        navigate  (fn [cookie]
                    (handler (request {:request-method :post
                                       :uri            "/hyper/navigate"
                                       :query-string   "tab-id=T&path=%2Fother"}
                                      cookie)))
        events    (fn [cookie]
                    (handler (request {:uri "/hyper/events" :query-string "tab-id=T"} cookie)))]
    (testing "an action, a navigation and the event stream with the cookie"
      (let [response (action "hyper-session=S")]
        (is (= 204 (:status response)))
        (is (empty? (set-cookies response))))
      (let [response (navigate "hyper-session=S")]
        (is (= 200 (:status response)))
        (is (empty? (set-cookies response))))
      (let [response (events "hyper-session=S")]
        (is (= 200 (:status response)))
        (is (empty? (set-cookies response)))))
    (testing "an action, a navigation and the event stream refused for lack of the cookie"
      (doseq [response [(action nil) (navigate nil) (events nil)]]
        (is (= 404 (:status response)))
        (is (empty? (set-cookies response)))))
    (testing "a route returning a Ring map, with or without the cookie"
      (is (empty? (set-cookies (handler (request {:uri "/api"} nil)))))
      (is (empty? (set-cookies (handler (request {:uri "/api"} "hyper-session=S"))))))))
