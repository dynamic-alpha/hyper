(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handlers for:
   - Initial page loads
   - SSE event streams
   - Action POST endpoints"
  (:require [reitit.ring :as ring]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [org.httpkit.server :as http-kit]
            [hiccup.core :as hiccup]
            [hyper.actions :as actions]
            [hyper.render :as render]))

(defn generate-session-id []
  (str "sess-" (java.util.UUID/randomUUID)))

(defn generate-tab-id []
  (str "tab-" (java.util.UUID/randomUUID)))

(defn wrap-hyper-context
  "Middleware that adds session-id and tab-id to the request.

   - Reads session-id from cookie or creates new one
   - Reads tab-id from query param or creates new one
   - Adds both to request under :hyper/session-id and :hyper/tab-id"
  [handler]
  (fn [req]
    (let [;; Get or create session-id (from cookie or new)
          cookies (get req :cookies {})
          existing-session (get-in cookies ["hyper-session" :value])
          session-id (or existing-session (generate-session-id))

          ;; Get or create tab-id (from query param or new)
          tab-id (or (get-in req [:query-params "tab-id"])
                     (get-in req [:params :tab-id])
                     (generate-tab-id))

          ;; Add to request
          req (assoc req
                     :hyper/session-id session-id
                     :hyper/tab-id tab-id)

          ;; Call handler
          response (handler req)]

      ;; Add session cookie to response if new session
      (if (and response (not existing-session))
        (assoc-in response [:cookies "hyper-session"]
                  {:value session-id
                   :path "/"
                   :http-only true
                   :max-age (* 60 60 24 7)}) ;; 7 days
        response))))

(defn datastar-script
  "Returns the Datastar CDN script tag."
  []
  [:script {:src "https://cdn.jsdelivr.net/npm/@sudodevnull/datastar"}])

(defn initial-page-handler
  "Handler for initial page load.
   Renders the page and includes Datastar setup."
  [render-fn]
  (fn [req]
    (let [_session-id (:hyper/session-id req)
          tab-id (:hyper/tab-id req)]

      ;; Register render function for this tab
      (render/register-render-fn! tab-id render-fn)

      ;; Render initial content
      (let [request-var (requiring-resolve 'hyper.core/*request*)]
        (push-thread-bindings {request-var req})
        (try
          (let [content (render-fn req)
                html (hiccup/html
                      [:html
                       [:head
                        [:meta {:charset "UTF-8"}]
                        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                        [:title "Hyper App"]
                        (datastar-script)]
                       [:body {:data-on-load (str "$$get('/hyper/events?tab-id=" tab-id "')")}
                        content]])]
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body html})
          (finally
            (pop-thread-bindings)))))))

(defn sse-events-handler
  "Handler for SSE event stream.
   Connects the tab to receive state updates."
  [req]
  (let [session-id (:hyper/session-id req)
        tab-id (:hyper/tab-id req)]

    (http-kit/as-channel req
      {:on-open (fn [channel]
                  ;; Register SSE channel
                  (render/register-sse-channel! tab-id channel)

                  ;; Setup watchers for this tab
                  (render/setup-watchers! session-id tab-id)

                  ;; Send initial connection message
                  (http-kit/send! channel
                                  (str "event: connected\n"
                                       "data: {\"tab-id\":\"" tab-id "\"}\n\n")
                                  false))

       :on-close (fn [_channel _status]
                   (println "Tab disconnected:" tab-id)
                   (render/cleanup-tab! session-id tab-id))})))

(defn action-handler
  "Handler for action POST requests.
   Executes the action and returns success."
  [req]
  (let [session-id (:hyper/session-id req)
        _tab-id (:hyper/tab-id req)
        action-id (get-in req [:query-params "action-id"])]

    (if-not action-id
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"error\": \"Missing action-id\"}"}

      (let [request-var (requiring-resolve 'hyper.core/*request*)]
        (push-thread-bindings {request-var req})
        (try
          ;; Execute action with request context
          (actions/execute-action! session-id action-id)

          ;; Return success
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body "{\"success\": true}"}

          (catch Exception e
            (println "Error executing action:" action-id e)
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (str "{\"error\": \"" (.getMessage e) "\"}")})
          (finally
            (pop-thread-bindings)))))))

(defn create-app
  "Create the Ring application with Reitit routing."
  [render-fn]
  (ring/ring-handler
   (ring/router
    [["/hyper/events" {:get sse-events-handler}]
     ["/hyper/actions" {:post action-handler}]
     ["/" {:get (initial-page-handler render-fn)}]])
   (ring/create-default-handler)))

(defn create-handler
  "Create the complete handler with middleware."
  [render-fn]
  (-> (create-app render-fn)
      (wrap-hyper-context)
      (keyword-params/wrap-keyword-params)
      (params/wrap-params)))

(defonce server-instance (atom nil))

(defn start-server!
  "Start the HTTP server."
  [render-fn {:keys [port] :or {port 3000}}]
  (when @server-instance
    (throw (ex-info "Server already running" {})))

  (let [handler (create-handler render-fn)
        server (http-kit/run-server handler {:port port})]
    (reset! server-instance server)
    (println "Hyper server started on port" port)
    server))

(defn stop-server!
  "Stop the HTTP server."
  []
  (when-let [server @server-instance]
    (server :timeout 100)
    (reset! server-instance nil)
    (println "Hyper server stopped")))
