(ns ^:no-doc hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [clojure.set]
            [clojure.string :as str]
            [compact-uuids.core :as uuid]
            [hyper.context :as context]
            [hyper.signal]
            [hyper.state]
            [taoensso.telemere :as t])
  (:import [hyper.signal LocalSignal Optimistic Signal]
           [hyper.state Cursor]))

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
   base-path is prepended to the /hyper/actions endpoint (empty string when not set).
   Each request carries the page's next sequence number (`hyper-seq`) so the
   server can ignore signals that arrive after those of a request sent later."
  [action-id used-params js base-path]
  (let [js-injection (when js (str js " && "))]
    (if (empty? used-params)
      (str js-injection "@post('" base-path "/hyper/actions?action-id=" action-id
           "&hyper-seq=' + hyper.nextSeq())")
      (let [obj-entries (->> used-params
                             vals
                             (map (fn [{:keys [js key]}]
                                    (str key ":" js)))
                             (str/join ","))]
        (str js-injection
             "@post('" base-path "/hyper/actions?action-id=" action-id
             "&hyper-seq=' + hyper.nextSeq() + '&' + hyper.encodeClientParams({" obj-entries "}))")))))

;; ---------------------------------------------------------------------------
;; Derived action ids
;; ---------------------------------------------------------------------------
;; An unkeyed action's id is a hash of where it is defined plus the values its
;; body closes over, so it follows what the action is rather than where it
;; sits on the page.

(defn- plain-value?
  "True for values whose hash reflects their contents and is cheap to compute."
  [v]
  (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)
      (char? v) (uuid? v) (inst? v)
      (and (coll? v)
           (not (instance? clojure.lang.LazySeq v)))))

(defn- id-part
  "What captured value `v` contributes to an action id, or ::by-occurrence
   when it has no stable value-based identity (fns, atoms, lazy seqs, other
   objects)."
  [v]
  (cond
    (instance? Cursor v)      [::cursor
                               (System/identityHashCode (.-parent-atom ^Cursor v))
                               (.-full-path ^Cursor v)]
    (instance? Signal v)      [::signal (.-tab-id ^Signal v) (.-sig-name ^Signal v)]
    (instance? LocalSignal v) [::local-signal (.-sig-name ^LocalSignal v)]
    (instance? Optimistic v)  [::optimistic (id-part (.-signal ^Optimistic v))]
    (plain-value? v)          v
    :else                     ::by-occurrence))

(defn- next-occurrence!
  "The number of earlier calls with `k` in this render, counting this one."
  [k]
  (if-let [claims context/*action-id-claims*]
    (get-in (swap! claims update-in [:occurrences k] (fnil inc -1)) [:occurrences k])
    0))

;; An action missing from a tab's latest render is not removed at once: it is
;; marked :stale-at and kept for :keep-stale-actions-ms, so a click sent from
;; the page that showed it still reaches the handler it was rendered with.

(def default-keep-stale-actions-ms
  "How long a stale action is kept when :keep-stale-actions-ms is not set."
  60000)

(def ^:private max-stale-actions-per-tab
  "The most stale actions kept per tab; beyond it the longest-stale go."
  1000)

(defn- keep-stale-ms [state]
  (long (or (:keep-stale-actions-ms state) default-keep-stale-actions-ms)))

(defn find-action
  "The action registered as `action-id`, current or stale, unless it has been
   stale for longer than :keep-stale-actions-ms."
  [app-state* action-id]
  (let [state @app-state*]
    (when-let [action (get-in state [:actions action-id])]
      (let [stale-at (:stale-at action)]
        (when (or (nil? stale-at)
                  (< (- (System/currentTimeMillis) stale-at) (keep-stale-ms state)))
          action)))))

(defn stale-refusal
  "What a click on `action` should do instead of running it: nil to run it,
   :reject to refuse it, or a fn to run in its place.  Only a stale action
   whose :on-stale is not :run is refused."
  [action]
  (let [on-stale (:on-stale action :run)]
    (when (and (:stale-at action) (not= :run on-stale))
      on-stale)))

(defn- held-by-other?
  "True when `id` belongs to a kept action other than the one identified by
   `identity-key`."
  [id identity-key]
  (when-let [app-state* (:hyper/app-state context/*request*)]
    (when-let [held (:identity-key (find-action app-state* id))]
      (not= held identity-key))))

(defn- claim-id!
  "Claim `id` for the action identified by `identity-key` in this render.
   An id already claimed in this render by a different action, or still held
   by a different kept action, gets a numeric suffix, so colliding ids are
   told apart."
  [id identity-key]
  (if-let [claims context/*action-id-claims*]
    (loop [n 1]
      (let [candidate (if (= 1 n) id (str id "_" n))
            claimed   (get-in @claims [:claimed candidate] ::none)]
        (cond
          (= claimed identity-key) candidate
          (and (= ::none claimed)
               (not (held-by-other? candidate identity-key))
               (= identity-key
                  (get-in (swap! claims update-in [:claimed candidate] #(or % identity-key))
                          [:claimed candidate])))
          candidate
          :else (recur (inc n)))))
    id))

(defn derived-action-id
  "The id for an unkeyed action on `tab-id` defined at `site` (a hash of its
   source location) that closes over `captured` values."
  [tab-id site captured]
  (let [parts        (mapv id-part captured)
        occurrence   (when (some #{::by-occurrence} parts)
                       (next-occurrence! [site context/*region-path*]))
        identity-key [site parts occurrence]]
    (claim-id! (str "a_" tab-id "_" (format "%08x" (bit-and (hash identity-key) 0xffffffff)))
               identity-key)))

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
                    replaces the waiting one with the new one
   - :on-stale    — what a click does when the action is stale (the latest
                    render no longer shows it): :run (default) runs it,
                    :reject refuses it, a zero-arg fn runs instead of it"
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
   (when-let [s (:on-stale opts)]
     (when-not (or (#{:run :reject} s) (fn? s))
       (throw (ex-info "action :on-stale must be :run, :reject or a fn"
                       {:on-stale s :hyper/action-id action-id}))))
   ;; What a derived id was derived from, so claim-id! can tell a different
   ;; action apart from this one while it is kept
   (let [identity-key (some-> context/*action-id-claims* deref (get-in [:claimed action-id]))]
     (swap! app-state* (fn [state]
                         (-> state
                             (assoc-in [:actions action-id]
                                       (cond-> {:fn         action-fn
                                                :session-id session-id
                                                :tab-id     tab-id}
                                         identity-key        (assoc :identity-key identity-key)
                                         (:as opts)          (assoc :as (:as opts))
                                         (:navigation? opts) (assoc :navigation? true)
                                         (:concurrency opts) (assoc :concurrency (:concurrency opts))
                                         (:on-stale opts)    (assoc :on-stale (:on-stale opts))))
                             (update-in [:actions-by-tab tab-id]
                                        (fnil conj #{}) action-id)
                             (cond-> (get-in state [:stale-actions-by-tab tab-id action-id])
                               (update-in [:stale-actions-by-tab tab-id] dissoc action-id))))))
   (when-let [acc context/*registered-action-ids*]
     (swap! acc conj action-id))
   action-id))

(defn execute-action!
  "Execute an action by ID with error handling.
   When client-params are provided they are passed to the action fn."
  ([app-state* action-id]
   (execute-action! app-state* action-id nil))
  ([app-state* action-id client-params]
   (if-let [action-data (find-action app-state* action-id)]
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
  "Mark the tab's actions that were not re-registered during the last render
   as stale.  `live-action-ids` is the set of IDs collected by
   `*registered-action-ids*` during render.  Stale actions are kept for
   :keep-stale-actions-ms (at most `max-stale-actions-per-tab` of them) so a
   click from the page that showed them still runs; with 0 they are removed
   at once."
  [app-state* tab-id live-action-ids]
  (let [now (System/currentTimeMillis)]
    (swap! app-state*
           (fn [state]
             (let [keep-ms   (keep-stale-ms state)
                   stale-ids (clojure.set/difference (get-in state [:actions-by-tab tab-id] #{})
                                                     live-action-ids)
                   index     (apply dissoc (or (get-in state [:stale-actions-by-tab tab-id]) {})
                                    live-action-ids)
                   index     (if (pos? keep-ms)
                               (into index (map (fn [id] [id now])) stale-ids)
                               index)
                   expired   (for [[id t] index
                                   :when  (or (not (pos? keep-ms)) (>= (- now t) keep-ms))]
                               id)
                   index     (apply dissoc index expired)
                   over      (when (> (count index) max-stale-actions-per-tab)
                               (map key (take (- (count index) max-stale-actions-per-tab)
                                              (sort-by val index))))
                   index     (apply dissoc index over)
                   removed   (concat expired over (when-not (pos? keep-ms) stale-ids))
                   mark      (fn [actions id]
                               (if (contains? actions id) (assoc-in actions [id :stale-at] now) actions))
                   remove-id (fn [actions id]
                               ;; An id rendered again since is current; leave it
                               (if (or (get-in actions [id :stale-at]) (contains? stale-ids id))
                                 (dissoc actions id)
                                 actions))]
               (-> state
                   (assoc-in [:actions-by-tab tab-id] live-action-ids)
                   (update :actions #(reduce remove-id
                                             (reduce mark % (when (pos? keep-ms) stale-ids))
                                             removed))
                   (assoc-in [:stale-actions-by-tab tab-id] index))))))
  nil)

(defn cleanup-tab-actions!
  "Remove all actions for a tab, current and stale.  Used during tab
   disconnect."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (concat (get-in state [:actions-by-tab tab-id])
                                               (keys (get-in state [:stale-actions-by-tab tab-id])))]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)
                            (update :stale-actions-by-tab dissoc tab-id)))))
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
