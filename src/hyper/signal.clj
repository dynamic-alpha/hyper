(ns hyper.signal
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
            [hyper.context :as context]))

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

(defn- escape-js-single-quote
  "Escape single quotes and backslashes for use inside JS single-quoted strings."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")))

(defn clj->js-literal
  "Convert a Clojure value to a JavaScript literal string suitable for
   use in Datastar expressions and data-signals attributes."
  [v]
  (cond
    (nil? v)     "null"
    (boolean? v) (str v)
    (number? v)  (str v)
    (string? v)  (str "'" (escape-js-single-quote v) "'")
    (keyword? v) (str "'" (name v) "'")
    (map? v)     (str "{" (str/join ", "
                                    (map (fn [[k v']]
                                           (str (if (keyword? k) (name k) k)
                                                ": " (clj->js-literal v')))
                                         v)) "}")
    (coll? v)    (str "[" (str/join ", " (map clj->js-literal v)) "]")
    :else        (str "'" (escape-js-single-quote (str v)) "'")))

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
    (.-sig-name this)))

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
    ;; Initialise the server-side value if not already set
    (when (nil? (get-in @app-state* (into [:tabs tab-id :signals] st-path)))
      (swap! app-state* assoc-in (into [:tabs tab-id :signals] st-path) default-val))
    ;; During render, register for HTML declaration
    (when-let [acc context/*declared-signals*]
      (swap! acc conj {:html-name   html-nm
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
      (swap! acc conj {:html-name   html-nm
                       :default-val default-val
                       :local?      true}))
    signal))

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
