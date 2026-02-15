(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - session-cursor, tab-cursor, and path-cursor for state management
   - action macro for handling user interactions
   - navigate function for SPA navigation
   - create-handler for building ring handlers"
  (:require [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.server :as server]
            [hyper.render :as render]
            [reitit.core :as reitit]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

(defn- require-context!
  "Extract and validate the request context from *request*.
   Throws if called outside a request context or if required keys are missing.
   Returns a map with :session-id, :tab-id, and :app-state*."
  [caller-name]
  (when-not *request*
    (throw (ex-info (str caller-name " called outside request context") {})))
  (let [session-id (:hyper/session-id *request*)
        tab-id (:hyper/tab-id *request*)
        app-state* (:hyper/app-state *request*)]
    (when-not app-state*
      (throw (ex-info "No app-state in request" {:request *request*})))
    {:session-id session-id
     :tab-id tab-id
     :app-state* app-state*}))

(defn session-cursor
  "Create a cursor to session state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (session-cursor :user)
     (session-cursor [:user :name])
     (session-cursor :counter 0)"
  ([path]
   (let [{:keys [session-id app-state*]} (require-context! "session-cursor")]
     (state/session-cursor app-state* session-id path)))
  ([path default-value]
   (let [{:keys [session-id app-state*]} (require-context! "session-cursor")]
     (state/session-cursor app-state* session-id path default-value))))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (tab-cursor :count)
     (tab-cursor [:todos :list])
     (tab-cursor :count 0)"
  ([path]
   (let [{:keys [tab-id app-state*]} (require-context! "tab-cursor")]
     (state/tab-cursor app-state* tab-id path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (require-context! "tab-cursor")]
     (state/tab-cursor app-state* tab-id path default-value))))

(defn path-cursor
  "Create a cursor backed by URL query parameters.
   Reading returns the current value of the query param from the tab's route state.
   Writing updates the query param, which triggers a re-render and a replaceState
   to update the browser URL bar.

   Path can be a keyword or vector of keywords for the query param key(s).
   If default-value is provided and the query param is nil, initializes with default-value.

   Example:
     (path-cursor :count 0)     ;; URL: /?count=0
     (path-cursor :search \"\")   ;; URL: /?search=hello"
  ([path]
   (let [{:keys [tab-id app-state*]} (require-context! "path-cursor")]
     (state/create-cursor app-state* [:tabs tab-id :route :query-params] path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (require-context! "path-cursor")]
     (let [cursor (state/create-cursor app-state* [:tabs tab-id :route :query-params] path)]
       (when (nil? @cursor)
         (reset! cursor default-value))
       cursor))))

(defmacro action
  "Create an action that executes the given body when triggered.
   Returns a map with :data-on-click attribute for Datastar.

   The action is registered with the current session/tab context
   and can access state via cursors.

   Example:
     [:button (action (swap! (tab-cursor :count) inc))
      \"Increment\"]"
  [& body]
  `(let [session-id# (get *request* :hyper/session-id)
         tab-id# (get *request* :hyper/tab-id)
         app-state*# (get *request* :hyper/app-state)
         router# (get *request* :hyper/router)]
     (when-not session-id#
       (throw (ex-info "action macro called outside request context" {})))
     (when-not tab-id#
       (throw (ex-info "No tab-id in request context" {})))
     (when-not app-state*#
       (throw (ex-info "No app-state in request context" {})))

     (let [action-fn# (fn []
                        (binding [*request* {:hyper/session-id session-id#
                                            :hyper/tab-id tab-id#
                                            :hyper/app-state app-state*#
                                            :hyper/router router#}]
                          ~@body))
           action-id# (actions/register-action! app-state*# session-id# tab-id# action-fn#)]
       {:data-on:click (str "@post('/hyper/actions?action-id=" action-id# "')")})))

(defn navigate
  "Create a navigation link using reitit named routes.
   Returns a map with :href for standard links and :data-on-click for SPA navigation.

   On click, registers an action that:
   1. Looks up the target route's handler
   2. Updates the tab's render fn and route state
   3. Triggers a re-render via SSE
   4. Pushes the new URL via pushState

   The :href ensures right-click → open in new tab works.

   route-name: Keyword name of the route
   params: Optional map of path parameters
   query-params: Optional map of query parameters

   Example:
     [:a (navigate :home) \"Go Home\"]
     [:a (navigate :user-profile {:id \"123\"}) \"View User\"]
     [:a (navigate :search {} {:q \"clojure\"}) \"Search\"]"
  ([route-name]
   (navigate route-name nil nil))
  ([route-name params]
   (navigate route-name params nil))
  ([route-name params query-params]
   (let [router (:hyper/router *request*)
         app-state* (:hyper/app-state *request*)
         session-id (:hyper/session-id *request*)
         tab-id (:hyper/tab-id *request*)]
     (when-let [path (:path (reitit/match-by-name router route-name params))]
       (let [href (state/build-url path query-params)
             ;; Register an action that performs the navigation server-side
             nav-fn (fn []
                      (let [routes (get @app-state* :routes)
                            render-fn (server/find-render-fn routes route-name)]
                        (when render-fn
                          (render/register-render-fn! app-state* tab-id render-fn))
                        ;; Setting the route triggers the route watcher,
                        ;; which handles re-rendering via SSE
                        (state/set-tab-route! app-state* tab-id
                                              {:name route-name
                                               :path path
                                               :path-params (or params {})
                                               :query-params (or query-params {})})))
             action-id (actions/register-action! app-state* session-id tab-id nav-fn)]
         {:href href
          :data-on:click__prevent
          (str "@post('/hyper/actions?action-id=" action-id "');"
               " window.history.pushState({}, '', '" href "')")})))))

(defn create-handler
  "Create a Ring handler for a hyper application.

   routes: Vector of reitit routes (using route names for navigation)
   app-state*: Optional atom for application state (creates new one if not provided)

   Example:
     (def routes
       [[\"/\" {:name :home
               :get (fn [req] [:div [:h1 \"Home\"]])}]
        [\"/about\" {:name :about
                    :get (fn [req] [:div [:h1 \"About\"]])}]])

     (def handler (create-handler routes))
     (def server (start! handler {:port 3000}))"
  ([routes]
   (create-handler routes (atom (state/init-state))))
  ([routes app-state*]
   (server/create-handler routes app-state* #'*request*)))

(defn start!
  "Start the hyper application server.

   handler: Ring handler created with create-handler
   options:
   - :port - Port to run server on (default: 3000)

   Returns server instance. Call (stop! server) to stop.

   Example:
     (def router ...)
     (def handler (create-handler router))
     (def server (start! handler {:port 3000}))
     ;; Later...
     (stop! server)"
  [handler {:keys [port] :or {port 3000}}]
  (server/start! handler {:port port}))

(defn stop!
  "Stop the hyper application server.

   server: Server instance returned from start!"
  [server]
  (server/stop! server))
