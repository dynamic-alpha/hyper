(ns hyper.test
  "Testing utilities for hyper page handlers.

   Provides `test-page` and `test-action` for rendering pages and
   simulating user interactions in an isolated test context.

   Example:
     (require '[hyper.test :as ht])
     (require '[hyper.core :as h])

     (defn my-page [req]
       (let [count* (h/tab-cursor :count 0)]
         [:div
          [:h1 \"Count: \" @count*]
          [:button {:data-on:click (h/action {:as \"increment\"}
                                    (swap! (h/tab-cursor :count) inc))}
           \"Increment\"]]))

     (let [result (ht/test-page my-page)]
       ;; Assert on rendered output
       (assert (str/includes? (:body-html result) \"Count: 0\"))
       ;; Simulate button click and check cursors
       (let [after (ht/test-action result \"increment\")]
         (assert (= 1 (get-in after [:cursors :tab :count]))))
       ;; Re-render and verify
       (let [result2 (ht/test-page my-page {:app-state (:app-state result)})]
         (assert (str/includes? (:body-html result2) \"Count: 1\"))))"
  (:require [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.effects :as effects]
            [hyper.lifecycle :as lifecycle]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.render.error :as render.error]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [reitit.core :as reitit]))

(def ^:private default-session-id "test-session")
(def ^:private default-tab-id "test-tab")

(defn- flatten-routes
  "Flatten a (possibly nested) reitit routes vector by compiling it and
   reading the routes back, mirroring how the server stores :routes in
   app-state for live route-metadata lookups."
  [routes]
  (when (seq routes)
    (-> routes reitit/router reitit/routes)))

(defn- resolve-router
  "Resolve `{:router r :routes flat-routes}` for navigation support in a test
   render, from the test-page opts.  Returns nil when no router/routes are
   supplied (so existing tests are unaffected).

   opts keys (all optional):
   - :router            — a pre-built reitit router, used as-is for name matching.
   - :routes            — a reitit routes vector (or a Var holding one) from
                          which a router is compiled as the server does, so
                          URL encoding matches production.  Each route's :name
                          is what h/navigate and effects/navigate! match against.
   - :param-transformer — malli transformer for route params of a compiled router.

   When both :router and :routes are supplied, :router is used for matching and
   :routes for the flattened route index (titles, render fns)."
  [{:keys [router routes param-transformer]}]
  (let [routes (cond-> routes (var? routes) deref)]
    (cond
      router
      {:router router
       :routes (flatten-routes (or routes (reitit/routes router)))}

      (reitit/router? routes)
      {:router routes
       :routes (flatten-routes (reitit/routes routes))}

      (seq routes)
      (let [flat (flatten-routes routes)]
        {:router (routes/build-router flat {:param-transformer param-transformer})
         :routes flat})

      :else nil)))

(defn- build-actions-map
  "Build the actions map for the test result. Actions with an :as name are
   keyed by that name; others are keyed by their action-id."
  [app-state* tab-id]
  (let [action-ids (get-in @app-state* [:actions-by-tab tab-id])]
    (reduce (fn [acc action-id]
              (let [action-data (get-in @app-state* [:actions action-id])
                    key         (or (:as action-data) action-id)]
                (assoc acc key (select-keys action-data [:fn :as]))))
            {}
            action-ids)))

(defn- collect-watches
  "Collect the external sources watched via h/watch! during render.
   User watches are mount-scoped, full-render subviews (see hyper.subview);
   in a test render there is no SSE renderer, so they are registered but not
   yet wired — the sources are still recoverable from the registry."
  [app-state* tab-id]
  (subview/watched-sources app-state* tab-id))

(defn- cursors-snapshot
  "Take a snapshot of cursor values for a session/tab."
  [app-state* session-id tab-id route]
  {:global  (get-in @app-state* [:global])
   :session (get-in @app-state* [:sessions session-id :data])
   :tab     (get-in @app-state* [:tabs tab-id :data])
   :route   route})

(defn test-page
  "Render a page handler in an isolated test context and return a result map.

   handler: A page render function (fn [req] -> hiccup).

   opts (optional map):
   - :app-state   — Atom for application state. Pass the :app-state from a
                     previous test-page call to preserve state across renders.
                     Default: fresh atom with init-state.
   - :cursors     — Initial cursor values to seed before rendering. A map with
                     optional keys :global, :session, :tab, each a map of data
                     that is merged into the corresponding cursor scope.
                     Example: {:tab {:count 5} :session {:user \"alice\"}}
   - :session-id  — Session ID string. Default: \"test-session\".
   - :tab-id      — Tab ID string. Default: \"test-tab\".
   - :route       — Route info map {:name :path :path-params :query-params}.
                     Default: {:name :test-page :path \"/\" :path-params {} :query-params {}}.
   - :routes      — A reitit routes vector (or a Var holding one) used to build
                     a bound router so that `h/navigate` (during render) and
                     `effects/navigate!` (during actions, via `test-action`)
                     resolve named routes reliably — no need to spin up a full
                     `create-handler`. The router and flattened routes are stored
                     in app-state and threaded into the request as :hyper/router,
                     and they persist across re-renders that share :app-state.
                     Example: {:routes [[\"/\" {:name :home :get home-fn}]
                                        [\"/about\" {:name :about :get about-fn}]]}.
   - :router      — A pre-built reitit router, used as-is for name matching.
                     Params are encoded into URLs only if it is a Ring router
                     with a :coercion. Escape hatch for advanced cases; prefer
                     :routes. When both are given, :router matches and :routes
                     provides metadata.
   - :param-transformer — Malli transformer for route params of the router built
                     from :routes, as passed to create-handler.
                     Default: `default-malli-transformer`.
   - :req         — Extra keys to merge into the request map passed to handler.
   - :render-middleware — Vector of middleware fns to wrap the handler.
                     Each is (fn [handler] (fn [req] ...)), identical to Ring
                     middleware.  Applied in order (first = outermost).

   Returns a map:
   - :body          — Raw hiccup returned by the handler (before HTML serialization).
   - :body-html     — Serialized HTML string of the body.
   - :title         — Resolved page title string, or nil.
   - :url           — Current route URL string.
   - :signals       — Map of signal declarations from the render, keyed by
                       the path used to create the signal (e.g. :user-name,
                       [:user :name]). Each value has :default-val and :local?.
   - :actions       — Map of actions registered during render. Actions with an
                       :as name are keyed by that name; others by their action-id.
                       Each value has :fn which can be called as ((:fn action) client-params).
   - :cursors       — Snapshot of cursor values after render:
                         :global  — global cursor state map
                         :session — this session's cursor data map
                         :tab     — this tab's cursor data map
                         :route   — this tab's route info
   - :watches       — Vector of external sources registered via h/watch!.
   - :app-state     — The app-state atom, for threading into subsequent calls.

   If the handler returns a Ring response map (a map with :status), it is
   returned as-is without wrapping."
  ([handler]
   (test-page handler {}))
  ([handler opts]
   (let [app-state* (or (:app-state opts) (atom (state/init-state)))
         session-id (or (:session-id opts) default-session-id)
         tab-id     (or (:tab-id opts) default-tab-id)
         route      (or (:route opts) {:name         :test-page
                                       :path         "/"
                                       :path-params  {}
                                       :query-params {}})
         cursors    (:cursors opts)
         extra-req  (:req opts)
         resolved   (resolve-router opts)]

     ;; Ensure session and tab exist in state
     (state/get-or-create-tab! app-state* session-id tab-id)
     (state/set-tab-route! app-state* tab-id route)

     ;; Store a bound router + flattened routes so h/navigate (render) and
     ;; effects/navigate! (action) can resolve named routes in tests.
     (when resolved
       (swap! app-state* assoc
              :router (:router resolved)
              :routes (:routes resolved)))

     ;; Seed cursor state when provided
     (when-let [global-data (:global cursors)]
       (swap! app-state* update :global merge global-data))
     (when-let [session-data (:session cursors)]
       (swap! app-state* update-in [:sessions session-id :data] merge session-data))
     (when-let [tab-data (:tab cursors)]
       (swap! app-state* update-in [:tabs tab-id :data] merge tab-data))

     ;; Build the request context
     (let [effective-router (or (:router resolved) (get @app-state* :router))
           req              (cond-> {:hyper/session-id session-id
                                     :hyper/tab-id     tab-id
                                     :hyper/app-state  app-state*
                                     :hyper/route      route}
                              effective-router (assoc :hyper/router effective-router)
                              extra-req        (merge extra-req))]

       ;; Bind context vars and render
       (push-thread-bindings (context/render-bindings req app-state*))
       (try
         (let [wrap-mw         #(render/apply-render-middleware % (:render-middleware opts))
               render-error-fn (or (get @app-state* :render-error) render.error/minimal)
               ;; Route through the same form-1/2/3 dispatch as render-tab so
               ;; test-page exercises form-2 (fn) and form-3 (View) handlers
               ;; and the render purity guard, while preserving the result map.
               body            (lifecycle/render-page app-state* tab-id handler req
                                                      render-error-fn wrap-mw)
               ;; Ring response passthrough
               ring?           (and (map? body) (:status body))]
           (if ring?
             body
             (let [declared    @context/*declared-signals*
                   body-html   (c/html body)
                   subview-ids @context/*registered-subview-ids*
                   signals     (reduce (fn [acc {:keys [path] :as entry}]
                                         (assoc acc path (dissoc entry :path)))
                                       {}
                                       declared)]
               ;; Flush default-value inits from overlay to live atom
               (context/flush-overlay! app-state*)
               ;; Sweep stale subviews (reactive regions) — same as server.clj
               (reactive/sweep-stale-components! app-state* tab-id subview-ids)
               {:body        body
                :body-html   body-html
                :title       nil
                :url         (routes/route-url effective-router route)
                :signals     signals
                :actions     (build-actions-map app-state* tab-id)
                :cursors     (cursors-snapshot app-state* session-id tab-id route)
                :watches     (collect-watches app-state* tab-id)
                :app-state   app-state*
                ;; Internal — used by test-action to recover context
                ::session-id session-id
                ::tab-id     tab-id})))
         (finally
           (pop-thread-bindings)))))))

(defn test-action
  "Execute a named action from a test-page result and return a state snapshot.

   Looks up the action by its :as name (or raw action-id) in the result's
   :actions map, executes it with proper request context bindings, and
   returns a map describing the cursor values after execution.

   result:        The map returned by test-page.
   action-name:   The :as name (or action-id) of the action to execute.
   client-params: Optional map of client params (e.g. {:value \"hello\"}).
                   Simulates $value, $checked, $key, $form-data, etc.

   Returns a map:
   - :cursors    — Cursor values after the action executed:
                      :global, :session, :tab, :route
   - :effects    — Effects accumulated during execution. A map with:
                      :cookies — map of cookie-name to cookie opts
                      :scripts — vector of JS strings to execute
                   Effects are collected but NOT applied — this lets tests
                   assert on what effects *would* happen without actually
                   setting cookies or sending SSE events.
   - :app-state  — The app-state atom, for threading into test-page.

   Throws if the action name is not found in the result.

   Example:
     (let [result (ht/test-page my-page)
           after  (ht/test-action result \"increment\")]
       (is (= 1 (get-in after [:cursors :tab :count]))))

     ;; With client params (simulating $value)
     (let [result (ht/test-page search-page)
           after  (ht/test-action result \"search\" {:value \"clojure\"})]
       (is (= \"clojure\" (get-in after [:cursors :tab :query]))))

     ;; Assert on effects
     (let [result (ht/test-page my-page)
           after  (ht/test-action result \"publish\")]
       (is (seq (get-in after [:effects :scripts]))))

     ;; Chain into another render
     (let [r1 (ht/test-page my-page)
           _  (ht/test-action r1 \"increment\")
           r2 (ht/test-page my-page {:app-state (:app-state r1)})]
       (is (str/includes? (:body-html r2) \"Count: 1\")))"
  ([result action-name]
   (test-action result action-name nil))
  ([result action-name client-params]
   (let [app-state* (:app-state result)
         action     (get-in result [:actions action-name])]
     (when-not action
       (throw (ex-info (str "Action not found: " (pr-str action-name)
                            ". Available actions: "
                            (pr-str (keys (:actions result))))
                       {:action-name       action-name
                        :available-actions (keys (:actions result))})))
     (let [session-id (::session-id result)
           tab-id     (::tab-id result)]
       (binding [context/*request*     {:hyper/session-id session-id
                                        :hyper/tab-id     tab-id
                                        :hyper/app-state  app-state*
                                        :hyper/router     (get @app-state* :router)
                                        :hyper/env        (get-in @app-state* [:tabs tab-id :env])}
                 context/*action-name* (:as action)
                 effects/*pending*     (effects/init-pending)]
         ((:fn action) client-params)
         ;; Read route AFTER execution so navigate! changes are reflected
         (let [route (get-in @app-state* [:tabs tab-id :route])]
           {:cursors   (cursors-snapshot app-state* session-id tab-id route)
            :effects   (effects/collect-pending!)
            :app-state app-state*}))))))
