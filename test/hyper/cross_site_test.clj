(ns hyper.cross-site-test
  "Hyper's endpoints refuse a request whose `Sec-Fetch-Site` is `same-site`
   or `cross-site`, before any app code runs; page routes do not."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.state :as state]))

(def ^:private refused-sites ["cross-site" "same-site" "Cross-Site"])

(def ^:private allowed-sites ["same-origin" "none" nil])

(defn- loaded-page
  "A hyper handler with a page at / holding an action, an upload and a
   counter, a page at /other, and middleware recording the URIs it sees; with
   / loaded into tab T.  `opts` go to `create-handler`."
  [& opts]
  (let [app*     (atom (state/init-state))
        seen     (atom [])
        handler  (apply h/create-handler
                        [["/" {:name :home
                               :get  (fn [_]
                                       [:div
                                        [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))} "+"]
                                        [:form {:data-on:submit__prevent (h/action (reset! (h/tab-cursor :uploaded)
                                                                                           (:filename (:avatar $form))))}
                                         [:input {:type "file" :name "avatar"}]]])}]
                         ["/other" {:name :other :get (fn [_] [:div "other"])}]]
                        :app-state app*
                        :middleware [(fn [handler]
                                       (fn [req]
                                         (swap! seen conj (:uri req))
                                         (handler req)))]
                        opts)
        response (handler {:request-method :get :uri "/" :query-string "tab-id=T"})]
    (reset! seen [])
    {:handler    handler
     :app*       app*
     :seen       seen
     :html       (:body response)
     :session-id (some #(second (re-find #"^hyper-session=([^;]+)" %))
                       (get-in response [:headers "Set-Cookie"]))}))

(defn- request
  "Send `req` through the page's handler with its session cookie, and a
   `Sec-Fetch-Site` of `site` unless it is nil."
  [{:keys [handler session-id]} site req]
  (handler (update req :headers merge
                   (cond-> {"cookie" (str "hyper-session=" session-id)}
                     site (assoc "sec-fetch-site" site)))))

(defn- post-action! [page site]
  (let [id (second (re-find #"hyperAction\(&apos;(a_T_\d+)" (:html page)))]
    (request page site
             {:request-method :post
              :uri            "/hyper/actions"
              :query-string   "tab-id=T"
              :headers        {"content-type" "application/json"}
              :body           (json/generate-string
                                {:base    nil
                                 :actions [{:id id :seq 1 :v nil :signals "{}" :params nil}]})})))

(defn- post-upload! [page site]
  (let [id       (second (re-find #"action-id=(a_T_\d+)" (:html page)))
        boundary "hyperboundary"
        body     (.getBytes (str "--" boundary "\r\n"
                                 "Content-Disposition: form-data; name=\"avatar\"; filename=\"a.txt\"\r\n"
                                 "Content-Type: text/plain\r\n\r\n"
                                 "hello\r\n"
                                 "--" boundary "--\r\n")
                            "UTF-8")]
    (request page site
             {:request-method :post
              :uri            "/hyper/upload"
              :query-string   (str "action-id=" id "&tab-id=T")
              :headers        {"content-type"   (str "multipart/form-data; boundary=" boundary)
                               "content-length" (str (alength body))}
              :body           (java.io.ByteArrayInputStream. body)})))

(defn- post-navigate! [page site]
  (request page site {:request-method :post
                      :uri            "/hyper/navigate"
                      :query-string   "tab-id=T&path=%2Fother"}))

(defn- get-events [page site]
  (request page site {:request-method :get
                      :uri            "/hyper/events"
                      :query-string   "tab-id=T"}))

(defn- cookie-token!
  "A token for collecting a cookie kept for tab T."
  [{:keys [app*]}]
  (h/run-in-tabs! app* #(effects/set-cookie! "remember" "yes"))
  (first (keys (:cookie-tokens @app*))))

(defn- post-token! [page site token]
  (request page site {:request-method :post
                      :uri            "/hyper/cookies"
                      :query-string   (str "token=" token)}))

(defn- refused? [response]
  (and (= 403 (:status response))
       (= {"error" "Cross-site request"} (json/parse-string (:body response)))))

(deftest test-actions
  (doseq [site refused-sites]
    (testing (str "an action with Sec-Fetch-Site " site " is refused")
      (let [{:keys [app* seen] :as page} (loaded-page)]
        (is (refused? (post-action! page site)))
        (is (nil? (get-in @app* [:tabs "T" :data :clicks])) "the action did not run")
        (is (empty? @seen) "nor the app's middleware"))))
  (doseq [site allowed-sites]
    (testing (str "an action with Sec-Fetch-Site " (or site "absent") " runs")
      (let [{:keys [app*] :as page} (loaded-page)]
        (is (= 204 (:status (post-action! page site))))
        (is (= 1 (get-in @app* [:tabs "T" :data :clicks])))))))

(deftest test-uploads
  (doseq [site refused-sites]
    (testing (str "an upload with Sec-Fetch-Site " site " is refused")
      (let [{:keys [app* seen] :as page} (loaded-page)]
        (is (refused? (post-upload! page site)))
        (is (nil? (get-in @app* [:tabs "T" :data :uploaded])) "the upload did not run")
        (is (empty? @seen) "nor the app's middleware"))))
  (doseq [site allowed-sites]
    (testing (str "an upload with Sec-Fetch-Site " (or site "absent") " runs")
      (let [{:keys [app*] :as page} (loaded-page)]
        (is (= 204 (:status (post-upload! page site))))
        (is (= "a.txt" (get-in @app* [:tabs "T" :data :uploaded])))))))

(deftest test-navigation
  (doseq [site refused-sites]
    (testing (str "a navigation with Sec-Fetch-Site " site " is refused")
      (let [{:keys [app* seen] :as page} (loaded-page)]
        (is (refused? (post-navigate! page site)))
        (is (= :home (get-in @app* [:tabs "T" :route :name])) "the tab stays where it was")
        (is (empty? @seen) "nor the app's middleware"))))
  (doseq [site allowed-sites]
    (testing (str "a navigation with Sec-Fetch-Site " (or site "absent") " runs")
      (let [{:keys [app*] :as page} (loaded-page)]
        (is (= 200 (:status (post-navigate! page site))))
        (is (= :other (get-in @app* [:tabs "T" :route :name])))))))

(deftest test-the-event-stream
  (doseq [site refused-sites]
    (testing (str "an event stream with Sec-Fetch-Site " site " is refused")
      (let [{:keys [app* seen] :as page} (loaded-page)]
        (is (refused? (get-events page site)))
        (is (nil? (get-in @app* [:tabs "T" :renderer])) "no stream is attached")
        (is (empty? @seen) "nor the app's middleware"))))
  (doseq [site allowed-sites]
    (testing (str "an event stream with Sec-Fetch-Site " (or site "absent") " opens")
      (let [response (get-events (loaded-page) site)]
        (is (= 200 (:status response)))
        (is (= "text/event-stream" (get-in response [:headers "Content-Type"])))
        (is (not (string? (:body response))) "a stream, not a short answer")))))

(deftest test-collecting-cookies
  (doseq [site refused-sites]
    (testing (str "collecting cookies with Sec-Fetch-Site " site " is refused")
      (let [{:keys [app*] :as page} (loaded-page)
            token                   (cookie-token! page)]
        (is (refused? (post-token! page site token)))
        (is (contains? (:cookie-tokens @app*) token) "the token is not used up"))))
  (doseq [site allowed-sites]
    (testing (str "collecting cookies with Sec-Fetch-Site " (or site "absent") " sets them")
      (let [page     (loaded-page)
            response (post-token! page site (cookie-token! page))]
        (is (= 204 (:status response)))
        (is (some #(str/starts-with? % "remember=yes")
                  (get-in response [:headers "Set-Cookie"])))))))

(deftest test-under-a-base-path
  (let [{:keys [app*] :as page} (loaded-page :base-path "/app")]
    (is (refused? (request page "cross-site" {:request-method :post
                                              :uri            "/app/hyper/navigate"
                                              :query-string   "tab-id=T&path=%2Fapp%2Fother"})))
    (is (= :home (get-in @app* [:tabs "T" :route :name])))))

(deftest test-page-routes-are-served-to-other-sites
  (doseq [site refused-sites]
    (testing (str "a page with Sec-Fetch-Site " site " is served")
      (let [page     (loaded-page)
            response (request page site {:request-method :get :uri "/other" :query-string "tab-id=U"})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "other"))
        (is (= ["/other"] @(:seen page)) "through the app's middleware")))))
