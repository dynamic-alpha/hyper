(ns ^:no-doc hyper.component
  "Client-side web components compiled via embedded Squint.

   This namespace provides the foundation for defining custom elements in a
   ClojureScript dialect (Squint) that are compiled to JavaScript at the JVM
   (no Node build step) and served as a single ES module bundle at
   /hyper/components.js.

   The model follows Datastar's recommended escape hatch for rich client-side
   islands — web components with \"props down, events up\":

   - **Attributes are the boundary.** The server renders component data into
     HTML attributes (deterministic JSON for collections).  Datastar's morph
     updates attributes in place, and the client runtime re-renders only when
     an attribute string actually changed.
   - **Events are the channel out.** Components dispatch bubbling, composed
     CustomEvents that the server catches with ordinary `data-on:*` +
     `h/action` (see the `$detail` client param).

   The primary API is the `defc` macro, which compiles the component's client
   behaviour to JS at macro-expansion time and emits a server-side render fn
   so call sites look like ordinary hiccup functions.  `register-component!`
   is the lower-level escape hatch for cases where raw Squint strings are
   needed.

   Related namespaces: hyper.component.hiccup (macro-time hiccup -> ($h ...)
   descriptor compilation) and hyper.component.bundle (ES module assembly
   and the head script tag)."
  (:require [cheshire.core :as json]
            [clojure.core.memoize :as memo]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hyper.component.hiccup :as hiccup]
            [hyper.signal :as signal]
            [squint.compiler :as squint]))

;; ---------------------------------------------------------------------------
;; Component registry
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Global component registry: name -> {:attrs [...] :js \"...\"}.
   Components are code (like defn), not per-app state, so the registry is
   global.  Watched by connected tabs so REPL redefinition hot-swaps the
   bundle over SSE."}
  registry*
  (atom {}))

;; ---------------------------------------------------------------------------
;; Squint compilation
;; ---------------------------------------------------------------------------

(def ^:private squint-opts
  {:elide-imports true
   :core-alias    "$sc"
   :context       :statement})

(defn- compile-squint*
  [source]
  (try
    (squint/compile* source squint-opts)
    (catch Exception e
      (throw (ex-info (str "Squint compilation failed: " (ex-message e))
                      {:source source}
                      e)))))

(defn compile-squint
  "Compile a string of Squint (CLJS-dialect) source to JavaScript.

   Imports are elided and squint.core references are emitted against the
   `$sc` alias, which the assembled bundle binds via a single import of
   squint's core.js.  Returns the JS string.

   Throws ex-info with the source attached when compilation fails."
  [source]
  (:body (compile-squint* source)))

(defn- ns-form-source
  "Build a Squint ns form declaring ES module requires, so alias-qualified
   symbols (e.g. d3/line) resolve during compilation.  The import statements
   themselves are elided from the compiled output (the bundle prelude emits
   them once, deduplicated across components)."
  [tag-name requires]
  (when (seq requires)
    (str "(ns hyper.component."
         (str/replace tag-name "-" "_")
         " (:require "
         (str/join " " (map (fn [{:keys [url alias]}]
                              (str "[\"" url "\" :as " alias "]"))
                            requires))
         "))\n")))

(defn- define-call-source
  "Assemble the full Squint source for a component definition: the optional
   ns/require form followed by a call to the runtime's `$define` (which the
   bundle prelude provides) with the given spec-entry strings as the spec
   map body.  Shared by registration-source (raw Squint strings) and
   squint-source (defc)."
  [tag-name requires spec-entries]
  (str (ns-form-source tag-name requires)
       "($define " (pr-str tag-name) " "
       "{" (str/join " " spec-entries) "})"))

(defn- registration-source
  "Build the Squint source for a component registration call.

   `name` is the custom element tag name (must contain a hyphen).
   `spec` keys:
   - :attrs    — vector of observed attribute name strings
   - :render   — Squint source string for a `(fn [props ctx] hiccup)` form
   - :requires — optional seq of {:url \"...\" :alias sym} ES module deps"
  [name {:keys [attrs render requires]}]
  (define-call-source name requires
    [(str ":attrs " (pr-str (mapv clojure.core/name attrs)))
     (str ":render " (str/trim render))]))

(defn parse-requires
  "Normalize and validate a :require option value.

   Accepts a vector of [\"url\" :as alias] entries and returns a vector of
   {:url \"...\" :alias sym} maps.  Throws on malformed entries."
  [requires]
  (mapv (fn [entry]
          (if (and (vector? entry)
                   (= 3 (count entry))
                   (string? (first entry))
                   (= :as (second entry))
                   (symbol? (nth entry 2)))
            {:url (first entry) :alias (nth entry 2)}
            (throw (ex-info (str "Malformed :require entry: " (pr-str entry)
                                 "\nExpected [\"url\" :as alias]")
                            {:entry entry}))))
        requires))

(defn check-alias-conflicts!
  "Throw when any of `requires` uses an alias already registered by another
   component with a different URL — the bundle is a single module scope, so
   aliases must map 1:1 to URLs across all components.

   Public because defc expansions call it from consumer namespaces."
  [tag-name requires]
  (doseq [{:keys [url alias]} requires]
    (doseq [[other-name {other-requires :requires}] @registry*
            :when                                   (not= other-name tag-name)
            {other-url :url other-alias :alias}     other-requires
            :when                                   (and (= other-alias alias) (not= other-url url))]
      (throw (ex-info (str "Component \"" tag-name "\" requires \"" url "\" :as " alias
                           ", but component \"" other-name "\" already requires \""
                           other-url "\" :as " alias
                           ". Aliases must map to a single URL across all components.")
                      {:alias alias :urls [other-url url]})))))

(defn register-component!
  "Compile and register a client-side component.

   name — custom element tag name string (must contain a hyphen, per the
          Custom Elements spec, e.g. \"temp-gauge\").
   spec — map with:
     :attrs   — vector of observed attribute names (strings or keywords)
     :render  — Squint source string for a `(fn [props ctx] hiccup)` form.
                `props` is a JS object keyed by attribute name with values
                parsed from the attributes (JSON for collections, raw for
                plain strings).  `ctx` provides:
                  (.-emit ctx) — (fn [event-name detail]) dispatch a bubbling,
                                 composed CustomEvent across the boundary.
     :require — optional vector of ES module deps: [[\"url\" :as alias] ...].
                Alias-qualified symbols (alias/fn) resolve in the source and
                the bundle imports each URL once.

   Compilation happens immediately (at the JVM); errors surface at
   definition time, not in the browser.  Re-registering a name replaces
   the spec — connected tabs watching the registry receive the new bundle
   over SSE and live instances re-render.

   Returns the component name."
  [name spec]
  {:pre [(string? name) (str/includes? name "-") (:render spec)]}
  (let [requires                      (parse-requires (:require spec))
        _                             (check-alias-conflicts! name requires)
        spec                          (-> spec (dissoc :require) (assoc :requires requires))
        {:keys [body used-core-vars]} (compile-squint* (registration-source name spec))]
    (swap! registry* assoc name (assoc spec :js body :core-vars used-core-vars))
    name))

;; ---------------------------------------------------------------------------
;; defc — the primary component authoring macro
;; ---------------------------------------------------------------------------

(defn sym->tag
  "Convert a Clojure symbol to a custom-element tag name string.
   CamelCase becomes kebab-case; underscores become hyphens.
   The name must contain a hyphen (Custom Elements spec requirement).

   Examples:
     temp-gauge   -> \"temp-gauge\"
     TempGauge    -> \"temp-gauge\"
     temp_gauge   -> \"temp-gauge\""
  [sym]
  (-> (name sym)
      (str/replace #"([A-Z])" "-$1")
      (str/replace #"_" "-")
      str/lower-case
      (str/replace #"^-" "")))

(defn extract-attrs
  "Extract the vector of observed attribute name keywords from a `defc`
   parameter vector of the form `[{:keys [attr1 attr2 ...]}]`.

   The first element of the vector must be a map-destructuring form
   containing `:keys`.  Attribute names must be statically visible at
   macro-expansion time so `observedAttributes` can be declared.

   Returns a vector of keywords, e.g. [:value :max :label]."
  [binding-vec]
  (let [first-form (first binding-vec)]
    (when (and (map? first-form) (contains? first-form :keys))
      (mapv keyword (:keys first-form)))))

(def ^:private lifecycle-arities
  "Allowed binding-vector arities per lifecycle segment.
   mount/unmount take [root]; update takes [root] or [root old-attrs]."
  {'mount #{1} 'update #{1 2} 'unmount #{1}})

(defn- parse-segments
  "Parse the body forms of a `defc` definition into a map of segments.

   Recognised segment forms:
   - `(event ::name [evt-sym] body...)`
     Declares an internal event handler.  `::name` is a namespace-qualified
     keyword that identifies the handler; it can be referenced in `render`
     hiccup via `{:on {:click ::name}}`.
   - `(render body...)`
     The hiccup-returning expression for the component's shadow root.
     In declarative mode it re-runs on every attribute change; when an
     `update` segment is present it is a once-only scaffold.
   - `(mount [root] body...)`
     Runs once after the scaffold renders.  `root` is the component's
     content root element.
   - `(update [root old-attrs?] body...)`
     Runs on each subsequent attribute change instead of re-rendering —
     the seamless path for JS libraries that transition on data updates.
   - `(unmount [root] body...)`
     Cleanup when the element truly leaves the DOM (morph-driven moves
     are debounced and do not unmount).

   At most one of each lifecycle segment; `render` or `mount` is required
   (validated by the caller).

   Returns {:events {::name (fn-form)}
            :render (hiccup-form)
            :mount/:update/:unmount {:args [...] :body (...)}}."
  [name body]
  (reduce
    (fn [acc form]
      (let [head (when (seq? form) (first form))]
        (cond
          (= 'event head)
          (let [[_ ev-kw args & ev-body] form]
            (assert (keyword? ev-kw)
                    (str "defc " name ": event name must be a keyword, got " (pr-str ev-kw)))
            (assert (and (vector? args) (= 1 (count args)))
                    (str "defc " name ": event handler takes exactly one argument "
                         "(the DOM event), got " (pr-str args)))
            (update acc :events assoc ev-kw `(fn ~args ~@ev-body)))

          (= 'render head)
          (do
            (assert (nil? (:render acc))
                    (str "defc " name ": only one render segment allowed"))
            ;; Multiple forms in render are wrapped in a `do`; single forms are kept bare.
            (let [forms (rest form)]
              (assoc acc :render (if (= 1 (count forms)) (first forms) `(do ~@forms)))))

          (contains? lifecycle-arities head)
          (let [[_ args & lc-body] form
                kw                 (keyword head)]
            (assert (nil? (get acc kw))
                    (str "defc " name ": only one " head " segment allowed"))
            (assert (and (vector? args)
                         (contains? (lifecycle-arities head) (count args)))
                    (str "defc " name ": " head " binding vector must be "
                         (if (= 'update head) "[root] or [root old-attrs]" "[root]")
                         ", got " (pr-str args)))
            (assoc acc kw {:args args :body lc-body}))

          :else
          (throw (ex-info (str "defc " name ": unrecognised segment " (pr-str form)
                               "\nExpected (event ...), (render ...), (mount ...), "
                               "(update ...) or (unmount ...)")
                          {:form form})))))
    {:events {} :render nil}
    body))

(defn- rewrite-event-refs
  "Walk a hiccup form and replace `::handler-kw` references inside `:on` maps
   with `(get $handlers ::handler-kw)` lookups.

   Input:  {:on {:click ::clicked}}
   Output: {:on {:click (get $handlers :clicked)}}"
  [form ns-str]
  (walk/postwalk
    (fn [x]
      (if (and (map? x) (contains? x :on))
        (update x :on
                (fn [on-map]
                  (reduce-kv
                    (fn [m ev handler]
                      (assoc m ev
                             (if (and (keyword? handler)
                                      (= (namespace handler) ns-str))
                               (list 'get '$handlers (keyword (clojure.core/name handler)))
                               handler)))
                    {}
                    on-map)))
        x))
    form))

(defn- squint-source
  "Build the complete Squint source for a `defc` component.

   The generated source is a call to `$define` with:
   - :attrs  — vector of attribute name strings
   - :render — a fn [props ctx] that:
       1. Destructures props by attr names
       2. Extracts `emit` from ctx
       3. Binds each named event handler (closing over props + emit)
       4. Binds `$handlers` — a map from handler keyword to fn
       5. Returns the render hiccup, with `::name` refs replaced by
          `(get $handlers :name)` lookups
   - :mount/:update/:unmount — lifecycle fns matching the runtime calling
     convention (props, ctx, root[, old-props]), with attrs destructured
     and `emit`/`ctx` in scope.

   When `requires` is non-empty, an ns form with the module requires is
   prepended so alias-qualified symbols resolve during compilation.

   All forms are emitted as Clojure data (readable by Squint) so no
   string-quoting escaping is needed — `pr-str` handles it."
  [tag-name attr-keys {:keys [events render mount update unmount]} ns-str requires]
  (let [attr-strs        (mapv clojure.core/name attr-keys)
        attr-destructure {:keys (mapv symbol (map clojure.core/name attr-keys))}
        lifecycle-fn     (fn [{:keys [args body]}]
                       ;; Runtime calls (props, ctx, root[, old-props]);
                       ;; user binding vector supplies names for root [+ old].
                           `(~'fn [~attr-destructure ~'ctx ~@args]
                                  (~'let [~'emit (.-emit ~'ctx)]
                                         ~@body)))
        render-fn
        (when render
          (let [render-rewritten (-> render
                                     (rewrite-event-refs ns-str)
                                     hiccup/compile-hiccup)
                handler-bindings (mapcat (fn [[kw fn-form]]
                                           [(symbol (str "on-" (clojure.core/name kw))) fn-form])
                                         events)
                handlers-map     (into {}
                                       (map (fn [[kw _]]
                                              [(keyword (clojure.core/name kw))
                                               (symbol (str "on-" (clojure.core/name kw)))]))
                                       events)]
            `(~'fn [~attr-destructure ~'ctx]
                   (~'let [~'emit (.-emit ~'ctx)
                           ~@handler-bindings
                           ~@(when (seq events) ['$handlers handlers-map])]
                          ~render-rewritten))))
        spec-entries     (cond-> [(str ":attrs " (pr-str attr-strs))]
                           render-fn (conj (str ":render " (pr-str render-fn)))
                           mount     (conj (str ":mount " (pr-str (lifecycle-fn mount))))
                           update    (conj (str ":update " (pr-str (lifecycle-fn update))))
                           unmount   (conj (str ":unmount " (pr-str (lifecycle-fn unmount)))))]
    (define-call-source tag-name requires spec-entries)))

(defmacro defc
  "Define a client-side web component.

   Compiles the component's client behaviour to JavaScript via Squint at
   macro-expansion time and registers it in the global component registry.
   Also emits a server-side render function (with the same name) that
   serializes attributes and returns the correct hiccup host element,
   so call sites look like ordinary Clojure functions.

   Syntax:
     (defc component-name
       \"Optional docstring\"
       {:require [[\"url\" :as alias] ...]}  ; optional ES module dependencies
       [{:keys [attr1 attr2 ...]}]   ; observed attributes — must be :keys form
       (event ::handler-name [e]     ; zero or more named event handlers
         ...)
       (render                       ; hiccup for the shadow root
         hiccup-returning-body)
       (mount [root] ...)            ; optional: runs once after first render
       (update [root old-attrs] ...) ; optional: runs on attr changes instead
                                     ; of re-rendering (seamless mode)
       (unmount [root] ...))         ; optional: cleanup on real DOM removal

   Two update models:

   - **Declarative** (no `update` segment): `render` re-runs whenever an
     attribute actually changes.

   - **Seamless** (`update` present): `render` is a once-only scaffold (or
     omit it for a bare root); `mount` initializes a JS library against the
     root element; `update` receives each data change so the library can
     transition in place — the DOM is never re-rendered, so chart instances
     and animations survive arbitrary server re-renders.  Morph-driven DOM
     moves are debounced and do NOT unmount; only real removal does.

   In every segment, `ctx` is in scope: a stable per-instance JS object
   carrying `emit`, and usable as the instance state slot:

     (mount [root] (set! (.-chart ctx) (init-chart! root data)))
     (update [root] (redraw! (.-chart ctx) data))
     (unmount [root] (destroy! (.-chart ctx)))

   `:require` declares ES module dependencies, available in all segments via
   the alias (e.g. `(d3/line)`).  Each URL is imported once in the bundle,
   deduplicated across components; an alias may map to only one URL across
   the whole app:

     (defc stock-chart
       {:require [[\"https://esm.sh/d3@7\" :as d3]]}
       [{:keys [points]}]
       (render [:svg (d3-path points)]))

   Inside `render`, reference event handlers in `:on` maps using
   namespace-qualified keywords matching the `event` names:
     {:on {:click ::handler-name}}

   Inside `event` bodies, `emit` is available as a function:
     (emit \"event-name\" detail-map)

   The emitted server-side function accepts a map argument followed by any
   number of child elements.  Pass your attribute values (including
   `data-on:*` action bindings) in the map; children are appended to the
   host element's light DOM (projected through any `<slot>` the component
   renders):

     (my-component {:value  @cursor*
                    :label  \"CPU\"
                    :data-on:selected (h/action (handle! $detail))})

     (my-component {:label \"CPU\"}
       [:span \"slotted child\"]
       [:span \"another\"])

   Example:
     (defc temp-gauge
       [{:keys [value max label]}]
       (event ::selected [_e]
         (emit \"gauge-selected\" {:value value :label label}))
       (render
         (let [pct (js/Math.round (* 100 (/ value max)))]
           [:div {:on {:click ::selected}}
            [:span label \": \" pct \"%\"]])))"
  {:style/indent        1
   :style.cljfmt/indent [[:block 1] [:inner 1]]}
  [cname & body]
  (let [[docstring body]              (if (string? (first body))
                                        [(first body) (rest body)]
                                        [nil body])
        [opts body]                   (if (map? (first body))
                                        [(first body) (rest body)]
                                        [{} body])
        requires                      (parse-requires (:require opts))
        [binding-vec & segments]      body
        _                             (assert (vector? binding-vec)
                                              (str "defc " cname ": expected attribute binding vector, got "
                                                   (pr-str binding-vec)))
        attr-keys                     (extract-attrs binding-vec)
        _                             (assert (seq attr-keys)
                                              (str "defc " cname ": binding vector must use {:keys [...]} form "
                                                   "so attribute names are statically visible"))
        tag-name                      (sym->tag cname)
        _                             (assert (str/includes? tag-name "-")
                                              (str "defc " cname ": tag name \"" tag-name
                                                   "\" must contain a hyphen (Custom Elements spec)"))
        parsed                        (parse-segments cname segments)
        _                             (assert (or (:render parsed) (:mount parsed))
                                              (str "defc " cname ": a (render ...) or (mount ...) segment is required"))
        ns-str                        (str *ns*)
        src                           (squint-source tag-name attr-keys parsed ns-str requires)
        tag-kw                        (keyword tag-name)
        ;; Compile at macro-expansion time so Squint errors surface at compile
        ;; time (with the source attached), but emit the registration into the
        ;; expansion so it executes at runtime.  This keeps defc AOT-safe
        ;; (expansion-time side effects don't survive AOT compilation) and
        ;; testable (with-redefs of registry* works).
        {compiled-js :body
         core-vars   :used-core-vars} (compile-squint* src)]
    `(do
       (check-alias-conflicts! ~tag-name '~requires)
       (swap! registry* assoc ~tag-name {:attrs     ~attr-keys
                                         :requires  '~requires
                                         :js        ~compiled-js
                                         :core-vars ~core-vars})
       (defn ~cname
         ~@(when docstring [docstring])
         [attr-map# & children#]
         (into [~tag-kw (attrs attr-map#)] children#)))))

;; ---------------------------------------------------------------------------
;; Attribute serialization (server -> attribute boundary)
;; ---------------------------------------------------------------------------

(defn- deep-sort
  "Recursively convert maps to sorted maps so JSON encoding is deterministic.
   Determinism matters twice: the client change gate compares raw attribute
   strings, and stable output maximizes brotli streaming compression."
  [v]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) x)
        x))
    v))

(def ^{:doc "Deterministic JSON encoding of a Clojure value, LRU-memoized so
   large unchanged data structures (e.g. chart datasets) serialize once
   across re-renders rather than per frame."}
  stable-json
  (memo/lru (fn [v] (json/generate-string (deep-sort v)))
            :lru/threshold 256))

(defn attr-value
  "Serialize a single component attribute value for HTML.
   Strings pass through raw (human-readable attributes); collections get
   deterministic JSON; keywords serialize as their name."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  v
    (keyword? v) (name v)
    (number? v)  (str v)
    (boolean? v) (str v)
    :else        (stable-json v)))

(defn- signal-link-attrs
  "Generate the attribute triple that live-links a component attribute to a
   Datastar signal:

   1. The attribute itself, JSON-encoded from the signal's current
      server-side value — correct first paint before Datastar runs.
   2. `data-attr:<name>` — Datastar reactively rewrites the attribute
      (JSON-encoded) whenever the signal changes, flowing through the
      component's normal change gate with zero server involvement.
   3. `data-on:<name>` — an event of the attribute's name assigns the
      signal from `evt.detail`, so `(emit \"<name>\" v)` inside the
      component writes the signal.

   The JSON encoding on both sides means values round-trip through the
   existing attribute parser uniformly (strings, numbers, booleans,
   collections)."
  [acc k sig]
  (let [attr-name (name k)
        sig-ref   (signal/js-ref sig)]
    (-> acc
        (assoc k (stable-json (signal/current-value sig)))
        (assoc (keyword (str "data-attr:" attr-name))
               (str "JSON.stringify(" sig-ref ")"))
        (assoc (keyword (str "data-on:" attr-name))
               (str sig-ref " = evt.detail")))))

(defn attrs
  "Serialize a map of component attribute values for use in hiccup.

   Component data attrs are serialized via `attr-value`.  Keys that are
   part of the host-element contract — `data-*` (e.g. `data-on:*` action
   bindings), :id, :class, :style — pass through untouched.

   Passing a **signal object** (un-deref'd) as an attribute value creates a
   live two-way client-side link: Datastar keeps the attribute synced to
   the signal (component re-renders/updates on signal change with no server
   round-trip), and the component writes the signal back by emitting an
   event named after the attribute:

     ;; server render fn
     (let [hover* (h/signal :hovered-symbol nil)]
       (stock-chart {:points @points* :hover hover*}))

     ;; inside the component: read `hover` like any attr; write it with
     (emit \"hover\" \"AAPL\")

   Example:
     [:temp-gauge (hc/attrs {:value @temp*
                             :label \"CPU\"
                             :data-on:gauge-selected (h/action ...)})]"
  [m]
  (reduce-kv
    (fn [acc k v]
      (let [kn (name k)]
        (cond
          (or (str/starts-with? kn "data-")
              (contains? #{"id" "class" "style"} kn))
          (assoc acc k v)

          (signal/signal? v)
          (signal-link-attrs acc k v)

          :else
          (assoc acc k (attr-value v)))))
    {}
    m))
