(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [reitit.ring :as ring]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [org.httpkit.server :as http-kit]
            [hiccup.core :as hiccup]
            [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.render :as render]))

(defn generate-session-id []
  (str "sess-" (java.util.UUID/randomUUID)))

(defn generate-tab-id []
  (str "tab-" (java.util.UUID/randomUUID)))

(defn wrap-hyper-context
  "Middleware that adds session-id and tab-id to the request."
  [app-state*]
  (fn [handler]
    (fn [req]
      (let [cookies (get req :cookies {})
            existing-session (get-in cookies ["hyper-session" :value])
            session-id (or existing-session (generate-session-id))
            tab-id (or (get-in req [:query-params "tab-id"])
                      (get-in req [:params :tab-id])
                      (generate-tab-id))
            req (assoc req
                       :hyper/session-id session-id
                       :hyper/tab-id tab-id
                       :hyper/app-state app-state*)
            response (handler req)]

        ;; Add session cookie to response if new session
        (if (and response (not existing-session))
          (assoc-in response [:cookies "hyper-session"]
                    {:value session-id
                     :path "/"
                     :http-only true
                     :max-age (* 60 60 24 7)}) ;; 7 days
          response)))))

(defn datastar-script
  "Returns the Datastar CDN script tag."
  []
  [:script {:src "https://cdn.jsdelivr.net/npm/@sudodevnull/datastar"}])

(defn sse-events-handler
  "Handler for SSE event stream."
  [app-state* request-var]
  (fn [req]
    (let [session-id (:hyper/session-id req)
          tab-id (:hyper/tab-id req)]

      (http-kit/as-channel req
        {:on-open (fn [channel]
                    (state/get-or-create-tab! app-state* session-id tab-id)
                    (render/register-sse-channel! app-state* tab-id channel)
                    (render/setup-watchers! app-state* session-id tab-id request-var)

                    (http-kit/send! channel
                                    (str "event: connected\n"
                                         "data: {\"tab-id\":\"" tab-id "\"}\n\n")
                                    false))

         :on-close (fn [_channel _status]
                     (println "Tab disconnected:" tab-id)
                     (render/cleanup-tab! app-state* tab-id))}))))

(defn action-handler
  "Handler for action POST requests."
  [app-state* request-var]
  (fn [req]
    (let [action-id (get-in req [:query-params "action-id"])]

      (if-not action-id
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body "{\"error\": \"Missing action-id\"}"}

        (let [req-with-state (assoc req :hyper/app-state app-state*)]
          (push-thread-bindings {request-var req-with-state})
          (try
            (actions/execute-action! app-state* action-id)

            {:status 200
             :headers {"Content-Type" "application/json"}
             :body "{\"success\": true}"}

            (catch Exception e
              (println "Error executing action:" action-id e)
              {:status 500
               :headers {"Content-Type" "application/json"}
               :body (str "{\"error\": \"" (.getMessage e) "\"}")})
            (finally
              (pop-thread-bindings))))))))

(defn page-handler
  "Wrap a page render function to provide full HTML response."
  [app-state* request-var]
  (fn [render-fn]
    (fn [req]
      (let [tab-id (:hyper/tab-id req)]

        (render/register-render-fn! app-state* tab-id render-fn)

        (let [req-with-state (assoc req :hyper/app-state app-state*)]
          (push-thread-bindings {request-var req-with-state})
          (try
            (let [content (render-fn req-with-state)
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
              (pop-thread-bindings))))))))

(defn create-handler
  "Create a Ring handler for the hyper application.

   routes: Vector of reitit routes (not a router instance)
   app-state*: Atom containing application state
   request-var: Dynamic var to bind request context (e.g., hyper.core/*request*)

   Routes should use :get handlers that return hiccup.
   Hyper will wrap them to provide full HTML responses and SSE connections."
  [routes app-state* request-var]
  (let [page-wrapper (page-handler app-state* request-var)
        ;; Transform user routes to wrap GET handlers with page-handler
        wrapped-routes (mapv (fn [[path route-data]]
                               [path (update route-data :get
                                             (fn [handler]
                                               (when handler
                                                 (page-wrapper handler))))])
                             routes)
        ;; Combine with hyper system routes
        all-routes (concat
                    [["/hyper/events" {:get (sse-events-handler app-state* request-var)}]
                     ["/hyper/actions" {:post (action-handler app-state* request-var)}]]
                    wrapped-routes)
        router (ring/router all-routes {:conflicts nil})]

    (-> (ring/ring-handler
         router
         (ring/create-default-handler))
        ((wrap-hyper-context app-state*))
        (keyword-params/wrap-keyword-params)
        (params/wrap-params))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   port: Port to run on (default: 3000)

   Returns server instance."
  [handler {:keys [port] :or {port 3000}}]
  (let [server (http-kit/run-server handler {:port port})]
    (println "Hyper server started on port" port)
    server))

(defn stop!
  "Stop the HTTP server."
  [server]
  (when server
    (server :timeout 100)
    (println "Hyper server stopped")))
