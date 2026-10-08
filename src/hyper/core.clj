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
            [hyper.component :as component]
            [hyper.context :as context :refer [*request* *action-idx*]]
            [hyper.expr]
            [hyper.lifecycle :as lifecycle]
            [hyper.protocols :as protocols]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.server :as server]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.uploads :as uploads]
            [hyper.utils :as utils]))

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
   If default-value is provided and the query param is unset, initializes with
   default-value.  A query param explicitly holding nil is left untouched.

   Example:
     (path-cursor :count 0)      ;; URL: /?count=0
     (path-cursor :search \"\")    ;; URL: /?search=   (an empty string is kept)

     ;; The default only fills an *unset* param; an explicit nil is preserved
     ;; (not re-defaulted) and, unlike \"\", is omitted from the URL.  A write's
     ;; URL shows on the next render, not the one that made it:
     (path-cursor :filter \"all\")               ;; unset → \"all\" (URL: /?filter=all)
     (reset! (path-cursor :filter \"all\") nil)  ;; clear to nil (URL: /)
     (path-cursor :filter \"all\")               ;; still nil — default not reapplied"
  ([path]
   (let [{:keys [tab-id app-state*]} (context/require-context! "path-cursor")]
     (state/stamp-scope!
       (state/create-cursor app-state* [:tabs tab-id :route :query-params] path)
       :path path)))
  ([path default-value]
   (state/init-default! (path-cursor path) default-value)))

(def Watchable
  "Protocol for external state sources that `watch!` can observe. Extended
   by default for atoms, refs, vars, and any `IRef`. Extend it yourself for
   custom sources — databases, message queues, or any stateful resource:

     (extend-protocol Watchable
       my.db/QueryResult
       (-add-watch [this key callback]
         ;; callback is (fn [old-val new-val]); set up your change listener
         )
       (-remove-watch [this key]
         ;; tear down the listener
         )
       (-dispose [this]
         ;; release resources (close connections, stop polling, etc.);
         ;; called once the last tab watching this source navigates away
         ;; or disconnects — no-op for sources that hold no resources
         (.close this)))"
  protocols/Watchable)

(defn watch!
  "Watch an external source for changes, triggering a re-render of the current
   tab when it changes. Source must satisfy the `Watchable` protocol
   (extended by default for atoms, refs, vars, and any IRef).

   A watch is a mount-scoped subscription, keyed by source identity for dedup.
   Prefer a **form-2 setup closure** (runs once per mount):

     (defn my-page [req]
       (let [results (db/query-atom (get-in req [:hyper/route :path-params :id]))]
         (watch! results)                     ;; setup — runs once per mount
         (fn [req] [:p \"Count: \" (count @results)])))

   It is idempotent, so a form-1 render body also works (re-subscribing each
   render); that path triggers the render-purity warning nudging you to form-2.

   The mount boundary is keyed on `[handler path-params]`, so a source derived
   from a path-param re-subscribes when that path-param changes: the page
   remounts, tearing down the old watch and running setup for the new one.
   Watches are cleaned up on remount and tab disconnect."
  [source]
  (context/guard-effect! :watch "h/watch!")
  (let [{:keys [tab-id app-state*]} (context/require-context! "watch!")]
    ;; A user watch is a mount-scoped, full-render subview: registered once
    ;; per mount (form-2 setup) or idempotently each render (form-1 body),
    ;; keyed by source identity for dedup.  Torn down on navigation
    ;; (page-view remount) and tab disconnect — never by the per-render sweep.
    ;; register-watch! wires it immediately when a renderer is present.
    (subview/register-watch! app-state* tab-id source)
    nil))

(defn spawn!
  "Spawn a background virtual-thread worker bound to the current view's
   lifecycle.  `worker-fn` is a zero-arg fn run once on a fresh virtual
   thread; the framework owns the thread handle and **interrupts it on
   unmount** (navigation away, route-handler redefinition, or tab
   disconnect).  Returns nil; a worker is fire-and-forget and communicates by
   writing state (cursors), which drives the usual declarative re-render.

   The worker runs on its own thread with `*request*` rebound, so cursor writes
   land and the same `tab-cursor`/`session-cursor`/`global-cursor`/`env` calls
   work inside it as in a handler.

   Mount-scoped and keyed by call order (like `reactive`), so a form-1 body
   that calls `spawn!` on every render still spawns exactly one worker.
   Prefer calling it from a **form-2 setup closure** (runs once per mount),
   where its intent — \"start this worker when the view mounts\" — is clearest:

     (defn ticker-page [req]
       (let [now* (h/tab-cursor :now)]
         (h/spawn!                           ;; setup — runs once per mount
           (fn []
             (loop []
               (reset! now* (System/currentTimeMillis))
               (Thread/sleep 1000)
               (recur))))                    ;; interrupted on unmount
         (fn [req] [:p \"Now: \" @now*])))     ;; render — pure

   For background work, prefer modeling results as state the worker writes;
   reach for `spawn!` only when you genuinely need a long-lived loop or
   blocking consumer tied to the view.  For one-shot data loading with a
   placeholder, prefer `async`."
  [worker-fn]
  (context/guard-effect! :spawn "h/spawn!")
  (let [{:keys [session-id tab-id app-state*]} (context/require-context! "spawn!")
        idx                                    (if *action-idx* (swap! *action-idx* inc) 0)
        sid                                    (str "s_" tab-id "_" idx)]
    (subview/spawn-worker! app-state* tab-id sid session-id worker-fn)
    nil))

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

(defn action-name
  "Return the `:as` name of the currently executing action, or nil when
   called outside an action context or the action was not given an `:as`
   name.

   Lets utility functions identify which action is running without the
   caller having to pass the name explicitly:

     (defn audit! []
       (log/info \"Action executed\" {:action (action-name)}))

     (action {:as \"delete-user\"}
       (audit!)          ;; logs {:action \"delete-user\"}
       (delete-user! id))"
  []
  context/*action-name*)

(defn route
  "Return the current route info map — :name, :path, :path-params, and
   :query-params — consistent with the tab's current route.

   Works anywhere within the request context (render functions, actions,
   render middleware); a render function can equivalently read it off the
   request via (:hyper/route req).

   Example:
     (route)  ;; => {:name :user :path \"/user/42\" :path-params {:id \"42\"} ...}"
  []
  (:hyper/route *request*))

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

   The atomicity guarantee covers the writes made in the batch body — not
   writes made by watches reacting to them.  If one cursor is derived from
   another via a watch (e.g. recomputing total* whenever items* changes),
   the watch only runs after the batched values are committed, so the
   derived cursor updates in a separate atomic update immediately after.
   The page may re-render between the two, showing the new batched values
   with the old derived value for one frame before a follow-up render
   corrects it.  To keep a derived value in the same frame as its source,
   compute it in the batch body instead:

     (h/batch
       (reset! items* new-items)
       (reset! total* (reduce + new-items)))  ;; same atomic update — no lag

   A batch inside background work (future, send-off, fiber) spawned from a
   render or outer batch ignores the conveyed overlay and creates its own,
   flushing to the live app-state when its body completes.

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
  `(if (context/current-overlay)
     ;; Already inside an overlay owned by this thread — just execute;
     ;; the outer boundary flushes.  A conveyed overlay from another
     ;; thread does not count (see context/current-overlay).
     (do ~@body)
     ;; Fresh overlay — snapshot current state, execute, flush.
     (let [{app-state*# :app-state*} (context/require-context! "batch")
           overlay#                  {:state* (atom @app-state*#)
                                      :ops*   (atom [])
                                      :owner  (Thread/currentThread)}]
       (binding [context/*state-overlay* overlay#]
         (let [result# (do ~@body)]
           (context/flush-overlay! app-state*#)
           result#)))))

;; ---------------------------------------------------------------------------
;; Reactive components
;; ---------------------------------------------------------------------------

(defmacro reactive
  "Create a reactive component that re-renders independently when its deps change.

   deps is a vector of Watchable sources (atoms, cursors, or any type
   extending Watchable).  The body is a hiccup expression that will be
   wrapped in a div with a stable ID.

   When any dep changes, only this component re-renders and a targeted Datastar
   fragment is sent — the rest of the page is untouched.  During full page
   re-renders, the body is always re-executed (since it may close over parent
   data not tracked in deps) and the result is cached for future partial renders.

   Supports nesting — inner reactive blocks re-execute independently during
   partial renders triggered by their own deps.

   An optional leading opts map accepts `:key` — a stable identity for the
   region.  Give a `:key` (or a stable `:id` on the body's root element) to
   any reactive in a dynamic/keyed list so its identity follows the item
   across reorders and sibling shape changes; without one the region id is
   positional.

   Usage:
     (let [clock* (h/global-cursor :clock)]
       (reactive [clock*]
         [:p \"The time is: \" @clock*]))

     ;; Multiple deps
     (let [x* (h/tab-cursor :x 0)
           y* (h/tab-cursor :y 0)]
       (reactive [x* y*]
         [:p \"Position: \" @x* \", \" @y*]))

     ;; Keyed region in a list — identity follows the node
     (reactive {:key (:id node)} [grid*]
       [:div.grid (render-grid @grid*)])"
  [& args]
  (let [[opts deps body] (if (map? (first args))
                           [(first args) (second args) (nnext args)]
                           [nil (first args) (next args)])]
    `(let [{tab-id# :tab-id app-state*# :app-state*} (context/require-context! "reactive")
           idx#                                      (if context/*action-idx* (swap! context/*action-idx* inc) 0)
           fallback-id#                              (str "r_" tab-id# "_" idx#)
           deps#                                     ~deps
           render-fn#                                (fn [] ~@body)]
       (reactive/render-component app-state*# tab-id# ~(:key opts) fallback-id# deps# render-fn#))))

(defmacro async
  "Render-time data loading with a placeholder.  Spawns the `fetch` on a
   background virtual thread, renders a placeholder immediately, and re-renders
   *just this region* when the value lands — no manual loading-state plumbing.

   Shape (a sibling of `reactive`):

     (h/async [deps] fetch-expr binding & render-body)

   - `deps`        a vector of Watchable sources (like `reactive`).  `[]` means
                   fetch once per mount; otherwise a change to a dep refetches
                   (stale-while-revalidate — see `:reloading` below).
   - `fetch-expr`  a single expression evaluated on a background worker thread
                   while the placeholder renders (wrap multiple forms in `do`).
                   Blocking I/O is fine (it is a virtual thread).
   - `binding`     a destructuring form bound to the status map.
   - `render-body` hiccup rendered from the status; must return a single rooted
                   element (as with `reactive`) so the region id can be injected.

   The status map is `{:status :result :error}` where `:status` is one of:
   - `:loading`   — first load in flight; `:result` is nil.
   - `:ready`     — `:result` holds the fetched value (a nil result is
                    `{:status :ready :result nil}`).
   - `:error`     — `:error` holds the throwable; `:result` keeps the prior
                    value (if any) so you can show stale data with an error.
   - `:reloading` — a dep changed and a refetch is in flight; `:result` still
                    holds the previous value (stale-while-revalidate).

   Example:

     (defn rows-page [req]
       (let [user-id* (h/path-cursor :user 0)]
         (h/async [user-id*]
           (db/fetch-rows @user-id*)
           {:keys [status result error]}
           (case status
             :ready     (render-rows result)
             :error     [:p \"Failed: \" (ex-message error)]
             :reloading [:div.stale (render-rows result)]
             [:p \"Loading…\"]))))

   `async` is a *declaration*: rendering it registers a region and starts the
   fetch on a worker thread, so it belongs in the render body like `reactive`.
   The region is torn down (and any in-flight fetch interrupted) when it
   disappears from the view tree, on navigation, or on tab disconnect.

   Prefer `async` for leaf / region-local data that wants its own loading state
   co-located with its render.  For page-level data you want before first paint
   or shared across regions, load it in a form-2 setup closure into a cursor.

   An optional leading opts map accepts `:key` — a stable identity for the
   region.  Give a `:key` to any async in a dynamic/keyed list so its in-flight
   fetch and store follow the item across reorders and sibling shape changes;
   without one the region id is positional.

   Example with a key:
     (h/async {:key (:id node)} [user-id*]
       (db/fetch-rows @user-id*)
       {:keys [status result]}
       ...)"
  [& args]
  (let [[opts deps fetch-expr binding render-body]
        (if (map? (first args))
          [(first args) (second args) (nth args 2) (nth args 3) (drop 4 args)]
          [nil (first args) (second args) (nth args 2) (drop 3 args)])]
    `(let [{tab-id#     :tab-id
            app-state*# :app-state*
            session-id# :session-id} (context/require-context! "async")
           idx#                      (if context/*action-idx* (swap! context/*action-idx* inc) 0)
           fallback-id#              (str "async_" tab-id# "_" idx#)]
       (subview/render-async! app-state*# tab-id# session-id# ~(:key opts) fallback-id#
                              ~deps
                              (fn [] ~fetch-expr)
                              (fn [~binding] ~@render-body)))))

;; ---------------------------------------------------------------------------
;; View lifecycle (form-3)
;; ---------------------------------------------------------------------------

(defn view
  "Declare a form-3 view that owns an external resource needing explicit
   teardown (a connection, a file handle, a subscription that is not a
   Watchable, etc.).

   A page handler returns a `view` instead of hiccup when it must allocate
   something at mount and release it at unmount.  The framework threads the
   resource immutably through the lifecycle — there is no mutable per-view
   slot, because the server always re-renders declaratively.

   Spec keys:
   - :render  (required) `(fn [resource req] -> hiccup)`.  `resource` is the
              value returned by `:mount` (nil when there is no `:mount`).
              Called on every render; must be pure.
   - :mount   (optional) `(fn [] -> resource)`.  Runs once when the view
              mounts; its return value is the resource.
   - :unmount (optional) `(fn [resource] -> any)`.  Runs once when the view
              unmounts (navigation away, handler redefinition, or tab
              disconnect).

   The view (re)mounts when the page first renders or the route handler
   identity changes; a superseded view is unmounted first.

   Prefer the simpler rungs of the ladder when you can: a pure `(fn [req] ->
   hiccup)` (form-1) when the view owns nothing, or a setup closure
   `(fn [req] (h/watch! …) (fn [req] hiccup))` (form-2) when it owns only
   framework-managed subscriptions.  Reach for `view` only for a genuine
   external resource — frequent need for it is a sign a resource should be
   modeled as Watchable or owned by the system layer (:hyper/env) instead.

   Example:
     (defn report-page [req]
       (h/view
         {:mount   (fn []          (db/open-cursor (h/env :db) :reports))
          :render  (fn [cursor req] [:ul (for [r (db/take! cursor 50)]
                                           [:li (:title r)])])
          :unmount (fn [cursor]     (db/close-cursor cursor))}))"
  [spec]
  (lifecycle/view spec))

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

(defn optimistic
  "Pair a scoped cursor with a client-side signal.  The client is
   authoritative while the user interacts; the cursor is authoritative at
   rest.  Use for continuous gestures (resize, drag, sliders) whose result
   should persist and propagate like any cursor.

   The signal name derives from the cursor's scope and path:
     (optimistic (session-cursor [:cols 0 :width] 240))  ;; → $sessionCols0Width

   During render, `@opt*` returns the Datastar expression string; during an
   action it returns the live client-reported value.  `reset!`/`swap!` in an
   action write the cursor — the authoritative value — and the client is
   patched on the next render.  `commit!` makes the client's value official.

   opts:
   - :auto-commit? — commit the client-reported value on every action POST
   - :on-conflict  — :client-wins (default) | :server-wins |
                     (fn [{:keys [base committed reported]}] → value to commit);
                     :server-wins and fn policies track the base value the
                     client's edit was based on via a companion signal."
  ([cursor]
   (optimistic cursor {}))
  ([cursor opts]
   (let [{:keys [tab-id app-state*]} (context/require-context! "optimistic")]
     (signal/create-optimistic app-state* tab-id cursor opts))))

(defn commit!
  "Make the client-reported value of an optimistic official: run its
   conflict policy and write the result to the backing cursor.  Returns
   the committed value.  Action-only.

     [:div.handle {:data-on:pointerup (action (commit! w*))}]

   Equivalent to `(reset! opt* @opt*)` plus conflict detection.  To
   validate or transform on the way in, skip the sugar:

     (action (reset! w* (clamp @w* 80 640)))"
  [opt*]
  (signal/commit! opt*))

;; ---------------------------------------------------------------------------
;; Connection status signals (client-only, maintained by Datastar)
;; ---------------------------------------------------------------------------

(def connected?*
  "Static client-only boolean signal — true while the SSE connection is
   healthy, false while disconnected/reconnecting.  Maintained entirely
   client-side from Datastar's connection lifecycle (the server cannot report
   on a connection that is down).

   Deref in render/`expr` yields its Datastar expression; deref in an action
   throws (connection state is not server-readable).

     [:div {:data-show (h/expr (not @h/connected?*))} \"Reconnecting…\"]"
  signal/connected?*)

(def connection*
  "Static client-only signal holding the SSE connection status as a keyword
   token from `connection-states` (`:connecting`, `:open`, `:reconnecting`,
   `:error`, `:closed`).  Use it for richer connection UX; compare against
   keyword tokens (they compile to the wire string):

     [:span {:data-show (h/expr (= @h/connection* :reconnecting))} \"Reconnecting…\"]
     [:span {:data-show (h/expr (= @h/connection* :error))}        \"Connection lost\"]"
  signal/connection*)

(def connection-states
  "The set of keyword tokens `connection*` may hold."
  signal/connection-states)

(defn reconnect
  "Return a Datastar expression that re-opens this tab's SSE connection, for
   binding to an event attribute — e.g. a \"Retry\" button shown when
   `connection*` is `:error`:

     [:button {:data-on:click (h/reconnect)} \"Retry\"]

   This is a *soft* reconnect: it re-attaches to the still-living tab (within
   the disconnect grace window), so cursor state, signals, and workers are
   preserved — unlike a full page reload, which starts a fresh tab.  Useful for
   the connection states Datastar does not auto-retry (`:error` / `:closed`).

   Reuses the exact same `@get` the page booted with (endpoint, base-path, and
   `openWhenHidden`), so the two can't drift.  Must be called in render
   context."
  []
  (let [{:keys [tab-id app-state*]} (context/require-context! "reconnect")
        base-path                   (get @app-state* :base-path "")
        owh?                        (get @app-state* :open-when-hidden? true)]
    (server/sse-connect-expr base-path tab-id owh?)))

;; ---------------------------------------------------------------------------
;; Client param support for actions
;; ---------------------------------------------------------------------------

(def client-param
  "Multimethod mapping a client-param symbol (like `$value`, `$key`) to its
   definition — a map of :js (JavaScript run client-side to extract the
   value) and :key (the key used to send it to the server). The built-ins
   are $value, $checked, $key, $detail, $form-data, $form, and $files.

   Extend it to add custom client-side param symbols, dispatching on the
   symbol itself:

     (defmethod client-param '$mouse-offset [_]
       {:js \"{x:evt.offsetX, y:evt.offsetY}\" :key \"mouseOffset\"})

   With this in place, an `action` body can reference `$mouse-offset`,
   bound on the server to an EDN map with :x and :y keys. Methods must be
   defined before any action that references the symbol. A definition
   marked :multipart? true switches the action's transport to a
   multipart/form-data upload instead of the default JSON @post()."
  client-params/client-param)

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

(defmacro ^{:hyper/datastar-expr true} action
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

     ;; :when is sugar — an action returns a Datastar expression that composes
     ;; inside `expr`, so the guard above is equivalent to:
     [:input {:data-on:keydown (expr (when (= evt.key \"Enter\")
                                       (action (search!))))}]

     ;; Named action for testing — :as gives the action a human-readable name
     ;; that hyper.test/test-page uses as the key in its :actions map
     [:button {:data-on:click (action {:as \"increment\"}
                                (swap! (tab-cursor :count) inc))}
      \"Increment\"]

     ;; :key gives the action a stable id (independent of render order) — the
     ;; same identity story as reactive/async, for actions in keyed lists
     [:button {:data-on:click (action {:key (:id node)}
                                (delete! (:id node)))}
      \"Delete\"]

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
                                 (some #{:when :as :upload :key} (keys maybe-opts)))
        [js as-name upload-ref-form key-form body]
        (if opts-map?
          (let [guard (:when maybe-opts)
                ;; String literals are validated here; any other form
                ;; (e.g. (expr ...)) is deferred to runtime evaluation —
                ;; sanitize-guard validates the result.
                js    (cond
                        (string? guard) (when-not (str/blank? guard) guard)
                        (some? guard)   guard)]
            [js (:as maybe-opts) (:upload maybe-opts) (:key maybe-opts) body])
          [nil nil nil nil args])
        _                   (when (and opts-map? (contains? maybe-opts :when))
                              (actions/warn-deprecated-when!))
        used-params         (find-client-params body)
        param-syms          (keys used-params)
        ;; Multipart params ($form/$files) switch the action to a file-upload
        ;; transport (see hyper.uploads).  Validate the combination at macro
        ;; time so misuse fails loudly with a clear message.
        multipart-syms      (filter client-params/multipart-param? param-syms)
        other-syms          (remove (set multipart-syms) param-syms)
        _                   (when (> (count multipart-syms) 1)
                              (throw (ex-info "An upload action may use at most one multipart param ($form or $files)."
                                              {:params (vec multipart-syms)})))
        _                   (when (and (seq multipart-syms) (seq other-syms))
                              (throw (ex-info "Multipart upload params ($form/$files) cannot be combined with other client params."
                                              {:multipart (vec multipart-syms) :others (vec other-syms)})))
        _                   (when (and upload-ref-form (empty? multipart-syms))
                              (throw (ex-info "action :upload requires a $form or $files param in the body."
                                              {:upload upload-ref-form})))
        cp-sym              (gensym "client-params")]
    `(let [{session-id# :session-id
            tab-id#     :tab-id
            app-state*# :app-state*
            router#     :router}    (context/require-context! "action")
           upload-ref#              ~upload-ref-form
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
           key#                     ~key-form
           action-id#               (if (some? key#)
                                      (subview/scoped-id "a_" tab-id# (subview/key->token key#))
                                      (str "a_" tab-id# "_" idx#))
           _#                       (actions/register-action! app-state*# session-id# tab-id# action-fn# action-id#
                                                              ~(when as-name {:as as-name}))
           ;; Stash the upload status ref so the /hyper/upload handler can
           ;; transition its phase/result; nil for non-upload actions.
           _#                       (when upload-ref#
                                      (swap! app-state*# assoc-in [:actions action-id# :upload-ref] upload-ref#))
           base-path#               (get @app-state*# :base-path "")]
       (uploads/build-expr action-id# '~used-params (actions/sanitize-guard ~js) base-path# upload-ref#))))

;; ---------------------------------------------------------------------------
;; Client-side expressions and components (re-exports)
;; ---------------------------------------------------------------------------

(defmacro expr
  "Compile Clojure forms into a Datastar expression string for use in
   data-* attributes, action :when guards, etc.

   Signals use atom vocabulary — the same (reset! sig v) that means a
   server round-trip inside `action` compiles to an instant client-side
   assignment here:

     (let [open?* (local-signal :open false)]
       [:button {:data-on:click (expr (swap! open?* not))} \"Toggle\"]
       [:div {:data-show (expr @open?*)} \"...\"])

     [:input {:data-on:keydown
              (expr (when (= evt.key \"Enter\") (@post \"/search\")))}]

   A server `action` composes as a client-side value — it registers at render
   time and contributes its raw @post(...), so it can be gated by client-side
   control flow (subsuming the action `:when` guard):

     [:input {:data-on:keydown
              (expr (when (= evt.key \"Enter\") (action (search! @query*))))}]

   Locals splice automatically; evt/el/$signals/JS interop pass through
   to the client."
  [& forms]
  `(hyper.expr/->expr ~@forms))

(defmacro defc
  "Define a client-side web component, authored in a CLJS dialect (Squint)
   and compiled to JavaScript on the JVM at macro-expansion time.

     (defc temp-gauge
       [{:keys [value max label]}]
       (event ::selected [_e]
         (emit \"gauge-selected\" {:value value :label label}))
       (render
         [:div {:on {:click ::selected}} label \": \" value]))

   Also defines a server-side render function of the same name, so pages
   call components like ordinary hiccup functions."
  {:style/indent        1
   :style.cljfmt/indent [[:block 1] [:inner 1]]}
  [& args]
  `(component/defc ~@args))

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
     (when-let [route (routes/tab-route router route-name params query-params)]
       (let [href          (routes/route-url router route)
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
                               (state/set-tab-route! app-state* tab-id route)))
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

(def default-malli-transformer
  "The default :param-transformer for create-handler: malli's string
   transformer, extended so a single query value decodes into a one-element
   vector, sequence or set.

   Compose with it to customise how route params are decoded and encoded:

     (create-handler routes
                     :param-transformer (mt/transformer my-transformer
                                                        default-malli-transformer))"
  routes/default-malli-transformer)

(defn create-handler
  "Create a Ring handler for a hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.

   Options (keyword arguments):
   - :app-state         — Atom for application state (default: fresh atom)
   - :datastar-script   - Override of the default datastar script tag (as Hiccup) or nil to suppress
   - :squint-core-url   - URL the page imports squint core from, for h/expr and
                          components (default: /hyper/squint-core.js, served by hyper).
   - :tree-shake?       - Serve only the squint core functions that h/expr output
                          and defc components use. Needs babashka.esbuild on the classpath.
   - :head              — Hiccup nodes appended to the HTML <head>, or (fn [req] ...) -> hiccup
   - :webkit-sse-shim?  — Inject a small client shim (into <head>, only for WebKit/Safari
                          user agents) that routes the GET render stream through a native
                          EventSource instead of fetch+ReadableStream, working around a
                          WebKit bug where a large isolated SSE patch is held back until
                          the next write.  Other browsers never receive it.  Default true;
                          pass false to disable.
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
   - :render-error      — Function `(fn [error req] -> hiccup)` rendered when a
                          view's render-fn throws.  May be a Var to pick up
                          redefinitions without restarting the server.  Defaults
                          to `hyper.render.error/minimal` (generic, production-
                          safe).  Use `hyper.render.error/explain` in development
                          to render the message, ex-data, and full stack trace.
   - :not-found         — Function `(fn [req] -> hiccup)` rendered when no route
                          matches, served as a full page with HTTP 404 (and over
                          SSE for client-side navigation).  May be a Var to pick
                          up redefinitions.  Defaults to a built-in 404 page;
                          pass `nil` to disable and fall back to reitit's
                          plain-text 404.
   - :param-transformer — Malli transformer used to decode route path and query
                          params from URL strings (per the route's :parameters)
                          and to encode them into the URLs Hyper builds, so values
                          round-trip.  Defaults to `default-malli-transformer`;
                          compose with it to add custom encodings.

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
                    render-error squint-core-url tree-shake? render-guard
                    max-file-size max-file-count upload-expires-in param-transformer]
             :or   {app-state       (atom (state/init-state))
                    datastar-script server/default-datastar-script}
             :as   opts}]
  (server/create-handler routes app-state
                         (cond-> {:head              head
                                  :datastar-script   datastar-script
                                  :static-resources  static-resources
                                  :static-dir        static-dir
                                  :watches           watches
                                  :base-path         base-path
                                  :middleware        middleware
                                  :render-middleware render-middleware
                                  :squint-core-url   squint-core-url
                                  :tree-shake?       tree-shake?}
                           ;; Only forward when supplied so server defaults apply.
                           render-error (assoc :render-error render-error)
                           render-guard (assoc :render-guard render-guard)
                           param-transformer (assoc :param-transformer param-transformer)
                           ;; File-upload limits for the /hyper/upload route.
                           max-file-size (assoc :max-file-size max-file-size)
                           max-file-count (assoc :max-file-count max-file-count)
                           upload-expires-in (assoc :upload-expires-in upload-expires-in)
                           ;; `contains?` so an explicit `false` is honored while
                           ;; absence lets the server default (true) apply.
                           (contains? opts :webkit-sse-shim?) (assoc :webkit-sse-shim? (:webkit-sse-shim? opts))
                           ;; `contains?` so an explicit `:not-found nil` (disable) is honored.
                           (contains? opts :not-found) (assoc :not-found (:not-found opts)))))

(defn start!
  "Start the hyper application server.

   handler: Ring handler created with create-handler
   options:
   - :port            - Port to run server on (default: 3000)
   - :stop-timeout-ms - Jetty graceful-stop timeout (default: 1000)

   Returns a stop function. Call (stop! app) to shut down the server
   and clean up all tab resources (watchers, SSE channels, actions).

   Example:
     (def handler (create-handler routes))
     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [handler opts]
  (server/start! handler opts))

(defn stop!
  "Stop the hyper application server and clean up all resources.

   app: Stop function returned from start!"
  [app]
  (server/stop! app))
