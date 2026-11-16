(ns hyper.session-effects-test
  "`effects/end-session!` and `effects/rotate-session!` run from actions."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.watch :as watch]
            [ring.core.protocols :as ring-protocols]))

(defn- app
  "A hyper handler with a page at / showing the session's :user, with an
   action running `(action-body user*)` for the session cursor `user*` the
   page rendered, and an upload; a page at /other; with `opts`.  And its
   app-state."
  [action-body & opts]
  (let [app* (atom (state/init-state))]
    [(apply h/create-handler
            [["/" {:name :home
                   :get  (fn [_]
                           (let [user* (h/session-cursor :user)]
                             [:div
                              [:p#user (str "user:" @user*)]
                              [:button {:data-on:click (h/action (action-body user*))} "go"]
                              [:form {:data-on:submit__prevent (h/action (let [filename (:filename (:avatar $form))]
                                                                           (reset! (h/tab-cursor :uploaded) filename)
                                                                           (case filename
                                                                             "end.txt"    (effects/end-session!)
                                                                             "rotate.txt" (effects/rotate-session!)
                                                                             nil)))}
                               [:input {:type "file" :name "avatar"}]]]))}]
             ["/other" {:name :other :get (fn [_] [:div "other"])}]]
            :app-state app*
            opts)
     app*]))

(defn- request
  "Send `req` through `handler` with cookie `cookie` (\"name=value\"), or
   none when it is nil."
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
  "Run the action of the page `html` of tab `tab-id`, as sequence number `n`."
  ([handler cookie tab-id html] (post-action! handler cookie tab-id html 1))
  ([handler cookie tab-id html n]
   (let [id (second (re-find #"hyperAction\(&apos;(a_[^&]+)&apos;" html))]
     (written!
       (request handler cookie
                {:request-method :post
                 :uri            "/hyper/actions"
                 :query-string   (str "tab-id=" tab-id)
                 :headers        {"content-type" "application/json"}
                 :body           (json/generate-string
                                   {:base    nil
                                    :actions [{:id id :seq n :v nil :signals "{}" :params nil}]})})))))

(defn- post-upload!
  "Upload a file named `filename` (default a.txt) with the upload of the page
   `html` of tab `tab-id`."
  ([handler cookie tab-id html] (post-upload! handler cookie tab-id html "a.txt"))
  ([handler cookie tab-id html filename]
   (let [id       (second (re-find #"action-id=(a_[^&]+)&" html))
         boundary "hyperboundary"
         body     (.getBytes (str "--" boundary "\r\n"
                                  "Content-Disposition: form-data; name=\"avatar\"; filename=\"" filename "\"\r\n"
                                  "Content-Type: text/plain\r\n\r\n"
                                  "hello\r\n"
                                  "--" boundary "--\r\n")
                             "UTF-8")]
     (written!
       (handler {:request-method :post
                 :uri            "/hyper/upload"
                 :query-string   (str "action-id=" id "&tab-id=" tab-id)
                 :headers        {"cookie"         cookie
                                  "content-type"   (str "multipart/form-data; boundary=" boundary)
                                  "content-length" (str (alength body))}
                 :body           (java.io.ByteArrayInputStream. body)})))))

(defn- post-navigate!
  [handler cookie tab-id]
  (request handler cookie {:request-method :post
                           :uri            "/hyper/navigate"
                           :query-string   (str "tab-id=" tab-id "&path=%2Fother")}))

(defn- get-events
  [handler cookie tab-id]
  (request handler cookie {:request-method :get :uri "/hyper/events" :query-string (str "tab-id=" tab-id)}))

(defn- connect!
  "Open the event stream of tab `tab-id` and return `{:done stream :out bytes}`
   once it is attached: `stream` is realized when the stream ends, `bytes`
   holds what it wrote."
  [handler app* cookie tab-id]
  (let [response (get-events handler cookie tab-id)
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
  "The cookie (\"name=value\") that `response` sets for cookie `cookie-name`
   (default hyper-session)."
  ([response] (cookie-of response "hyper-session"))
  ([response cookie-name]
   (some-> (set-cookie response cookie-name) (str/split #";") first)))

(defn- session-id-of
  "The session id cookie `cookie` (\"name=value\") names."
  [cookie]
  (second (str/split cookie #"=" 2)))

(defn- attributes
  "The attributes of `Set-Cookie` header `header`, lower-cased."
  [header]
  (set (map (comp str/lower-case str/trim) (rest (str/split header #";")))))

(defn- refused? [response]
  (= 404 (:status response)))

;; ---------------------------------------------------------------------------
;; end-session!
;; ---------------------------------------------------------------------------

(deftest test-end-session
  (let [late           (atom nil)
        [handler app*] (app (fn [_]
                              (effects/end-session!)
                              (effects/end-session!)
                              (reset! (h/session-cursor :late) 1)
                              (reset! late (get-in @(:hyper/app-state context/*request*)
                                                   [:sessions (:hyper/session-id context/*request*) :data :late]))))
        page           (load-page! handler nil "T1")
        html           (:body page)
        cookie         (cookie-of page)
        session-id     (session-id-of cookie)
        _              (load-page! handler cookie "T2")
        _              (load-page! handler cookie "T3")
        other-cookie   (cookie-of (load-page! handler nil "O"))
        other-id       (session-id-of other-cookie)]
    (swap! app* assoc-in [:sessions session-id :data :user] "alice")
    (let [t1       (connect! handler app* cookie "T1")
          t2       (connect! handler app* cookie "T2")
          o        (connect! handler app* other-cookie "O")
          response (post-action! handler cookie "T1" html)]
      (testing "the action handler returns normally"
        (is (= 204 (:status response))))
      (testing "the session cookie is deleted"
        (let [header (set-cookie response "hyper-session")]
          (is (str/starts-with? header "hyper-session=;"))
          (is (contains? (attributes header) "max-age=0"))
          (is (contains? (attributes header) "path=/"))))
      (testing "the session's connected tabs reload, then their streams end"
        (doseq [conn [t1 t2]]
          (is (ended? conn))
          (is (str/includes? (written conn) "window.location.reload()"))))
      (testing "every tab of the session is torn down, connected or not"
        (is (= #{"O"} (set (keys (:tabs @app*)))))
        (is (not (contains? (:sessions @app*) session-id))))
      (testing "session cursor writes later in the action land in the session until it ends"
        (is (= 1 @late)))
      (testing "another session is untouched"
        (is (= #{"O"} (get-in @app* [:sessions other-id :tabs])))
        (is (some? (get-in @app* [:tabs "O" :renderer])))
        (is (not (str/includes? (written o) "window.location.reload()"))))
      (testing "the ended session's tabs are refused"
        (is (refused? (post-action! handler cookie "T1" html 2))))
      (testing "a page loaded with the old cookie gets a new, empty session"
        (let [page   (load-page! handler cookie "T4")
              new-id (session-id-of (cookie-of page))]
          (is (not= session-id new-id))
          (is (not (contains? (:sessions @app*) session-id)))
          (is (= {:data {} :tabs #{"T4"}} (get-in @app* [:sessions new-id])))
          (is (str/includes? (:body page) "user:<"))))
      (server/cleanup-tab! app* "O")
      (is (ended? o)))))

(deftest test-end-session-uses-the-cookie-settings
  (let [[handler app*] (app (fn [_] (effects/end-session!))
                            :session {:cookie {:name "sid" :path "/app" :same-site :strict}})
        page           (load-page! handler nil "T")
        cookie         (cookie-of page "sid")
        header         (set-cookie (post-action! handler cookie "T" (:body page)) "sid")]
    (is (str/starts-with? header "sid=;"))
    (is (= #{"path=/app" "httponly" "max-age=0" "samesite=strict"} (attributes header)))
    (is (not (contains? (:sessions @app*) (session-id-of cookie))))))

(deftest test-end-session-for-a-gone-session-does-nothing
  (let [[handler app*] (app (fn [_]))
        page           (load-page! handler nil "T")
        other-id       (session-id-of (cookie-of (load-page! handler nil "O")))]
    (testing "applied for a tab whose session is gone"
      (let [response {:status 204}]
        (server/cleanup-tab! app* "T")
        (is (= response (#'server/apply-session-effect response app* {} "T" :end)))
        (is (= response (#'server/apply-session-effect response app* {} "T" :rotate)))
        (is (= #{"O"} (set (keys (:tabs @app*)))))
        (is (= #{other-id} (set (keys (:sessions @app*)))))))
    (testing "its tab's actions are refused"
      (is (refused? (post-action! handler (cookie-of page) "T" (:body page)))))))

(deftest test-end-session-in-an-upload
  (let [[handler app*] (app (fn [_]))
        page           (load-page! handler nil "T")
        cookie         (cookie-of page)
        response       (post-upload! handler cookie "T" (:body page) "end.txt")]
    (is (= 204 (:status response)))
    (is (str/starts-with? (set-cookie response "hyper-session") "hyper-session=;"))
    (is (not (contains? (:sessions @app*) (session-id-of cookie))))
    (is (empty? (:tabs @app*)))))

;; ---------------------------------------------------------------------------
;; rotate-session!
;; ---------------------------------------------------------------------------

(deftest test-rotate-session
  (let [rotated?       (atom false)
        [handler app*] (app (fn [user*]
                              (if (compare-and-set! rotated? false true)
                                (do (effects/rotate-session!)
                                    (reset! user* "alice")
                                    (reset! (h/session-cursor :later) 1))
                                (reset! user* "carol"))))
        page           (load-page! handler nil "T1")
        html           (:body page)
        cookie         (cookie-of page)
        session-id     (session-id-of cookie)
        html2          (:body (load-page! handler cookie "T2"))
        _              (load-page! handler cookie "T3")
        t1             (connect! handler app* cookie "T1")
        t2             (connect! handler app* cookie "T2")
        other-id       (session-id-of (cookie-of (load-page! handler nil "O")))
        response       (post-action! handler cookie "T1" html)
        new-cookie     (cookie-of response)
        new-id         (session-id-of new-cookie)]
    (testing "the action handler returns normally"
      (is (= 204 (:status response))))
    (testing "the response sets the cookie to a new id, with its attributes"
      (is (str/starts-with? new-id "ses_"))
      (is (not= session-id new-id))
      (is (= #{"path=/" "httponly" "max-age=604800" "samesite=lax"}
             (attributes (set-cookie response "hyper-session")))))
    (testing "the action's tab moves to the new id, with the session's data"
      (is (= #{"T1"} (get-in @app* [:sessions new-id :tabs])))
      (is (= new-id (get-in @app* [:tabs "T1" :session-id]))))
    (testing "session cursor writes later in the action land in the new session"
      (is (= {:user "alice" :later 1} (get-in @app* [:sessions new-id :data]))))
    (testing "the session's other tabs reload and are torn down, connected or not"
      (is (ended? t2))
      (is (str/includes? (written t2) "window.location.reload()"))
      (is (= #{"T1" "O"} (set (keys (:tabs @app*))))))
    (testing "the old id names no session"
      (is (= #{new-id other-id} (set (keys (:sessions @app*))))))
    (testing "another session is untouched"
      (is (= #{"O"} (get-in @app* [:sessions other-id :tabs]))))
    (testing "the action's tab keeps its stream, which renders the session's changes"
      (is (not (str/includes? (written t1) "window.location.reload()")))
      (swap! app* assoc-in [:sessions new-id :data :user] "bob")
      (is (wait-for-written t1 "user:bob")))
    (testing "requests with the old cookie are refused"
      (is (refused? (post-action! handler cookie "T1" html 2)))
      (is (refused? (post-action! handler cookie "T2" html2 2)))
      (is (refused? (post-upload! handler cookie "T1" html)))
      (is (refused? (post-navigate! handler cookie "T1")))
      (let [events (get-events handler cookie "T1")]
        (is (string? (:body events)) "no stream is opened")
        (is (str/includes? (:body events) "window.location.reload()") "the connected tab is told to reload")))
    (testing "the action's tab works with the new cookie"
      (is (= 204 (:status (post-action! handler new-cookie "T1" html 2))))
      (is (= "carol" (get-in @app* [:sessions new-id :data :user]))
          "the next action ran, its session cursor rendered after the rotation")
      (is (= #{new-id other-id} (set (keys (:sessions @app*)))) "and nothing was written to the old id")
      (is (= 204 (:status (post-upload! handler new-cookie "T1" html))))
      (is (= "a.txt" (get-in @app* [:tabs "T1" :data :uploaded]))))
    (testing "a page loaded with the old cookie gets a new, empty session"
      (let [page (load-page! handler cookie "A")
            id   (session-id-of (cookie-of page))]
        (is (not (contains? #{session-id new-id} id)))
        (is (= {:data {} :tabs #{"A"}} (get-in @app* [:sessions id])))
        (is (str/includes? (:body page) "user:<"))))
    (testing "a page loaded with the new cookie is of the session, with its data"
      (let [page (load-page! handler new-cookie "B")]
        (is (= new-cookie (cookie-of page)))
        (is (= #{"T1" "B"} (get-in @app* [:sessions new-id :tabs])))
        (is (str/includes? (:body page) "user:carol"))))
    (server/cleanup-tab! app* "T1")
    (is (ended? t1))))

(deftest test-rotate-session-in-an-upload
  (let [[handler app*] (app (fn [_]))
        page           (load-page! handler nil "T1")
        cookie         (cookie-of page)
        _              (swap! app* assoc-in [:sessions (session-id-of cookie) :data :user] "alice")
        _              (load-page! handler cookie "T2")
        t2             (connect! handler app* cookie "T2")
        response       (post-upload! handler cookie "T1" (:body page) "rotate.txt")
        new-id         (session-id-of (cookie-of response))]
    (is (= 204 (:status response)))
    (is (not= (session-id-of cookie) new-id))
    (is (= {:data {:user "alice"} :tabs #{"T1"}} (get-in @app* [:sessions new-id])))
    (is (= #{new-id} (set (keys (:sessions @app*)))))
    (is (ended? t2))
    (is (str/includes? (written t2) "window.location.reload()"))
    (is (= #{"T1"} (set (keys (:tabs @app*)))))))

(deftest test-rotate-session-secure-over-https
  (let [[handler _] (app (fn [_] (effects/rotate-session!)))
        page        (load-page! handler nil "T")
        response    (handler {:request-method :post
                              :scheme         :https
                              :uri            "/hyper/actions"
                              :query-string   "tab-id=T"
                              :headers        {"cookie"       (cookie-of page)
                                               "content-type" "application/json"}
                              :body           (json/generate-string
                                                {:base    nil
                                                 :actions [{:id      (second (re-find #"hyperAction\(&apos;(a_[^&]+)&apos;" (:body page)))
                                                            :seq     1
                                                            :v       nil
                                                            :signals "{}"
                                                            :params  nil}]})})]
    (is (contains? (attributes (set-cookie response "hyper-session")) "secure"))))

(deftest test-end-session-wins-over-rotate-session
  (let [[handler app*] (app (fn [_] (effects/rotate-session!) (effects/end-session!)))
        page           (load-page! handler nil "T")
        response       (post-action! handler (cookie-of page) "T" (:body page))]
    (is (str/starts-with? (set-cookie response "hyper-session") "hyper-session=;"))
    (is (empty? (:sessions @app*)))
    (is (empty? (:tabs @app*)))))

(deftest test-rotation-re-renders-the-actions-tab
  (let [app*    (atom (state/init-state))
        renders (atom {})]
    (state/get-or-create-tab! app* "S" "T1")
    (state/get-or-create-tab! app* "S" "T2")
    (state/get-or-create-tab! app* "OTHER" "O")
    (doseq [t ["T1" "T2" "O"]]
      (watch/setup-watchers! app* t #(swap! renders update t (fnil inc 0))))
    (try
      (let [response (#'server/apply-session-effect {} app* {} "T1" :rotate)]
        (testing "the old id names no session before the response is sent"
          (is (not (contains? (:sessions @app*) "S")))
          (is (contains? (:tabs @app*) "T2") "while the other tabs wait for it"))
        (written! response))
      (is (= 1 (@renders "T1")) "so its actions' session cursors are of the new id")
      (is (nil? (@renders "O")))
      (is (= #{"T1" "O"} (set (keys (:tabs @app*)))) "and the session's other tabs are removed")
      (finally
        (doseq [t ["T1" "O"]]
          (watch/remove-watchers! app* t))))))

;; ---------------------------------------------------------------------------
;; Tabs reloaded at once without a session the server holds
;; ---------------------------------------------------------------------------

(defn- load-pages-at-once!
  "Load / into each of tabs `tab-ids` at the same time, with cookie
   `cookie`; returns the responses."
  [handler cookie tab-ids]
  (let [start (promise)
        loads (mapv (fn [tab-id] (future @start (load-page! handler cookie tab-id))) tab-ids)]
    (deliver start true)
    (mapv deref loads)))

(defn- streams-join-the-last-cookie
  "Asserts that pages `a` and `b` of tabs A and B, loaded at once, are each of
   their own new session, other than `old-id`, and that both streams, opened
   with the cookie `b` set, render that session and leave both tabs in it."
  [handler app* old-id a b]
  (let [kept      (cookie-of b)
        kept-id   (session-id-of kept)
        former-id (session-id-of (cookie-of a))]
    (testing "each page gets its own new session"
      (is (not= former-id kept-id))
      (is (not (contains? #{former-id kept-id} old-id)))
      (is (= #{"A"} (get-in @app* [:sessions former-id :tabs])))
      (is (= #{"B"} (get-in @app* [:sessions kept-id :tabs]))))
    (swap! app* assoc-in [:sessions kept-id :data :user] "bob")
    (testing "both streams succeed with the cookie the browser kept"
      (let [conns [(connect! handler app* kept "A") (connect! handler app* kept "B")]]
        (doseq [conn conns]
          (is (wait-for-written conn "user:bob") "rendering that session's data"))
        (testing "landing in one session, with the other removed"
          (is (= #{"A" "B"} (get-in @app* [:sessions kept-id :tabs])))
          (is (= kept-id (get-in @app* [:tabs "A" :session-id])))
          (is (not (contains? (:sessions @app*) former-id))))
        (doseq [t ["A" "B"]]
          (server/cleanup-tab! app* t))
        (doseq [conn conns]
          (is (ended? conn)))))))

(deftest test-tabs-reloaded-at-once-after-sign-out-share-a-session
  (let [[handler app*] (app (fn [_] (effects/end-session!)))
        page           (load-page! handler nil "T1")
        cookie         (cookie-of page)
        _              (load-page! handler cookie "T2")
        t1             (connect! handler app* cookie "T1")]
    (post-action! handler cookie "T1" (:body page))
    (is (ended? t1))
    (is (empty? (:sessions @app*)) "the session has ended")
    (let [[a b] (load-pages-at-once! handler cookie ["A" "B"])]
      (streams-join-the-last-cookie handler app* (session-id-of cookie) a b))))

(deftest test-tabs-reloaded-at-once-after-a-restart-share-a-session
  (let [[handler app*] (app (fn [_]))
        [a b]          (load-pages-at-once! handler "hyper-session=ses_gone" ["A" "B"])]
    (streams-join-the-last-cookie handler app* "ses_gone" a b)))
