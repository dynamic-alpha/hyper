(ns ^:no-doc hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [clojure.set]
            [clojure.string :as str]
            [compact-uuids.core :as uuid]
            [hyper.context :as context]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Action expression building (client side of the boundary)
;; ---------------------------------------------------------------------------
;; Used by the hyper.core/action macro expansion at render time.

(defn sanitize-guard
  "Validate an action :when guard at runtime.  Returns the guard string,
   nil for nil/blank guards, and throws for anything else — a guard must
   evaluate to a Datastar expression string (e.g. from `expr` or a raw
   string)."
  [g]
  (cond
    (nil? g)    nil
    (string? g) (when-not (str/blank? g) g)
    :else       (throw (ex-info "action :when guard must evaluate to a string Datastar expression"
                                {:guard g}))))

(defn warn-deprecated-when!
  "Emit a deprecation warning for the `action :when` guard, steering callers to
   the `h/expr` composition.  Called at macro-expansion time."
  []
  (t/log! {:level :warn
           :id    :hyper.warn/action-when-deprecated
           :msg   (str "action :when is deprecated — gate the action with h/expr instead: "
                       "(h/expr (when <cond> (h/action ...))). "
                       "Client-param symbols like $key/$value work inside h/expr.")}))

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

(def concurrency-values
  "Accepted values for an action's :concurrency option."
  #{:queue :drop :latest})

(defn register-action!
  "Register an action function and return its ID.
   Stores in app-state under [:actions action-id].

   When action-id is provided it is used as-is (deterministic actions).
   When omitted a random UUID is generated (legacy / dynamic actions).

   opts may include:
   - :as          — a human-readable name for the action, useful for testing
   - :navigation? — the action changes the tab's route, so it runs
                    immediately rather than on the tab's action queue
   - :concurrency — what happens to a request for this action while another
                    one is waiting or running: :queue (default) runs every
                    request in order, :drop ignores the new one, :latest
                    replaces the waiting one with the new one"
  ([app-state* session-id tab-id action-fn]
   (register-action! app-state* session-id tab-id action-fn
                     (str "act_" (uuid/str (java.util.UUID/randomUUID)))
                     nil))
  ([app-state* session-id tab-id action-fn action-id]
   (register-action! app-state* session-id tab-id action-fn action-id nil))
  ([app-state* session-id tab-id action-fn action-id opts]
   (when-let [c (:concurrency opts)]
     (when-not (concurrency-values c)
       (throw (ex-info (str "action :concurrency must be one of " (pr-str concurrency-values))
                       {:concurrency c :hyper/action-id action-id}))))
   (swap! app-state* (fn [state]
                       (-> state
                           (assoc-in [:actions action-id]
                                     (cond-> {:fn         action-fn
                                              :session-id session-id
                                              :tab-id     tab-id}
                                       (:as opts)          (assoc :as (:as opts))
                                       (:navigation? opts) (assoc :navigation? true)
                                       (:concurrency opts) (assoc :concurrency (:concurrency opts))))
                           (update-in [:actions-by-tab tab-id]
                                      (fnil conj #{}) action-id))))
   (when-let [acc context/*registered-action-ids*]
     (swap! acc conj action-id))
   action-id))

(defn execute-action!
  "Execute an action by ID with error handling.
   When client-params are provided they are passed to the action fn."
  ([app-state* action-id]
   (execute-action! app-state* action-id nil))
  ([app-state* action-id client-params]
   (if-let [action-data (get-in @app-state* [:actions action-id])]
     (let [{:keys [fn]} action-data]
       (t/catch->error! :hyper.error/execute-action
                        (fn client-params))
       true)
     (do
       (t/log! {:level :warn
                :id    :hyper.error/action-not-found
                :data  {:hyper/action-id action-id}
                :msg   "Action not found"})
       (throw (ex-info "Action not found" {:hyper/action-id action-id}))))))

(defn sweep-stale-tab-actions!
  "Remove actions for a tab that were not re-registered during the last
   render cycle.  `live-action-ids` is the set of IDs collected by
   `*registered-action-ids*` during render.  Actions in that set are
   kept; any other action belonging to this tab is removed.

   This replaces the old cleanup-before-render pattern with a
   cleanup-after-render approach that eliminates the window where
   actions are missing."
  [app-state* tab-id live-action-ids]
  (swap! app-state* (fn [state]
                      (let [all-tab-ids (get-in state [:actions-by-tab tab-id] #{})
                            stale-ids   (clojure.set/difference all-tab-ids live-action-ids)]
                        (if (seq stale-ids)
                          (-> state
                              (update :actions #(apply dissoc % stale-ids))
                              (assoc-in [:actions-by-tab tab-id] live-action-ids))
                          (assoc-in state [:actions-by-tab tab-id] live-action-ids)))))
  nil)

(defn cleanup-tab-actions!
  "Remove all actions for a tab.  Used during tab disconnect."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (get-in state [:actions-by-tab tab-id])]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)))))
  nil)

;; ---------------------------------------------------------------------------
;; Per-tab action queue
;; ---------------------------------------------------------------------------
;; Each tab's actions run one at a time, in arrival order, on a single worker
;; thread.  The queue lives at [:tabs tab-id :action-queue] and its worker is
;; started on the tab's first action.

(def ^:private slow-action-ms
  "Actions that hold their tab's queue longer than this log a warning."
  100)

(defn- new-action-queue
  "`:in-flight` counts the waiting and running requests per action id; it and
   the queue's contents change together under `:lock`."
  []
  {:queue     (java.util.concurrent.LinkedBlockingQueue.)
   :lock      (java.util.concurrent.locks.ReentrantLock.)
   :in-flight (atom {})
   :thread    (atom nil)
   :stopped?  (atom false)})

(defmacro ^:private with-lock [lock & body]
  `(let [^java.util.concurrent.locks.ReentrantLock l# ~lock]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn- release-in-flight!
  "Count one request for `action-id` as no longer waiting or running."
  [in-flight action-id]
  (swap! in-flight (fn [m]
                     (let [n (dec (get m action-id 0))]
                       (if (pos? n) (assoc m action-id n) (dissoc m action-id))))))

(defn- fail-waiting!
  "Deliver an error to every entry still waiting in `queue`."
  [^java.util.concurrent.LinkedBlockingQueue queue]
  (let [entries (java.util.ArrayList.)]
    (.drainTo queue entries)
    (doseq [{:keys [result]} entries
            :when            result]
      (deliver result {:error (ex-info "Tab closed before the action ran" {})}))))

(defn- run-entry
  "Run one queued entry's job, returning {:value v} or {:error e}, and warn
   when it ran longer than `slow-action-ms`."
  [tab-id {:keys [job action-id as run-action?]}]
  (let [start  (System/nanoTime)
        result (try {:value (job run-action?)}
                    (catch Throwable e {:error e}))
        ms     (quot (- (System/nanoTime) start) 1000000)]
    (when (> ms slow-action-ms)
      (t/log! {:level :warn
               :id    :hyper.warn/slow-action
               :data  {:hyper/action-id action-id
                       :hyper/tab-id    tab-id
                       :hyper/action-as as
                       :elapsed-ms      ms}
               :msg   (str "Action held its tab for " ms "ms; the tab's other actions "
                           "wait behind it. Move slow work into h/spawn! and write "
                           "the result to a cursor.")}))
    result))

(defn- worker-loop
  [tab-id {:keys [^java.util.concurrent.LinkedBlockingQueue queue lock in-flight stopped?]}]
  (try
    (loop []
      (let [entry (.take queue)]
        (cond
          (= ::stop entry) nil
          @stopped?        (do (.put queue entry) (fail-waiting! queue))
          :else            (let [result (run-entry tab-id entry)]
                             (when (:run-action? entry)
                               (with-lock lock (release-in-flight! in-flight (:action-id entry))))
                             (some-> (:result entry) (deliver result))
                             (recur)))))
    (catch InterruptedException _ nil)))

(defn- ensure-worker!
  "Start the queue's worker thread unless it is already running."
  [tab-id {:keys [thread] :as action-queue}]
  (when (compare-and-set! thread nil ::starting)
    (reset! thread (-> (Thread/ofVirtual)
                       (.name (str "hyper-actions-" tab-id))
                       (.start ^Runnable #(worker-loop tab-id action-queue))))))

(defn- tab-action-queue
  "The action queue for `tab-id`, created if needed.  nil when the tab does
   not exist."
  [app-state* tab-id]
  (-> (swap! app-state* (fn [state]
                          (if (and (contains? (:tabs state) tab-id)
                                   (nil? (get-in state [:tabs tab-id :action-queue])))
                            (assoc-in state [:tabs tab-id :action-queue] (new-action-queue))
                            state)))
      (get-in [:tabs tab-id :action-queue])))

(defn- enqueue!
  "Put a request on the queue according to its action's :concurrency.
   Returns the promise to wait on, or nil when the request was dropped (its
   job is still queued, with `run-action?` false, so its signals apply in
   order)."
  [{:keys [^java.util.concurrent.LinkedBlockingQueue queue lock in-flight]}
   {:keys [action-id concurrency] :as entry}]
  (with-lock lock
    (if (and (= :drop concurrency) (pos? (get @in-flight action-id 0)))
      (do (.put queue (assoc entry :run-action? false :result nil))
          nil)
      (let [result (promise)]
        (when (= :latest concurrency)
          (doseq [waiting (vec queue)
                  :when   (and (map? waiting)
                               (:run-action? waiting)
                               (= action-id (:action-id waiting)))]
            (when (.remove queue waiting)
              (release-in-flight! in-flight action-id)
              (some-> (:result waiting) (deliver {:value nil})))))
        (swap! in-flight update action-id (fnil inc 0))
        (.put queue (assoc entry :run-action? true :result result))
        result))))

(defn run-in-order!
  "Run `job` after every job already submitted for `tab-id`, one at a time,
   and return its value (or rethrow what it threw).  The job runs with the
   caller's dynamic bindings.

   `job` takes one argument, `run-action?`.  It is false when the request was
   dropped (:concurrency :drop): the job still runs in order so it can apply
   the request's state, but must not run the action.

   Returns nil without waiting when the request was dropped, or once it is
   replaced by a newer one (:concurrency :latest).

   Runs `job` directly when the tab does not exist, or when called from the
   tab's own worker (an action invoking another action).

   `info` is a map with :action-id, :as and :concurrency."
  [app-state* tab-id info job]
  (let [{:keys [queue thread stopped?] :as action-queue} (tab-action-queue app-state* tab-id)]
    (if (or (nil? action-queue)
            (identical? (Thread/currentThread) @thread))
      (job true)
      (do
        (ensure-worker! tab-id action-queue)
        (when-let [result (enqueue! action-queue (assoc info :job (bound-fn* job)))]
          ;; Covers a put that lands after stop-action-queue! drained the queue.
          (when @stopped? (fail-waiting! queue))
          (let [{:keys [value error]} @result]
            (if error (throw error) value)))))))

(defn stop-action-queue!
  "Stop `tab-id`'s action worker and fail any requests still waiting.  The
   running action, if any, is interrupted."
  [app-state* tab-id]
  (when-let [{:keys [queue thread stopped?]} (get-in @app-state* [:tabs tab-id :action-queue])]
    (reset! stopped? true)
    (fail-waiting! queue)
    (.put ^java.util.concurrent.LinkedBlockingQueue queue ::stop)
    (let [t @thread]
      (when (and (instance? Thread t) (not (identical? t (Thread/currentThread))))
        (.interrupt ^Thread t))))
  nil)
