(ns hyper.session-lifetime-test
  "A session lives as long as its tabs: it is removed with its last tab, and
   a tab whose stream never connects is reaped like a disconnected one."
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.server :as server]
            [hyper.state :as state]
            [ring.core.protocols :as ring-protocols]))

(def ^:private grace-ms 180000)

(defn- app
  "A hyper handler with a page at /, and its app-state."
  []
  (let [app* (atom (state/init-state))]
    [(h/create-handler [["/" {:name :home :get (fn [_] [:div "home"])}]]
                       :app-state app*)
     app*]))

(defn- load-page!
  "Load / into tab `tab-id` with a session cookie of `session-id`, or none
   when it is nil; returns the response."
  [handler session-id tab-id]
  (handler (cond-> {:request-method :get
                    :uri            "/"
                    :query-string   (str "tab-id=" tab-id)
                    :headers        {}}
             session-id (assoc-in [:headers "cookie"] (str "hyper-session=" session-id)))))

(defn- session-of
  "The session id whose cookie `response` set."
  [response]
  (some #(second (re-find #"^hyper-session=([^;]+)" %))
        (get-in response [:headers "Set-Cookie"])))

(defn- connect!
  "Open the event stream of tab `tab-id` in its session and return it once the
   stream is attached."
  [handler app* tab-id]
  (let [response (handler {:request-method :get
                           :uri            "/hyper/events"
                           :query-string   (str "tab-id=" tab-id)
                           :headers        {"cookie" (str "hyper-session=" (get-in @app* [:tabs tab-id :session-id]))}})
        stream   (future
                   (ring-protocols/write-body-to-stream
                     (:body response) response (java.io.OutputStream/nullOutputStream)))]
    (loop [i 0]
      (when (and (< i 200) (nil? (get-in @app* [:tabs tab-id :renderer])))
        (Thread/sleep 10)
        (recur (inc i))))
    (is (some? (get-in @app* [:tabs tab-id :renderer])) "the stream is attached")
    stream))

(defn- disconnect!
  "End the event stream `stream` of tab `tab-id`."
  [app* tab-id stream]
  ((get-in @app* [:tabs tab-id :renderer :stop!]))
  (is (not= :timeout (deref stream 2000 :timeout)) "the stream ended"))

(defn- reap-after-grace!
  "Run the reaper as if the grace period has passed since now."
  [app*]
  (server/reap-disconnected-tabs! app* (+ (System/currentTimeMillis) grace-ms)))

(deftest test-a-session-is-removed-with-its-last-tab
  (let [[handler app*] (app)
        session-id     (session-of (load-page! handler nil "T1"))]
    (load-page! handler session-id "T2")
    (swap! app* assoc-in [:sessions session-id :data :user] "alice")
    (let [stream (connect! handler app* "T2")]
      (reap-after-grace! app*)
      (testing "while another tab remains, the session is kept"
        (is (not (contains? (:tabs @app*) "T1")))
        (is (= #{"T2"} (get-in @app* [:sessions session-id :tabs])))
        (is (= {:user "alice"} (get-in @app* [:sessions session-id :data]))))
      (disconnect! app* "T2" stream))
    (testing "once its last tab is reaped, the session is removed"
      (reap-after-grace! app*)
      (is (empty? (:tabs @app*)))
      (is (not (contains? (:sessions @app*) session-id))))))

(deftest test-a-session-with-a-reconnected-tab-survives
  (let [[handler app*] (app)
        session-id     (session-of (load-page! handler nil "T"))]
    (swap! app* assoc-in [:sessions session-id :data :user] "alice")
    (disconnect! app* "T" (connect! handler app* "T"))
    (is (number? (get-in @app* [:tabs "T" :disconnected-at])))
    (let [stream (connect! handler app* "T")]
      (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
      (reap-after-grace! app*)
      (is (contains? (:tabs @app*) "T"))
      (is (= {:user "alice"} (get-in @app* [:sessions session-id :data])))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout))))))

(deftest test-a-tab-whose-stream-never-connects-is-reaped
  (testing "a served page's tab counts as disconnected until its stream connects"
    (let [[handler app*] (app)
          served-at      (System/currentTimeMillis)
          session-id     (session-of (load-page! handler nil "T"))]
      (is (<= served-at (get-in @app* [:tabs "T" :disconnected-at])))
      (server/reap-disconnected-tabs! app* (+ served-at grace-ms -1000))
      (is (contains? (:tabs @app*) "T") "it is kept within the grace period")
      (reap-after-grace! app*)
      (is (not (contains? (:tabs @app*) "T")) "and reaped after it")
      (is (not (contains? (:sessions @app*) session-id)))))
  (testing "a tab whose stream first connects in time is kept"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))
          stream         (connect! handler app* "T")]
      (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
      (reap-after-grace! app*)
      (is (contains? (:tabs @app*) "T"))
      (is (contains? (get-in @app* [:sessions session-id :tabs]) "T"))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout)))))
  (testing "a page served into a tab with its stream attached does not stamp it"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))
          stream         (connect! handler app* "T")]
      (load-page! handler session-id "T")
      (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout))))))

(deftest test-a-new-page-load-after-reaping-gets-a-fresh-session
  (let [[handler app*] (app)
        session-id     (session-of (load-page! handler nil "T1"))]
    (swap! app* assoc-in [:sessions session-id :data :user] "alice")
    (reap-after-grace! app*)
    (is (not (contains? (:sessions @app*) session-id)))
    (let [new-id (session-of (load-page! handler session-id "T2"))]
      (is (not= session-id new-id) "under a new id")
      (is (not (contains? (:sessions @app*) session-id)))
      (is (= {:data {} :tabs #{"T2"}} (get-in @app* [:sessions new-id])))
      (is (= new-id (get-in @app* [:tabs "T2" :session-id]))))))

(defn- consistent-session?
  "True when tab `tab-id` has its session, with its data."
  [app* tab-id]
  (let [session (get-in @app* [:sessions (get-in @app* [:tabs tab-id :session-id])])]
    (and (map? (:data session))
         (contains? (:tabs session) tab-id))))

(deftest test-a-tab-created-around-the-session-removal-has-its-session
  (testing "a new tab created before the last old tab is removed keeps the session"
    (let [app* (atom (state/init-state))]
      (state/get-or-create-tab! app* "S" "OLD")
      (swap! app* assoc-in [:sessions "S" :data :user] "alice")
      (state/get-or-create-tab! app* "S" "NEW")
      (server/cleanup-tab! app* "OLD")
      (is (consistent-session? app* "NEW"))
      (is (= {:user "alice"} (get-in @app* [:sessions "S" :data])))))
  (testing "a new tab created after the session is removed gets a fresh one"
    (let [app* (atom (state/init-state))]
      (state/get-or-create-tab! app* "S" "OLD")
      (swap! app* assoc-in [:sessions "S" :data :user] "alice")
      (server/cleanup-tab! app* "OLD")
      (state/get-or-create-tab! app* "S" "NEW")
      (is (consistent-session? app* "NEW"))
      (is (= {} (get-in @app* [:sessions "S" :data])))))
  (testing "the last old tab removed as soon as a new tab's creation changes state"
    (let [app* (atom (state/init-state))]
      (state/get-or-create-tab! app* "S" "OLD")
      (swap! app* assoc-in [:sessions "S" :data :user] "alice")
      (add-watch app* ::remove-old (fn [k r _ _]
                                     (remove-watch r k)
                                     (server/cleanup-tab! r "OLD")))
      (state/get-or-create-tab! app* "S" "NEW")
      (is (not (contains? (:tabs @app*) "OLD")))
      (is (consistent-session? app* "NEW"))
      (is (= {:user "alice"} (get-in @app* [:sessions "S" :data])))))
  (testing "a page load racing the reaper always ends up with its session"
    (dotimes [i 200]
      (let [[handler app*] (app)
            new-tab        (str "NEW" i)
            start          (promise)
            session-id     (session-of (load-page! handler nil "OLD"))]
        (swap! app* update-in [:tabs "OLD" :disconnected-at] - grace-ms)
        (let [reaper (future @start (server/reap-disconnected-tabs!
                                      app* (System/currentTimeMillis)))
              page   (future @start (load-page! handler session-id new-tab))]
          (deliver start true)
          @reaper
          @page)
        (is (not (contains? (:tabs @app*) "OLD")))
        (is (consistent-session? app* new-tab))))))

(defn- app-with-ring-routes
  "A hyper handler with a page at /, a route at /api returning a Ring map, and
   a page at /secret whose render middleware redirects to /; and its
   app-state."
  []
  (let [app*     (atom (state/init-state))
        redirect (fn [handler]
                   (fn [req]
                     (if (= :secret (get-in req [:hyper/route :name]))
                       {:status 302 :headers {"Location" "/"} :body ""}
                       (handler req))))]
    [(h/create-handler [["/" {:name :home :get (fn [_] [:div "home"])}]
                        ["/api" {:get (fn [_] {:status 200 :body "{}"})}]
                        ["/secret" {:name :secret :get (fn [_] [:div "secret"])}]]
                       :app-state app*
                       :render-middleware [redirect]
                       :not-found (fn [_] {:status 404 :body "gone"}))
     app*]))

(defn- get!
  "GET `uri` from `handler`, with a session cookie of `session-id` when given."
  ([handler uri] (get! handler uri nil))
  ([handler uri session-id]
   (handler (cond-> {:request-method :get :uri uri :headers {}}
              session-id (assoc-in [:headers "cookie"] (str "hyper-session=" session-id))))))

(deftest test-a-ring-response-leaves-no-tab
  (testing "without a session cookie, no tab or session is left"
    (let [[handler app*] (app-with-ring-routes)]
      (is (= 200 (:status (get! handler "/api"))))
      (is (= 302 (:status (get! handler "/secret"))))
      (is (= 404 (:status (get! handler "/nowhere"))))
      (is (empty? (:tabs @app*)))
      (is (empty? (:sessions @app*)))))
  (testing "with a session cookie, the session and its other tabs are untouched"
    (let [[handler app*] (app-with-ring-routes)
          session-id     (session-of (load-page! handler nil "T"))]
      (swap! app* assoc-in [:sessions session-id :data :user] "alice")
      (let [before @app*]
        (is (= 200 (:status (get! handler "/api" session-id))))
        (is (= 302 (:status (get! handler "/secret" session-id))))
        (is (= 404 (:status (get! handler "/nowhere" session-id))))
        (is (= (:sessions before) (:sessions @app*)))
        (is (= #{"T"} (set (keys (:tabs @app*))))))))
  (testing "a page still creates its tab"
    (let [[handler app*] (app-with-ring-routes)]
      (is (= 200 (:status (get! handler "/"))))
      (is (= 1 (count (:tabs @app*))))
      (is (= 1 (count (:sessions @app*))))))
  (testing "a tab that existed before the request is kept"
    (let [[handler app*] (app-with-ring-routes)
          session-id     (session-of (load-page! handler nil "T"))
          stream         (connect! handler app* "T")]
      (is (= 302 (:status (handler {:request-method :get
                                    :uri            "/secret"
                                    :query-string   "tab-id=T"
                                    :headers        {"cookie" (str "hyper-session=" session-id)}}))))
      (is (some? (get-in @app* [:tabs "T" :renderer])))
      (is (= #{"T"} (get-in @app* [:sessions session-id :tabs])))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout))))))

(defn- open-stream!
  "Open the event stream of tab `tab-id` with a session cookie of
   `session-id`; returns the response and a future of its body's write."
  [handler tab-id session-id]
  (let [response (handler {:request-method :get
                           :uri            "/hyper/events"
                           :query-string   (str "tab-id=" tab-id)
                           :headers        {"cookie" (str "hyper-session=" session-id)}})]
    [response (future
                (ring-protocols/write-body-to-stream
                  (:body response) response (java.io.OutputStream/nullOutputStream)))]))

(defn- reap-meeting-stream!
  "Run the reaper at `now`, opening the event stream of tab `tab-id` with a
   session cookie of `session-id` as the reaper starts tearing the tab down.
   Returns the stream's `:status`, and whether it `:ended?` before the
   teardown went on."
  [handler app* now tab-id session-id]
  (let [cleanup-tab! server/cleanup-tab!
        stream*      (atom nil)]
    (with-redefs [server/cleanup-tab! (fn [app* t]
                                        (when (= tab-id t)
                                          (let [[response body] (open-stream! handler tab-id session-id)]
                                            (reset! stream* {:status (:status response)
                                                             :ended? (not= :timeout (deref body 2000 :timeout))})))
                                        (cleanup-tab! app* t))]
      (server/reap-disconnected-tabs! app* now))
    @stream*))

(deftest test-a-tab-whose-stream-attaches-as-the-reaper-runs-is-kept
  (testing "a reconnected tab whose stamp is not yet cleared"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))]
      (disconnect! app* "T" (connect! handler app* "T"))
      (swap! app* assoc-in [:sessions session-id :data :user] "alice")
      (swap! app* assoc-in [:tabs "T" :data :draft] "hello")
      (let [stamp    (get-in @app* [:tabs "T" :disconnected-at])
            stream   (connect! handler app* "T")
            renderer (get-in @app* [:tabs "T" :renderer])]
        (swap! app* assoc-in [:tabs "T" :disconnected-at] stamp)
        (server/reap-disconnected-tabs! app* (+ stamp grace-ms))
        (is (identical? renderer (get-in @app* [:tabs "T" :renderer])))
        (is (= {:draft "hello"} (get-in @app* [:tabs "T" :data])))
        (is (= #{"T"} (get-in @app* [:sessions session-id :tabs])))
        (is (= {:user "alice"} (get-in @app* [:sessions session-id :data])))
        (is (= :timeout (deref stream 0 :timeout)) "its stream runs on")
        (server/cleanup-tab! app* "T")
        (is (not= :timeout (deref stream 2000 :timeout))))))
  (testing "a page whose stream first connects about a grace period after it was served"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))
          served-at      (get-in @app* [:tabs "T" :disconnected-at])
          stream         (connect! handler app* "T")
          renderer       (get-in @app* [:tabs "T" :renderer])]
      (swap! app* assoc-in [:tabs "T" :disconnected-at] served-at)
      (server/reap-disconnected-tabs! app* (+ served-at grace-ms))
      (is (identical? renderer (get-in @app* [:tabs "T" :renderer])))
      (is (= #{"T"} (get-in @app* [:sessions session-id :tabs])))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout))))))

(deftest test-a-tab-still-disconnected-after-the-grace-period-is-torn-down
  (let [[handler app*] (app)
        session-id     (session-of (load-page! handler nil "T"))]
    (disconnect! app* "T" (connect! handler app* "T"))
    (is (contains? (.getWatches app*) :render-T))
    (server/reap-disconnected-tabs! app* (+ (get-in @app* [:tabs "T" :disconnected-at]) grace-ms))
    (is (not (contains? (:tabs @app*) "T")))
    (is (not (contains? (.getWatches app*) :render-T)) "with its watchers")
    (is (not (contains? (:actions-by-tab @app*) "T")) "and actions")
    (is (not (contains? (:sessions @app*) session-id)) "and its session, as its last tab")))

(deftest test-a-stream-attaching-to-a-tab-being-reaped-ends
  (testing "a tab that was connected before"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))]
      (disconnect! app* "T" (connect! handler app* "T"))
      (is (= {:status 200 :ended? true}
             (reap-meeting-stream! handler app* (+ (get-in @app* [:tabs "T" :disconnected-at]) grace-ms)
                                   "T" session-id)))
      (is (empty? (:tabs @app*)))
      (is (empty? (:sessions @app*)) "the session is not brought back")))
  (testing "a page whose stream never connected"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))]
      (is (= {:status 200 :ended? true}
             (reap-meeting-stream! handler app* (+ (get-in @app* [:tabs "T" :disconnected-at]) grace-ms)
                                   "T" session-id)))
      (is (empty? (:tabs @app*)))
      (is (empty? (:sessions @app*)))))
  (testing "a page whose stream never connected, with another session's cookie"
    (let [[handler app*] (app)
          session-id     (session-of (load-page! handler nil "T"))
          served-at      (get-in @app* [:tabs "T" :disconnected-at])
          other-id       (session-of (load-page! handler nil "U"))]
      (swap! app* assoc-in [:tabs "U" :disconnected-at] (+ served-at grace-ms))
      (is (= {:status 404 :ended? true}
             (reap-meeting-stream! handler app* (+ served-at grace-ms) "T" other-id)))
      (is (= #{"U"} (set (keys (:tabs @app*)))))
      (is (= #{"U"} (get-in @app* [:sessions other-id :tabs])) "the tab does not move to it")
      (is (not (contains? (:sessions @app*) session-id))))))
