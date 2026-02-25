(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [clojure.edn :as edn]
            [clojure.string]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.brotli :as br]
            [hyper.render :as render]
            [hyper.state :as state]
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
            [taoensso.telemere :as t]))

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

(defn- accepts-br?
  "Check if the request's Accept-Encoding header includes brotli."
  [req]
  (when-let [accept (get-in req [:headers "accept-encoding"])]
    (some? (re-find #"\bbr\b" accept))))

(defn sse-events-handler
  "Handler for SSE event stream."
  [app-state* request-var]
  (fn [req]
    (let [session-id (:hyper/session-id req)
          tab-id     (:hyper/tab-id req)
          compress?  (accepts-br? req)]

      (http-kit/as-channel req
                           {:on-open  (fn [channel]
                                        (state/get-or-create-tab! app-state* session-id tab-id)
                                        (render/register-sse-channel! app-state* tab-id channel compress?)
                                        (render/setup-watchers! app-state* session-id tab-id request-var)
                    ;; Auto-watch the routes Var so title/route changes
                    ;; trigger re-renders for all connected tabs
                                        (when-let [routes-source (get @app-state* :routes-source)]
                                          (when (var? routes-source)
                                            (render/watch-source! app-state* session-id tab-id request-var routes-source)))
                    ;; Set up route-level watches (:watches + Var :get handlers)
                                        (render/setup-route-watches! app-state* session-id tab-id request-var)
                                        (let [connected-msg (str "event: connected\n"
                                                                 "data: {\"tab-id\":\"" tab-id "\"}\n\n")]
                                          (if compress?
                        ;; Brotli: send headers with the first chunk compressed
                        ;; through the tab's streaming compressor so all bytes
                        ;; on this connection form one contiguous brotli stream.
                                            (let [tab-data   (get-in @app-state* [:tabs tab-id])
                                                  compressed (br/compress-stream
                                                               (:br-out tab-data)
                                                               (:br-stream tab-data)
                                                               connected-msg)]
                                              (http-kit/send!
                                                channel
                                                {:headers {"Content-Type"     "text/event-stream"
                                                           "Content-Encoding" "br"}
                                                 :body    compressed}
                                                false))
                        ;; No compression: plain text
                                            (http-kit/send!
                                              channel
                                              {:headers {"Content-Type" "text/event-stream"}
                                               :body    connected-msg}
                                              false))))

                            :on-close (fn [_channel _status]
                                        (t/log! {:level :info
                                                 :id    :hyper.event/tab-disconnect
                                                 :data  {:hyper/tab-id tab-id}
                                                 :msg   "Tab disconnected"})
                                        (render/cleanup-tab! app-state* tab-id))}))))

(defn action-handler
  "Handler for action POST requests."
  [app-state* request-var]
  (fn [req]
    (let [action-id (get-in req [:query-params "action-id"])]

      (if-not action-id
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing action-id\"}"}

        (let [req-with-state (assoc req
                                    :hyper/app-state app-state*
                                    :hyper/router (get @app-state* :router))]
          (push-thread-bindings {request-var req-with-state})
          (try
            (actions/execute-action! app-state* action-id)

            {:status  200
             :headers {"Content-Type" "application/json"}
             :body    "{\"success\": true}"}

            (catch Exception e
              (t/error! e
                        {:id   :hyper.error/action-handler
                         :data {:hyper/action-id action-id}})
              {:status  500
               :headers {"Content-Type" "application/json"}
               :body    (str "{\"error\": \"" (.getMessage e) "\"}")})
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

(defn find-route-title
  "Find the :title metadata for a named route.
   Returns the title value (string or fn) or nil."
  [routes route-name]
  (->> routes
       (some (fn [[_path data]]
               (when (= route-name (:name data))
                 (:title data))))))

(defn find-route-watches
  "Collect all Watchable sources for a named route.
   Takes the dereferenced app-state map and a route name.
   Returns a vector of sources built from:
   - The global :watches supplied to create-handler (applied to every route)
   - The route's :watches vector (explicit per-route external sources)
   - The route's :get value, if it's a Var (auto-watch for live reloading)
   Returns nil if there are no watches."
  [app-state route-name]
  (let [routes (or (:routes app-state) [])]
    (when-let [route-data (->> routes
                               (some (fn [[_path data]]
                                       (when (= route-name (:name data))
                                         data))))]
      (let [global      (vec (:global-watches app-state))
            explicit    (vec (or (:watches route-data) []))
            get-handler (:get route-data)
            watches     (cond-> (into global explicit)
                          (var? get-handler) (conj get-handler))]
        (when (seq watches)
          watches)))))

(defn live-routes
  "Get the current routes, resolving through :routes-source if it's a Var.
   This ensures we always read the latest route metadata (including :title)
   when a render is triggered."
  [app-state*]
  (let [source (get @app-state* :routes-source)]
    (if (var? source)
      @source
      (get @app-state* :routes))))

(defn resolve-title
  "Resolve a title value. If it's a fn, call it with the request.
   Returns the resolved string, or nil."
  [title req]
  (cond
    (nil? title)  nil
    (fn? title)   (title req)
    (string? title) title
    (instance? clojure.lang.IDeref title) (str @title)
    :else         (str title)))

(defn escape-js-string
  "Escape a string for safe embedding in a single-quoted JavaScript string literal
   inside a <script> block. Handles backslashes, quotes, newlines, line/paragraph
   separators, and </script> injection."
  [s]
  (when s
    (-> s
        (clojure.string/replace "\\" "\\\\")
        (clojure.string/replace "'" "\\'")
        (clojure.string/replace "\"" "\\\"")
        (clojure.string/replace "\n" "\\n")
        (clojure.string/replace "\r" "\\r")
        (clojure.string/replace "\u2028" "\\u2028")
        (clojure.string/replace "\u2029" "\\u2029")
        ;; Prevent </script> from closing the script block in HTML parser
        (clojure.string/replace "</" "<\\/"))))

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

(defn resolve-head
  "Resolve extra <head> content.

   - If :head is a function, it is called with the Ring request (already enriched
     with Hyper context) and should return hiccup nodes.
   - Otherwise, :head is treated as hiccup and used as-is.

   Intended for injecting stylesheets/scripts (e.g. Tailwind output CSS) without
   Hyper needing to know anything about build tooling."
  [head req]
  (cond
    (fn? head)   (head req)
    (some? head) head
    :else        nil))

(defn page-handler
  "Wrap a page render function to provide full HTML response.
   Resolves :title from route metadata — supports static strings, functions,
   and deref-able values (cursors/atoms).

   Options:
   - :head Hiccup nodes to append into the <head>, or (fn [req] ...) -> hiccup.
           When head is a function, it is re-evaluated on each SSE render cycle
           and the full <head> is pushed to the client."
  [app-state* request-var {:keys [head]}]
  (fn [render-fn]
    (fn [req]
      (let [tab-id     (:hyper/tab-id req)
            route-info (extract-route-info req)]

        (render/register-render-fn! app-state* tab-id render-fn)
        (state/set-tab-route! app-state* tab-id route-info)
        (let [req-with-state (-> req
                                 (assoc :hyper/app-state app-state*
                                        :hyper/router (get @app-state* :router)
                                        :hyper/route-match (:reitit.core/match req))
                                 (dissoc :reitit.core/match))
              action-idx-var (requiring-resolve 'hyper.core/*action-idx*)]
          (push-thread-bindings {request-var    req-with-state
                                 action-idx-var (atom 0)})
          (try
            (let [content    (render-fn req-with-state)
                  route-name (:name route-info)
                  routes     (live-routes app-state*)
                  title-spec (find-route-title routes route-name)
                  title      (or (resolve-title title-spec req-with-state) "Hyper App")
                  extra-head (resolve-head head req-with-state)
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
                                  [:div {:id "hyper-app"} content]
                                  (hyper-scripts tab-id)]]])]
              {:status  200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body    html})
            (finally
              (pop-thread-bindings))))))))

(defn- navigate-handler
  "Handler for popstate/navigation POST requests.
   Looks up the route for the given path and updates the tab's route + render fn."
  [app-state* _request-var]
  (fn [req]
    (let [path        (get-in req [:query-params "path"])
          tab-id      (:hyper/tab-id req)
          _session-id (:hyper/session-id req)
          router      (get @app-state* :router)
          routes      (live-routes app-state*)]
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
                  render-fn    (find-render-fn routes route-name)]
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
                               [path (update route-data :get
                                             (fn [handler]
                                               (when handler
                                                 (page-wrapper handler))))])
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
   executor: ExecutorService for dispatching render tasks
   request-var: Dynamic var to bind request context (e.g., hyper.core/*request*)

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
  ([routes app-state* executor request-var]
   (create-handler routes app-state* executor request-var {}))
  ([routes app-state* executor request-var {:keys [watches head] :as opts}]
   (let [page-wrapper                             (page-handler app-state* request-var opts)
         system-routes                            [["/hyper/events" {:get (sse-events-handler app-state* request-var)}]
                                                   ["/hyper/actions" {:post (action-handler app-state* request-var)}]
                                                   ["/hyper/navigate" {:post (navigate-handler app-state* request-var)}]]
         ;; Store the routes source (Var or value) so title resolution can
         ;; always read the latest route metadata, even between router rebuilds.
         ;; Store executor and request-var so render watches can access them.
         ;; Store global :watches so find-route-watches can prepend them to
         ;; every page route's watch list.
         _                                        (swap! app-state* assoc
                                                         :routes-source routes
                                                         :executor executor
                                                         :request-var request-var
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
     (wrap-static handler-with-mw opts))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   port: Port to run on (default: 3000)

   Returns server instance."
  [handler {:keys [port] :or {port 3000}}]
  (let [server (http-kit/run-server handler {:port port})]
    (t/log! {:level :info
             :id    :hyper.event/server-start
             :data  {:hyper/port port}
             :msg   "Hyper server started"})
    server))

(defn stop!
  "Stop the HTTP server."
  [server]
  (when server
    (server :timeout 100)
    (t/log! {:level :info
             :id    :hyper.event/server-stop
             :msg   "Hyper server stopped"})))
