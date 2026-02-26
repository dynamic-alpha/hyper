(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - global-cursor, session-cursor, tab-cursor, and path-cursor for state management
   - action macro for handling user interactions
   - navigate function for SPA navigation
   - watch! for observing external state sources
   - create-handler for building ring handlers"
  (:require [clojure.string :as str]
            [hyper.actions :as actions]
            [hyper.render :as render]
            [hyper.server :as server]
            [hyper.state :as state]
            [reitit.core :as reitit]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

;; Per-render action counter. Bound to (atom 0) before each render so that
;; deterministic render functions produce the same action IDs every time,
;; enabling effective brotli streaming compression.
(def ^:dynamic *action-idx* nil)

(defn- require-context!
  "Extract and validate the request context from *request*.
   Throws if called outside a request context or if required keys are missing.
   Returns a map with :session-id, :tab-id, and :app-state*."
  [caller-name]
  (when-not *request*
    (throw (ex-info (str caller-name " called outside request context") {})))
  (let [session-id (:hyper/session-id *request*)
        tab-id     (:hyper/tab-id *request*)
        app-state* (:hyper/app-state *request*)]
    (when-not app-state*
      (throw (ex-info "No app-state in request" {:request *request*})))
    {:session-id session-id
     :tab-id     tab-id
     :app-state* app-state*}))

(defn global-cursor
  "Create a cursor to global state at the given path.
   Global state is shared across all sessions and tabs — a change to global
   state triggers a re-render for every connected tab.

   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (global-cursor :theme)
     (global-cursor [:config :feature-flags])
     (global-cursor :user-count 0)"
  ([path]
   (let [{:keys [app-state*]} (require-context! "global-cursor")]
     (state/global-cursor app-state* path)))
  ([path default-value]
   (let [{:keys [app-state*]} (require-context! "global-cursor")]
     (state/global-cursor app-state* path default-value))))

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
   (let [{:keys [tab-id app-state*]} (require-context! "path-cursor")
         cursor                      (state/create-cursor
                                       app-state*
                                       [:tabs tab-id :route :query-params]
                                       path)]
     (when (nil? @cursor)
       (reset! cursor default-value))
     cursor)))

(defn watch!
  "Watch an external source for changes, triggering a re-render of the current
   tab when it changes. Source must satisfy the hyper.protocols/Watchable protocol
   (extended by default for atoms, refs, vars, and any IRef).

   Idempotent — safe to call on every render with the same source.
   Watches are automatically cleaned up when the tab disconnects.

   Example:
     ;; Watch a database query result atom
     (defn my-page [req]
       (watch! db-results)
       [:div [:p \"Count: \" (count @db-results)]])

     ;; Watch any Watchable source
     (watch! my-event-stream)"
  [source]
  (let [{:keys [session-id tab-id app-state*]} (require-context! "watch!")
        request-var                            (get @app-state* :request-var)]
    (render/watch-source! app-state* session-id tab-id request-var source)))

;; ---------------------------------------------------------------------------
;; Client param support for actions
;; ---------------------------------------------------------------------------

(def ^:private client-param-registry
  "Maps special $ symbols to their JavaScript extraction expression and
   JSON key name. When these symbols appear in an action body, the macro
   generates a fetch() call that sends the values as a JSON POST body."
  {'$value     {:js "evt.target.value" :key "value"}
   '$checked   {:js "evt.target.checked" :key "checked"}
   '$key       {:js "evt.key" :key "key"}
   '$form-data {:js  "Object.fromEntries(new FormData(evt.target.closest('form')))"
                :key "formData"}})

(defn- find-client-params
  "Walk the action body forms and return the subset of client-param-registry
   entries whose symbols appear in the body."
  [body]
  (let [all-syms (set (filter symbol? (tree-seq coll? seq body)))]
    (into {} (filter (fn [[sym _]] (all-syms sym))) client-param-registry)))

(defn build-action-expr
  "Build the Datastar/JS expression string for an action.
   When no client params are needed, returns a simple @post expression.
   When client params are present, returns a fetch() call that sends
   the extracted DOM values as a JSON POST body."
  [action-id used-params]
  (if (empty? used-params)
    (str "@post('/hyper/actions?action-id=" action-id "')")
    (let [json-entries (->> used-params
                            vals
                            (map (fn [{:keys [js key]}]
                                   (str key ":" js)))
                            (str/join ","))]
      (str "fetch('/hyper/actions?action-id=" action-id
           "',{method:'POST',headers:{'Content-Type':'application/json'}"
           ",body:JSON.stringify({" json-entries "})})"))))

(defmacro action
  "Create a server action expression for use in Datastar event attributes.
   Returns a Datastar expression string that can be bound to any event.

   The action is registered with the current session/tab context
   and can access state via cursors. Action IDs are deterministic
   (derived from a per-render counter + tab-id) so that re-renders
   produce identical HTML, enabling effective brotli streaming compression.

   Supports client-side special forms that transmit DOM values to the server:
   - $value     — the value of the input/select/textarea that fired the event
   - $checked   — the checked state of a checkbox/radio (boolean)
   - $key       — the key name for keyboard events (e.g. \"Enter\", \"Escape\")
   - $form-data — all named fields in the enclosing form as a map

   Example:
     [:button {:data-on:click (action (swap! (tab-cursor :count) inc))}
      \"Increment\"]

     ;; Capture input value
     [:input {:data-on:change (action (reset! (tab-cursor :query) $value))}]

     ;; Keyboard shortcut
     [:input {:data-on:keydown (action (when (= $key \"Enter\") (search!)))}]

     ;; Checkbox
     [:input {:type \"checkbox\"
              :data-on:change (action (reset! (tab-cursor :dark?) $checked))}]

     ;; Form submission
     [:form {:data-on:submit__prevent (action (save-user! $form-data))}
      [:input {:name \"email\"}]
      [:button \"Save\"]]"
  [& body]
  (let [used-params (find-client-params body)
        param-syms  (keys used-params)
        cp-sym      (gensym "client-params")]
    `(let [session-id# (get *request* :hyper/session-id)
           tab-id#     (get *request* :hyper/tab-id)
           app-state*# (get *request* :hyper/app-state)
           router#     (get *request* :hyper/router)]
       (when-not session-id#
         (throw (ex-info "action macro called outside request context" {})))
       (when-not tab-id#
         (throw (ex-info "No tab-id in request context" {})))
       (when-not app-state*#
         (throw (ex-info "No app-state in request context" {})))

       (let [action-fn# (fn [~cp-sym]
                          (let [~@(mapcat (fn [sym]
                                            (let [k (keyword (:key (get client-param-registry sym)))]
                                              [sym (list `get cp-sym k)]))
                                          param-syms)]
                            (binding [*request* {:hyper/session-id session-id#
                                                 :hyper/tab-id     tab-id#
                                                 :hyper/app-state  app-state*#
                                                 :hyper/router     router#}]
                              ~@body)))
             idx#       (if *action-idx* (swap! *action-idx* inc) (hash action-fn#))
             action-id# (str "a-" tab-id# "-" idx#)
             _#         (actions/register-action! app-state*# session-id# tab-id# action-fn# action-id#)]
         (build-action-expr action-id# '~used-params)))))

(defn navigate
  "Create a navigation link using reitit named routes.
   Returns a map with :href for standard links and :data-on:click__prevent for SPA navigation.

   On click, registers an action that:
   1. Looks up the target route's handler
   2. Updates the tab's render fn and route state
   3. Triggers a re-render via SSE
   4. Pushes the new URL via pushState (with title in history state)

   The :href ensures right-click → open in new tab works.
   The title from the target route's :title metadata is resolved eagerly and
   included in the pushState call so browser history entries have meaningful titles.

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
   (let [router     (:hyper/router *request*)
         app-state* (:hyper/app-state *request*)
         session-id (:hyper/session-id *request*)
         tab-id     (:hyper/tab-id *request*)]
     (when-let [path (:path (reitit/match-by-name router route-name params))]
       (let [href          (state/build-url path query-params)
             ;; Use live-routes to always get the latest route metadata
             routes        (server/live-routes app-state*)
             ;; Resolve title eagerly for the pushState call
             title-spec    (server/find-route-title routes route-name)
             title         (server/resolve-title title-spec *request*)
             ;; Register an action that performs the navigation server-side
             nav-fn        (fn [_client-params]
                             (let [routes    (server/live-routes app-state*)
                                   render-fn (server/find-render-fn routes route-name)]
                               (when render-fn
                                 (render/register-render-fn! app-state* tab-id render-fn))
                        ;; Setting the route triggers the route watcher,
                        ;; which handles re-rendering via SSE
                               (state/set-tab-route! app-state* tab-id
                                                     {:name         route-name
                                                      :path         path
                                                      :path-params  (or params {})
                                                      :query-params (or query-params {})})))
             nav-idx       (if *action-idx* (swap! *action-idx* inc) (hash nav-fn))
             action-id     (actions/register-action! app-state* session-id tab-id nav-fn
                                                     (str "a-" tab-id "-" nav-idx))
             escaped-title (or (server/escape-js-string title) "")
             escaped-href  (server/escape-js-string href)]
         {:href                                                                                   href
          :data-on:click__prevent
          (str "@post('/hyper/actions?action-id=" action-id "');"
               " window.history.pushState({title: '" escaped-title "'}, '', '" escaped-href "');"
               (when title
                 (str " document.title = '" escaped-title "'")))})))))

(defn create-handler
  "Create a Ring handler for a hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.

   Options (keyword arguments):
   - :app-state         — Atom for application state (default: fresh atom)
   - :executor          — java.util.concurrent.ExecutorService for render dispatch
                          (default: virtual-thread-per-task executor)
   - :head              — Hiccup nodes appended to the HTML <head>, or (fn [req] ...) -> hiccup
   - :static-resources  — Classpath resource root(s) to serve as static assets
   - :static-dir        — Filesystem directory (or directories) to serve as static assets
   - :watches           — Vector of Watchable sources added to every page route.
                          Useful for top-level atoms that should trigger a re-render
                          on any page (e.g. a global config or feature-flags atom).

   Example:
     (def routes
       [[\"/\" {:name :home
               :get (fn [req] [:div [:h1 \"Home\"]])}]
        [\"/about\" {:name :about
                    :get (fn [req] [:div [:h1 \"About\"]])}]])

     ;; Static routes
     (def handler (create-handler routes))

     ;; Live-reloading routes (pass the Var)
     (def handler (create-handler #'routes))

     ;; Inject a stylesheet (e.g. Tailwind output)
     (def handler
       (create-handler routes
                       :static-resources \"public\"
                       :head [[:link {:rel \"stylesheet\" :href \"/app.css\"}]]))

     ;; Custom executor
     (def handler (create-handler routes :executor my-executor))

     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [routes & {:keys [app-state executor head static-resources static-dir watches]
             :or   {app-state (atom (state/init-state))
                    executor  (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)}}]
  (server/create-handler routes app-state executor #'*request*
                         {:head             head
                          :static-resources static-resources
                          :static-dir       static-dir
                          :watches          watches}))

(defn start!
  "Start the hyper application server.

   handler: Ring handler created with create-handler
   options:
   - :port - Port to run server on (default: 3000)

   Returns a stop function. Call (stop! app) to shut down the server
   and clean up all tab resources (watchers, SSE channels, actions).

   Example:
     (def handler (create-handler routes))
     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [handler {:keys [port] :or {port 3000}}]
  (server/start! handler {:port port}))

(defn stop!
  "Stop the hyper application server and clean up all resources.

   app: Stop function returned from start!"
  [app]
  (server/stop! app))
