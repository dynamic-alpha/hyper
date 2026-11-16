(ns ^:no-doc hyper.state
  "State management for hyper applications.

   Manages session and tab-scoped state using atoms and cursors.
   Cursors implement IRef for familiar Clojure semantics.

   State structure:
   {:global {}
    :sessions {session-id {:data {} :tabs #{tab-id}}}
    :tabs {tab-id {:data {} :session-id session-id :render-fn fn :sse-channel ch
                   :route {:name :home :path \"/\" :path-params {} :query-params {}}}}
    :actions {action-id {:fn fn :session-id sid :tab-id tid}}
    :router <reitit-router>
    :routes <original-routes-vector>
    :routes-source <var-or-routes-vector>}"
  (:require [clojure.string]
            [hyper.context :as context]
            [hyper.utils :as utils]
            [taoensso.telemere :as t]))

(defn normalize-path
  "Convert keyword or vector to vector path."
  [path]
  (if (keyword? path)
    [path]
    (vec path)))

(defn- missing-session?
  "Whether `path` is in a session that `state` holds no entry for."
  [state path]
  (and (= :sessions (first path))
       (not (contains? (:sessions state) (second path)))))

(defn- write!
  "Apply op `op` (see `context/apply-ops`) to `parent-atom`, returning the
   states before and after it.  A write to a session that no longer exists
   does nothing and logs a warning."
  [parent-atom {:keys [path] :as op}]
  (let [[before :as states] (swap-vals! parent-atom context/apply-ops [op])]
    (when (missing-session? before path)
      (t/log! {:level :warn
               :id    :hyper.warn/session-cursor-write-dropped
               :data  {:hyper/session-id (second path) :hyper/path path}
               :msg   "Session cursor write to a session that no longer exists was dropped"}))
    states))

(deftype Cursor [parent-atom full-path meta-data ^:volatile-mutable validator watches]
  clojure.lang.IRef
  (deref [_]
    (if-let [{state* :state*} (context/current-overlay)]
      (get-in @state* full-path)
      (get-in @parent-atom full-path)))

  (setValidator [_ vf]
    (set! validator vf))

  (getValidator [_]
    validator)

  (getWatches [_]
    @watches)

  (addWatch [this key callback]
    (swap! watches assoc key callback)
    (add-watch parent-atom key
               (fn [k _r old-state new-state]
                 (let [old-val (get-in old-state full-path)
                       new-val (get-in new-state full-path)]
                   (when (not= old-val new-val)
                     (callback k this old-val new-val)))))
    this)

  (removeWatch [_this key]
    (swap! watches dissoc key)
    (remove-watch parent-atom key)
    _this)

  clojure.lang.IAtom
  ;; With an owned overlay, each mutation is applied to the shadow :state*
  ;; (for read-your-writes) and appended to :ops* as a composable operation
  ;; replayed against live state at flush.  Without an overlay, writes hit
  ;; the live parent-atom directly like a plain atom.  Either way a write to a
  ;; session that no longer exists is dropped (see `write!`).
  (swap [_ f]
    (context/guard-effect! :cursor-mutation (str "swap! " full-path))
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (let [new-val (get-in (swap! state* update-in full-path f) full-path)]
        (swap! ops* conj {:kind :update :path full-path :f f})
        new-val)
      (get-in (second (write! parent-atom {:kind :update :path full-path :f f})) full-path)))

  (swap [_ f arg]
    (context/guard-effect! :cursor-mutation (str "swap! " full-path))
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (let [g       (fn [v] (f v arg))
            new-val (get-in (swap! state* update-in full-path g) full-path)]
        (swap! ops* conj {:kind :update :path full-path :f g})
        new-val)
      (get-in (second (write! parent-atom {:kind :update :path full-path :f #(f % arg)})) full-path)))

  (swap [_ f arg1 arg2]
    (context/guard-effect! :cursor-mutation (str "swap! " full-path))
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (let [g       (fn [v] (f v arg1 arg2))
            new-val (get-in (swap! state* update-in full-path g) full-path)]
        (swap! ops* conj {:kind :update :path full-path :f g})
        new-val)
      (get-in (second (write! parent-atom {:kind :update :path full-path :f #(f % arg1 arg2)})) full-path)))

  (swap [_ f arg1 arg2 args]
    (context/guard-effect! :cursor-mutation (str "swap! " full-path))
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (let [g       (fn [v] (apply f v arg1 arg2 args))
            new-val (get-in (swap! state* update-in full-path g) full-path)]
        (swap! ops* conj {:kind :update :path full-path :f g})
        new-val)
      (get-in (second (write! parent-atom {:kind :update :path full-path :f #(apply f % arg1 arg2 args)})) full-path)))

  (compareAndSet [_ oldv newv]
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (let [current-val (get-in @state* full-path)]
        (if (= current-val oldv)
          (do (swap! state* assoc-in full-path newv)
              (swap! ops* conj {:kind :cas :path full-path :old oldv :new newv})
              true)
          false))
      (let [[before] (write! parent-atom {:kind :cas :path full-path :old oldv :new newv})]
        (and (not (missing-session? before full-path))
             (= oldv (get-in before full-path))))))

  (reset [_ newv]
    (context/guard-effect! :cursor-mutation (str "reset! " full-path))
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (do (swap! state* assoc-in full-path newv)
          (swap! ops* conj {:kind :reset :path full-path :value newv})
          newv)
      (do (write! parent-atom {:kind :reset :path full-path :value newv})
          newv)))

  clojure.lang.IMeta
  (meta [_] @meta-data)

  clojure.lang.IReference
  (alterMeta [_ f args]
    (apply swap! meta-data f args))

  (resetMeta [_ m]
    (reset! meta-data m)))

(defn create-cursor
  "Create a cursor pointing to a path in the parent atom.
   path-prefix is the base path, path is relative to that."
  [parent-atom path-prefix path]
  (let [full-path (into (vec path-prefix) (normalize-path path))]
    (->Cursor parent-atom full-path (atom {}) nil (atom {}))))

(defn init-default!
  "Initialize a cursor's path with `default-value`, but only when the path is
   currently *absent* — never written.  A path explicitly holding nil is left
   untouched, so a value a component set to nil is not silently reset to the
   default on the next render.  Does nothing in a session that no longer
   exists.  Returns the cursor.

   Under an owned overlay (render or `batch`) the init is recorded as an
   :init op replayed at flush, so it yields to any concurrent same-path write
   — including one that sets the path to nil — rather than clobbering it."
  [^Cursor cursor default-value]
  (let [full-path   (.-full-path cursor)
        parent-atom (.-parent-atom cursor)]
    (if-let [{state* :state* ops* :ops*} (context/current-overlay)]
      (when (identical? ::absent (get-in @state* full-path ::absent))
        (swap! state* assoc-in full-path default-value)
        (swap! ops* conj {:kind :init :path full-path :value default-value}))
      (loop []
        (let [s @parent-atom]
          (when (and (identical? ::absent (get-in s full-path ::absent))
                     (not (missing-session? s full-path)))
            (when-not (compare-and-set! parent-atom s (assoc-in s full-path default-value))
              (recur)))))))
  cursor)

(defn stamp-scope!
  "Stamp a cursor with :hyper/scope and :hyper/path metadata.  Returns the
   cursor."
  [cursor scope path]
  (reset-meta! cursor {:hyper/scope scope
                       :hyper/path  (normalize-path path)})
  cursor)

(defn session-cursor
  "Create a cursor to session state at the given path.
   If default-value is provided and the path is unset, initializes with
   default-value.  A path explicitly holding nil is left untouched."
  ([app-state* session-id path]
   (stamp-scope! (create-cursor app-state* [:sessions session-id :data] path)
                 :session path))
  ([app-state* session-id path default-value]
   (init-default! (session-cursor app-state* session-id path) default-value)))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   If default-value is provided and the path is unset, initializes with
   default-value.  A path explicitly holding nil is left untouched."
  ([app-state* tab-id path]
   (stamp-scope! (create-cursor app-state* [:tabs tab-id :data] path)
                 :tab path))
  ([app-state* tab-id path default-value]
   (init-default! (tab-cursor app-state* tab-id path) default-value)))

(defn global-cursor
  "Create a cursor to global state at the given path.
   Global state is shared across all sessions and tabs.
   If default-value is provided and the path is unset, initializes with
   default-value.  A path explicitly holding nil is left untouched."
  ([app-state* path]
   (stamp-scope! (create-cursor app-state* [:global] path)
                 :global path))
  ([app-state* path default-value]
   (init-default! (global-cursor app-state* path) default-value)))

(defn init-state
  "Create initial app state structure."
  []
  {:global         {}
   :sessions       {}
   :tabs           {}
   :actions        {}
   :actions-by-tab {}
   :router         nil
   :routes         nil})

(defn get-or-create-session!
  "Ensure session exists in app-state."
  [app-state* session-id]
  (swap! app-state* update-in [:sessions session-id]
         #(or % {:data {} :tabs #{}}))
  nil)

(defn get-or-create-tab!
  "Ensure tab exists in app-state and is linked to session, creating the
   session if missing.  A tab already linked to another session keeps that
   link, and session is then left as it is."
  [app-state* session-id tab-id]
  (swap! app-state* (fn [state]
                      (let [state (update-in state [:tabs tab-id]
                                             (fn [tab]
                                               (cond
                                                 (nil? tab)              {:data        {}
                                                                          :session-id  session-id
                                                                          :render-fn   nil
                                                                          :sse-channel nil}
                                                 (nil? (:session-id tab)) (assoc tab :session-id session-id)
                                                 :else                   tab)))]
                        ;; One swap, so a concurrent removal of the session's
                        ;; last tab cannot leave this tab without its session.
                        (cond-> state
                          (= session-id (get-in state [:tabs tab-id :session-id]))
                          (update-in [:sessions session-id]
                                     #(-> (merge {:data {} :tabs #{}} %)
                                          (update :tabs (fnil conj #{}) tab-id)))))))
  nil)

(defn cleanup-tab!
  "Remove tab state and unlink it from its session, removing the session
   when this was its last tab."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [session-id (get-in state [:tabs tab-id :session-id])
                            state      (update state :tabs dissoc tab-id)
                            session    (get-in state [:sessions session-id])]
                        (cond
                          (nil? session)
                          state

                          (empty? (disj (:tabs session) tab-id))
                          (update state :sessions dissoc session-id)

                          :else
                          (update-in state [:sessions session-id :tabs] disj tab-id)))))
  nil)

(defn set-tab-route!
  "Set the current route for a tab."
  [app-state* tab-id route-info]
  (swap! app-state* assoc-in [:tabs tab-id :route] route-info)
  nil)

(defn get-tab-route
  "Get the current route for a tab."
  [app-state* tab-id]
  (get-in @app-state* [:tabs tab-id :route]))

(defn parse-query-string
  "Parse a query string into a keyword-keyed map with URL-decoded values.
   Returns nil if query-string is nil.
   Delegates to hyper.utils/parse-query-string."
  [query-string]
  (utils/parse-query-string query-string))

(defn build-url
  "Build a URL string from a path and query params map.
   Omits query params with nil values.
   Returns path if no query params remain.
   Delegates to hyper.utils/build-url."
  [path query-params]
  (utils/build-url path query-params))
