(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [cheshire.core :as json]
            [clojure.string]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.brotli :as br]
            [hyper.context :as context]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.watch :as watch]
            [org.httpkit.server :as http-kit]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as malli]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.file :as file]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [taoensso.telemere :as t])
  (:import (java.util.concurrent Semaphore)))

(defn generate-session-id []
  (str "sess-" (java.util.UUID/randomUUID)))

(defn generate-tab-id []
  (str "tab-" (java.util.UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Per-tab renderer thread
;; ---------------------------------------------------------------------------

(def ^:private default-render-throttle-ms
  "Minimum interval between renders for a single tab (~60fps)."
  16)

(defn- -renderer-loop!
  "Virtual-thread render loop for a single tab.

   Owns the http-kit AsyncChannel and (optional) streaming Brotli state,
   guaranteeing single-writer semantics by construction.

   Blocks on a Semaphore until signalled by a watcher, then renders the
   latest state via render/render-tab, compresses (if enabled), and sends.
   Exits when shutdown-renderer* is delivered."
  [app-state* session-id tab-id channel compress?
   ^Semaphore semaphore shutdown-renderer*]
  (let [br-out      (when compress? (br/byte-array-out-stream))
        br-stream   (when br-out (br/compress-out-stream br-out :window-size 18))
        headers     (cond-> {"Content-Type" "text/event-stream"}
                      compress? (assoc "Content-Encoding" "br"))
        throttle-ms (long (or (get @app-state* :render-throttle-ms)
                              default-render-throttle-ms))]
    (try
      ;; Send the connected event as the initial SSE response (headers + body).
      (let [connected-msg (render/format-connected-event tab-id)
            payload       (if br-stream
                            (br/compress-stream br-out br-stream connected-msg)
                            connected-msg)
            sent?         (boolean (http-kit/send! channel {:headers headers
                                                            :body    payload}
                                                   false))]
        (when sent?
          ;; Main render loop
          (loop []
            (.acquire semaphore)
            (.drainPermits semaphore)
            (when-not (realized? shutdown-renderer*)
              (let [sent? (try
                            ;; Clean slate — remove stale actions before re-rendering
                            (actions/cleanup-tab-actions! app-state* tab-id)
                            (when-let [{:keys [title head body url]}
                                       (render/render-tab app-state* session-id tab-id)]
                              (let [head-html   (some-> head c/html)
                                    head-event  (render/format-head-update title head-html)
                                    div-attrs   (cond-> {:id "hyper-app"}
                                                  url (assoc :data-hyper-url url))
                                    body-html   (c/html [:div div-attrs body])
                                    body-event  (render/format-datastar-fragment body-html)
                                    sse-payload (str head-event body-event)
                                    payload     (if br-stream
                                                  (br/compress-stream br-out br-stream sse-payload)
                                                  sse-payload)]
                                (boolean (http-kit/send! channel payload false))))
                            (catch Throwable e
                              (t/error! e {:id   :hyper.error/renderer
                                           :data {:hyper/tab-id tab-id}})
                              nil))]
                ;; sent? is true (ok), nil (no render-fn or error), false (channel closed)
                (when-not (false? sent?)
                  ;; Throttle: sleep so triggers during this window accumulate
                  ;; as semaphore permits, which drainPermits collapses into
                  ;; a single render on the next iteration.
                  (Thread/sleep throttle-ms)
                  (recur)))))))
      (catch Throwable e
        (when-not (realized? shutdown-renderer*)
          (t/error! e {:id   :hyper.error/renderer
                       :data {:hyper/tab-id tab-id}})))
      (finally
        (br/close-stream br-stream)
        (when (instance? org.httpkit.server.AsyncChannel channel)
          (t/catch->error! :hyper.error/close-sse-channel
                           (http-kit/close channel)))
        (t/log! {:level :debug
                 :id    :hyper.event/renderer-close
                 :data  {:hyper/tab-id tab-id}
                 :msg   "Tab renderer closed"})))))

(defn- -start-renderer!
  "Start a per-tab renderer on a virtual thread.

   Returns a map with:
   - :trigger-render! — zero-arg fn; call to signal a re-render
   - :stop!           — zero-arg fn; call to shut down the renderer"
  [app-state* session-id tab-id channel compress?]
  (let [semaphore          (Semaphore. 0)
        shutdown-renderer* (promise)
        trigger-render!    #(.release semaphore)
        stop!              #(do (deliver shutdown-renderer* true)
                                (.release semaphore))
        thread             (-> (Thread/ofVirtual)
                               (.name (str "hyper-renderer-" tab-id))
                               (.start ^Runnable
                                 #(-renderer-loop! app-state* session-id tab-id
                                                   channel compress?
                                                   semaphore shutdown-renderer*)))]
    {:trigger-render! trigger-render!
     :stop!           stop!
     :thread          thread}))

(defn wrap-hyper-context
  "Middleware that adds session-id and tab-id to the request."
  [app-state*]
  (fn [handler]
    (fn [req]
      (let [cookies          (get req :cookies {})
            existing-session (get-in cookies ["hyper-session" :value])
            session-id       (or existing-session (generate-session-id))
            tab-id           (or (get-in req [:query-params "tab-id"])
                                 (get-in req [:params :tab-id])
                                 (generate-tab-id))
            req              (assoc req
                                    :hyper/session-id session-id
                                    :hyper/tab-id tab-id
                                    :hyper/app-state app-state*)]

        ;; Add hyper context to telemere for all downstream logging
        (t/with-ctx+ {:hyper/session-id session-id
                      :hyper/tab-id     tab-id}
          (let [response (handler req)]
            ;; Add session cookie to response if new session
            (if (and response (not existing-session))
              (assoc-in response [:cookies "hyper-session"]
                        {:value     session-id
                         :path      "/"
                         :http-only true
                         :max-age   (* 60 60 24 7)}) ;; 7 days
              response)))))))

(defn datastar-script
  "Returns the Datastar CDN script tag."
  []
  [:script {:type "module"
            :src  "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}])

(defn cleanup-tab!
  "Clean up all resources for a tab: watchers, renderer thread, actions, and state."
  [app-state* tab-id]
  (watch/remove-watchers! app-state* tab-id)
  (watch/remove-external-watches! app-state* tab-id)
  (watch/teardown-route-watches! app-state* tab-id)
  (when-let [stop! (get-in @app-state* [:tabs tab-id :renderer :stop!])]
    (stop!))
  (actions/cleanup-tab-actions! app-state* tab-id)
  (state/cleanup-tab! app-state* tab-id)
  nil)

(defn sse-events-handler
  "Handler for SSE event stream.
   Starts a per-tab renderer thread that owns the channel and optional
   brotli stream, then wires up watchers to trigger re-renders."
  [app-state*]
  (fn [req]
    (let [session-id (:hyper/session-id req)
          tab-id     (:hyper/tab-id req)
          compress?  (br/accepts-br? req)]

      (http-kit/as-channel req
                           {:on-open  (fn [channel]
                                        (state/get-or-create-tab! app-state* session-id tab-id)

                                        ;; Start the renderer — it sends the connected event
                                        ;; and then blocks until triggered.
                                        (let [{:keys [trigger-render!] :as renderer}
                                              (-start-renderer! app-state* session-id tab-id
                                                                channel compress?)]
                                          (swap! app-state* assoc-in [:tabs tab-id :renderer] renderer)

                                          (watch/setup-watchers! app-state* session-id tab-id trigger-render!)
                                          ;; Auto-watch the routes Var so title/route changes
                                          ;; trigger re-renders for all connected tabs
                                          (when-let [routes-source (get @app-state* :routes-source)]
                                            (when (var? routes-source)
                                              (watch/watch-source! app-state* tab-id trigger-render! routes-source)))
                                          ;; Set up route-level watches (:watches + Var :get handlers)
                                          (watch/setup-route-watches! app-state* tab-id trigger-render!)))

                            :on-close (fn [_channel _status]
                                        (t/log! {:level :info
                                                 :id    :hyper.event/tab-disconnect
                                                 :data  {:hyper/tab-id tab-id}
                                                 :msg   "Tab disconnected"})
                                        (cleanup-tab! app-state* tab-id))}))))

(defn- parse-client-params
  "Parse client params from a JSON request body, if present.
   Returns a keyword-keyed map or nil."
  [req]
  (try
    (when-let [body (:body req)]
      (let [s (if (string? body) body (slurp body))]
        (when-not (clojure.string/blank? s)
          (json/parse-string s true))))
    (catch Exception _ nil)))

(defn action-handler
  "Handler for action POST requests.
   Parses an optional JSON body for client params ($value, $checked, $key,
   $form-data) and passes them to the action function."
  [app-state*]
  (fn [req]
    (let [action-id (get-in req [:query-params "action-id"])]

      (if-not action-id
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing action-id\"}"}

        (let [req-with-state (assoc req
                                    :hyper/app-state app-state*
                                    :hyper/router (get @app-state* :router))
              client-params  (parse-client-params req)]
          (push-thread-bindings {#'context/*request* req-with-state})
          (try
            (actions/execute-action! app-state* action-id client-params)

            {:status  200
             :headers {"Content-Type" "application/json"}
             :body    "{\"success\": true}"}

            (catch Exception e
              (t/error! e
                        {:id   :hyper.error/action-handler
                         :data {:hyper/action-id action-id}})
              {:status  500
               :headers {"Content-Type" "application/json"}
               :body    (json/generate-string {:error (.getMessage e)})})
            (finally
              (pop-thread-bindings))))))))

(defn- extract-route-info
  "Extract route info from a reitit match and Ring request.
   Uses coerced parameters from reitit's coercion middleware when available,
   falling back to raw query params for routes without parameter specs."
  [req]
  (let [match                (:reitit.core/match req)
        route-name           (get-in match [:data :name])
        path                 (:uri req)
        ;; Prefer coerced parameters (set by reitit coercion middleware)
        coerced-path-params  (get-in req [:parameters :path])
        coerced-query-params (get-in req [:parameters :query])
        ;; Fall back to raw params for routes without coercion specs
        raw-path-params      (:path-params match)
        raw-query-params     (into {}
                                   (map (fn [[k v]] [(keyword k) v]))
                                   (:query-params req))]
    {:name         route-name
     :path         path
     :path-params  (or coerced-path-params raw-path-params {})
     :query-params (or coerced-query-params raw-query-params {})}))

(defn- hyper-scripts
  "JavaScript for SPA navigation support:
   - MutationObserver on #hyper-app watches data-hyper-url attribute changes
     and syncs the browser URL bar via replaceState. Title syncing is handled
     server-side by re-rendering the full <head> (including <title>) via SSE.
   - popstate listener handles browser back/forward by posting to /hyper/navigate
     and restoring document.title from history state.

   Uses c/raw to prevent Chassis from HTML-escaping the JavaScript content
   (e.g., && would become &amp;&amp; which breaks JS syntax)."
  [tab-id]
  [:script
   (c/raw
     (str "
(function() {
  var appEl = document.getElementById('hyper-app');
  if (appEl) {
    // Seed the initial history entry with the current title so back-navigation restores it
    window.history.replaceState({title: document.title}, '', window.location.href);
    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        if (mutations[i].attributeName === 'data-hyper-url') {
          var url = appEl.getAttribute('data-hyper-url');
          if (url && url !== window.location.pathname + window.location.search) {
            window.history.replaceState({title: document.title}, '', url);
          }
          break;
        }
      }
    });
    observer.observe(appEl, { attributes: true, attributeFilter: ['data-hyper-url'] });
  }
  window.addEventListener('popstate', function(e) {
    if (e.state && e.state.title) {
      document.title = e.state.title;
    }
    fetch('/hyper/navigate?tab-id=" tab-id "&path=' + encodeURIComponent(window.location.pathname + window.location.search), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    });
  });
})();
"))])

(defn page-handler
  "Wrap a page render function to provide full HTML response.
   Delegates rendering to render/render-tab so initial page loads share
   the same render pipeline as SSE updates (error boundaries, live route
   re-resolution, title/head resolution).

   Options:
   - :head Hiccup nodes to append into the <head>, or (fn [req] ...) -> hiccup.
           When head is a function, it is re-evaluated on each SSE render cycle
           and the full <head> is pushed to the client."
  [app-state* _opts]
  (fn [render-fn]
    (fn [req]
      (let [tab-id     (:hyper/tab-id req)
            session-id (:hyper/session-id req)
            route-info (extract-route-info req)]

        (render/register-render-fn! app-state* tab-id render-fn)
        (state/set-tab-route! app-state* tab-id route-info)

        (let [{:keys [title head body]} (render/render-tab app-state* session-id tab-id req)]
          ;; If the render fn returns a Ring response map (e.g. a 302
          ;; redirect), pass it through without wrapping in HTML.
          (if (and (map? body) (:status body))
            body
            (let [title      (or title "Hyper App")
                  extra-head (when head (c/html head))
                  html       (c/html
                               [c/doctype-html5
                                [:html
                                 [:head
                                  [:meta {:charset "UTF-8"}]
                                  [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                  [:title title]
                                  (datastar-script)
                                  extra-head]
                                 [:body
                                  {:data-init (str "@get('/hyper/events?tab-id=" tab-id "', {openWhenHidden: true})")}
                                  [:div {:id "hyper-app"} body]
                                  (hyper-scripts tab-id)]]])]
              {:status  200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body    html})))))))

(defn- navigate-handler
  "Handler for popstate/navigation POST requests.
   Looks up the route for the given path and updates the tab's route + render fn."
  [app-state*]
  (fn [req]
    (let [path        (get-in req [:query-params "path"])
          tab-id      (:hyper/tab-id req)
          _session-id (:hyper/session-id req)
          router      (get @app-state* :router)
          route-index (routes/live-route-index app-state*)]
      (if-not (and path tab-id router)
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing path or tab-id\"}"}

        ;; Parse path and query string
        (let [[path-part query-string] (clojure.string/split path #"\?" 2)
              raw-query-params         (state/parse-query-string query-string)
              ;; Match the path using the reitit router
              match                    (reitit/match-by-path router path-part)]
          (if-not match
            {:status  404
             :headers {"Content-Type" "application/json"}
             :body    "{\"error\": \"Route not found\"}"}

            (let [route-name   (get-in match [:data :name])
                  ;; Coerce parameters using reitit's coercion if the route has specs
                  coerced      (try
                                 (coercion/coerce!
                                   (assoc match :query-params raw-query-params))
                                 (catch Exception _e nil))
                  query-params (or (:query coerced) raw-query-params {})
                  path-params  (or (:path coerced) (:path-params match) {})
                  render-fn    (routes/find-render-fn route-index route-name)]
              (when render-fn
                (render/register-render-fn! app-state* tab-id render-fn))
              ;; Setting the route triggers the route watcher,
              ;; which handles re-rendering and URL updates via SSE
              (state/set-tab-route! app-state* tab-id
                                    {:name         route-name
                                     :path         path-part
                                     :path-params  path-params
                                     :query-params query-params})
              {:status  200
               :headers {"Content-Type" "application/json"}
               :body    "{\"success\": true}"})))))))

(defn- build-ring-handler
  "Build the Ring handler for the given user routes.
   Wraps user :get handlers with page-handler, combines with hyper system routes,
   compiles the reitit router, and updates app-state with the current routes and router.
   Returns the compiled Ring handler (without outer middleware)."
  [user-routes app-state* page-wrapper system-routes]
  (let [wrapped-routes (mapv (fn [[path route-data]]
                               (if (and (:get route-data) (not (:hyper/disabled? route-data)))
                                 [path (update route-data :get (fn [handler] (page-wrapper handler)))]
                                 [path route-data]))
                             user-routes)
        all-routes     (concat system-routes wrapped-routes)
        router         (ring/router all-routes
                                    {:conflicts nil
                                     :data      {:coercion   malli/coercion
                                                 :middleware [ring-coercion/coerce-request-middleware]}})]
    ;; Store routes and router in app-state for access during actions/renders/navigation
    (swap! app-state* assoc
           :router router
           :routes user-routes)
    (ring/ring-handler router (ring/create-default-handler))))

(defn- wrap-static
  "Optionally serve static assets.

   This is intentionally lightweight: it makes it easy for Hyper consumers to
   serve precompiled assets (e.g. Tailwind CSS output) without Hyper needing to
   know about the asset pipeline.

   Options:
   - :static-resources  Classpath resource root(s) (e.g. \"public\"), served by URI
   - :static-dir        Filesystem directory (or directories) to serve (useful in dev)

   When enabled, adds content-type + not-modified support."
  [handler {:keys [static-resources static-dir]}]
  (let [resource-roots (cond
                         (nil? static-resources) nil
                         (sequential? static-resources) static-resources
                         :else [static-resources])
        static-dirs    (cond
                         (nil? static-dir) nil
                         (sequential? static-dir) static-dir
                         :else [static-dir])
        handler'       (cond-> handler
                         (seq resource-roots) (as-> h
                                                    (reduce (fn [acc root]
                                                              (resource/wrap-resource acc root))
                                                            h
                                                            resource-roots))
                         (seq static-dirs) (as-> h
                                                 (reduce (fn [acc dir]
                                                           (file/wrap-file acc dir))
                                                         h
                                                         static-dirs)))]
    (if (or (seq resource-roots) (seq static-dirs))
      (-> handler'
          (content-type/wrap-content-type)
          (not-modified/wrap-not-modified))
      handler)))

(defn create-handler
  "Create a Ring handler for the hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.
   app-state*: Atom containing application state

   Options:
   - :head              Hiccup nodes appended to the <head> (e.g. stylesheet <link>),
                        or (fn [req] ...) -> hiccup nodes appended to the <head>
   - :static-resources  Classpath resource root(s) to serve as static assets
   - :static-dir        Filesystem directory (or directories) to serve as static assets
   - :watches           Vector of Watchable sources added to every page route.
                        Useful for top-level atoms that should trigger a re-render
                        on any page (e.g. a global config or feature-flags atom).

   Routes should use :get handlers that return hiccup (Chassis vectors).
   Hyper will wrap them to provide full HTML responses and SSE connections."
  ([routes app-state*]
   (create-handler routes app-state* {}))
  ([routes app-state* {:keys [watches head] :as opts}]
   (let [page-wrapper                             (page-handler app-state* opts)
         system-routes                            [["/hyper/events" {:get (sse-events-handler app-state*)}]
                                                   ["/hyper/actions" {:post (action-handler app-state*)}]
                                                   ["/hyper/navigate" {:post (navigate-handler app-state*)}]]
         ;; Store the routes source (Var or value) so title resolution can
         ;; always read the latest route metadata, even between router rebuilds.
         ;; Store global :watches so find-route-watches can prepend them to
         ;; every page route's watch list.
         _                                        (swap! app-state* assoc
                                                         :routes-source routes
                                                         :global-watches (vec watches)
                                                         :head head)
         initial-routes                           (if (var? routes) @routes routes)
         initial-handler                          (build-ring-handler initial-routes app-state* page-wrapper system-routes)
         handler                                  (if (var? routes)
                   ;; Dynamic: rebuild router when the routes Var is redefined.
                   ;; Uses identical? since a re-def always creates a new object,
                   ;; avoiding deep equality checks on every request.
                                                    (let [cached (atom {:routes  initial-routes
                                                                        :handler initial-handler})]
                                                      (fn [req]
                                                        (let [current-routes @routes]

                                                          (when-not (identical? current-routes (:routes @cached))
                                                            (t/log! {:level :info
                                                                     :id    :hyper.event/routes-reload
                                                                     :msg   "Routes changed, rebuilding router"})
                                                            (let [h (build-ring-handler current-routes app-state*
                                                                                        page-wrapper system-routes)]
                                                              (reset! cached {:routes current-routes :handler h})))
                                                          ((:handler @cached) req))))
                   ;; Static: use the compiled handler directly
                                                    initial-handler)
         handler-with-mw
         (-> handler
             ((wrap-hyper-context app-state*))
             (br/wrap-brotli)
             (keyword-params/wrap-keyword-params)
             (params/wrap-params)
             (cookies/wrap-cookies))]
     ;; Static middleware should be outermost so static requests avoid params/cookies.
     ;; Attach app-state* as metadata so start! can build a stop fn that cleans up.
     (with-meta (wrap-static handler-with-mw opts)
       {::app-state app-state*}))))

(defn- -do-stop
  "Stop the HTTP server and clean up all tab resources.
   Tears down all watchers, renderer threads, SSE channels, and actions."
  [server app-state*]
  (when server
    (server :timeout 100))
  (when app-state*
    (let [tab-ids (keys (:tabs @app-state*))]
      (doseq [tab-id tab-ids]
        (cleanup-tab! app-state* tab-id))
      (t/log! {:level :info
               :id    :hyper.event/server-stop
               :data  {:hyper/tab-count (count tab-ids)}
               :msg   "Hyper server stopped"}))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   port: Port to run on (default: 3000)

   Returns a stop function. Call (stop-fn) or pass to stop! to shut down
   the server and clean up all tab resources (renderer threads, watchers, actions)."
  [handler {:keys [port] :or {port 3000}}]
  (let [server     (http-kit/run-server handler {:port port})
        app-state* (::app-state (meta handler))]
    (t/log! {:level :info
             :id    :hyper.event/server-start
             :data  {:hyper/port port}
             :msg   "Hyper server started"})
    (partial -do-stop server app-state*)))

(defn stop!
  "Stop the HTTP server and clean up all resources."
  [stop-fn]
  (when stop-fn
    (stop-fn)))
