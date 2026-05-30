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
            [hyper.client-params :as client-params]
            [hyper.context :as context :refer [*request* *action-idx*]]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.server :as server]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [hyper.watch :as watch]
            [reitit.core :as reitit]))

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
   (let [{:keys [app-state*]} (context/require-context! "global-cursor")]
     (state/global-cursor app-state* path)))
  ([path default-value]
   (let [{:keys [app-state*]} (context/require-context! "global-cursor")]
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
   (let [{:keys [session-id app-state*]} (context/require-context! "session-cursor")]
     (state/session-cursor app-state* session-id path)))
  ([path default-value]
   (let [{:keys [session-id app-state*]} (context/require-context! "session-cursor")]
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
   (let [{:keys [tab-id app-state*]} (context/require-context! "tab-cursor")]
     (state/tab-cursor app-state* tab-id path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "tab-cursor")]
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
   (let [{:keys [tab-id app-state*]} (context/require-context! "path-cursor")]
     (state/create-cursor app-state* [:tabs tab-id :route :query-params] path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "path-cursor")
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
  (let [{:keys [tab-id app-state*]} (context/require-context! "watch!")
        trigger-render!             (get-in @app-state* [:tabs tab-id :renderer :trigger-render!])]
    (if trigger-render!
      (watch/watch-source! app-state* tab-id trigger-render! source)
      ;; No SSE renderer yet (initial HTTP render) — stash for later promotion
      (watch/stash-pending-watch! app-state* tab-id source))))

(defn env
  "Get the request environment, or a specific key from it.

   Ring middleware can set `:hyper/env` on the request to provide context
   that persists across SSE re-renders and action handlers.  Hyper
   automatically stashes the env per-tab on each HTTP request (page load,
   action POST, navigation) and propagates it to every subsequent render
   and action execution.

   Use Ring middleware for I/O and request-dependent context (database
   connections, authenticated user, feature flags read from headers/cookies).
   Use render middleware to guard renders based on env (e.g. permission checks).

   Example:
     ;; Ring middleware sets :hyper/env
     (defn wrap-app-env [handler db]
       (fn [req]
         (handler (assoc req :hyper/env {:db db}))))

     ;; Read in a render function
     (defn my-page [req]
       (let [db (h/env :db)]
         [:div \"Connected to: \" (str db)]))

     ;; Read in an action
     [:button {:data-on:click (h/action
                                (let [db (h/env :db)]
                                  (db/insert! db ...)))}
      \"Save\"]"
  ([]
   (:hyper/env context/*request*))
  ([key]
   (get (:hyper/env context/*request*) key))
  ([key default]
   (get (:hyper/env context/*request*) key default)))

;; ---------------------------------------------------------------------------
;; Batched cursor updates
;; ---------------------------------------------------------------------------

(defmacro batch
  "Execute body with all cursor writes batched into a single atomic update.

   During the body, cursor reads see the accumulated writes (read-your-writes).
   After the body completes, all mutations are flushed to app-state* in a
   single swap!, so the renderer only sees the final state — never an
   intermediate one.

   Side effects (I/O, HTTP calls, DB queries) inside batch work normally —
   only cursor writes are deferred.

   Nested batches are transparent — the inner batch executes within the
   existing overlay and the outermost boundary handles the flush.

   Example:
     ;; Without batch, the renderer might snapshot between cursor updates
     ;; and show a partial state (e.g. new data with loading still true).
     (h/action
       (h/batch
         (reset! (h/tab-cursor :data) (fetch-data!))
         (reset! (h/tab-cursor :loading?) false)))

     ;; Progress bar: use batch only for the atomic pair, leave the
     ;; intermediate :loading state unbatched so the renderer picks it up.
     (h/action
       (reset! (h/tab-cursor :loading?) true)     ;; immediate — shows spinner
       (let [data (fetch-data!)]
         (h/batch                                  ;; atomic — one render
           (reset! (h/tab-cursor :data) data)
           (reset! (h/tab-cursor :loading?) false))))"
  [& body]
  `(if context/*state-overlay*
     ;; Already inside an overlay (render or outer batch) — just execute.
     ;; Writes accumulate in the existing overlay; outer boundary flushes.
     (do ~@body)
     ;; Fresh overlay — snapshot current state, execute, flush.
     (let [{app-state*# :app-state*} (context/require-context! "batch")
           overlay#                  {:state* (atom @app-state*#)
                                      :paths* (atom #{})}]
       (binding [context/*state-overlay* overlay#]
         (let [result# (do ~@body)]
           (context/flush-overlay! app-state*#)
           result#)))))

;; ---------------------------------------------------------------------------
;; Reactive components
;; ---------------------------------------------------------------------------

(defmacro reactive
  "Create a reactive component that re-renders independently when its deps change.

   deps is a vector of Watchable sources (atoms, cursors, or any type extending
   hyper.protocols/Watchable).  The body is a hiccup expression that will be
   wrapped in a div with a stable ID.

   When any dep changes, only this component re-renders and a targeted Datastar
   fragment is sent — the rest of the page is untouched.  During full page
   re-renders, the body is always re-executed (since it may close over parent
   data not tracked in deps) and the result is cached for future partial renders.

   Supports nesting — inner reactive blocks re-execute independently during
   partial renders triggered by their own deps.

   Usage:
     (let [clock* (h/global-cursor :clock)]
       (reactive [clock*]
         [:p \"The time is: \" @clock*]))

     ;; Multiple deps
     (let [x* (h/tab-cursor :x 0)
           y* (h/tab-cursor :y 0)]
       (reactive [x* y*]
         [:p \"Position: \" @x* \", \" @y*]))"
  [deps & body]
  `(let [{tab-id# :tab-id app-state*# :app-state*} (context/require-context! "reactive")
         idx#                                      (if context/*action-idx* (swap! context/*action-idx* inc) 0)
         component-id#                             (str "r_" tab-id# "_" idx#)
         deps#                                     ~deps
         render-fn#                                (fn [] ~@body)]
     (reactive/render-component app-state*# tab-id# component-id# deps# render-fn#)))

;; ---------------------------------------------------------------------------
;; Signals
;; ---------------------------------------------------------------------------

(defn signal
  "Create a Datastar signal — a reactive client-side variable that syncs
   between the browser and server.

   During render, `@signal*` returns the Datastar expression string (e.g.
   `\"$userName\"`), suitable for use in `data-text`, `data-show`, etc.
   During action execution, `@signal*` returns the live value sent by
   Datastar in the `@post()` request body.

   `reset!` and `swap!` update the signal value on the server, which
   triggers a `datastar-patch-signals` SSE event to push the new value
   to the client.

   Path can be a keyword or a vector of keywords:
     (signal :name)                ;; → $name
     (signal :user-name \"default\") ;; → $userName
     (signal [:user :name] \"\")     ;; → $user.name

   Example:
     (let [name* (signal :name \"\")]
       [:div
        [:input (bind name*)]
        [:p {:data-text @name*} \"\"]])"
  ([path]
   (signal path nil))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "signal")]
     (signal/create-signal app-state* tab-id path default-value))))

(defn local-signal
  "Create a local Datastar signal (underscore-prefixed).  Local signals
   are client-only: Datastar does not send them to the server, so
   `reset!` and `swap!` are not supported and `deref` in an action throws.

   During render, `@local*` returns the Datastar expression string
   (e.g. `\"$_open\"`), suitable for `data-show`, `data-text`, etc.
   The signal itself (without deref) can be used as a `data-bind` value.

   Path can be a keyword or a vector of keywords:
     (local-signal :open? false)   ;; → $_open
     (local-signal :show-menu false)  ;; → $_showMenu

   Example:
     (let [open?* (local-signal :open false)]
       [:div
        [:button {:data-on:click (str @open?* \" = !\" @open?*)} \"Toggle\"]
        [:div {:data-show @open?*} \"Content\"]])"
  ([path]
   (local-signal path nil))
  ([path default-value]
   (signal/create-local-signal path default-value)))

;; ---------------------------------------------------------------------------
;; Client param support for actions
;; ---------------------------------------------------------------------------

(defn- find-client-params
  "Walk the action body forms and return the subset of the defined client-params
   whose symbols appear in the body.
   
   Returns a map of symbols (that appear in the body) to their definition."
  [body]
  (let [all-syms (set (filter symbol? (tree-seq coll? seq body)))]
    (->> (client-params/defined-client-params)
         (filter all-syms)
         (reduce (fn [m sym]
                   (assoc m sym (client-params/client-param sym)))
                 {}))))

(defn build-action-expr
  "Build the Datastar/JS expression string for an action.
   Always uses Datastar's @post() so that all non-underscore signals are
   automatically sent in the request body.  When client params are present,
   they are URL-encoded into the query string via the hyper.encodeClientParams helper
   so the server can read them from query-params.
   Optionally injects a custom Datastar expression to conditionally prevent the post.
   base-path is prepended to the /hyper/actions endpoint (empty string when not set)."
  [action-id used-params js base-path]
  (let [js-injection (when js (str js " && "))]
    (if (empty? used-params)
      (str js-injection "@post('" base-path "/hyper/actions?action-id=" action-id "')")
      (let [obj-entries (->> used-params
                             vals
                             (map (fn [{:keys [js key]}]
                                    (str key ":" js)))
                             (str/join ","))]
        (str js-injection
             "@post('" base-path "/hyper/actions?action-id=" action-id
             "&' + hyper.encodeClientParams({" obj-entries "}))")))))

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
     [:input {:data-on:keydown (action (when (= $key \"Enter\")
                                 (search!)))}]

     ;; ... with client side check
     [:input {:data-on:keydown (action {:when \"evt.key === 'Enter'\"}
                                 (search!))}]

     ;; Named action for testing — :as gives the action a human-readable name
     ;; that hyper.test/test-page uses as the key in its :actions map
     [:button {:data-on:click (action {:as \"increment\"}
                                (swap! (tab-cursor :count) inc))}
      \"Increment\"]

     ;; Checkbox
     [:input {:type \"checkbox\"
              :data-on:change (action (reset! (tab-cursor :dark?) $checked))}]

     ;; Form submission
     [:form {:data-on:submit__prevent (action (save-user! $form-data))}
      [:input {:name \"email\"}]
      [:button \"Save\"]]
      
     Applications may define additional client parameters by extending
     the hyper.client-params/client-param multi-method."
  [& args]
  (let [[maybe-opts & body] args
        opts-map?           (and (map? maybe-opts)
                                 (some #{:when :as} (keys maybe-opts)))
        [js as-name body]   (if opts-map?
                              (let [guard (:when maybe-opts)
                                    js    (when (and (string? guard)
                                                     (not (str/blank? guard)))
                                            guard)]
                                [js (:as maybe-opts) body])
                              [nil nil args])
        used-params         (find-client-params body)
        param-syms          (keys used-params)
        cp-sym              (gensym "client-params")]
    `(let [{session-id# :session-id
            tab-id#     :tab-id
            app-state*# :app-state*
            router#     :router}    (context/require-context! "action")
           action-fn#               (fn [~cp-sym]
                                      (let [~@(mapcat (fn [sym]
                                                        (let [k (keyword (:key (client-params/client-param sym)))]
                                                          [sym (list `get cp-sym k)]))
                                                      param-syms)]
                                        (binding [context/*request* {:hyper/session-id session-id#
                                                                     :hyper/tab-id     tab-id#
                                                                     :hyper/app-state  app-state*#
                                                                     :hyper/router     router#
                                                                     :hyper/env        (get-in @app-state*# [:tabs tab-id# :env])}]
                                          ~@body)))
           idx#                     (if context/*action-idx* (swap! context/*action-idx* inc) (hash action-fn#))
           action-id#               (str "a_" tab-id# "_" idx#)
           _#                       (actions/register-action! app-state*# session-id# tab-id# action-fn# action-id#
                                                              ~(when as-name {:as as-name}))
           base-path#               (get @app-state*# :base-path "")]
       (build-action-expr action-id# '~used-params ~js base-path#))))

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
             base-path     (get @app-state* :base-path "")
             ;; Use live-routes to always get the latest route metadata
             route-index   (routes/live-route-index app-state*)
             ;; Resolve title eagerly for the pushState call
             title-spec    (routes/find-route-title route-index route-name)
             title         (routes/resolve-title title-spec *request*)
             ;; Register an action that performs the navigation server-side
             nav-fn        (fn [_client-params]
                             (let [route-idx (routes/live-route-index app-state*)
                                   render-fn (routes/find-render-fn route-idx route-name)]
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
                                                     (str "a_" tab-id "_" nav-idx))
             escaped-title (or (utils/escape-js-string title) "")
             escaped-href  (utils/escape-js-string href)]
         {:href href
          :data-on:click__prevent
          (str "@post('" base-path "/hyper/actions?action-id=" action-id "');"
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
   - :datastar-script   - Override of the default datastar script tag (as Hiccup) or nil to suppress
   - :head              — Hiccup nodes appended to the HTML <head>, or (fn [req] ...) -> hiccup
   - :base-path         — URL path prefix for reverse-proxy deployments where the app is served
                          under a subfolder (e.g. \"/my-app\"). When set, all internal hyper
                          endpoints (/hyper/events, /hyper/actions, /hyper/navigate) are mounted
                          and referenced under this prefix. Must start with \"/\" and have no
                          trailing slash.
   - :static-resources  — Classpath resource root(s) to serve as static assets
   - :static-dir        — Filesystem directory (or directories) to serve as static assets
   - :watches           — Vector of Watchable sources added to every page route.
                          Useful for top-level atoms that should trigger a re-render
                          on any page (e.g. a global config or feature-flags atom).
   - :middleware        — Vector of Ring middleware fns applied inside the HTTP stack.
                          Each is (fn [handler] (fn [req] ...)).  Runs after Hyper's
                          built-in cookie, params, and session middleware, so your
                          middleware sees parsed :cookies, :params, :hyper/session-id,
                          and :hyper/tab-id.  Use this for auth, :hyper/env setup, and
                          other request-level concerns.  Middleware can also be applied
                          outside create-handler, but will not have access to parsed
                          cookies/params.
   - :render-middleware — Vector of middleware fns applied to every page render.
                          Each is (fn [handler] (fn [req] ...)), identical to Ring
                          middleware.  Applied on both initial page loads and SSE
                          re-renders.  Per-route :render-middleware wraps inside these.
   - :hiccup-transform  — (fn [hiccup] hiccup) applied to body and head hiccup before
                          Chassis serialization.  Useful for expanding component systems
                          (e.g. lambdaisland/ornament defstyled components) into plain
                          keyword-first hiccup vectors that Chassis can serialize.
   - :render-error      — Function `(fn [error req] -> hiccup)` rendered when a
                          view's render-fn throws.  May be a Var to pick up
                          redefinitions without restarting the server.  Defaults
                          to `hyper.render.error/minimal` (generic, production-
                          safe).  Use `hyper.render.error/explain` in development
                          to render the message, ex-data, and full stack trace.

   The request key :hyper/env is reserved for application-provided context.
   Ring middleware that sets :hyper/env on the request will have it automatically
   stashed per-tab and propagated to every SSE re-render and action handler.
   See `env` for details.

   Example:
     (def routes
       [[\"/\" {:name :home
               :get (fn [req] [:div [:h1 \"Home\"]])}]
        [\"/about\" {:name :about
                    :get (fn [req] [:div [:h1 \"About\"]])}]
        [\"/api/info\" {:name :api-info
                       :hyper/disabled? true
                       :get (fn [req] .. a json api endpoint ..)]])

     ;; Static routes
     (def handler (create-handler routes))

     ;; Live-reloading routes (pass the Var)
     (def handler (create-handler #'routes))

     ;; Inject a stylesheet (e.g. Tailwind output)
     (def handler
       (create-handler routes
                       :static-resources \"public\"
                       :head [[:link {:rel \"stylesheet\" :href \"/app.css\"}]]))

     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [routes & {:keys [app-state head static-resources static-dir watches
                    datastar-script base-path middleware render-middleware
                    render-error hiccup-transform]
             :or   {app-state       (atom (state/init-state))
                    datastar-script server/default-datastar-script}}]
  (server/create-handler routes app-state
                         (cond-> {:head              head
                                  :datastar-script   datastar-script
                                  :static-resources  static-resources
                                  :static-dir        static-dir
                                  :watches           watches
                                  :base-path         base-path
                                  :middleware        middleware
                                  :render-middleware render-middleware
                                  :hiccup-transform  hiccup-transform}
                           ;; Only forward when supplied so server-level
                           ;; default (`render.error/minimal`) applies otherwise.
                           render-error (assoc :render-error render-error))))

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
