(ns ^:no-doc hyper.signal
  "Client-side Datastar signals for hyper.

   Signals are reactive client-side variables backed by Datastar's signal
   system.  They integrate with hyper's server-rendered model:

   - During render, deref returns a Datastar expression string (e.g. \"$userName\")
     suitable for use in data-text, data-show, data-bind, etc.
   - During action execution, deref returns the live value sent by Datastar
     in the @post() request body.
   - reset! / swap! update the signal value in tab state, which triggers a
     datastar-patch-signals SSE event on the next render cycle.

   Local signals (prefixed with underscore in Datastar) are client-only:
   they cannot be read or written from the server."
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.core.memoize :as m]
            [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.datastar :as datastar]
            [hyper.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Name conversion
;; ---------------------------------------------------------------------------

(def ^:private -memoized->camelCaseString
  (m/fifo csk/->camelCaseString {} :fifo/threshold 1024))

(def ^:private -memoized->kebab-case-string
  (m/fifo csk/->kebab-case-string {} :fifo/threshold 1024))

(defn signal-js-name
  "Convert a keyword or keyword vector path to the Datastar signal JS name.
   :user-name       → \"userName\"
   [:user :name]    → \"user.name\"
   [:user-profile :first-name] → \"userProfile.firstName\""
  [path]
  (if (keyword? path)
    (-memoized->camelCaseString (name path))
    (str/join "." (map (comp -memoized->camelCaseString name) path))))

(defn signal-html-name
  "Convert a keyword or keyword vector path to the HTML attribute suffix
   for data-signals.  Datastar auto-converts hyphens to camelCase.
   :user-name       → \"user-name\"
   [:user :name]    → \"user.name\"
   [:user-profile :first-name] → \"user-profile.first-name\""
  [path]
  (if (keyword? path)
    (name path)
    (str/join "." (map name path))))

(defn- signal-store-path
  "Normalize a signal path (keyword or keyword vector) to a vector
   of keywords for storage and lookup."
  [path]
  (if (keyword? path)
    [path]
    (vec path)))

;; ---------------------------------------------------------------------------
;; Value encoding
;; ---------------------------------------------------------------------------

(defn clj->js-literal
  "Convert a Clojure value to a JavaScript literal string suitable for
   use in Datastar expressions and data-signals attributes."
  [v]
  (cond
    (nil? v)     "null"
    (boolean? v) (str v)
    (number? v)  (str v)
    (string? v)  (str "'" (utils/escape-js-string v) "'")
    (keyword? v) (str "'" (name v) "'")
    (map? v)     (str "{" (str/join ", "
                                    (map (fn [[k v']]
                                           (str (if (keyword? k) (name k) k)
                                                ": " (clj->js-literal v')))
                                         v)) "}")
    (coll? v)    (str "[" (str/join ", " (map clj->js-literal v)) "]")
    :else        (str "'" (utils/escape-js-string (str v)) "'")))

;; ---------------------------------------------------------------------------
;; Signal types
;; ---------------------------------------------------------------------------

(deftype Signal [sig-name   ;; JS name e.g. "userName" or "user.name"
                 html-name  ;; HTML attr suffix e.g. "user-name" or "user.name"
                 store-path ;; keyword vector for storage e.g. [:user-name] or [:user :name]
                 app-state* ;; ref to app state atom
                 tab-id     ;; tab this signal belongs to
                 default-val]
  clojure.lang.IDeref
  (deref [_]
    (if-let [signals context/*signals*]
      ;; Action context — return the live value from Datastar request
      (get-in signals store-path default-val)
      ;; Render context — return Datastar expression string
      (str "$" sig-name)))

  clojure.lang.IAtom
  (reset [_ newv]
    (swap! app-state* assoc-in (into [:tabs tab-id :signals] store-path) newv)
    newv)

  (swap [this f]
    (let [current (if context/*signals*
                    (get-in context/*signals* store-path default-val)
                    (get-in @app-state* (into [:tabs tab-id :signals] store-path) default-val))
          new-val (f current)]
      (.reset this new-val)))

  (swap [this f arg]
    (let [current (if context/*signals*
                    (get-in context/*signals* store-path default-val)
                    (get-in @app-state* (into [:tabs tab-id :signals] store-path) default-val))
          new-val (f current arg)]
      (.reset this new-val)))

  (swap [this f arg1 arg2]
    (let [current (if context/*signals*
                    (get-in context/*signals* store-path default-val)
                    (get-in @app-state* (into [:tabs tab-id :signals] store-path) default-val))
          new-val (f current arg1 arg2)]
      (.reset this new-val)))

  (swap [this f arg1 arg2 args]
    (let [current (if context/*signals*
                    (get-in context/*signals* store-path default-val)
                    (get-in @app-state* (into [:tabs tab-id :signals] store-path) default-val))
          new-val (apply f current arg1 arg2 args)]
      (.reset this new-val)))

  (compareAndSet [_ _oldv _newv]
    (throw (UnsupportedOperationException.
             "compareAndSet is not supported on Datastar signals")))

  Object
  (toString [_] sig-name))

(deftype LocalSignal [sig-name   ;; JS name e.g. "_enabled"
                      html-name  ;; HTML attr suffix e.g. "_enabled"
                      default-val]
  clojure.lang.IDeref
  (deref [_]
    (if context/*signals*
      ;; Action context — local signals are not sent by Datastar
      (throw (ex-info "Cannot deref local signal in an action. Local signals (underscore-prefixed) are client-only and are not sent to the backend by Datastar."
                      {:signal sig-name}))
      ;; Render context — return Datastar expression string
      (str "$" sig-name)))

  Object
  (toString [_] sig-name))

(deftype Optimistic [^Signal signal ;; the paired client signal
                     cursor        ;; the authoritative scoped cursor
                     opts]         ;; {:auto-commit? bool :on-conflict policy}
  clojure.lang.IDeref
  ;; Deref reads what the client sees; writes decree what the server says.
  (deref [_]
    (if-let [signals context/*signals*]
      ;; Action context — the live client-reported value, falling back to
      ;; the committed value when the signal did not ride the request.
      (let [v (get-in signals (.-store-path signal) ::absent)]
        (if (identical? ::absent v) @cursor v))
      ;; Render context — the Datastar expression string
      (str "$" (.-sig-name signal))))

  clojure.lang.IAtom
  (reset [_ newv] (reset! cursor newv))
  (swap [_ f] (swap! cursor f))
  (swap [_ f arg] (swap! cursor f arg))
  (swap [_ f arg1 arg2] (swap! cursor f arg1 arg2))
  (swap [_ f arg1 arg2 args] (apply swap! cursor f arg1 arg2 args))
  (compareAndSet [_ oldv newv] (compare-and-set! cursor oldv newv))

  Object
  (toString [_] (.-sig-name signal)))

;; ---------------------------------------------------------------------------
;; Signal introspection
;; ---------------------------------------------------------------------------
;; Used by hyper.component (signal-linked attributes) and hyper.expr
;; (runtime splicing) without reaching into deftype fields from other
;; namespaces.

(defn signal?
  "True when x is a (non-local) Datastar signal."
  [x]
  (instance? Signal x))

(defn local-signal?
  "True when x is a client-only (underscore-prefixed) local signal."
  [x]
  (instance? LocalSignal x))

(defn optimistic?
  "True when x is an Optimistic (a cursor paired with a client signal)."
  [x]
  (instance? Optimistic x))

(defn any-signal?
  "True when x is any signal object — Signal, LocalSignal, or Optimistic."
  [x]
  (or (signal? x) (local-signal? x) (optimistic? x)))

(defn optimistic-signal
  "The client Signal paired inside an Optimistic."
  ^Signal [^Optimistic o]
  (.-signal o))

(defn js-name
  "The signal's Datastar JS name, e.g. \"userName\", \"user.name\" or
   \"_open\" (local signals carry their underscore prefix)."
  [sig]
  (cond
    (signal? sig)       (.-sig-name ^Signal sig)
    (local-signal? sig) (.-sig-name ^LocalSignal sig)
    (optimistic? sig)   (.-sig-name (optimistic-signal sig))
    :else (throw (ex-info "Not a signal" {:value sig}))))

(defn js-ref
  "The Datastar expression reference for a signal, e.g. \"$userName\"."
  [sig]
  (str "$" (js-name sig)))

(defn current-value
  "The signal's current server-side value from tab state, falling back to
   its default.  Used to seed signal-linked component attributes on first
   paint, before Datastar's reactive attributes take over."
  [^Signal sig]
  (get-in @(.-app-state* sig)
          (into [:tabs (.-tab-id sig) :signals] (.-store-path sig))
          (.-default-val sig)))

;; ---------------------------------------------------------------------------
;; Chassis protocol extensions
;; ---------------------------------------------------------------------------
;; Extend Chassis's AttributeValueFragment so that signals used as attribute
;; values render their signal name directly (unescaped is fine — signal names
;; are safe ASCII identifiers).
;;
;;   {:data-bind signal*}   →  data-bind="userName"
;;   {:data-bind local*}    →  data-bind="_showMenu"
;;
;; This lets users write idiomatic hiccup without a separate bind helper.

(extend-protocol c/AttributeValueFragment
  Signal
  (attribute-value-fragment [this]
    (.-sig-name this))
  LocalSignal
  (attribute-value-fragment [this]
    (.-sig-name this))
  Optimistic
  (attribute-value-fragment [this]
    (.-sig-name (optimistic-signal this))))

;; ---------------------------------------------------------------------------
;; Datastar expression protocol
;; ---------------------------------------------------------------------------
;; A signal spliced into an `h/expr` contributes its Datastar reference
;; ("$userName"), the same string `js-ref` yields.  Implementing DatastarExpr
;; lets `hyper.expr/splice` treat signals and actions through one dispatch.

(extend-protocol datastar/DatastarExpr
  Signal
  (-datastar-js [this] (js-ref this))
  LocalSignal
  (-datastar-js [this] (js-ref this))
  Optimistic
  (-datastar-js [this] (js-ref this)))

;; ---------------------------------------------------------------------------
;; Signal construction
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a Signal for the given path and register it in tab state and
   the render-time declaration accumulator."
  [app-state* tab-id path default-val]
  (let [js-name (signal-js-name path)
        html-nm (signal-html-name path)
        st-path (signal-store-path path)
        signal  (->Signal js-name html-nm st-path app-state* tab-id default-val)]
    ;; Initialise the server-side value if not already set.
    ;; Use a sentinel to distinguish "never set" from "explicitly set to nil",
    ;; so that (reset! sig nil) is not silently overwritten by the default.
    (when (identical? ::not-found (get-in @app-state* (into [:tabs tab-id :signals] st-path) ::not-found))
      (swap! app-state* assoc-in (into [:tabs tab-id :signals] st-path) default-val))
    ;; During render, register for HTML declaration
    (when-let [acc context/*declared-signals*]
      (swap! acc conj {:path        path
                       :html-name   html-nm
                       :default-val default-val
                       :local?      false}))
    signal))

(defn create-local-signal
  "Create a LocalSignal for the given path.  Local signals are client-only
   (underscore-prefixed in Datastar) and cannot be read or written from
   the server."
  [path default-val]
  (let [js-name (str "_" (signal-js-name path))
        html-nm (str "_" (signal-html-name path))
        signal  (->LocalSignal js-name html-nm default-val)]
    ;; During render, register for HTML declaration
    (when-let [acc context/*declared-signals*]
      (swap! acc conj {:path        path
                       :html-name   html-nm
                       :default-val default-val
                       :local?      true}))
    signal))

;; ---------------------------------------------------------------------------
;; Optimistic construction
;; ---------------------------------------------------------------------------

(defn derived-signal-path
  "The flat signal path derived from a cursor's scope and logical path.
   (:session, [:cols 0 :width]) → :session-cols-0-width ($sessionCols0Width)."
  [scope path]
  ;; Canonicalize through one camelCase→kebab round trip so the path always
  ;; matches what parse-signals produces for the wire name — segments not
  ;; already in kebab form (e.g. mixed-case strings) would otherwise come
  ;; back from a Datastar request under a different keyword and miss the
  ;; store path.
  (keyword
    (-memoized->kebab-case-string
      (-memoized->camelCaseString
        (str/join "-" (map #(if (keyword? %) (name %) (str %))
                           (cons scope path)))))))

(defn- versioned-policy?
  "True when the conflict policy needs base tracking (a companion signal
   echoing the committed value the client's edit was based on)."
  [policy]
  (or (= :server-wins policy) (fn? policy)))

(defn base-sig-path
  "The companion base-signal path for a derived signal path.
   :session-col-w → :session-col-w-base ($sessionColWBase)."
  [sig-path]
  (keyword (str (name sig-path) "-base")))

(defn resolve-commit
  "The value to commit for an optimistic, given its conflict policy and the
   commit context {:base :committed :reported}.  A commit is clean when
   base = committed; a nil base with a differing committed value counts as
   a conflict (unknown base cannot prove freshness)."
  [policy {:keys [base committed reported] :as ctx}]
  (if (or (nil? policy)
          (= :client-wins policy)
          (= base committed))
    reported
    (case policy
      :server-wins committed
      (policy ctx))))

(def ^:private optimistic-opt-keys #{:auto-commit? :on-conflict})

(defn- validate-optimistic-opts!
  [opts]
  (when-let [unknown (seq (remove optimistic-opt-keys (keys opts)))]
    (throw (ex-info (str "h/optimistic: unknown option(s) " (pr-str (vec unknown)))
                    {:opts opts :allowed optimistic-opt-keys})))
  (let [policy (:on-conflict opts)]
    (when-not (or (nil? policy)
                  (#{:client-wins :server-wins} policy)
                  (fn? policy))
      (throw (ex-info "h/optimistic: :on-conflict must be :client-wins, :server-wins, or a fn"
                      {:on-conflict policy})))))

(defn- sync-optimistic!
  "Reconcile the tab's client signal with the cursor's committed value.
   When the committed value changed since this tab last synced, write it
   (and the base companion, when tracked) into tab signal state — the
   renderer patches the client — and record it as synced."
  [app-state* tab-id st-path base-st-path cursor-val]
  (swap! app-state*
         (fn [state]
           (let [synced (get-in state [:tabs tab-id :optimistic-synced st-path] ::none)]
             (cond
               ;; First render for this tab — the signal was just initialized
               ;; to the committed value; nothing to patch.
               (identical? ::none synced)
               (assoc-in state [:tabs tab-id :optimistic-synced st-path] cursor-val)

               (not= synced cursor-val)
               (cond-> (-> state
                           (assoc-in (into [:tabs tab-id :signals] st-path) cursor-val)
                           (assoc-in [:tabs tab-id :optimistic-synced st-path] cursor-val))
                 base-st-path
                 (assoc-in (into [:tabs tab-id :signals] base-st-path) cursor-val))

               :else state))))
  nil)

(defn create-optimistic
  "Create an Optimistic pairing a scoped cursor with a derived client signal,
   registering the signal declaration and the pairing in tab state."
  [app-state* tab-id cursor opts]
  (let [{:hyper/keys [scope path]} (meta cursor)
        opts                       (or opts {})]
    (when-not scope
      (throw (ex-info (str "h/optimistic requires a scoped cursor — one created by "
                           "global-cursor, session-cursor, tab-cursor, or path-cursor")
                      {:cursor-meta (meta cursor)})))
    (validate-optimistic-opts! opts)
    (let [sig-path (derived-signal-path scope path)
          st-path  (signal-store-path sig-path)]
      ;; The same cursor wrapped again in this render must carry the same
      ;; opts; the declaration accumulator scopes the check to this render.
      (when-let [acc context/*declared-signals*]
        (when (some #(= sig-path (:path %)) @acc)
          (let [existing (get-in @app-state* [:tabs tab-id :optimistics st-path :opts])]
            (when (not= existing opts)
              (throw (ex-info "h/optimistic: cursor already wrapped with different options"
                              {:signal sig-path :existing existing :opts opts}))))))
      (let [cursor-val   @cursor
            signal       (create-signal app-state* tab-id sig-path cursor-val)
            base-st-path (when (versioned-policy? (:on-conflict opts))
                           (let [base-path (base-sig-path sig-path)]
                             (create-signal app-state* tab-id base-path cursor-val)
                             (signal-store-path base-path)))]
        (swap! app-state* assoc-in [:tabs tab-id :optimistics st-path]
               {:opts opts :cursor cursor :base-st-path base-st-path})
        (sync-optimistic! app-state* tab-id st-path base-st-path cursor-val)
        (->Optimistic signal cursor opts)))))

;; ---------------------------------------------------------------------------
;; Committing
;; ---------------------------------------------------------------------------

(defn- override-client!
  "Write `resolved` into the tab's client signal state (and base companion),
   replacing the value the client reported."
  [app-state* tab-id st-path base-st-path resolved]
  (swap! app-state*
         (fn [state]
           (cond-> (-> state
                       (assoc-in (into [:tabs tab-id :signals] st-path) resolved)
                       (assoc-in [:tabs tab-id :optimistic-synced st-path] resolved))
             base-st-path
             (assoc-in (into [:tabs tab-id :signals] base-st-path) resolved))))
  nil)

(defn- commit-reported!
  "Resolve `reported` against the optimistic's conflict policy, write the
   result to `cursor`, and correct the client when the result differs from
   what it reported.  Returns the committed value."
  [app-state* tab-id st-path base-st-path cursor opts signals reported]
  (let [base      (when base-st-path (get-in signals base-st-path))
        committed @cursor
        resolved  (resolve-commit (:on-conflict opts)
                                  {:base base :committed committed :reported reported})]
    (when (not= resolved committed)
      (reset! cursor resolved))
    ;; A pure rejection leaves the cursor untouched, so down-sync alone would
    ;; never patch — override the client here.
    (when (not= resolved reported)
      (override-client! app-state* tab-id st-path base-st-path resolved))
    resolved))

(defn commit!
  "Make the client-reported value of an optimistic official: run its
   conflict policy and write the result to the backing cursor.  Returns
   the committed value.  Action-only."
  [^Optimistic o]
  (let [signals context/*signals*]
    (when-not signals
      (throw (ex-info "commit! must be called inside an action — there are no client signals in context"
                      {:signal (str o)})))
    (let [sig      ^Signal (.-signal o)
          st-path  (.-store-path sig)
          reported (get-in signals st-path ::absent)]
      (when (identical? ::absent reported)
        (throw (ex-info (str "commit!: signal " o " did not accompany this request. "
                             "File-upload actions carry form fields, not Datastar signals.")
                        {:signal (str o) :signals (keys signals)})))
      (let [tab-id                 (.-tab-id sig)
            {:keys [base-st-path]} (get-in @(.-app-state* sig)
                                           [:tabs tab-id :optimistics st-path])]
        (commit-reported! (.-app-state* sig) tab-id st-path base-st-path
                          (.-cursor o) (.-opts o) signals reported)))))

(defn auto-commit!
  "Commit the client-reported value for every registered auto-commit
   optimistic whose signal rode this request's signals map."
  [app-state* tab-id signals]
  (doseq [[st-path {:keys [opts cursor base-st-path]}]
          (get-in @app-state* [:tabs tab-id :optimistics])
          :when                                        (:auto-commit? opts)]
    (let [reported (get-in signals st-path ::absent)]
      (when-not (identical? ::absent reported)
        (commit-reported! app-state* tab-id st-path base-st-path
                          cursor opts signals reported)))))

;; ---------------------------------------------------------------------------
;; Connection status (static, client-only signals)
;; ---------------------------------------------------------------------------
;;
;; Connection state is fundamentally CLIENT-maintained: the server cannot push
;; "you are disconnected" over the very connection that is down, and on
;; reconnect the snapshot render already reflects truth.  So these are static
;; `LocalSignal`s (underscore-prefixed, client-only) whose values are kept in
;; sync by Datastar's fetch lifecycle events on the page `<body>` — never by
;; the server.  They are constructed directly (NOT via `create-local-signal`),
;; so they do not register into the per-render `*declared-signals*`
;; accumulator: declaration + maintenance happens once in the page scaffolding
;; (see `connection-attrs`), not per element per render.
;;
;; During render, `@connection*` / `@connected?*` yield their Datastar
;; expression strings (`"$_hyperConnection"` / `"$_hyperConnected"`), suitable
;; for `data-show`, `expr`, etc.  In an action they throw, like any local
;; signal — connection state is not server-readable.

(def connection-states
  "The set of connection status tokens `connection*` can hold.

   - :connecting   — first connection attempt in progress (initial paint)
   - :open         — connected and streaming
   - :reconnecting — dropped; Datastar is retrying
   - :error        — retries exhausted / terminal failure
   - :closed       — intentionally closed (e.g. a hidden tab under
                     `:open-when-hidden? false`)"
  #{:connecting :open :reconnecting :error :closed})

(def connection*
  "Static client-only signal holding the current SSE connection status as a
   keyword token (one of `connection-states`).  Client-maintained from
   Datastar's fetch lifecycle.

   Compare it with keyword tokens in render/expr — keywords compile to the
   same JS string the wire uses:

     [:span {:data-show (h/expr (= @h/connection* :reconnecting))} \"Reconnecting…\"]"
  (->LocalSignal "_hyperConnection" "_hyper-connection" :connecting))

(def connected?*
  "Static client-only boolean signal: true while the SSE connection is healthy.
   Sugar for the common case; equivalent to `(= @connection* :open)`.

     [:div {:data-show (h/expr (not @h/connected?*))} \"Offline\"]"
  (->LocalSignal "_hyperConnected" "_hyper-connected" true))

;; datastar-fetch event detail.type -> connection token (wire string).
;; `started` means the SSE request opened; the retry/error/finished family
;; means the stream is no longer healthy.  Only `retries-failed` is terminal
;; (Datastar's default `retry: auto` keeps retrying network errors), so a
;; transient blip shows :reconnecting and only a genuine give-up shows :error.
(def ^:private fetch-type->token
  {"started"        "open"
   "retrying"       "reconnecting"
   "error"          "reconnecting"
   "retries-failed" "error"
   "finished"       "reconnecting"})

(defn- connection-tracking-js
  "The Datastar `data-on:datastar-fetch` expression that maps the SSE
   connection's fetch lifecycle to the connection signals.  Filtered to the
   element that initiated the SSE `@get` (`evt.detail.el === el`) so action
   POSTs do not perturb it."
  []
  (let [branches (map (fn [[event-type token]]
                        (format "evt.detail.type === '%s' ? ($_hyperConnection = '%s', $_hyperConnected = %s)"
                                event-type token (if (= token "open") "true" "false")))
                      fetch-type->token)]
    (str "evt.detail.el === el && (" (str/join " : " branches) " : null)")))

(defn connection-attrs
  "Hiccup attribute map for the page `<body>` that declares the client-only
   connection signals (with their defaults) and wires Datastar's fetch
   lifecycle to keep them current.  Returns a map merged into the body attrs
   by the page scaffolding."
  []
  (let [conn-html (.-html-name ^LocalSignal connection*)
        cd-html   (.-html-name ^LocalSignal connected?*)]
    {(keyword (str "data-signals:" conn-html "__ifmissing"))
     (clj->js-literal (name (.-default-val ^LocalSignal connection*)))

     (keyword (str "data-signals:" cd-html "__ifmissing"))
     (clj->js-literal (.-default-val ^LocalSignal connected?*))

     (keyword "data-on:datastar-fetch")
     (connection-tracking-js)}))

(def navigating?*
  "Static client-only boolean signal: true from the moment a client-side
   navigation starts (an `h/navigate` link click, browser back/forward, or an
   action calling `effects/navigate!`) until the new route's render arrives
   over SSE.  Client-maintained, like `connection*`.

     [:div.progress {:data-show (h/expr @h/navigating?*)}]"
  (->LocalSignal "_hyperNavigating" "_hyper-navigating" false))

(def navigating-event
  "Name of the DOM event, dispatched on `<body>` with a boolean `detail`, that
   sets `navigating?*`.  Fired by the page scripts."
  "hyper-navigating")

(defn navigation-attrs
  "Hiccup attribute map for the page `<body>` that declares the client-only
   `navigating?*` signal (with its default) and sets it from the
   `navigating-event` DOM event.  Merged into the body attrs by the page
   scaffolding."
  []
  (let [html-name (.-html-name ^LocalSignal navigating?*)
        js-name   (.-sig-name ^LocalSignal navigating?*)]
    {(keyword (str "data-signals:" html-name "__ifmissing"))
     (clj->js-literal (.-default-val ^LocalSignal navigating?*))

     (keyword (str "data-on:" navigating-event))
     (str "$" js-name " = evt.detail === true")}))

;; ---------------------------------------------------------------------------
;; Signal parsing (from Datastar request bodies)
;; ---------------------------------------------------------------------------

(defn parse-signals
  "Parse a JSON signal body from a Datastar @post() request, converting
   camelCase keys to kebab-case keywords recursively.  Returns a
   keyword-keyed map or nil."
  [json-str]
  (when (and json-str (not (str/blank? json-str)))
    (json/parse-string json-str (fn [k] (keyword (-memoized->kebab-case-string k))))))

;; ---------------------------------------------------------------------------
;; HTML signal attribute generation
;; ---------------------------------------------------------------------------

(defn format-signal-attrs
  "Build a hiccup attribute map declaring signals on an element using
   data-signals:NAME__ifmissing attributes.

   declared-signals is a sequence of maps with :html-name, :default-val,
   and :local? keys."
  [declared-signals]
  (when (seq declared-signals)
    (reduce (fn [attrs {:keys [html-name default-val]}]
              (let [attr-key (keyword (str "data-signals:" html-name "__ifmissing"))]
                (assoc attrs attr-key (clj->js-literal default-val))))
            {}
            declared-signals)))

;; ---------------------------------------------------------------------------
;; SSE patch-signals event
;; ---------------------------------------------------------------------------

(defn format-patch-signals-event
  "Format a map of {kebab-keyword → value} as a Datastar
   datastar-patch-signals SSE event.  Keys are converted to
   camelCase strings for the wire format.  Nil values become
   JSON null (Datastar removes signals set to null)."
  [signal-patches]
  (let [json-str (json/generate-string signal-patches
                                       {:key-fn (comp -memoized->camelCaseString name)})]
    (str "event: datastar-patch-signals\n"
         "data: signals " json-str "\n\n")))

(defn changed-signals
  "Return a map of signal names whose values differ between old-signals
   and new-signals.  Values are taken from new-signals.  Signals present
   in old-signals but absent from new-signals are included with nil
   values (Datastar removes signals set to null)."
  [old-signals new-signals]
  (let [changed (reduce-kv (fn [acc k v]
                             (if (= v (get old-signals k))
                               acc
                               (assoc acc k v)))
                           {}
                           new-signals)
        removed (reduce-kv (fn [acc k _v]
                             (if (contains? new-signals k)
                               acc
                               (assoc acc k nil)))
                           {}
                           (or old-signals {}))]
    (merge changed removed)))

(defn- signal-path-vec
  "Normalize a declared signal :path (keyword or keyword vector) to a
   vector of keywords for get-in/dissoc-in lookups."
  [path]
  (cond
    (keyword? path) [path]
    (vector? path)  (vec path)
    :else           nil))

(defn- dissoc-path
  "Remove the value at `path` from nested map `m`, pruning any map left
   empty by the removal so an emptied branch disappears entirely."
  [m [k & ks]]
  (if (seq ks)
    (let [child (dissoc-path (get m k) ks)]
      (if (and (map? child) (empty? child))
        (dissoc m k)
        (assoc m k child)))
    (dissoc m k)))

(defn drop-ifmissing-covered-patches
  "Return the signal patches still worth sending after dropping those the
   body fragment's `data-signals:NAME__ifmissing` declarations already
   cover on a fresh client.

   The body fragment declares each signal via
   `data-signals:NAME__ifmissing=DEFAULT`, which initializes the signal to
   DEFAULT when the client does not already have it.  A patch leaf is kept
   only when it adds information the declaration cannot supply: the signal
   was already sent to the client before (present in `sent-signals`), or
   its value differs from the declared default.  Dropping the rest lets
   Datastar keep state it materializes from the DOM — e.g. a checkbox group
   whose array signal Datastar builds from the checkboxes (issue #44).

   Suppression is per declared signal path, so both top-level and nested
   signals are covered; branches emptied by a drop are pruned.

   - `sig-patches`      map of {kebab-keyword → value} about to be sent
   - `declared-signals` declared-signal maps for this render
   - `sent-signals`     signals the client already holds — last sent merged
                        with client-reported (nil on first render)"
  [sig-patches declared-signals sent-signals]
  (if (seq sig-patches)
    (reduce (fn [patches {:keys [path default-val]}]
              (let [p (signal-path-vec path)]
                (if (and p
                         (= default-val (get-in patches p ::not-found))
                         (= ::not-found (get-in sent-signals p ::not-found)))
                  (dissoc-path patches p)
                  patches)))
            sig-patches
            declared-signals)
    sig-patches))
