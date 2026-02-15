(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [reitit.ring :as ring]
            [reitit.core :as reitit]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as malli]
            [reitit.ring.coercion :as ring-coercion]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.cookies :as cookies]
            [org.httpkit.server :as http-kit]
            [hiccup.core :as hiccup]
            [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.render :as render]
            [taoensso.telemere :as t]
            [clojure.string]
            [clojure.edn :as edn]))

(defn generate-session-id []
  (str "sess-" (java.util.UUID/randomUUID)))

(defn generate-tab-id []
  (str "tab-" (java.util.UUID/randomUUID)))

(defn serialize-session-data
  "Serialize session data to a string for cookie storage."
  [session-data]
  (when session-data
    (try
      (pr-str session-data)
      (catch Exception _
        nil))))

(defn deserialize-session-data
  "Deserialize session data from a cookie string."
  [cookie-value]
  (when (and cookie-value (not (empty? cookie-value)))
    (try
      (edn/read-string cookie-value)
      (catch Exception _
        {}))))

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
                       :hyper/app-state app-state*)]

        ;; Add hyper context to telemere for all downstream logging
        (t/with-ctx+ {:hyper/session-id session-id
                      :hyper/tab-id tab-id}
          (let [response (handler req)]
            ;; Add session cookie to response if new session
            (if (and response (not existing-session))
              (assoc-in response [:cookies "hyper-session"]
                        {:value session-id
                         :path "/"
                         :http-only true
                         :max-age (* 60 60 24 7)}) ;; 7 days
              response)))))))

(defn datastar-script
  "Returns the Datastar CDN script tag."
  []
  [:script {:type "module"
            :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}])

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
                    (http-kit/send!
                      channel
                      {:headers {"Content-Type" "text/event-stream"}
                       :body    (str "event: connected\n"
                                     "data: {\"tab-id\":\"" tab-id "\"}\n\n")}
                      false))

         :on-close (fn [_channel _status]
                     (t/log! {:level :info
                              :id :hyper.event/tab-disconnect
                              :data {:hyper/tab-id tab-id}
                              :msg "Tab disconnected"})
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

        (let [req-with-state (assoc req
                                    :hyper/app-state app-state*
                                    :hyper/router (get @app-state* :router))]
          (push-thread-bindings {request-var req-with-state})
          (try
            (actions/execute-action! app-state* action-id)

            {:status 200
             :headers {"Content-Type" "application/json"}
             :body "{\"success\": true}"}

            (catch Exception e
              (t/error! e
                        {:id :hyper.error/action-handler
                         :data {:hyper/action-id action-id}})
              {:status 500
               :headers {"Content-Type" "application/json"}
               :body (str "{\"error\": \"" (.getMessage e) "\"}")})
            (finally
              (pop-thread-bindings))))))))

(defn find-render-fn
  "Find the original (unwrapped) render fn for a named route.
   Looks up the :get handler from the routes vector by :name."
  [routes route-name]
  (->> routes
       (some (fn [[_path data]]
               (when (= route-name (:name data))
                 (:get data))))))

(defn- extract-route-info
  "Extract route info from a reitit match and Ring request.
   Uses coerced parameters from reitit's coercion middleware when available,
   falling back to raw query params for routes without parameter specs."
  [req]
  (let [match (:reitit.core/match req)
        route-name (get-in match [:data :name])
        path (:uri req)
        ;; Prefer coerced parameters (set by reitit coercion middleware)
        coerced-path-params (get-in req [:parameters :path])
        coerced-query-params (get-in req [:parameters :query])
        ;; Fall back to raw params for routes without coercion specs
        raw-path-params (:path-params match)
        raw-query-params (into {}
                               (map (fn [[k v]] [(keyword k) v]))
                               (:query-params req))]
    {:name route-name
     :path path
     :path-params (or coerced-path-params raw-path-params {})
     :query-params (or coerced-query-params raw-query-params {})}))

(defn- hyper-scripts
  "JavaScript for SPA navigation support:
   - MutationObserver on #hyper-app watches data-hyper-url attribute changes
     and calls replaceState to sync the browser URL bar.
   - popstate listener handles browser back/forward by posting to /hyper/navigate."
  [tab-id]
  [:script
   (str "
(function() {
  var appEl = document.getElementById('hyper-app');
  if (appEl) {
    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        if (mutations[i].attributeName === 'data-hyper-url') {
          var url = appEl.getAttribute('data-hyper-url');
          if (url && url !== window.location.pathname + window.location.search) {
            window.history.replaceState({}, '', url);
          }
        }
      }
    });
    observer.observe(appEl, { attributes: true, attributeFilter: ['data-hyper-url'] });
  }
  window.addEventListener('popstate', function(e) {
    fetch('/hyper/navigate?tab-id=" tab-id "&path=' + encodeURIComponent(window.location.pathname + window.location.search), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    });
  });
})();
")])

(defn page-handler
  "Wrap a page render function to provide full HTML response."
  [app-state* request-var]
  (fn [render-fn]
    (fn [req]
      (let [tab-id (:hyper/tab-id req)
            route-info (extract-route-info req)]

        (render/register-render-fn! app-state* tab-id render-fn)
        (state/set-tab-route! app-state* tab-id route-info)

        (let [req-with-state (-> req
                                 (assoc :hyper/app-state app-state*
                                        :hyper/router (get @app-state* :router)
                                        :hyper/route-match (:reitit.core/match req))
                                 (dissoc :reitit.core/match))]
          (push-thread-bindings {request-var req-with-state})
          (try
            (let [content (render-fn req-with-state)
                  html    (hiccup/html
                            [:html
                             [:head
                              [:meta {:charset "UTF-8"}]
                              [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                              [:title "Hyper App"]
                              (datastar-script)]
                             [:body
                              {:data-init (str "@get('/hyper/events?tab-id=" tab-id "', {openWhenHidden: true})")}
                              [:div {:id "hyper-app"}
                               content]
                              (hyper-scripts tab-id)]])]
              {:status  200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body    html})
            (finally
              (pop-thread-bindings))))))))

(defn- navigate-handler
  "Handler for popstate/navigation POST requests.
   Looks up the route for the given path and updates the tab's route + render fn."
  [app-state* request-var]
  (fn [req]
    (let [path (get-in req [:query-params "path"])
          tab-id (:hyper/tab-id req)
          session-id (:hyper/session-id req)
          router (get @app-state* :router)
          routes (get @app-state* :routes)]
      (if-not (and path tab-id router)
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body "{\"error\": \"Missing path or tab-id\"}"}

        ;; Parse path and query string
        (let [[path-part query-string] (clojure.string/split path #"\?" 2)
              raw-query-params (state/parse-query-string query-string)
              ;; Match the path using the reitit router
              match (reitit/match-by-path router path-part)]
          (if-not match
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body "{\"error\": \"Route not found\"}"}

            (let [route-name (get-in match [:data :name])
                  ;; Coerce parameters using reitit's coercion if the route has specs
                  coerced (try
                            (coercion/coerce!
                              (assoc match :query-params raw-query-params))
                            (catch Exception _e nil))
                  query-params (or (:query coerced) raw-query-params {})
                  path-params (or (:path coerced) (:path-params match) {})
                  render-fn (find-render-fn routes route-name)]
              (when render-fn
                (render/register-render-fn! app-state* tab-id render-fn))
              ;; Setting the route triggers the route watcher,
              ;; which handles re-rendering and URL updates via SSE
              (state/set-tab-route! app-state* tab-id
                                    {:name route-name
                                     :path path-part
                                     :path-params path-params
                                     :query-params query-params})
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body "{\"success\": true}"})))))))

(defn- build-ring-handler
  "Build the Ring handler for the given user routes.
   Wraps user :get handlers with page-handler, combines with hyper system routes,
   compiles the reitit router, and updates app-state with the current routes and router.
   Returns the compiled Ring handler (without outer middleware)."
  [user-routes app-state* page-wrapper system-routes]
  (let [wrapped-routes (mapv (fn [[path route-data]]
                               [path (update route-data :get
                                             (fn [handler]
                                               (when handler
                                                 (page-wrapper handler))))])
                             user-routes)
        all-routes (concat system-routes wrapped-routes)
        router (ring/router all-routes
                            {:conflicts nil
                             :data {:coercion malli/coercion
                                    :middleware [ring-coercion/coerce-request-middleware]}})]
    ;; Store routes and router in app-state for access during actions/renders/navigation
    (swap! app-state* assoc
           :router router
           :routes user-routes)
    (ring/ring-handler router (ring/create-default-handler))))

(defn create-handler
  "Create a Ring handler for the hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.
   app-state*: Atom containing application state
   request-var: Dynamic var to bind request context (e.g., hyper.core/*request*)

   Routes should use :get handlers that return hiccup.
   Hyper will wrap them to provide full HTML responses and SSE connections."
  [routes app-state* request-var]
  (let [page-wrapper (page-handler app-state* request-var)
        system-routes [["/hyper/events" {:get (sse-events-handler app-state* request-var)}]
                       ["/hyper/actions" {:post (action-handler app-state* request-var)}]
                       ["/hyper/navigate" {:post (navigate-handler app-state* request-var)}]]
        initial-routes (if (var? routes) @routes routes)
        initial-handler (build-ring-handler initial-routes app-state* page-wrapper system-routes)
        handler (if (var? routes)
                  ;; Dynamic: rebuild router when the routes Var is redefined.
                  ;; Uses identical? since a re-def always creates a new object,
                  ;; avoiding deep equality checks on every request.
                  (let [cached (atom {:routes initial-routes
                                      :handler initial-handler})]
                    (fn [req]
                      (let [current-routes @routes]
                        (when-not (identical? current-routes (:routes @cached))
                          (t/log! {:level :info
                                   :id :hyper.event/routes-reload
                                   :msg "Routes changed, rebuilding router"})
                          (let [h (build-ring-handler current-routes app-state*
                                                     page-wrapper system-routes)]
                            (reset! cached {:routes current-routes :handler h})))
                        ((:handler @cached) req))))
                  ;; Static: use the compiled handler directly
                  initial-handler)]
    (-> handler
        ((wrap-hyper-context app-state*))
        (keyword-params/wrap-keyword-params)
        (params/wrap-params)
        (cookies/wrap-cookies))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   port: Port to run on (default: 3000)

   Returns server instance."
  [handler {:keys [port] :or {port 3000}}]
  (let [server (http-kit/run-server handler {:port port})]
    (t/log! {:level :info
             :id :hyper.event/server-start
             :data {:hyper/port port}
             :msg "Hyper server started"})
    server))

(defn stop!
  "Stop the HTTP server."
  [server]
  (when server
    (server :timeout 100)
    (t/log! {:level :info
             :id :hyper.event/server-stop
             :msg "Hyper server stopped"})))
