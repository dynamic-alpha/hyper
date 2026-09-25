(ns ^:no-doc hyper.expr
  "Clojure → Datastar expression transpiler.

   Write Datastar (data-*) expressions as s-expressions with hyper's own
   vocabulary — signals are atoms here exactly as they are in actions:

     (let [open?* (h/local-signal :open false)]
       [:button {:data-on:click (->expr (swap! open?* not))}]   ;; \"$_open = !($_open)\"
       [:div {:data-show (->expr @open?*)}])                     ;; \"$_open\"

   The same `(reset! sig v)` form means a server round-trip inside
   `h/action` and an instant client-side assignment inside `->expr` —
   the surrounding macro decides where the code runs, not the syntax.

   **Boundary inference** — what is Clojure and what is client-side:

   - Locals (anything bound in the surrounding scope) splice at runtime:
     signal objects become `$signal` references, plain values become JS
     literals.  No unquoting needed.
   - `(reset! sig v)` / `(swap! sig f & args)` on a signal local compile
     to assignments: `$sig = ...`.
   - `@sig` (deref of a local) becomes a `$sig` reference.
   - Keyword-call forms `(:id person)` evaluate as Clojure at runtime and
     splice as literals (keywords are not callable client-side).
   - `~form` explicitly splices any other Clojure expression.
   - `(h/action ...)` (any var tagged `:hyper/datastar-expr`) runs as Clojure
     at render time — registering the action — and contributes its raw
     `@post(...)` expression, so actions compose inside client-side control
     flow: `(->expr (when (= $key \"Enter\") (h/action ...)))`.
   - Client-param symbols (`$value`, `$checked`, `$key`, `$detail`,
     `$form-data`, and any registered via `hyper.client-params`) expand to
     their client-side JS accessor, the same vocabulary `action` uses:
     `$key` → `evt.key`, `$value` → `evt.target.value`.  These names take
     precedence, so a signal literally named `value` is reached via its
     signal object (`@value*`), not the raw `$value` symbol.
   - Everything else is client-side: `$signal` symbols, `evt`/`el`,
     Datastar actions (`(@post \"/x\")`), JS interop and operators.

   Compilation happens at macro-expansion time via Squint — runtime cost
   is string interpolation of spliced values only.  Output calls squint
   core through the `hyper_sc` global, which hyper serves.

   The transpiler core is ported, with gratitude, from Casey Link's
   datastar-expressions (https://github.com/outskirtslabs/datastar-expressions,
   MIT).  Hyper's additions: macro-time compilation with runtime splicing,
   boundary inference via &env, and atom-vocabulary signal support.

   Covered: the simple, obvious expressions a human would write.  It is
   possible to write forms that produce broken JavaScript — raw strings
   remain the escape hatch everywhere expressions are accepted."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hyper.client-params :as client-params]
            [hyper.datastar :as datastar]
            [hyper.signal :as signal]
            [squint.compiler :as squint]))

;; ---------------------------------------------------------------------------
;; Squint special-form overrides
;; ---------------------------------------------------------------------------
;; Compiler macros (squint's :macros option) that replace the defaults.

(defn- expr-raw
  ([_ _] (list 'js* ""))
  ([_ _ x]
   (let [js (str/join (repeat (count x) "~{}"))]
     (concat (list 'js* js) x))))

(def ^:private compiler-macros
  {'expr {'raw expr-raw}})

;; ---------------------------------------------------------------------------
;; Pre-processing (form level)
;; ---------------------------------------------------------------------------

(defn- process-client-params
  "Replace client-param symbols ($value, $key, $form-data, … and any
   user-registered ones) with their client-side JS, so expr shares the
   event-accessor vocabulary of `action`: `(= $key \"Enter\")` compiles the
   same as `(= evt.key \"Enter\")`.

   These are the same registry symbols `action` uses; there, they also ride a
   server round-trip, but expr is purely client-side, so only the `:js` is
   emitted.  Because these names take precedence, a Datastar signal literally
   named e.g. `value` must be referenced via its signal object (`@value*`)
   rather than the raw `$value` symbol."
  [form]
  (let [params (client-params/defined-client-params)]
    (walk/postwalk
      (fn [node]
        (if (and (symbol? node) (contains? params node))
          (list 'js* (:js (client-params/client-param node)))
          node))
      form)))

(defn- pre-process [form]
  (process-client-params form))

;; ---------------------------------------------------------------------------
;; Post-processing (compiled JS level)
;; ---------------------------------------------------------------------------

(defn- replace-deref
  "(@get \"/x\") reads as ((deref get) \"/x\") and compiles to
   squint_core.deref(get)(...) — restore Datastar's @action syntax."
  [js]
  (-> js
      (str/replace #"hyper_sc\.deref\(hyper_sc\.(get)\)\s*\(" "@$1(")
      (str/replace #"hyper_sc\.deref\(([a-zA-Z_$][a-zA-Z0-9_$]*)\)\s*\(" "@$1(")))

(defn- collect-kebab-signals
  "All $signal symbols containing dashes, as name strings."
  [form]
  (into #{}
        (comp (filter symbol?)
              (map name)
              (filter #(and (str/starts-with? % "$")
                            (str/includes? % "-"))))
        (tree-seq coll? seq form)))

(defn- restore-signal-casing
  "Squint munges $record-id to $record_id; Datastar accepts kebab signal
   names, so restore the original spelling."
  [js form]
  (reduce (fn [s kebab]
            (str/replace s (str/replace kebab "-" "_") kebab))
          js
          (collect-kebab-signals form)))

;; ---------------------------------------------------------------------------
;; Boundary inference
;; ---------------------------------------------------------------------------
;; Walk the expression deciding what is Clojure (spliced at runtime) and
;; what is client-side (compiled by Squint).  Placeholder symbols stand in
;; for spliced values and are substituted into the compiled template at
;; runtime.

(def ^:private deref-sym 'clojure.core/deref)

(defn- deref-form? [form]
  (and (seq? form) (= deref-sym (first form))))

(defn- signal-var?
  "True when `sym` resolves (in the caller's ns at macro-expansion) to a Var
   whose value is a signal object — e.g. a static signal held in a top-level
   def like `hyper.signal/connected?*`.  This lets `->expr` splice deref of
   such vars (`@connected?*`) the same way it splices deref of signal locals,
   so they compile to a `$ref` rather than a broken `squint_core.deref(...)`."
  [sym]
  (and (symbol? sym)
       (when-let [v (try (resolve sym) (catch Exception _ nil))]
         (and (var? v) (signal/any-signal? (deref v))))))

(defn- datastar-expr-head?
  "True when `sym` resolves (in the caller's ns at macro-expansion) to a Var
   marked `:hyper/datastar-expr` — a macro/fn that produces a finished
   Datastar expression, e.g. `h/action`.  Such a form is opaque to Squint:
   `->expr` splices it whole so it runs as Clojure (in render context,
   registering the action) and contributes its raw JS."
  [sym]
  (and (symbol? sym)
       (when-let [v (try (resolve sym) (catch Exception _ nil))]
         (boolean (:hyper/datastar-expr (meta v))))))

(defn- add-placeholder!
  "Register expr for runtime splicing; returns the placeholder symbol.
   mode is :value (signals -> $ref, other values -> JS literal) or
   :signal (must be a signal; -> $ref)."
  [pairs* expr mode]
  (let [ph (symbol (str "__hyper_s" (count @pairs*) "__"))]
    (swap! pairs* conj [(name ph) expr mode])
    ph))

(defn- infer-boundary
  "Transform form, replacing Clojure-side expressions with placeholders.
   env-locals is the set of locally-bound symbols from &env."
  [form env-locals pairs*]
  (letfn [(clj? [sym] (and (symbol? sym) (contains? env-locals sym)))
          (xf [node]
            (cond
              ;; ~form — explicit splice
              (and (seq? node) (= 'clojure.core/unquote (first node)))
              (add-placeholder! pairs* (second node) :value)

              ;; (reset! sig v) -> (set! $sig v)
              (and (seq? node) (= 'reset! (first node)))
              (let [[_ target v] node
                    ph           (add-placeholder! pairs* target :signal)]
                (list 'set! ph (xf v)))

              ;; (swap! sig f & args) -> (set! $sig (f $sig & args))
              (and (seq? node) (= 'swap! (first node)))
              (let [[_ target f & args] node
                    ph                  (add-placeholder! pairs* target :signal)]
                (list 'set! ph (xf (list* f ph (map xf args)))))

              ;; @local or @signal-var -> $ref / literal splice
              (and (deref-form? node)
                   (or (clj? (second node))
                       (signal-var? (second node))))
              (add-placeholder! pairs* (second node) :value)

              ;; (:kw m) — keyword calls are Clojure, not client-side
              (and (seq? node) (keyword? (first node)))
              (add-placeholder! pairs* node :value)

              ;; (action ...) and friends — a form producing a finished
              ;; Datastar expression.  Splice the whole form as Clojure; it
              ;; contributes its raw JS at runtime.
              (and (seq? node) (datastar-expr-head? (first node)))
              (add-placeholder! pairs* node :value)

              ;; calls: keep the head symbol (or @action head) client-side,
              ;; transform arguments
              (seq? node)
              (let [[head & args] node]
                (if (or (symbol? head) (deref-form? head))
                  (cons head (map xf args))
                  (cons (xf head) (map xf args))))

              (vector? node) (mapv xf node)
              (map? node)    (into {} (map (fn [[k v]] [(xf k) (xf v)])) node)
              (set? node)    (into #{} (map xf) node)

              ;; bare local in value position -> splice
              (clj? node)
              (add-placeholder! pairs* node :value)

              :else node))]
    (xf form)))

;; ---------------------------------------------------------------------------
;; Compilation
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Munged squint core names referenced by compiled expressions."}
  core-vars*
  (atom (sorted-set)))

(defn use-core-vars!
  "Adds names to core-vars*.
   names is a set of munged squint core names.
   Returns js."
  [names js]
  (when-not (every? @core-vars* names)
    (swap! core-vars* into names))
  js)

(defn- compile-form*
  "Returns {:js ... :used-core-vars ...} for a single (boundary-inferred) form."
  [form]
  (let [processed (pre-process form)
        ;; pr-str, not str, because embedded lazy seqs from the boundary walk would
        ;; stringify as "clojure.lang.LazySeq@hash" under str.
        {:keys [body used-core-vars]}
        (squint/compile* (pr-str processed)
                         {:elide-imports true
                          :elide-exports true
                          :top-level     false
                          :context       :expr
                          :core-alias    "hyper_sc"
                          :macros        compiler-macros})]
    {:js             (-> body
                         replace-deref
                         (restore-signal-casing processed)
                         (str/replace #"\n" " ")
                         str/trim
                         (str/replace #";$" ""))
     :used-core-vars used-core-vars}))

(defn compile-form
  "Compile a single (boundary-inferred) form to a Datastar expression
   string.  Public for testing."
  [form]
  (:js (compile-form* form)))

;; ---------------------------------------------------------------------------
;; Runtime splicing
;; ---------------------------------------------------------------------------

(defn splice
  "Encode a spliced runtime value.  :signal mode requires a signal object
   (the target of reset!/swap!); :value mode renders anything implementing
   DatastarExpr (signals -> $ref, actions -> raw @post(...)) as raw JS and
   everything else as a JS literal."
  [v mode]
  (case mode
    :signal (if (signal/any-signal? v)
              (signal/js-ref v)
              (throw (ex-info "reset!/swap! target in an expression must be a signal"
                              {:value v})))
    :value  (if (datastar/datastar-expr? v)
              (datastar/-datastar-js v)
              (signal/clj->js-literal v))))

(defn substitute
  "Replace placeholders in a compiled template with spliced runtime values."
  [template pairs]
  (reduce (fn [s [ph v mode]] (str/replace s ph (splice v mode)))
          template
          pairs))

;; Datastar adds `return` after the last `;`, even one inside a nested function (datastar#1215).
(defn- join-statements
  "Returns jss, the compiled forms, joined with `;`.
   Returns the last form through `__hyper_r` if any form contains a `;`."
  [jss]
  (if (some #(str/includes? % ";") jss)
    (str (str/join (map #(str % "; ") (butlast jss)))
         "var __hyper_r = (" (last jss) "); return __hyper_r;")
    (str/join "; " jss)))

;; ---------------------------------------------------------------------------
;; The macro
;; ---------------------------------------------------------------------------

(defmacro ->expr
  "Compile Clojure forms into a Datastar expression string.

   Signals use atom vocabulary — reset!, swap!, deref — exactly as in
   actions, but compile to instant client-side signal operations.  Locals
   splice automatically; `evt`, `el`, `$signal` symbols, Datastar actions
   ((@post \"/x\")) and JS interop pass through to the client.  Multiple
   forms join with `;`.

   Examples:
     (->expr (swap! open?* not))                ;; \"$_open = !($_open)\"
     (->expr (reset! query* evt.target.value))
     (->expr (when (= evt.key \"Enter\") (@post \"/search\")))
     (->expr (set! $count 0))                   ;; raw Datastar style works too
     (->expr (reset! person-id* (:id person)))  ;; keyword calls are Clojure

   Compilation happens at macro-expansion; spliced values interpolate at
   runtime.  See the hyper.expr namespace docs for boundary rules."
  [& forms]
  (let [env-locals (set (keys &env))
        pairs*     (atom [])
        forms*     (mapv #(infer-boundary % env-locals pairs*) forms)
        compiled   (map compile-form* forms*)
        template   (join-statements (map :js compiled))
        names      (into #{} (mapcat :used-core-vars) compiled)
        pairs      @pairs*
        js         (if (empty? pairs)
                     template
                     `(substitute ~template
                                  [~@(map (fn [[ph expr mode]] [ph expr mode]) pairs)]))]
    (if (seq names)
      `(use-core-vars! ~(set names) ~js)
      js)))
