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
  "Load / into tab `tab-id` in session S."
  [handler tab-id]
  (handler {:request-method :get
            :uri            "/"
            :query-string   (str "tab-id=" tab-id)
            :headers        {"cookie" "hyper-session=S"}}))

(defn- connect!
  "Open the event stream of tab `tab-id` in session S and return it once the
   stream is attached."
  [handler app* tab-id]
  (let [response (handler {:request-method :get
                           :uri            "/hyper/events"
                           :query-string   (str "tab-id=" tab-id)
                           :headers        {"cookie" "hyper-session=S"}})
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
  (let [[handler app*] (app)]
    (load-page! handler "T1")
    (load-page! handler "T2")
    (swap! app* assoc-in [:sessions "S" :data :user] "alice")
    (let [stream (connect! handler app* "T2")]
      (reap-after-grace! app*)
      (testing "while another tab remains, the session is kept"
        (is (not (contains? (:tabs @app*) "T1")))
        (is (= #{"T2"} (get-in @app* [:sessions "S" :tabs])))
        (is (= {:user "alice"} (get-in @app* [:sessions "S" :data]))))
      (disconnect! app* "T2" stream))
    (testing "once its last tab is reaped, the session is removed"
      (reap-after-grace! app*)
      (is (empty? (:tabs @app*)))
      (is (not (contains? (:sessions @app*) "S"))))))

(deftest test-a-session-with-a-reconnected-tab-survives
  (let [[handler app*] (app)]
    (load-page! handler "T")
    (swap! app* assoc-in [:sessions "S" :data :user] "alice")
    (disconnect! app* "T" (connect! handler app* "T"))
    (is (number? (get-in @app* [:tabs "T" :disconnected-at])))
    (let [stream (connect! handler app* "T")]
      (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
      (reap-after-grace! app*)
      (is (contains? (:tabs @app*) "T"))
      (is (= {:user "alice"} (get-in @app* [:sessions "S" :data])))
      (server/cleanup-tab! app* "T")
      (is (not= :timeout (deref stream 2000 :timeout))))))

(deftest test-a-tab-whose-stream-never-connects-is-reaped
  (testing "a served page's tab counts as disconnected until its stream connects"
    (let [[handler app*] (app)
          served-at      (System/currentTimeMillis)]
      (load-page! handler "T")
      (is (<= served-at (get-in @app* [:tabs "T" :disconnected-at])))
      (server/reap-disconnected-tabs! app* (+ served-at grace-ms -1000))
      (is (contains? (:tabs @app*) "T") "it is kept within the grace period")
      (reap-after-grace! app*)
      (is (not (contains? (:tabs @app*) "T")) "and reaped after it")
      (is (not (contains? (:sessions @app*) "S")))))
  (testing "a tab whose stream first connects in time is kept"
    (let [[handler app*] (app)]
      (load-page! handler "T")
      (let [stream (connect! handler app* "T")]
        (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
        (reap-after-grace! app*)
        (is (contains? (:tabs @app*) "T"))
        (is (contains? (get-in @app* [:sessions "S" :tabs]) "T"))
        (server/cleanup-tab! app* "T")
        (is (not= :timeout (deref stream 2000 :timeout))))))
  (testing "a page served into a tab with its stream attached does not stamp it"
    (let [[handler app*] (app)]
      (load-page! handler "T")
      (let [stream (connect! handler app* "T")]
        (load-page! handler "T")
        (is (nil? (get-in @app* [:tabs "T" :disconnected-at])))
        (server/cleanup-tab! app* "T")
        (is (not= :timeout (deref stream 2000 :timeout)))))))

(deftest test-a-new-page-load-after-reaping-gets-a-fresh-session
  (let [[handler app*] (app)]
    (load-page! handler "T1")
    (swap! app* assoc-in [:sessions "S" :data :user] "alice")
    (reap-after-grace! app*)
    (is (not (contains? (:sessions @app*) "S")))
    (load-page! handler "T2")
    (is (= {:data {} :tabs #{"T2"}} (get-in @app* [:sessions "S"])))
    (is (= "S" (get-in @app* [:tabs "T2" :session-id])))))

(defn- consistent-session?
  "True when tab `tab-id` of session S has the session, with its data."
  [app* tab-id]
  (let [session (get-in @app* [:sessions "S"])]
    (and (= "S" (get-in @app* [:tabs tab-id :session-id]))
         (map? (:data session))
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
            start          (promise)]
        (load-page! handler "OLD")
        (swap! app* update-in [:tabs "OLD" :disconnected-at] - grace-ms)
        (let [reaper (future @start (server/reap-disconnected-tabs!
                                      app* (System/currentTimeMillis)))
              page   (future @start (load-page! handler new-tab))]
          (deliver start true)
          @reaper
          @page)
        (is (not (contains? (:tabs @app*) "OLD")))
        (is (consistent-session? app* new-tab))))))
