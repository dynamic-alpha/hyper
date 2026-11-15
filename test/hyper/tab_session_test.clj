(ns hyper.tab-session-test
  "A tab belongs to the session it was loaded in: its actions, uploads,
   navigations and event stream are refused for any other session."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.brotli :as brotli]
            [hyper.core :as h]
            [hyper.state :as state]
            [hyper.test :as ht]))

(defn- app
  "A hyper handler with a page at / holding an action, an upload and a
   counter, a page at /other, and its app-state."
  []
  (let [app* (atom (state/init-state))]
    [(h/create-handler
       [["/" {:name :home
              :get  (fn [_]
                      [:div
                       [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))} "+"]
                       [:form {:data-on:submit__prevent (h/action (reset! (h/tab-cursor :uploaded)
                                                                          (:filename (:avatar $form))))}
                        [:input {:type "file" :name "avatar"}]]])}]
        ["/other" {:name :other :get (fn [_] [:div "other"])}]]
       :app-state app*)
     app*]))

(defn- request
  "Send `req` through `handler` with a `hyper-session` cookie of `session-id`,
   or with no cookie when it is nil."
  [handler session-id req]
  (handler (cond-> req
             session-id (assoc-in [:headers "cookie"] (str "hyper-session=" session-id)))))

(defn- load-page!
  "Load / into tab T in session `session-id`; returns the response."
  [handler session-id]
  (request handler session-id {:request-method :get :uri "/" :query-string "tab-id=T"}))

(defn- post-action!
  [handler session-id html]
  (let [id (second (re-find #"hyperAction\(&apos;(a_T_\d+)" html))]
    (request handler session-id
             {:request-method :post
              :uri            "/hyper/actions"
              :query-string   "tab-id=T"
              :headers        {"content-type" "application/json"}
              :body           (json/generate-string
                                {:base    nil
                                 :actions [{:id id :seq 1 :v nil :signals "{}" :params nil}]})})))

(defn- post-upload!
  [handler session-id html]
  (let [id       (second (re-find #"action-id=(a_T_\d+)" html))
        boundary "hyperboundary"
        body     (.getBytes (str "--" boundary "\r\n"
                                 "Content-Disposition: form-data; name=\"avatar\"; filename=\"a.txt\"\r\n"
                                 "Content-Type: text/plain\r\n\r\n"
                                 "hello\r\n"
                                 "--" boundary "--\r\n")
                            "UTF-8")]
    (request handler session-id
             {:request-method :post
              :uri            "/hyper/upload"
              :query-string   (str "action-id=" id "&tab-id=T")
              :headers        {"content-type"   (str "multipart/form-data; boundary=" boundary)
                               "content-length" (str (alength body))}
              :body           (java.io.ByteArrayInputStream. body)})))

(defn- post-navigate!
  [handler session-id]
  (request handler session-id {:request-method :post
                               :uri            "/hyper/navigate"
                               :query-string   "tab-id=T&path=%2Fother"}))

(defn- get-events
  [handler session-id]
  (request handler session-id {:request-method :get
                               :uri            "/hyper/events"
                               :query-string   "tab-id=T"}))

(defn- refused? [response]
  (and (= 404 (:status response))
       (str/includes? (str (:body response)) "Unknown tab")))

(defn- reloads?
  "True when `response` is an event stream that reloads the page."
  [response]
  (and (= 200 (:status response))
       (= "text/event-stream" (get-in response [:headers "Content-Type"]))
       (str/includes? (:body response) "event: datastar-patch-elements")
       (str/includes? (:body response) "window.location.reload()")))

(deftest test-a-page-load-links-the-tab-to-its-session
  (testing "a page load with a session cookie links the tab to that session"
    (let [[handler app*] (app)]
      (load-page! handler "S")
      (is (= "S" (get-in @app* [:tabs "T" :session-id])))
      (is (contains? (get-in @app* [:sessions "S" :tabs]) "T"))))
  (testing "a page load without one links the tab to the session it starts"
    (let [[handler app*] (app)
          response       (load-page! handler nil)
          session-id     (some #(second (re-find #"^hyper-session=([^;]+)" %))
                               (get-in response [:headers "Set-Cookie"]))]
      (is (some? session-id))
      (is (= session-id (get-in @app* [:tabs "T" :session-id])))
      (is (contains? (get-in @app* [:sessions session-id :tabs]) "T"))))
  (testing "a page load naming another session's tab is served in a new tab"
    (let [[handler app*] (app)
          _              (load-page! handler "S")
          html           (:body (load-page! handler "OTHER"))
          other-tabs     (get-in @app* [:sessions "OTHER" :tabs])]
      (is (= "S" (get-in @app* [:tabs "T" :session-id])))
      (is (not (contains? other-tabs "T")))
      (is (= 1 (count other-tabs)))
      (is (str/includes? html (str "tab-id=" (first other-tabs))))
      (is (not (str/includes? html "tab-id=T'"))))))

(deftest test-a-tab-without-a-session-takes-the-one-it-is-linked-to
  (let [app* (atom (state/init-state))]
    (swap! app* assoc-in [:tabs "T"] {:data {:n 1}})
    (state/get-or-create-tab! app* "S" "T")
    (is (= "S" (get-in @app* [:tabs "T" :session-id])))
    (is (= {:n 1} (get-in @app* [:tabs "T" :data])))
    (is (contains? (get-in @app* [:sessions "S" :tabs]) "T"))
    (testing "and keeps it when linked to another"
      (state/get-or-create-tab! app* "OTHER" "T")
      (is (= "S" (get-in @app* [:tabs "T" :session-id])))
      (is (not (contains? (get-in @app* [:sessions "OTHER" :tabs]) "T"))))))

(deftest test-actions-need-the-tabs-session
  (doseq [session-id ["OTHER" nil]]
    (testing (str "with session cookie " (pr-str session-id))
      (let [[handler app*] (app)
            html           (:body (load-page! handler "S"))]
        (is (refused? (post-action! handler session-id html)))
        (is (nil? (get-in @app* [:tabs "T" :data :clicks])) "the action did not run")
        (is (nil? (get-in @app* [:tabs "T" :action-seq])) "nor counted as run")
        (testing "while the tab's own session still can"
          (is (= 204 (:status (post-action! handler "S" html))))
          (is (= 1 (get-in @app* [:tabs "T" :data :clicks]))))))))

(deftest test-uploads-need-the-tabs-session
  (doseq [session-id ["OTHER" nil]]
    (testing (str "with session cookie " (pr-str session-id))
      (let [[handler app*] (app)
            html           (:body (load-page! handler "S"))]
        (is (refused? (post-upload! handler session-id html)))
        (is (nil? (get-in @app* [:tabs "T" :data :uploaded])) "the upload did not run")
        (testing "while the tab's own session still can"
          (is (= 204 (:status (post-upload! handler "S" html))))
          (is (= "a.txt" (get-in @app* [:tabs "T" :data :uploaded]))))))))

(deftest test-navigation-needs-the-tabs-session
  (doseq [session-id ["OTHER" nil]]
    (testing (str "with session cookie " (pr-str session-id))
      (let [[handler app*] (app)]
        (load-page! handler "S")
        (is (refused? (post-navigate! handler session-id)))
        (is (= :home (get-in @app* [:tabs "T" :route :name])) "the tab stays where it was")
        (testing "while the tab's own session still can"
          (is (= 200 (:status (post-navigate! handler "S"))))
          (is (= :other (get-in @app* [:tabs "T" :route :name]))))))))

(deftest test-the-event-stream-needs-the-tabs-session
  (doseq [session-id ["OTHER" nil]]
    (testing (str "with session cookie " (pr-str session-id))
      (let [[handler app*] (app)]
        (load-page! handler "S")
        (let [response (get-events handler session-id)]
          (is (refused? response))
          (is (string? (:body response)) "no stream is opened"))
        (testing "while the tab's own session still can"
          (let [response (get-events handler "S")]
            (is (= 200 (:status response)))
            (is (= "text/event-stream" (get-in response [:headers "Content-Type"])))))
        (is (nil? (get-in @app* [:tabs "T" :renderer])) "the tab has no stream attached")))))

(deftest test-an-unknown-tab-is-refused-everywhere
  (let [[handler _] (app)
        html        "hyperAction(&apos;a_T_1&apos; action-id=a_T_2"]
    (is (refused? (post-action! handler "S" html)))
    (is (refused? (post-upload! handler "S" html)))
    (is (refused? (post-navigate! handler "S")))
    (testing "its event stream reloads the page"
      (is (reloads? (get-events handler "S"))))))

(deftest test-a-connected-tab-of-another-session-reloads
  (doseq [session-id ["OTHER" nil]]
    (testing (str "with session cookie " (pr-str session-id))
      (let [[handler app*] (app)]
        (load-page! handler "S")
        ;; as the tab's first stream connection does
        (swap! app* assoc-in [:tabs "T" :connection-initialized?] true)
        (is (reloads? (get-events handler session-id)))
        (is (nil? (get-in @app* [:tabs "T" :renderer])) "no stream is attached")
        (testing "while the tab's own session still streams"
          (let [response (get-events handler "S")]
            (is (= 200 (:status response)))
            (is (= "text/event-stream" (get-in response [:headers "Content-Type"])))
            (is (not (string? (:body response))) "a stream, not a short answer")))))))

(deftest test-a-reloading-stream-is-compressed-when-accepted
  (let [[handler _] (app)
        response    (handler {:request-method :get
                              :uri            "/hyper/events"
                              :query-string   "tab-id=T"
                              :headers        {"cookie"          "hyper-session=S"
                                               "accept-encoding" "gzip, br"}})]
    (is (= "br" (get-in response [:headers "Content-Encoding"])))
    (is (reloads? (update response :body brotli/decompress)))))

(deftest test-test-page-tabs-carry-their-session
  (let [result (ht/test-page (fn [_]
                               [:button {:data-on:click (h/action {:as "inc"}
                                                                  (swap! (h/tab-cursor :n 0) inc))}
                                "+"])
                             {:session-id "S" :tab-id "T"})
        app*   (:app-state result)]
    (is (= "S" (get-in @app* [:tabs "T" :session-id])))
    (is (contains? (get-in @app* [:sessions "S" :tabs]) "T"))
    (is (= 1 (get-in (ht/test-action result "inc") [:cursors :tab :n])))))
