(ns ^:no-doc hyper.context
  "Request context and dynamic vars for hyper applications.

   Lives in a low-level namespace so that both render.clj and core.clj
   can reference these vars without circular dependencies."
  (:require [taoensso.telemere :as t]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

;; Per-render action counter. Bound to (atom 0) before each render so that
;; deterministic render functions produce the same action IDs every time,
;; enabling effective brotli streaming compression.
(def ^:dynamic *action-idx* nil)

;; Ambient key-path of the enclosing keyed regions. A keyed `reactive`/`async`
;; pushes its key token while rendering its body, so a nested region's `:key`
;; only has to be unique among its siblings (its id is the full path). Bound to
;; [] at the root of a full render; restored from the region's stored path on a
;; partial re-render.
(def ^:dynamic *region-path* [])

;; Datastar signal values the client sent with the action, during
;; action execution.  Bound to a keyword-keyed map by the action handler
;; so that Signal/deref can return the live client-side value.  nil
;; outside of an action context (i.e. during render).
(def ^:dynamic *signals* nil)

;; Accumulator for signal declarations emitted during a render pass.
;; Bound to (atom []) before each render so that (h/signal ...) and
;; (h/local-signal ...) can register themselves for HTML injection.
;; Each entry is a map with :html-name, :default-val, and :local? keys.
(def ^:dynamic *declared-signals* nil)

;; Accumulator for action IDs registered during a render pass.
;; Bound to (atom #{}) before each render so that register-action!
;; can track which action IDs are live.  After render, stale actions
;; (present in the previous cycle but absent from this one) are removed
;; in a single atomic swap — no cleanup-before-render gap needed.
(def ^:dynamic *registered-action-ids* nil)

;; The actions a render pass registered, as {action-id action-data}.
;; Bound to (atom {}) before each render; stored afterwards under the
;; render's version, so a click resolves its action id against the render
;; it was made on (see hyper.actions/record-render!).
(def ^:dynamic *rendered-actions* nil)

;; State overlay for cursor read/write indirection.
;;
;; When non-nil, a map with:
;;   :state*  — shadow atom serving cursor reads / read-your-writes
;;   :ops*    — atom [] of ordered mutations (not values):
;;                {:kind :update :path p :f g}
;;                {:kind :reset  :path p :value v}
;;                {:kind :cas    :path p :old o :new n}
;;                {:kind :init   :path p :value v}   ;; set only if p absent
;;   :owner   — the Thread that created the overlay (see ownership below)
;;
;; Bound during render (read consistency) and inside `batch` (atomicity).
;; Cursors use the overlay when it's bound AND owned by the current thread;
;; otherwise they hit app-state* directly.  At the boundary the op-log is
;; replayed onto the live atom via `flush-overlay!` — replaying operations
;; (not absolute values) lets a swap!/update-style write compose with a
;; concurrent same-path write instead of clobbering it; reset! still
;; overwrites by design.  The flush iterates to a fixpoint: cursor writes
;; made by watch callbacks reacting to a phase are buffered and applied as
;; subsequent atomic phases rather than dropped (see `flush-overlay!`).
;; nil during action execution (without batch).
;;
;; Thread ownership: `future`, `send-off`, fibers, etc. convey dynamic
;; bindings, so background work spawned from a render/batch would inherit
;; an overlay whose boundary may already have flushed — silently routing
;; its writes into dead state.  The :owner stamp guards against this; see
;; `current-overlay`.
(def ^:dynamic *state-overlay* nil)

(defn current-overlay
  "Return the bound state overlay, but only when the current thread created
   it.  An overlay seen on any other thread is a conveyed binding (future /
   send-off / fiber spawned inside a render or batch) — treat it as absent
   so cursor reads/writes hit the live app-state directly.

   Future extension: a sanctioned `bound-fn`-style opt-in could let work
   on another thread deliberately participate in the owner's overlay by
   adopting its :owner; until then, ownership is strictly single-thread."
  []
  (when-let [overlay *state-overlay*]
    (when (identical? (:owner overlay) (Thread/currentThread))
      overlay)))

(defn apply-ops
  "Replay an ordered op-log against a state value, returning the new state.
   :update applies its fn to the current value (composing with concurrent
   writes), :reset overwrites the path, :cas writes only when the value
   still equals the expected old, :init writes only when the path is still
   absent (a default-init that yields to any concurrent write, including one
   that set the path to nil).  Pure — runs inside flush-overlay!'s swap!, so
   op fns must be side-effect free, as with clojure.core/swap!."
  [state ops]
  (reduce (fn [s {:keys [kind path] :as op}]
            (case kind
              :update (update-in s path (:f op))
              :reset  (assoc-in s path (:value op))
              :cas    (if (= (get-in s path) (:old op))
                        (assoc-in s path (:new op))
                        s)
              :init   (if (identical? ::absent (get-in s path ::absent))
                        (assoc-in s path (:value op))
                        s)))
          state
          ops))

(declare ^:dynamic *render-guard*)

(def ^:private max-flush-phases
  "Ceiling on reactive flush phases before flush-overlay! throws — a watch
   cycle whose values never reach a fixpoint would otherwise loop forever."
  100)

(defn flush-overlay!
  "Replay the current overlay's op-log onto the live atom, iterating to a
   fixpoint.  All ops buffered before the call land in the first swap!, so
   user write-pairs stay atomic.  Watch callbacks that run during a phase's
   notifications and write cursors append new ops; each batch of appended
   ops is applied as the next atomic phase until the log drains.  The render
   guard is released for the duration — the render body has already been
   judged, and reaction writes are applied rather than dropped, so they are
   not render effects.  Throws when the log keeps growing past
   `max-flush-phases` (a non-converging write cycle).  No-op when nothing
   was recorded, or the calling thread doesn't own the overlay."
  [app-state*]
  (when-let [{:keys [ops*]} (current-overlay)]
    (binding [*render-guard* nil]
      (loop [applied 0
             phase   1]
        (let [ops     @ops*
              pending (subvec ops applied)]
          (when (seq pending)
            (when (> phase max-flush-phases)
              (throw (ex-info (str "flush-overlay! did not converge after "
                                   max-flush-phases " phases — a cursor watch "
                                   "keeps writing on every phase (write cycle?)")
                              {:hyper/flush-phases (dec phase)
                               :hyper/pending-ops  (mapv #(select-keys % [:kind :path])
                                                         pending)})))
            (swap! app-state* apply-ops pending)
            (recur (count ops) (inc phase))))))))

;; The :as name of the currently executing action, or nil when outside
;; an action context or when the action was not given an :as name.
;; Bound by the action handler in server.clj so that utility functions
;; called from within actions can identify which action is running
;; without the caller having to pass the name explicitly.
;;
;; Example:
;;   (h/action {:as "delete-user"}
;;     (audit! context/*action-name*)  ;; => "delete-user"
;;     (delete-user! id))
(def ^:dynamic *action-name* nil)

;; Accumulator for subview IDs registered during a render pass (reactive
;; regions and, in future, other managed sub-regions).  Bound to (atom #{})
;; before each full render so the subview registry can track which subviews
;; are live.  After render, stale subviews (present in the previous cycle but
;; absent from this one) are swept — their watches are removed, dep refcounts
;; released, and any :unmount run.
(def ^:dynamic *registered-subview-ids* nil)

;; Render purity guard (see the full commentary near the guard fns below).
;; Defined here so the render-binding builders can reference it.
(def ^:dynamic *render-guard* nil)

(defn make-guard
  "Create a fresh render-guard atom at the given level (:warn, :error, or
   :off).  Starts in :deferred mode."
  [level]
  (atom {:mode :deferred :level (or level :warn) :events []}))

(defn render-bindings
  "Build the thread-binding map for a full render context.
   Includes a state overlay snapshot so cursor reads/writes are isolated.
   Returns a map suitable for `push-thread-bindings`."
  [req app-state*]
  {#'*request*                req
   #'*action-idx*             (atom 0)
   #'*region-path*            []
   #'*declared-signals*       (atom [])
   #'*registered-action-ids*  (atom #{})
   #'*rendered-actions*       (atom {})
   #'*registered-subview-ids* (atom #{})
   #'*render-guard*           (make-guard (get @app-state* :render-guard :warn))
   #'*state-overlay*          {:state* (atom @app-state*)
                               :ops*   (atom [])
                               :owner  (Thread/currentThread)}})

(defn partial-render-bindings
  "Build the thread-binding map for a partial (reactive component) render.
   No state overlay — reads/writes go directly to the live atom."
  [req]
  {#'*request*                req
   #'*action-idx*             (atom 0)
   #'*region-path*            []
   #'*declared-signals*       (atom [])
   #'*registered-action-ids*  (atom #{})
   #'*rendered-actions*       (atom {})
   #'*registered-subview-ids* (atom #{})
   ;; Partial reactive renders have no setup phase — the body is a pure
   ;; render fn, so the guard is active immediately.
   #'*render-guard*           (let [app-state* (:hyper/app-state req)
                                    level      (when app-state*
                                                 (get @app-state* :render-guard :warn))]
                                (doto (make-guard level)
                                  (swap! assoc :mode :active)))
   #'*state-overlay*          nil})

(defn require-context!
  "Extract and validate the request context from *request*.
   Throws if called outside a request context or if required keys are missing.
   Returns a map with :session-id, :tab-id, :app-state*, and :router."
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
     :app-state* app-state*
     :router     (:hyper/router *request*)}))

(defn context-request
  "Build the hyper request-context map for `tab-id`: :hyper/session-id,
   :hyper/tab-id, :hyper/app-state, :hyper/router, :hyper/route, and
   :hyper/env. Route, env, and (2-arity) session-id come from the tab record;
   router from app-state."
  ([app-state* tab-id]
   (context-request app-state* tab-id (get-in @app-state* [:tabs tab-id :session-id])))
  ([app-state* tab-id session-id]
   {:hyper/session-id session-id
    :hyper/tab-id     tab-id
    :hyper/app-state  app-state*
    :hyper/router     (get @app-state* :router)
    :hyper/route      (get-in @app-state* [:tabs tab-id :route])
    :hyper/env        (get-in @app-state* [:tabs tab-id :env])}))

(defn teardown-request
  "Build the request-context map for running a tab's teardown (:unmount) fns."
  [app-state* tab-id]
  (context-request app-state* tab-id))

;; ---------------------------------------------------------------------------
;; Render purity guard
;; ---------------------------------------------------------------------------
;;
;; Under the form-1/2/3 ladder, a render fn is a pure function of state:
;; effects (cursor mutation, watch!, async workers) belong in form-2 setup
;; or a form-3 :mount, NEVER in the render phase.  This guard makes that
;; boundary observable — it warns (or, when configured, throws) when an
;; effect runs while a render is in progress.
;;
;; *render-guard* is nil outside of rendering (actions, setup closures,
;; form-3 mount/unmount, background workers) so effects there are silent.
;; During a render it is bound to a guard atom:
;;
;;   {:mode   :deferred | :active   ;; see below
;;    :level  :warn | :error | :off ;; what an offending effect does
;;    :events [ {:kind .. :detail ..} ... ]}
;;
;; The page handler is called once with mode :deferred because we cannot yet
;; tell form-1 (pure body — effects should warn) from a form-2 setup closure
;; (effects are legal) until we see the return value.  Offending effects are
;; buffered into :events during this phase.  Once the form is known the
;; dispatcher either discards the buffer (form-2/3 — setup was legal) via
;; `guard-discard!`, or flushes it as warnings (form-1) via
;; `guard-flush-and-activate!`.  Both transition the mode to :active, under
;; which any further effect (the render body, lazy hiccup realization, a
;; form-2/3 render fn) is judged immediately.
;;
;; (*render-guard* and make-guard are defined earlier in this file so the
;; render-binding builders can reference them.)

(defn- guard-message [{:keys [kind detail]}]
  (str "Effect during render: " (name (or kind :effect))
       (when detail (str " (" detail ")"))
       ". Render functions must be pure — move effects (cursor writes, "
       "watch!, workers) into a form-2 setup closure or a form-3 :mount."))

(defn- emit-guard-event!
  "Emit a single buffered/active guard event according to level."
  [level event]
  (case level
    :off  nil
    :error (throw (ex-info (guard-message event)
                           (assoc event :hyper/render-guard true)))
    ;; default :warn
    (t/log! {:level :warn
             :id    :hyper.warn/effect-in-render
             :data  event
             :msg   (guard-message event)})))

(defn guard-effect!
  "Report an effect (cursor mutation, watch!, …) to the active render guard.
   `kind` is a keyword (e.g. :cursor-mutation, :watch); `detail` is an
   optional string/value for the message.  No-op when no guard is bound or
   the guard level is :off.  In :deferred mode the event is buffered; in
   :active mode it is emitted immediately (warn) or throws (error)."
  ([kind] (guard-effect! kind nil))
  ([kind detail]
   (when-let [g *render-guard*]
     (let [{:keys [mode level]} @g]
       (when (not= level :off)
         (let [event {:kind kind :detail detail}]
           (if (= mode :deferred)
             (swap! g update :events conj event)
             (emit-guard-event! level event))))))))

(defn guard-discard!
  "form-2/3 resolution: the page handler call was a legal setup/construction
   phase, so drop any buffered effects and switch the guard to :active so the
   render fn itself is judged."
  []
  (when-let [g *render-guard*]
    (swap! g assoc :mode :active :events [])))

(defn guard-flush-and-activate!
  "form-1 resolution: the page handler call WAS the render body, so emit any
   buffered effects (warn, or throw on the first when :error) and switch the
   guard to :active for lazy hiccup realization."
  []
  (when-let [g *render-guard*]
    (let [{:keys [level events]} @g]
      (swap! g assoc :mode :active :events [])
      (doseq [event events]
        (emit-guard-event! level event)))))
