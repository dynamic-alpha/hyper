(ns hyper.ring-test
  "`hyper.ring` and `hyper.ring.middleware/wrap-hyper`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.ring :as h.ring]
            [hyper.ring.middleware :as h.ring.middleware]
            [hyper.server :as server]
            [hyper.state :as state]
            [ring.core.protocols :as ring-protocols]
            [ring.util.response :as response]))

(defn- app
  "A hyper handler, with `opts`, and its app-state.  It has a page at / showing
   the session's :user; GET /sign-in and /page-sign-in (a page route) rotate
   the session and redirect to /; POST /sign-out ends it; POST /whoami
   answers with the request's `h.ring/session-id`.  Its middleware records
   `h.ring/endpoint` of each request by URI in `endpoints`."
  [endpoints & opts]
  (let [app* (atom (state/init-state))]
    [(apply h/create-handler
            [["/" {:name :home
                   :get  (fn [_] [:p#user (str "user:" @(h/session-cursor :user))])}]
             ["/sign-in" {:hyper/disabled? true
                          :get             (fn [req] (-> (response/redirect "/") (h.ring/rotate-session! req)))}]
             ["/page-sign-in" {:name :page-sign-in
                               :get  (fn [req] (-> (response/redirect "/") (h.ring/rotate-session! req)))}]
             ["/sign-out" {:post (fn [req] (-> (response/redirect "/") (h.ring/end-session! req)))}]
             ["/whoami" {:post (fn [req] {:status 200 :body (pr-str (h.ring/session-id req))})}]]
            :app-state app*
            :middleware [(fn [handler]
                           (fn [req]
                             (swap! endpoints assoc (:uri req) (h.ring/endpoint req))
                             (handler req)))]
            opts)
     app*]))

(defn- request
  "Send a `method` request for `uri` through `handler`, with cookie `cookie`
   (\"name=value\"), or none when it is nil."
  ([handler cookie method uri] (request handler cookie method uri nil))
  ([handler cookie method uri query-string]
   (handler (cond-> {:request-method method :uri uri :headers {}}
              query-string (assoc :query-string query-string)
              cookie       (assoc-in [:headers "cookie"] cookie)))))

(defn- load-page!
  [handler cookie tab-id]
  (request handler cookie :get "/" (str "tab-id=" tab-id)))

(defn- written!
  "`response`, once its body has been written, as the server does."
  [response]
  (ring-protocols/write-body-to-stream (:body response) response (java.io.ByteArrayOutputStream.))
  response)

(defn- connect!
  "Open the event stream of tab `tab-id` and return `{:done stream :out bytes}`
   once it is attached."
  [handler app* cookie tab-id]
  (let [response (request handler cookie :get "/hyper/events" (str "tab-id=" tab-id))
        out      (java.io.ByteArrayOutputStream.)
        done     (future (ring-protocols/write-body-to-stream (:body response) response out))]
    (loop [i 0]
      (when (and (< i 200) (nil? (get-in @app* [:tabs tab-id :renderer])))
        (Thread/sleep 10)
        (recur (inc i))))
    (is (some? (get-in @app* [:tabs tab-id :renderer])) "the stream is attached")
    {:done done :out out}))

(defn- written [{:keys [out]}]
  (.toString ^java.io.ByteArrayOutputStream out "UTF-8"))

(defn- ended? [{:keys [done]}]
  (not= :timeout (deref done 2000 :timeout)))

(defn- set-cookies
  "The `Set-Cookie` headers of `response` for cookie `cookie-name`."
  [response cookie-name]
  (let [header (get-in response [:headers "Set-Cookie"])]
    (filter #(str/starts-with? % (str cookie-name "="))
            (if (string? header) [header] header))))

(defn- set-cookie [response]
  (first (set-cookies response "hyper-session")))

(defn- cookie-of
  "The session cookie (\"name=value\") that `response` sets."
  [response]
  (some-> (set-cookie response) (str/split #";") first))

(defn- session-id-of [cookie]
  (second (str/split cookie #"=" 2)))

(defn- attributes [header]
  (set (map (comp str/lower-case str/trim) (rest (str/split header #";")))))

(defn- signed-in-session
  "Load tabs T1 and T2 into one session holding :user alice, with T1's stream
   open.  Returns `[cookie session-id t1]`."
  [handler app*]
  (let [cookie     (cookie-of (load-page! handler nil "T1"))
        session-id (session-id-of cookie)]
    (load-page! handler cookie "T2")
    (swap! app* assoc-in [:sessions session-id :data :user] "alice")
    [cookie session-id (connect! handler app* cookie "T1")]))

;; ---------------------------------------------------------------------------
;; rotate-session!
;; ---------------------------------------------------------------------------

(defn- assert-rotated
  "Asserts that `response`, to a route that rotated session `session-id`
   of tabs T1 (stream `t1`) and T2, rotated it."
  [handler app* cookie session-id t1 response]
  (let [new-cookie (cookie-of response)
        new-id     (session-id-of new-cookie)]
    (testing "the route's response is kept"
      (is (= 302 (:status response)))
      (is (= "/" (get-in response [:headers "Location"]))))
    (testing "the response sets the cookie to a new id, with its attributes"
      (is (str/starts-with? new-id "ses_"))
      (is (not= session-id new-id))
      (is (= #{"path=/" "httponly" "max-age=604800" "samesite=lax"}
             (attributes (set-cookie response)))))
    (testing "the session has the new id and its data before the response is written"
      (is (not (contains? (:sessions @app*) session-id)))
      (is (= {:user "alice"} (get-in @app* [:sessions new-id :data])))
      (is (contains? (:tabs @app*) "T2") "while its tabs wait for the response"))
    (written! response)
    (testing "then every tab of the session reloads and is removed"
      (is (ended? t1))
      (is (str/includes? (written t1) "window.location.reload()"))
      (is (empty? (:tabs @app*))))
    (testing "the old id names no session"
      (is (= #{new-id} (set (keys (:sessions @app*))))))
    (testing "a page loaded with the new cookie is of the session, with its data"
      (let [page (load-page! handler new-cookie "B")]
        (is (= new-cookie (cookie-of page)))
        (is (= #{"B"} (get-in @app* [:sessions new-id :tabs])))
        (is (str/includes? (:body page) "user:alice"))))
    (testing "a page loaded with the old cookie gets a new, empty session"
      (let [page (load-page! handler cookie "A")
            id   (session-id-of (cookie-of page))]
        (is (not (contains? #{session-id new-id} id)))
        (is (= {:data {} :tabs #{"A"}} (get-in @app* [:sessions id])))
        (is (str/includes? (:body page) "user:<"))))))

(deftest test-rotate-session-from-a-route
  (let [[handler app*]         (app (atom {}))
        [cookie session-id t1] (signed-in-session handler app*)]
    (assert-rotated handler app* cookie session-id t1
                    (request handler cookie :get "/sign-in"))))

(deftest test-rotate-session-from-a-page-route
  (let [[handler app*]         (app (atom {}))
        [cookie session-id t1] (signed-in-session handler app*)]
    (assert-rotated handler app* cookie session-id t1
                    (request handler cookie :get "/page-sign-in" "tab-id=P"))))

(deftest test-rotate-session-secure-over-https
  (let [[handler app*] (app (atom {}) :session {:cookie {:name "sid" :same-site :strict}})
        cookie         (str "sid=" (second (re-find #"sid=([^;]+)" (first (set-cookies (load-page! handler nil "T") "sid")))))
        response       (handler {:request-method :get
                                 :uri            "/sign-in"
                                 :scheme         :https
                                 :headers        {"cookie" cookie}})]
    (is (= #{"path=/" "httponly" "max-age=604800" "samesite=strict" "secure"}
           (attributes (first (set-cookies response "sid")))))
    (is (not (contains? (:sessions @app*) (session-id-of cookie))))
    (written! response)))

(defn- rotated-from-route
  "Rotate a signed-in session from a route, its response written.  Returns
   `[new-cookie new-id tabless-at]`."
  [handler app*]
  (let [[cookie _ t1] (signed-in-session handler app*)
        response      (written! (request handler cookie :get "/sign-in"))
        new-cookie    (cookie-of response)
        new-id        (session-id-of new-cookie)]
    (is (ended? t1))
    (is (= #{} (get-in @app* [:sessions new-id :tabs])))
    [new-cookie new-id (get-in @app* [:sessions new-id :tabless-at])]))

(deftest test-reap-a-session-rotated-from-a-route
  (testing "a session whose redirect is never followed is removed after the grace period"
    (let [[handler app*]        (app (atom {}))
          [_ new-id tabless-at] (rotated-from-route handler app*)
          grace-ms              (long (:disconnect-grace-ms @app*))]
      (is (int? tabless-at))
      (server/reap-disconnected-tabs! app* (+ tabless-at grace-ms -1))
      (is (contains? (:sessions @app*) new-id) "kept within the grace period")
      (server/reap-disconnected-tabs! app* (+ tabless-at grace-ms))
      (is (not (contains? (:sessions @app*) new-id)) "removed once it has passed")))
  (testing "a session whose page loads in time is kept"
    (let [[handler app*]                 (app (atom {}))
          [new-cookie new-id tabless-at] (rotated-from-route handler app*)
          grace-ms                       (long (:disconnect-grace-ms @app*))]
      (load-page! handler new-cookie "B")
      (is (= #{"B"} (get-in @app* [:sessions new-id :tabs])))
      (is (nil? (get-in @app* [:sessions new-id :tabless-at])) "the page's tab clears the stamp")
      (let [b (connect! handler app* new-cookie "B")]
        (server/reap-disconnected-tabs! app* (+ tabless-at (* 2 grace-ms)))
        (is (= {:user "alice"} (get-in @app* [:sessions new-id :data])))
        (server/cleanup-tab! app* "B")
        (is (ended? b)))))
  (testing "a session a stream follows the cookie into is kept"
    (let [[handler app*]                 (app (atom {}))
          [new-cookie new-id tabless-at] (rotated-from-route handler app*)
          grace-ms                       (long (:disconnect-grace-ms @app*))]
      ;; A page loaded before the browser had the new cookie.
      (load-page! handler nil "X")
      (let [x (connect! handler app* new-cookie "X")]
        (is (= #{"X"} (get-in @app* [:sessions new-id :tabs])))
        (is (nil? (get-in @app* [:sessions new-id :tabless-at])))
        (server/reap-disconnected-tabs! app* (+ tabless-at (* 2 grace-ms)))
        (is (= {:user "alice"} (get-in @app* [:sessions new-id :data])))
        (server/cleanup-tab! app* "X")
        (is (ended? x))))))

;; ---------------------------------------------------------------------------
;; end-session!
;; ---------------------------------------------------------------------------

(deftest test-end-session-from-a-route
  (let [[handler app*]         (app (atom {}))
        [cookie session-id t1] (signed-in-session handler app*)
        response               (written! (request handler cookie :post "/sign-out"))]
    (testing "the route's response is kept"
      (is (= 302 (:status response))))
    (testing "the session cookie is deleted"
      (let [header (set-cookie response)]
        (is (str/starts-with? header "hyper-session=;"))
        (is (contains? (attributes header) "max-age=0"))))
    (testing "the session's tabs reload and are removed"
      (is (ended? t1))
      (is (str/includes? (written t1) "window.location.reload()"))
      (is (empty? (:tabs @app*))))
    (testing "the session is gone"
      (is (not (contains? (:sessions @app*) session-id)))
      (is (empty? (:sessions @app*))))))

;; ---------------------------------------------------------------------------
;; Without a session the server holds
;; ---------------------------------------------------------------------------

(deftest test-no-held-session
  (doseq [[desc cookie] [["no cookie" nil] ["an unknown cookie" "hyper-session=ses_unknown"]]]
    (testing (str "with " desc)
      (let [[handler app*] (app (atom {}))
            other          (cookie-of (load-page! handler nil "O"))
            before         @app*]
        (testing "rotate-session! does nothing"
          (let [response (written! (request handler cookie :get "/sign-in"))]
            (is (= 302 (:status response)))
            (is (empty? (set-cookies response "hyper-session")))))
        (testing "end-session! does nothing"
          (let [response (written! (request handler cookie :post "/sign-out"))]
            (is (= 302 (:status response)))
            (is (empty? (set-cookies response "hyper-session")))))
        (is (= (:sessions before) (:sessions @app*)))
        (is (= #{(session-id-of other)} (set (keys (:sessions @app*)))))
        (is (= #{"O"} (set (keys (:tabs @app*)))))))))

;; ---------------------------------------------------------------------------
;; session-id and endpoint
;; ---------------------------------------------------------------------------

(deftest test-session-id
  (let [[handler _] (app (atom {}))
        cookie      (cookie-of (load-page! handler nil "T"))
        whoami      #(read-string (:body (request handler % :post "/whoami")))]
    (is (= (session-id-of cookie) (whoami cookie)) "the held session's id")
    (is (nil? (whoami "hyper-session=ses_unknown")) "nil for an unknown cookie")
    (is (nil? (whoami nil)) "nil without a cookie")))

(deftest test-endpoint
  (doseq [base-path [nil "/app"]]
    (testing (str "with base path " (pr-str base-path))
      (let [endpoints      (atom {})
            [handler app*] (if base-path
                             (app endpoints :base-path base-path)
                             (app endpoints))
            path           #(str base-path %)]
        (doseq [[method uri] [[:post "/hyper/actions"]
                              [:post "/hyper/upload"]
                              [:post "/hyper/navigate"]
                              [:get "/hyper/events"]
                              [:post "/hyper/cookies"]
                              [:get "/"]
                              [:post "/whoami"]]]
          (request handler nil method (path uri) "tab-id=T"))
        (testing "from the app's middleware"
          (is (= {(path "/hyper/actions")  :action
                  (path "/hyper/upload")   :upload
                  (path "/hyper/navigate") :navigate
                  (path "/hyper/events")   :events
                  (path "/")               nil
                  (path "/whoami")         nil}
                 @endpoints)
              "the cookies request skips the middleware"))
        (testing "for the cookies request"
          (is (nil? (h.ring/endpoint {:uri (path "/hyper/cookies") :hyper/app-state app*}))))
        (doseq [t (keys (:tabs @app*))]
          (server/cleanup-tab! app* t))))))

;; ---------------------------------------------------------------------------
;; Outside create-handler
;; ---------------------------------------------------------------------------

(defn- outside-route
  "A plain Ring handler for /out/sign-in and /out/sign-out."
  [req]
  (case (:uri req)
    "/out/sign-in"  (-> (response/redirect "/") (h.ring/rotate-session! req))
    "/out/sign-out" (-> (response/redirect "/") (h.ring/end-session! req))))

(deftest test-outside-create-handler
  (let [[handler app*] (app (atom {}))
        outside        (-> outside-route (h.ring.middleware/wrap-hyper app*))]
    (testing "rotate-session!"
      (let [[cookie session-id t1] (signed-in-session handler app*)
            response               (outside {:request-method :get :uri "/out/sign-in" :headers {"cookie" cookie}})
            new-id                 (session-id-of (cookie-of response))]
        (is (= 1 (count (set-cookies response "hyper-session"))) "the cookie is a Set-Cookie header")
        (is (nil? (:cookies response)))
        (is (not= session-id new-id))
        (is (= {:user "alice"} (get-in @app* [:sessions new-id :data])))
        (written! response)
        (is (ended? t1))
        (is (empty? (:tabs @app*)))
        (testing "and end-session!"
          (let [response (outside {:request-method :post
                                   :uri            "/out/sign-out"
                                   :headers        {"cookie" (str "hyper-session=" new-id)}})]
            (is (str/starts-with? (set-cookie response) "hyper-session=;"))
            (is (empty? (:sessions @app*)))))))))

(deftest test-without-wrap-hyper
  (doseq [[fn-name f] [["rotate-session!" #(h.ring/rotate-session! {:status 200} %)]
                       ["end-session!" #(h.ring/end-session! {:status 200} %)]
                       ["session-id" h.ring/session-id]
                       ["endpoint" h.ring/endpoint]]]
    (testing fn-name
      (let [e (try (f {:uri "/" :cookies {}}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "throws")
        (is (str/includes? (ex-message e) "(hyper.ring.middleware/wrap-hyper handler app-state*)"))
        (is (str/includes? (ex-message e) "hyper's handler"))))))

;; ---------------------------------------------------------------------------
;; The session cookie
;; ---------------------------------------------------------------------------

(deftest test-a-page-sets-the-session-cookie-once
  (let [[handler _] (app (atom {}))
        page        (load-page! handler nil "T")]
    (is (= 1 (count (set-cookies page "hyper-session"))))
    (is (nil? (:cookies page)))
    (is (= 1 (count (set-cookies (load-page! handler (cookie-of page) "T2") "hyper-session"))))))
