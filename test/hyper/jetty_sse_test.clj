(ns hyper.jetty-sse-test
  "Integration tests for the live Jetty SSE transport: a real server, a real
   HTTP client holding an SSE connection, exercising the push-after-initial
   path that browser e2e tests depend on (but without a browser).

   Not tagged :e2e — these need no browser, just a JVM + a socket."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.brotli :as br]
            [hyper.core :as h]
            [hyper.state :as state])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net CookieManager URI ServerSocket]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- free-port ^long []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- cookie-client
  "An HttpClient with a cookie jar, so page GET, SSE connect, and action
   POSTs share one hyper session — like a real browser.  Required for any
   test exercising session-scoped state."
  ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.cookieHandler (CookieManager.))
      (.build)))

(defn- sse-reader
  "Drain an SSE InputStream into a StringBuilder on a background thread.
   Returns {:text (fn [] accumulated) :stop (fn [])}."
  [^InputStream is]
  (let [sb  (StringBuilder.)
        run (atom true)
        fut (future
              (let [buf (byte-array 4096)]
                (try
                  (loop []
                    (when @run
                      (let [n (.read is buf)]
                        (when (>= n 0)
                          (when (pos? n)
                            (locking sb (.append sb (String. buf 0 n "UTF-8"))))
                          (recur)))))
                  (catch Exception _ nil))))]
    {:text (fn [] (locking sb (.toString sb)))
     :stop (fn [] (reset! run false) (future-cancel fut))}))

(defn- wait-until
  "Poll text-fn until pred matches or timeout-ms elapses. Returns the text."
  [text-fn pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [t (text-fn)]
        (cond
          (pred t)                                 t
          (> (System/currentTimeMillis) deadline)  t
          :else                                    (do (Thread/sleep 25) (recur)))))))

(defn- GET [^HttpClient client url body-handler]
  (.send client
         (.. (HttpRequest/newBuilder (URI/create url)) (GET) (build))
         body-handler))

(defn- GET-br [^HttpClient client url body-handler]
  (.send client
         (.. (HttpRequest/newBuilder (URI/create url))
             (header "Accept-Encoding" "br")
             (GET) (build))
         body-handler))

(defn- sse-byte-reader
  "Like sse-reader but accumulates raw bytes (for compressed streams)."
  [^InputStream is]
  (let [baos (ByteArrayOutputStream.)
        run  (atom true)
        fut  (future
               (let [buf (byte-array 4096)]
                 (try
                   (loop []
                     (when @run
                       (let [n (.read is buf)]
                         (when (>= n 0)
                           (when (pos? n) (locking baos (.write baos buf 0 (int n))))
                           (recur)))))
                   (catch Exception _ nil))))]
    {:bytes (fn [] (locking baos (.toByteArray baos)))
     :stop  (fn [] (reset! run false) (future-cancel fut))}))

(defn- decode-br-stream
  "Best-effort incremental decode of a flushed brotli stream prefix."
  [^bytes bs]
  (if (pos? (alength bs))
    (try (br/decompress-stream bs) (catch Exception _ ""))
    ""))

(deftest sse-push-after-initial-render
  (testing "a re-render triggered after the SSE connection is live streams to the client"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_] [:div {:id "box"} "n=" @(h/global-cursor :n 0)])}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        ;; 1. Initial GET registers the render-fn for tab "T".
        (is (= 200 (.statusCode (GET client (str base "/?tab-id=T")
                                  (HttpResponse$BodyHandlers/ofString)))))
        ;; 2. Open the SSE connection (no compression, to assert on raw text).
        (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                    (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            ;; Initial full render must arrive with n=0.
            (let [t (wait-until text #(str/includes? % "n=0") 5000)]
              (is (str/includes? t "event: connected"))
              (is (str/includes? t "datastar-patch-elements"))
              (is (str/includes? t "n=0")))
            ;; 3. Change server state — the app-state watcher must enqueue a
            ;;    re-render that the live renderer pushes over the SAME stream.
            (swap! app* assoc-in [:global :n] 42)
            ;; 4. The push must arrive (this is what the browser depends on).
            (let [t (wait-until text #(str/includes? % "n=42") 5000)]
              (is (str/includes? t "n=42")
                  "re-render after initial paint must stream to the client"))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(deftest sse-push-with-brotli-after-initial-render
  (testing "a brotli SSE stream delivers a re-render pushed after the initial paint"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_] [:div {:id "box"} "n=" @(h/global-cursor :n 0)])}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        (is (= 200 (.statusCode (GET client (str base "/?tab-id=T")
                                  (HttpResponse$BodyHandlers/ofString)))))
        (let [resp                 (GET-br client (str base "/hyper/events?tab-id=T")
                                           (HttpResponse$BodyHandlers/ofInputStream))
              enc                  (-> resp .headers (.firstValue "content-encoding")
                                       (.orElse ""))
              {:keys [bytes stop]} (sse-byte-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            (is (= "br" enc) "the server should brotli-compress the SSE stream")
            (let [t (wait-until #(decode-br-stream (bytes)) #(str/includes? % "n=0") 5000)]
              (is (str/includes? t "n=0") "initial brotli render decodes"))
            (swap! app* assoc-in [:global :n] 42)
            (let [t (wait-until #(decode-br-stream (bytes)) #(str/includes? % "n=42") 5000)]
              (is (str/includes? t "n=42")
                  "a re-render pushed onto a live brotli stream must decode on the client"))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(deftest sse-push-via-action-post
  (testing "an action POST (the browser-click path) triggers a re-render that
            streams over the live SSE connection"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   [:div {:id "box"} "n=" @(h/tab-cursor :n 0)
                                    [:button {:data-on:click
                                              (h/action (swap! (h/tab-cursor :n 0) inc))}
                                     "+"]])}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        ;; GET registers the render-fn + the action; grab its action-id.
        (let [page      (.body ^java.net.http.HttpResponse
                          (GET client (str base "/?tab-id=T")
                            (HttpResponse$BodyHandlers/ofString)))
              action-id (second (re-find #"hyperAction\(&apos;(a_T_\d+)" page))]
          (is (some? action-id) "the page renders an action with an id")
          ;; Open SSE and wait for the initial render (n=0).
          (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                      (HttpResponse$BodyHandlers/ofInputStream))
                {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
            (try
              (wait-until text #(str/includes? % "n=0") 5000)
              ;; POST the action exactly like Datastar's @post would.
              (let [ar (.send client
                              (.. (HttpRequest/newBuilder
                                    (URI/create (str base "/hyper/actions?action-id=" action-id)))
                                  (POST (HttpRequest$BodyPublishers/noBody))
                                  (build))
                              (HttpResponse$BodyHandlers/ofString))]
                (is (= 204 (.statusCode ar)) "action POST returns 204"))
              ;; The re-render must stream over the SAME SSE connection.
              (let [t (wait-until text #(str/includes? % "n=1") 5000)]
                (is (str/includes? t "n=1")
                    "action-triggered re-render must reach the live SSE client"))
              (finally
                (stop)))))
        (finally
          (h/stop! stop))))))

(deftest sse-client-reported-signals-are-not-echoed
  (testing "signal values round-tripped from an action POST are not echoed back
            as patches, while server-initiated writes still patch down"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   (let [w* (h/signal :col-w 100)
                                         n* (h/tab-cursor :n 0)]
                                     [:div {:id "box"} "n=" @n*
                                      [:span {:data-text @w*}]
                                      [:button {:data-on:click (h/action (swap! n* inc))}
                                       "+"]]))}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        (let [page      (.body ^java.net.http.HttpResponse
                          (GET client (str base "/?tab-id=T")
                            (HttpResponse$BodyHandlers/ofString)))
              action-id (second (re-find #"hyperAction\(&apos;(a_T_\d+)" page))]
          (is (some? action-id) "the page renders an action with an id")
          (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                      (HttpResponse$BodyHandlers/ofInputStream))
                {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
            (try
              (wait-until text #(str/includes? % "n=0") 5000)
              ;; 1. POST the action with a signal body, exactly like Datastar's
              ;;    @post: the client reports colW=110 (e.g. a drag in flight).
              (let [ar (.send client
                              (.. (HttpRequest/newBuilder
                                    (URI/create (str base "/hyper/actions?action-id=" action-id)))
                                  (POST (HttpRequest$BodyPublishers/ofString "{\"colW\": 110}"))
                                  (build))
                              (HttpResponse$BodyHandlers/ofString))]
                (is (= 204 (.statusCode ar)) "action POST returns 204"))
              (wait-until text #(str/includes? % "n=1") 5000)
              ;; 2. A server-initiated signal write must still patch down.
              (swap! app* assoc-in [:tabs "T" :signals :col-w] 120)
              (let [t (wait-until text #(str/includes? % "data: signals {\"colW\":120}") 5000)]
                (is (str/includes? t "data: signals {\"colW\":120}")
                    "a server-initiated signal write must reach the client")
                ;; SSE is ordered: an echo of the client's own 110 would have
                ;; arrived before the 120 patch, so its absence now is final.
                (is (not (str/includes? t "data: signals {\"colW\":110}"))
                    "the client-reported value must not be echoed back"))
              ;; 3. The server writing a value the client once reported must
              ;;    still patch — the client has since been moved to 120, so a
              ;;    stale record of the old report must not suppress this.
              (swap! app* assoc-in [:tabs "T" :signals :col-w] 110)
              (let [t (wait-until text #(str/includes? % "data: signals {\"colW\":110}") 5000)]
                (is (str/includes? t "data: signals {\"colW\":110}")
                    "a server write back to a previously client-reported value must patch"))
              (finally
                (stop)))))
        (finally
          (h/stop! stop))))))

(deftest sse-optimistic-down-sync
  (testing "a server-side cursor write behind an optimistic patches the derived
            signal down; the initial connect carries no redundant patch"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   (let [w* (h/optimistic (h/session-cursor :col-w 100))]
                                     [:div {:id "box"}
                                      [:span {:data-text @w*}]]))}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (cookie-client)
          base    (str "http://localhost:" port)]
      (try
        (let [page (.body ^java.net.http.HttpResponse
                     (GET client (str base "/?tab-id=T")
                       (HttpResponse$BodyHandlers/ofString)))]
          (is (str/includes? page "data-signals:session-col-w__ifmissing=\"100\"")
              "the declaration seeds a fresh client with the committed value"))
        (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                    (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            (wait-until text #(str/includes? % "sessionColW") 5000)
            ;; Server-side write to the backing session cursor.  With a
            ;; cookie client every request shares the one session.
            (let [session-id (first (keys (:sessions @app*)))]
              (swap! app* assoc-in [:sessions session-id :data :col-w] 200))
            (let [t (wait-until text #(str/includes? % "data: signals {\"sessionColW\":200}") 5000)]
              (is (str/includes? t "data: signals {\"sessionColW\":200}")
                  "the cursor change must patch the derived signal")
              ;; Ordered stream: a redundant initial patch (100) would have
              ;; arrived before the 200 patch, so its absence now is final.
              (is (not (str/includes? t "data: signals {\"sessionColW\":100}"))
                  "__ifmissing covers the fresh client — no redundant patch"))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(defn- signal-events
  "All datastar-patch-signals payload lines in an SSE transcript."
  [t]
  (re-seq #"data: signals \{[^\n]*\}" t))

(defn- post-signals!
  "POST an action with a JSON signal body, as Datastar's @post does."
  [^HttpClient client base action-id body]
  (.send client
         (.. (HttpRequest/newBuilder
               (URI/create (str base "/hyper/actions?action-id=" action-id)))
             (POST (HttpRequest$BodyPublishers/ofString body))
             (build))
         (HttpResponse$BodyHandlers/ofString)))

(defn- poll-until
  "Poll f until pred matches or timeout; returns the last value."
  [f pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [v (f)]
        (cond
          (pred v)                                v
          (> (System/currentTimeMillis) deadline) v
          :else (do (Thread/sleep 25) (recur)))))))

(deftest sse-optimistic-commit-and-clamp
  (testing "a commit round trip produces no signal patch; a clamped commit
            pushes the correction down"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   (let [w* (h/optimistic (h/session-cursor :col-w 100))]
                                     [:div {:id "box"}
                                      [:span {:data-text @w*}]
                                      [:button {:data-on:click (h/action (h/commit! w*))} "commit"]
                                      [:button {:data-on:click (h/action (reset! w* (min @w* 300)))} "clamp"]]))}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (cookie-client)
          base    (str "http://localhost:" port)]
      (try
        (let [page                 (.body ^java.net.http.HttpResponse
                                     (GET client (str base "/?tab-id=T")
                                       (HttpResponse$BodyHandlers/ofString)))
              [commit-id clamp-id] (distinct (map second (re-seq #"hyperAction\(&apos;(a_T_\d+)" page)))
              session-id           (first (keys (:sessions @app*)))
              resp                 (GET client (str base "/hyper/events?tab-id=T")
                                     (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]}  (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (is (and commit-id clamp-id) "both actions render with ids")
          (try
            (wait-until text #(str/includes? % "sessionColW") 5000)
            ;; 1. Clean commit — cursor moves, nothing patches back.
            (post-signals! client base commit-id "{\"sessionColW\": 150}")
            (is (= 150 (poll-until #(get-in @app* [:sessions session-id :data :col-w])
                                   #(= 150 %) 5000))
                "commit! persists the reported value")
            ;; 2. Clamped commit — reported 900, committed (min 900 300) = 300.
            (post-signals! client base clamp-id "{\"sessionColW\": 900}")
            (let [t (wait-until text #(some (fn [e] (str/includes? e ":300")) (signal-events %)) 5000)]
              (is (some #(str/includes? % ":300") (signal-events t))
                  "the clamped value is patched down")
              ;; Ordered stream: any echo would precede the 300 patch.
              (is (not (some #(or (str/includes? % ":150") (str/includes? % ":900"))
                             (signal-events t)))
                  "neither round-tripped value is echoed back"))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(deftest sse-optimistic-auto-commit
  (testing "an auto-commit optimistic persists from a no-op action POST"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   (let [w* (h/optimistic (h/session-cursor :col-w 100)
                                                          {:auto-commit? true})]
                                     [:div {:id "box"}
                                      [:span {:data-text @w*}]
                                      [:button {:data-on:click (h/action nil)} "touch"]]))}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (cookie-client)
          base    (str "http://localhost:" port)]
      (try
        (let [page                (.body ^java.net.http.HttpResponse
                                    (GET client (str base "/?tab-id=T")
                                      (HttpResponse$BodyHandlers/ofString)))
              action-id           (second (re-find #"hyperAction\(&apos;(a_T_\d+)" page))
              session-id          (first (keys (:sessions @app*)))
              resp                (GET client (str base "/hyper/events?tab-id=T")
                                    (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            (wait-until text #(str/includes? % "sessionColW") 5000)
            ;; The action body is empty — the signal riding the POST is the payload.
            (post-signals! client base action-id "{\"sessionColW\": 175}")
            (is (= 175 (poll-until #(get-in @app* [:sessions session-id :data :col-w])
                                   #(= 175 %) 5000))
                "auto-commit persists without commit! in the action body")
            ;; Fence: a server write must still patch, and no 175 echo precedes it.
            (swap! app* assoc-in [:sessions session-id :data :col-w] 500)
            (let [t (wait-until text #(some (fn [e] (str/includes? e ":500")) (signal-events %)) 5000)]
              (is (some #(str/includes? % ":500") (signal-events t)))
              (is (not (some #(str/includes? % ":175") (signal-events t)))
                  "the auto-committed round trip is not echoed"))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(deftest sse-optimistic-multiplayer-and-conflict
  (testing "a commit patches other tabs on the session but never the committer;
            a stale commit under :server-wins is rejected and the client corrected"
    (let [port    (free-port)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   (let [w* (h/optimistic (h/session-cursor :col-w 100)
                                                          {:on-conflict :server-wins})]
                                     [:div {:id "box"}
                                      [:span {:data-text @w*}]
                                      [:button {:data-on:click (h/action (h/commit! w*))} "commit"]]))}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (cookie-client)
          base    (str "http://localhost:" port)]
      (try
        (let [page-a     (.body ^java.net.http.HttpResponse
                           (GET client (str base "/?tab-id=TA")
                             (HttpResponse$BodyHandlers/ofString)))
              _page-b    (GET client (str base "/?tab-id=TB")
                           (HttpResponse$BodyHandlers/ofString))
              commit-a   (second (re-find #"hyperAction\(&apos;(a_TA_\d+)" page-a))
              session-id (first (keys (:sessions @app*)))
              resp-a     (GET client (str base "/hyper/events?tab-id=TA")
                           (HttpResponse$BodyHandlers/ofInputStream))
              sse-a      (sse-reader (.body ^java.net.http.HttpResponse resp-a))
              resp-b     (GET client (str base "/hyper/events?tab-id=TB")
                           (HttpResponse$BodyHandlers/ofInputStream))
              sse-b      (sse-reader (.body ^java.net.http.HttpResponse resp-b))]
          (try
            (wait-until (:text sse-a) #(str/includes? % "sessionColW") 5000)
            (wait-until (:text sse-b) #(str/includes? % "sessionColW") 5000)
            ;; 1. TA commits cleanly (base = committed = 100) → 250.
            (post-signals! client base commit-a
                           "{\"sessionColW\": 250, \"sessionColWBase\": 100}")
            (let [tb (wait-until (:text sse-b)
                                 #(some (fn [e] (str/includes? e "\"sessionColW\":250")) (signal-events %)) 5000)]
              (is (some #(str/includes? % "\"sessionColW\":250") (signal-events tb))
                  "the other tab on the session receives the committed value"))
            ;; 2. Fence TA with a server write; any echo of 250 would precede it.
            (swap! app* assoc-in [:sessions session-id :data :col-w] 260)
            (let [ta (wait-until (:text sse-a)
                                 #(some (fn [e] (str/includes? e "\"sessionColW\":260")) (signal-events %)) 5000)]
              (is (some #(str/includes? % "\"sessionColW\":260") (signal-events ta)))
              (is (not (some #(str/includes? % "\"sessionColW\":250") (signal-events ta)))
                  "the committing tab's value signal is never echoed back")
              (is (some #(str/includes? % "\"sessionColWBase\":250") (signal-events ta))
                  "but its base signal advances, keeping the next commit clean"))
            ;; 3. TA commits off a stale base (100, committed now 260) → rejected.
            (let [mark (count ((:text sse-a)))]
              (post-signals! client base commit-a
                             "{\"sessionColW\": 400, \"sessionColWBase\": 100}")
              (let [tail (wait-until #(subs ((:text sse-a)) (min mark (count ((:text sse-a)))))
                                     #(some (fn [e] (str/includes? e "\"sessionColW\":260")) (signal-events %)) 5000)]
                (is (some #(str/includes? % "\"sessionColW\":260") (signal-events tail))
                    "the rejected client is corrected back to the committed value")
                (is (not (some #(str/includes? % "\"sessionColW\":400") (signal-events tail)))))
              (is (= 260 (get-in @app* [:sessions session-id :data :col-w]))
                  "the stale commit never lands"))
            (finally
              ((:stop sse-a))
              ((:stop sse-b)))))
        (finally
          (h/stop! stop))))))

(deftest sse-stop-is-prompt-with-open-connection
  (testing "stop! returns promptly even while an SSE connection is open"
    (let [port    (free-port)
          handler (h/create-handler [["/" {:name :home :get (fn [_] [:div "ok"])}]])
          _stop   (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (GET client (str base "/?tab-id=T") (HttpResponse$BodyHandlers/ofString))
      (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                  (HttpResponse$BodyHandlers/ofInputStream))
            {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
        (wait-until text #(str/includes? % "event: connected") 5000)
        ;; stop! must not hang on / throw from the open SSE request.
        (let [t0      (System/currentTimeMillis)
              _       (h/stop! stop)
              elapsed (- (System/currentTimeMillis) t0)]
          (is (< elapsed 5000)
              "stop! should return well under the Jetty graceful-stop timeout"))
        (stop)))))

(deftest sse-async-revealed-by-partial-flushes
  (testing "an async region first revealed during a PARTIAL re-render of its
            parent has its cell watch wired, so the fetch landing streams a
            fragment without waiting for a full render"
    (let [port    (free-port)
          show?*  (atom false)            ; plain atom dep -> partial-only re-render
          gate    (promise)               ; gates the async fetch
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   [:div {:id "page"}
                                    (h/reactive {:key :node} [show?*]
                                                (if @show?*
                                                  [:div
                                                   (h/async {:key :fold} []
                                                            (do @gate :rows)
                                                            {:keys [status result]}
                                                            [:div "fold=" (str status) "/" (str result)])]
                                                  [:div "collapsed"]))])}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        (GET client (str base "/?tab-id=T") (HttpResponse$BodyHandlers/ofString))
        (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                    (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            (wait-until text #(str/includes? % "collapsed") 5000)
            ;; Reveal the async child via a partial-only re-render of the parent.
            (reset! show?* true)
            (wait-until text #(str/includes? % "fold=:loading") 5000)
            (let [mark (count (text))]
              ;; Land the fetch — the ready fragment must stream on its own.
              (deliver gate true)
              (let [t (wait-until #(subs (text) (min mark (count (text))))
                                  #(str/includes? % "fold=:ready") 5000)]
                (is (str/includes? t "fold=:ready")
                    "async revealed by a partial render must flush when its fetch lands")))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))

(deftest sse-instant-async-revealed-by-partial-flushes
  (testing "an async whose fetch completes synchronously, revealed by a partial
            render, still streams its ready fragment (the register->wire race)"
    (let [port    (free-port)
          show?*  (atom false)
          app*    (atom (state/init-state))
          handler (h/create-handler
                    [["/" {:name :home
                           :get  (fn [_]
                                   [:div {:id "page"}
                                    (h/reactive {:key :node} [show?*]
                                                (if @show?*
                                                  [:div
                                                   (h/async {:key :fold} []
                                                            :rows               ; completes immediately
                                                            {:keys [status result]}
                                                            [:div "fold=" (str status) "/" (str result)])]
                                                  [:div "collapsed"]))])}]]
                    :app-state app*)
          stop    (h/start! handler {:port port})
          client  (HttpClient/newHttpClient)
          base    (str "http://localhost:" port)]
      (try
        (GET client (str base "/?tab-id=T") (HttpResponse$BodyHandlers/ofString))
        (let [resp                (GET client (str base "/hyper/events?tab-id=T")
                                    (HttpResponse$BodyHandlers/ofInputStream))
              {:keys [text stop]} (sse-reader (.body ^java.net.http.HttpResponse resp))]
          (try
            (wait-until text #(str/includes? % "collapsed") 5000)
            (let [mark (count (text))]
              (reset! show?* true)
              (let [t (wait-until #(subs (text) (min mark (count (text))))
                                  #(str/includes? % "fold=:ready") 5000)]
                (is (str/includes? t "fold=:ready")
                    "a synchronously-completing async must still stream its ready fragment")))
            (finally
              (stop))))
        (finally
          (h/stop! stop))))))
