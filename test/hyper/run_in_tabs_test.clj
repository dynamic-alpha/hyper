(ns hyper.run-in-tabs-test
  "`h/run-in-tabs!`: running code in open tabs from outside their requests."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.test :as ht]
            [ring.core.protocols :as ring-protocols]
            [taoensso.telemere :as t]))

(def ^:private admin-action
  "The body of the action of the page at /, run with no arguments."
  (atom (fn [])))

(defn- app
  "A hyper handler with a page at / showing the session's :user and the
   tab's :draft, with an action running `@admin-action`, and a page at
   /other; with `opts`.  And its app-state."
  [& opts]
  (let [app* (atom (state/init-state))]
    [(apply h/create-handler
            [["/" {:name :home
                   :get  (fn [_]
                           [:div
                            [:p#user (str "user:" @(h/session-cursor :user))]
                            [:p#draft (str "draft:" @(h/tab-cursor :draft))]
                            [:button {:data-on:click (h/action {:as "admin"} (@admin-action))} "go"]])}]
             ["/other" {:name :other :get (fn [_] [:div "other"])}]]
            :app-state app*
            opts)
     app*]))

(defn- request
  [handler cookie req]
  (handler (cond-> req
             cookie (assoc-in [:headers "cookie"] cookie))))

(defn- load-page!
  "Load / into tab `tab-id` with cookie `cookie`; returns the response."
  [handler cookie tab-id]
  (request handler cookie {:request-method :get :uri "/" :query-string (str "tab-id=" tab-id)}))

(defn- written!
  "`response`, once its body has been written, as the server does."
  [response]
  (ring-protocols/write-body-to-stream (:body response) response (java.io.ByteArrayOutputStream.))
  response)

(defn- post-action!
  "Run the action of the page `html` of tab `tab-id`."
  [handler cookie tab-id html]
  (let [id (second (re-find #"hyperAction\(&apos;(a_[^&]+)&apos;" html))]
    (written!
      (request handler cookie
               {:request-method :post
                :uri            "/hyper/actions"
                :query-string   (str "tab-id=" tab-id)
                :headers        {"content-type" "application/json"}
                :body           (json/generate-string
                                  {:base    nil
                                   :actions [{:id id :seq 1 :v nil :signals "{}" :params nil}]})}))))

(defn- connect!
  "Open the event stream of tab `tab-id` and return `{:done stream :out bytes}`
   once it is attached."
  [handler app* cookie tab-id]
  (let [response (request handler cookie {:request-method :get
                                          :uri            "/hyper/events"
                                          :query-string   (str "tab-id=" tab-id)})
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

(defn- wait-for-written
  "True once the stream `conn` has written `s`, within 2 seconds."
  [conn s]
  (loop [i 0]
    (cond
      (str/includes? (written conn) s) true
      (< i 200)                        (do (Thread/sleep 10) (recur (inc i)))
      :else                            false)))

(defn- ended? [{:keys [done]}]
  (not= :timeout (deref done 2000 :timeout)))

(defn- set-cookie
  "The `Set-Cookie` header of `response` for cookie `cookie-name`."
  [response cookie-name]
  (let [header (get-in response [:headers "Set-Cookie"])]
    (some #(when (str/starts-with? % (str cookie-name "=")) %)
          (if (string? header) [header] header))))

(defn- cookie-of
  ([response] (cookie-of response "hyper-session"))
  ([response cookie-name]
   (some-> (set-cookie response cookie-name) (str/split #";") first)))

(defn- session-id-of [cookie]
  (second (str/split cookie #"=" 2)))

(defn- attributes [header]
  (set (map (comp str/lower-case str/trim) (rest (str/split header #";")))))

(defn- token-of
  "The cookie token in script or stream output `s`."
  [s]
  (second (re-find #"/hyper/cookies\?token=([0-9a-f]+)" s)))

(defn- post-token!
  "Collect the cookies kept under `token`, with cookie `cookie`."
  [handler cookie token]
  (written! (request handler cookie {:request-method :post
                                     :uri            "/hyper/cookies"
                                     :query-string   (str "token=" token)})))

(defn- signal-ids [signals]
  (map :id signals))

;; ---------------------------------------------------------------------------
;; Selecting tabs and running code in them
;; ---------------------------------------------------------------------------

(defn- test-app
  "The app-state of `test-page` renders of tabs `tabs`, {tab-id [session-id
   tab-data session-data]}."
  [tabs]
  (let [app* (atom (state/init-state))]
    (doseq [[tab-id [session-id tab-data session-data]] tabs]
      (ht/test-page (fn [_] [:div]) {:app-state  app*
                                     :tab-id     tab-id
                                     :session-id session-id
                                     :cursors    {:tab tab-data :session session-data}}))
    app*))

(deftest test-run-in-every-tab
  (let [app* (test-app {"A1" ["A" {} {}] "A2" ["A" {} {}] "B1" ["B" {} {}]})]
    (is (nil? (h/run-in-tabs! app* #(swap! (h/session-cursor :n 0) inc))))
    (is (= 2 (get-in @app* [:sessions "A" :data :n])))
    (is (= 1 (get-in @app* [:sessions "B" :data :n])))))

(deftest test-run-in-the-tabs-pred-selects
  (let [app* (test-app {"A1" ["A" {:role :editor} {:user "alice"}]
                        "A2" ["A" {:role :viewer} {:user "alice"}]
                        "B1" ["B" {:role :editor} {:user "bob"}]})
        ran  (atom #{})]
    (h/run-in-tabs! app*
                    #(and (= "alice" @(h/session-cursor :user))
                          (= :editor @(h/tab-cursor :role)))
                    (fn []
                      (swap! ran conj [(:hyper/tab-id context/*request*) @(h/session-cursor :user)])
                      (reset! (h/tab-cursor :notice) "hi")
                      (swap! (h/session-cursor :seen 0) inc)))
    (testing "f runs in the selected tabs, with their context"
      (is (= #{["A1" "alice"]} @ran)))
    (testing "its cursor writes land in that tab and its session"
      (is (= "hi" (get-in @app* [:tabs "A1" :data :notice])))
      (is (nil? (get-in @app* [:tabs "A2" :data :notice])))
      (is (= 1 (get-in @app* [:sessions "A" :data :seen])))
      (is (nil? (get-in @app* [:sessions "B" :data :seen]))))))

(deftest test-pred-writes-nothing
  (testing "at :render-guard :warn a default value or write is reported, and the tab is selected"
    (let [app*              (test-app {"A1" ["A" {} {}]})
          ran               (atom 0)
          {:keys [signals]} (t/with-signals
                              (h/run-in-tabs! app*
                                              #(do (h/tab-cursor :x 0)
                                                   (h/session-cursor :y 0)
                                                   (reset! (h/tab-cursor :z) 1)
                                                   true)
                                              #(swap! ran inc)))]
      (is (= 1 @ran))
      (is (= 3 (count (filter #{:hyper.warn/effect-in-render} (signal-ids signals)))))
      (is (= {} (get-in @app* [:tabs "A1" :data])))
      (is (= {} (get-in @app* [:sessions "A" :data])))))
  (testing "at :render-guard :error the tab is not selected, and the error is logged"
    (let [app*              (doto (test-app {"A1" ["A" {} {}]})
                              (swap! assoc :render-guard :error))
          ran               (atom 0)
          {:keys [signals]} (t/with-signals
                              (h/run-in-tabs! app* #(do (h/tab-cursor :x 0) true) #(swap! ran inc)))]
      (is (= 0 @ran))
      (is (some #{:hyper.error/run-in-tabs-pred} (signal-ids signals)))
      (is (= {} (get-in @app* [:tabs "A1" :data])))))
  (testing "a value already there is no effect"
    (let [app*              (test-app {"A1" ["A" {:x 1} {}]})
          {:keys [signals]} (t/with-signals
                              (h/run-in-tabs! app* #(= 1 @(h/tab-cursor :x 0)) (fn [])))]
      (is (not-any? #{:hyper.warn/effect-in-render} (signal-ids signals))))))

(deftest test-an-exception-in-one-tab-does-not-stop-the-others
  (let [app*              (test-app {"A1" ["A" {:n 1} {}] "A2" ["A" {:n 2} {}] "A3" ["A" {:n 3} {}]})
        {:keys [signals]} (t/with-signals
                            (h/run-in-tabs! app*
                                            #(if (= 1 @(h/tab-cursor :n)) (throw (ex-info "pred" {})) true)
                                            (fn []
                                              (reset! (h/tab-cursor :ran) true)
                                              (when (= 2 @(h/tab-cursor :n))
                                                (throw (ex-info "boom" {}))))))]
    (is (nil? (get-in @app* [:tabs "A1" :data :ran])) "a throwing pred counts as false")
    (is (true? (get-in @app* [:tabs "A2" :data :ran])))
    (is (true? (get-in @app* [:tabs "A3" :data :ran])))
    (is (= 1 (count (filter #{:hyper.error/run-in-tabs-pred} (signal-ids signals)))))
    (is (= 1 (count (filter #{:hyper.error/run-in-tabs} (signal-ids signals)))))))

(deftest test-run-from-a-background-thread
  (let [app* (test-app {"A1" ["A" {} {}]})]
    @(future (h/run-in-tabs! app* #(reset! (h/tab-cursor :bg) true)))
    (is (true? (get-in @app* [:tabs "A1" :data :bg])))))

(deftest test-tabs-without-their-session-are-skipped
  (let [app* (test-app {"A1" ["A" {} {}]})
        ran  (atom 0)]
    (swap! app* assoc-in [:tabs "X" :session-id] "gone")
    (h/run-in-tabs! app* #(swap! ran inc))
    (is (= 1 @ran))))

;; ---------------------------------------------------------------------------
;; Scripts
;; ---------------------------------------------------------------------------

(deftest test-scripts-reach-a-connected-tab
  (let [[handler app*] (app)
        cookie         (cookie-of (load-page! handler nil "T"))
        conn           (connect! handler app* cookie "T")]
    (h/run-in-tabs! app* #(effects/execute-script! "hello()"))
    (is (wait-for-written conn "hello()"))
    (is (nil? (get-in @app* [:tabs "T" :queued-scripts])))
    (server/cleanup-tab! app* "T")
    (is (ended? conn))))

(deftest test-scripts-for-a-tab-without-a-stream
  (let [[handler app*] (app)
        cookie         (cookie-of (load-page! handler nil "T"))]
    (testing "a page whose stream has not connected gets them when it connects"
      (h/run-in-tabs! app* #(effects/execute-script! "first()"))
      (is (= ["first()"] (get-in @app* [:tabs "T" :queued-scripts])))
      (let [conn (connect! handler app* cookie "T")]
        (is (wait-for-written conn "first()"))
        (is (nil? (get-in @app* [:tabs "T" :queued-scripts])))
        (server/detach-tab! app* "T")
        (is (ended? conn))))
    (testing "a tab whose stream dropped gets them, in order, when it reconnects"
      (h/run-in-tabs! app* #(effects/execute-script! "second()"))
      (h/run-in-tabs! app* #(effects/execute-script! "third()"))
      (is (= ["second()" "third()"] (get-in @app* [:tabs "T" :queued-scripts])))
      (let [conn (connect! handler app* cookie "T")]
        (is (wait-for-written conn "third()"))
        (let [out (written conn)]
          (is (< (str/index-of out "second()") (str/index-of out "third()")))
          (is (not (str/includes? out "first()")) "and not those sent before"))
        (server/detach-tab! app* "T")
        (is (ended? conn))))
    (testing "they are dropped with the tab when it is reaped"
      (h/run-in-tabs! app* #(effects/execute-script! "never()"))
      (is (= ["never()"] (get-in @app* [:tabs "T" :queued-scripts])))
      (server/reap-disconnected-tabs! app* (+ (System/currentTimeMillis) (* 60 60 1000)))
      (is (empty? (:tabs @app*))))))

;; ---------------------------------------------------------------------------
;; Session effects
;; ---------------------------------------------------------------------------

(deftest test-end-session-in-the-tabs-of-a-session
  (let [[handler app*] (app)
        cookie-a       (cookie-of (load-page! handler nil "A1"))
        id-a           (session-id-of cookie-a)
        _              (load-page! handler cookie-a "A2")
        cookie-b       (cookie-of (load-page! handler nil "B1"))
        id-b           (session-id-of cookie-b)
        _              (swap! app* assoc-in [:sessions id-a :data :user] "alice")
        _              (swap! app* assoc-in [:sessions id-b :data :user] "bob")
        a1             (connect! handler app* cookie-a "A1")
        a2             (connect! handler app* cookie-a "A2")
        b1             (connect! handler app* cookie-b "B1")
        ran            (atom [])]
    (h/run-in-tabs! app* #(= "alice" @(h/session-cursor :user))
                    (fn []
                      (swap! ran conj (:hyper/tab-id context/*request*))
                      (effects/end-session!)))
    (testing "the session ends once: its other tab is gone before its turn"
      (is (= 1 (count @ran)))
      (is (not (contains? (:sessions @app*) id-a)))
      (is (= #{"B1"} (set (keys (:tabs @app*))))))
    (testing "the tab f ran in deletes the session cookie, then reconnects"
      (is (ended? (if (= ["A1"] @ran) a1 a2)))
      (let [out    (written (if (= ["A1"] @ran) a1 a2))
            header (set-cookie (post-token! handler cookie-a (token-of out)) "hyper-session")]
        (is (str/includes? out "window.hyper.reconnect()"))
        (is (not (str/includes? out "window.location.reload()")))
        (is (< (str/index-of out "hyper/cookies") (str/index-of out "window.hyper.reconnect()")))
        (is (str/starts-with? header "hyper-session=;"))
        (is (contains? (attributes header) "max-age=0"))))
    (testing "the other tab reconnects"
      (is (ended? (if (= ["A1"] @ran) a2 a1)))
      (is (str/includes? (written (if (= ["A1"] @ran) a2 a1)) "window.hyper.reconnect()")))
    (testing "their pages reload when they reconnect"
      (doseq [t ["A1" "A2"]]
        (is (str/includes? (str (:body (request handler nil {:request-method :get
                                                             :uri            "/hyper/events"
                                                             :query-string   (str "tab-id=" t)})))
                           "window.location.reload()"))))
    (testing "the other session is untouched"
      (is (= {:data {:user "bob"} :tabs #{"B1"}} (get-in @app* [:sessions id-b])))
      (is (not (str/includes? (written b1) "window.hyper"))))
    (testing "ending it again does nothing"
      (h/run-in-tabs! app* effects/end-session!)
      (is (empty? (:sessions @app*))))
    (is (ended? b1))))

(deftest test-rotate-session-in-a-tab-throws
  (let [[handler app*]    (app)
        cookie            (cookie-of (load-page! handler nil "T1"))
        old-id            (session-id-of cookie)
        _                 (load-page! handler cookie "T2")
        _                 (swap! app* assoc-in [:sessions old-id :data :user] "alice")
        t1                (connect! handler app* cookie "T1")
        t2                (connect! handler app* cookie "T2")
        ran               (atom [])
        caught            (atom [])
        {:keys [signals]} (t/with-signals
                            (h/run-in-tabs! app* (fn []
                                                   (swap! ran conj (:hyper/tab-id context/*request*))
                                                   (try
                                                     (effects/rotate-session!)
                                                     (catch clojure.lang.ExceptionInfo e
                                                       (swap! caught conj e)
                                                       (throw e)))
                                                   (reset! (h/tab-cursor :after) true))))]
    (testing "the call site sees it, in each tab"
      (is (= #{"T1" "T2"} (set @ran)))
      (is (= 2 (count @caught)))
      (let [e (first @caught)]
        (is (str/includes? (ex-message e) "run-in-tabs!"))
        (is (str/includes? (ex-message e) "hyper.ring/rotate-session!"))
        (is (str/includes? (ex-message e) "end-session!"))
        (is (= (first @ran) (:hyper/tab-id (ex-data e))))))
    (testing "the error is logged, with its message, for each tab"
      (let [logged (filter #(= :hyper.error/run-in-tabs (:id %)) signals)]
        (is (= 2 (count logged)))
        (is (every? #(str/includes? (ex-message (:error %)) "rotate-session! can't be called in run-in-tabs!")
                    logged))))
    (testing "the session is not rotated, and nothing after the throw ran"
      (is (= {:data {:user "alice"} :tabs #{"T1" "T2"}} (get-in @app* [:sessions old-id])))
      (is (= 1 (count (:sessions @app*))))
      (is (every? #(= old-id (get-in @app* [:tabs % :session-id])) ["T1" "T2"]))
      (is (empty? (:cookie-tokens @app*)))
      (is (nil? (get-in @app* [:tabs "T1" :data :after]))))
    (testing "the tabs' streams stay open"
      (is (not (ended? t1)))
      (is (not (str/includes? (written t1) "window.hyper"))))
    (server/cleanup-tab! app* "T1")
    (server/cleanup-tab! app* "T2")
    (is (ended? t1))
    (is (ended? t2))))

(deftest test-rotate-session-does-not-stop-the-other-tabs
  (let [app*              (test-app {"A1" ["A" {:n 1} {}] "A2" ["A" {:n 2} {}] "B1" ["B" {:n 3} {}]})
        {:keys [signals]} (t/with-signals
                            (h/run-in-tabs! app* (fn []
                                                   (if (= 1 @(h/tab-cursor :n))
                                                     (effects/rotate-session!)
                                                     (reset! (h/tab-cursor :ran) true)))))]
    (is (= 1 (count (filter #{:hyper.error/run-in-tabs} (signal-ids signals)))))
    (is (true? (get-in @app* [:tabs "A2" :data :ran])))
    (is (true? (get-in @app* [:tabs "B1" :data :ran])))
    (is (= #{"A" "B"} (set (keys (:sessions @app*)))))))

;; ---------------------------------------------------------------------------
;; Cookies
;; ---------------------------------------------------------------------------

(deftest test-cookies
  (let [[handler app*] (app :middleware [(fn [_] (fn [_] {:status 302 :headers {"Location" "/sign-in"} :body ""}))])
        _              (state/get-or-create-tab! app* "S" "T")
        run-once!      (fn []
                         (h/run-in-tabs! app* (fn []
                                                (effects/set-cookie! "remember" "yes" {:http-only true :max-age 60})
                                                (effects/delete-cookie! "old")
                                                (effects/execute-script! "after()")))
                         (let [js (peek (get-in @app* [:tabs "T" :queued-scripts]))]
                           (swap! app* update-in [:tabs "T"] dissoc :queued-scripts)
                           js))]
    (testing "a cookie set in a tab yields a script that collects it, then runs the others"
      (let [js    (run-once!)
            token (token-of js)]
        (is (some? token))
        (is (str/starts-with? js "fetch('/hyper/cookies?token="))
        (is (str/includes? js "[\"after()\"]"))
        (testing "collecting it sets the cookies, past the app's middleware"
          (let [response (post-token! handler nil token)]
            (is (= 204 (:status response)))
            (is (= #{"path=/" "httponly" "max-age=60"} (attributes (set-cookie response "remember"))))
            (is (str/starts-with? (set-cookie response "remember") "remember=yes;"))
            (is (str/starts-with? (set-cookie response "old") "old=;"))
            (is (nil? (set-cookie response "hyper-session")) "and not the session cookie")))
        (testing "a token works once"
          (is (= 404 (:status (post-token! handler nil token)))))))
    (testing "an expired token fails"
      (let [token (token-of (run-once!))]
        (swap! app* assoc-in [:cookie-tokens token :expires-at] (dec (System/currentTimeMillis)))
        (is (= 404 (:status (post-token! handler nil token))))
        (is (not (contains? (:cookie-tokens @app*) token)))))
    (testing "expired tokens are removed"
      (let [token (token-of (run-once!))]
        (server/expire-cookie-tokens! app* (System/currentTimeMillis))
        (is (contains? (:cookie-tokens @app*) token) "not before they expire")
        (server/expire-cookie-tokens! app* (+ (System/currentTimeMillis) (* 60 60 1000)))
        (is (empty? (:cookie-tokens @app*)))))
    (testing "an unknown token fails"
      (is (= 404 (:status (post-token! handler nil "nope"))))
      (is (= 404 (:status (request handler nil {:request-method :post :uri "/hyper/cookies"})))))))

(deftest test-cookie-script-under-a-base-path
  (let [[_ app*] (app :base-path "/app")]
    (state/get-or-create-tab! app* "S" "T")
    (h/run-in-tabs! app* #(effects/set-cookie! "a" "b"))
    (is (str/starts-with? (first (get-in @app* [:tabs "T" :queued-scripts]))
                          "fetch('/app/hyper/cookies?token="))))

;; ---------------------------------------------------------------------------
;; From an action
;; ---------------------------------------------------------------------------

(deftest test-run-from-an-action
  (let [[handler app*] (app)
        seen           (atom nil)
        page           (load-page! handler nil "ADMIN")
        admin-cookie   (cookie-of page)
        _              (swap! app* assoc-in [:sessions (session-id-of admin-cookie) :data :role] :admin)
        other-cookie   (cookie-of (load-page! handler nil "O"))]
    (reset! admin-action
            (fn []
              (effects/set-cookie! "admin" "1")
              (effects/execute-script! "adminBefore()")
              (h/run-in-tabs! (:hyper/app-state context/*request*)
                              #(not= :admin @(h/session-cursor :role))
                              (fn []
                                (reset! (h/tab-cursor :notice) "revoked")
                                (effects/set-cookie! "victim" "1")
                                (effects/execute-script! "victim()")))
              (reset! seen {:tab-id      (:hyper/tab-id context/*request*)
                            :action-name (h/action-name)})
              (reset! (h/tab-cursor :after) true)
              (effects/execute-script! "adminAfter()")))
    (let [response (post-action! handler admin-cookie "ADMIN" (:body page))]
      (testing "the other tab got f's effects"
        (is (= "revoked" (get-in @app* [:tabs "O" :data :notice])))
        (is (token-of (first (get-in @app* [:tabs "O" :queued-scripts])))))
      (testing "the action's context and effects are its own"
        (is (= {:tab-id "ADMIN" :action-name "admin"} @seen))
        (is (true? (get-in @app* [:tabs "ADMIN" :data :after])))
        (is (nil? (get-in @app* [:tabs "ADMIN" :data :notice])))
        (is (= "admin=1" (cookie-of response "admin")))
        (is (nil? (set-cookie response "victim")))
        (is (= ["adminBefore()" "adminAfter()"] (get-in @app* [:tabs "ADMIN" :queued-scripts]))
            "and its scripts are kept for its tab, which has no stream")))
    (is (some? other-cookie))))
