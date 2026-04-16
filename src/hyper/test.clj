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
            [hyper.render :as render]
            [hyper.state :as state]))

(def ^:private default-session-id "test-session")
(def ^:private default-tab-id "test-tab")

(defn- build-actions-map
  "Build the actions map for the test result. Actions with an :as name are
   keyed by that name; others are keyed by their action-id."
  [app-state* tab-id]
  (let [action-ids (get-in @app-state* [:actions-by-tab tab-id])]
    (reduce (fn [acc action-id]
              (let [action-data (get-in @app-state* [:actions action-id])
                    key         (or (:as action-data) action-id)]
                (assoc acc key (select-keys action-data [:fn]))))
            {}
            action-ids)))

(defn- collect-watches
  "Collect the external sources watched via h/watch! during render.
   During an initial render (no SSE), watches are stashed as pending."
  [app-state* tab-id]
  (let [pending (get-in @app-state* [:tabs tab-id :pending-watches])]
    (vec (vals (or pending {})))))

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
   - :req         — Extra keys to merge into the request map passed to handler.

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
         extra-req  (:req opts)]

     ;; Ensure session and tab exist in state
     (state/get-or-create-tab! app-state* session-id tab-id)
     (state/set-tab-route! app-state* tab-id route)

     ;; Seed cursor state when provided
     (when-let [global-data (:global cursors)]
       (swap! app-state* update :global merge global-data))
     (when-let [session-data (:session cursors)]
       (swap! app-state* update-in [:sessions session-id :data] merge session-data))
     (when-let [tab-data (:tab cursors)]
       (swap! app-state* update-in [:tabs tab-id :data] merge tab-data))

     ;; Build the request context
     (let [req (cond-> {:hyper/session-id session-id
                        :hyper/tab-id     tab-id
                        :hyper/app-state  app-state*
                        :hyper/route      route}
                 extra-req (merge extra-req))]

       ;; Bind context vars and render
       (push-thread-bindings {#'context/*request*          req
                              #'context/*action-idx*       (atom 0)
                              #'context/*declared-signals* (atom [])})
       (try
         (let [body  (render/safe-render handler req)
               ;; Ring response passthrough
               ring? (and (map? body) (:status body))]
           (if ring?
             body
             (let [declared  @context/*declared-signals*
                   body-html (c/html body)
                   signals   (reduce (fn [acc {:keys [path] :as entry}]
                                       (assoc acc path (dissoc entry :path)))
                                     {}
                                     declared)]
               {:body        body
                :body-html   body-html
                :title       nil
                :url         (state/build-url (:path route) (:query-params route))
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
           tab-id     (::tab-id result)
           route      (get-in @app-state* [:tabs tab-id :route])]
       (binding [context/*request* {:hyper/session-id session-id
                                    :hyper/tab-id     tab-id
                                    :hyper/app-state  app-state*}]
         ((:fn action) client-params))
       {:cursors   (cursors-snapshot app-state* session-id tab-id route)
        :app-state app-state*}))))
