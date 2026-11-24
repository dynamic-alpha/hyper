(ns hooks.hyper.component
  "clj-kondo hooks for hyper.component macros.

   The defc hook does two jobs (inspired by shadow-grove's defc linter):

   1. Validates component structure at lint time, registering findings with
      precise locations: segment names, event handler shape, exactly one
      render segment, and the attrs binding form.

   2. Rewrites the component into an analyzable defn so clj-kondo's normal
      linting applies: attrs destructuring scopes over segments, `emit`
      resolves inside event bodies, and unused bindings are reported."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(def ^:private valid-segment-names
  #{'event 'render 'mount 'update 'unmount})

(def ^:private lifecycle-arities
  {'mount #{1} 'update #{1 2} 'unmount #{1}})

(defn- segment-name
  "The leading symbol of a segment list node, or nil."
  [node]
  (when (api/list-node? node)
    (let [head (first (:children node))]
      (when (api/token-node? head)
        (api/sexpr head)))))

(defn- finding!
  [node level message type]
  (api/reg-finding!
    (assoc (meta node)
           :level level
           :message message
           :type type)))

;; ---------------------------------------------------------------------------
;; Segment rewriting
;; ---------------------------------------------------------------------------

(defn- rewrite-event
  "(event ::name [e] body...) -> (fn [e] body...)
   Validates the event name is a keyword and the params vector has exactly
   one argument (the DOM event)."
  [node]
  (let [[_ ev-name params & body] (:children node)]
    (when-not (and ev-name (api/keyword-node? ev-name))
      (finding! (or ev-name node) :error
                "defc event name must be a keyword (e.g. ::clicked)"
                :hyper.component/invalid-event))
    (if (and params (api/vector-node? params))
      (do
        (when-not (= 1 (count (:children params)))
          (finding! params :error
                    "defc event handler takes exactly one argument (the DOM event)"
                    :hyper.component/invalid-event-arity))
        (api/list-node
          (list* (api/token-node 'fn) params body)))
      (do
        (finding! (or params node) :error
                  "defc event handler requires an argument vector"
                  :hyper.component/invalid-event)
        (api/token-node nil)))))

(defn- rewrite-render
  "(render body...) -> (do body...)"
  [node]
  (api/list-node
    (list* (api/token-node 'do) (rest (:children node)))))

(defn- rewrite-lifecycle
  "(mount [root] body...)         -> (fn [root] body...)
   (update [root old?] body...)   -> (fn [root old?] body...)
   (unmount [root] body...)       -> (fn [root] body...)
   Validates the binding vector arity per segment."
  [node]
  (let [[head args & body] (:children node)
        seg                (api/sexpr head)
        allowed            (lifecycle-arities seg)]
    (if (and args (api/vector-node? args))
      (do
        (when-not (contains? allowed (count (:children args)))
          (finding! args :error
                    (str "defc " seg " binding vector must be "
                         (if (= 'update seg) "[root] or [root old-attrs]" "[root]"))
                    :hyper.component/invalid-lifecycle-arity))
        (api/list-node
          (list* (api/token-node 'fn) args body)))
      (do
        (finding! (or args node) :error
                  (str "defc " seg " requires a binding vector")
                  :hyper.component/invalid-lifecycle)
        (api/token-node nil)))))

(defn- rewrite-segment
  [node]
  (case (segment-name node)
    event   (rewrite-event node)
    render  (rewrite-render node)
    (mount update unmount) (rewrite-lifecycle node)
    ;; Invalid segment: report it and elide it from analysis output so the
    ;; unknown head symbol doesn't cascade into unresolved-symbol noise.
    (do
      (finding! node :error
                (str "Invalid defc segment: "
                     (pr-str (or (segment-name node) (api/sexpr node)))
                     ". Expected one of: " (str/join ", " valid-segment-names))
                :hyper.component/invalid-segment)
      (api/token-node nil))))

;; ---------------------------------------------------------------------------
;; Event cross-validation
;; ---------------------------------------------------------------------------
;; ::handler keywords referenced in :on maps must have matching (event ...)
;; declarations, and declared events should be referenced somewhere.

(defn- walk-nodes
  "Depth-first seq of node and all its descendants."
  [node]
  (tree-seq (fn [n] (seq (:children n))) :children node))

(defn- auto-kw-name
  "When node is an auto-resolved keyword literal (::foo), return \"foo\".
   Comparison is by literal text — the convention is to use the same ::kw
   form in (event ...) and in :on references."
  [node]
  (let [s (str node)]
    (when (str/starts-with? s "::")
      (subs s 2))))

(defn- declared-event-nodes
  "Seq of [name-string event-kw-node] for each (event ::kw ...) segment."
  [segments]
  (for [seg segments
        :when (= 'event (segment-name seg))
        :let [kw-node (second (:children seg))
              nm      (some-> kw-node auto-kw-name)]
        :when nm]
    [nm kw-node]))

(defn- on-map-event-refs
  "Seq of [name-string kw-node] for every ::kw used as a handler value
   inside an {:on {...}} map anywhere in the given segments."
  [segments]
  (for [seg   segments
        node  (walk-nodes seg)
        :when (api/map-node? node)
        :let  [children (:children node)
               pairs    (partition 2 children)]
        [k v] pairs
        :when (and (api/keyword-node? k) (= :on (api/sexpr k))
                   (api/map-node? v))
        [_ev handler] (partition 2 (:children v))
        :let  [nm (auto-kw-name handler)]
        :when nm]
    [nm handler]))

(defn- validate-events!
  [segments]
  (let [declared (declared-event-nodes segments)
        refs     (on-map-event-refs segments)
        decl-set (set (map first declared))
        ref-set  (set (map first refs))]
    (doseq [[nm node] refs
            :when (not (contains? decl-set nm))]
      (finding! node :error
                (str "No (event ::" nm " ...) declaration for this handler reference")
                :hyper.component/undeclared-event))
    (doseq [[nm node] declared
            :when (not (contains? ref-set nm))]
      (finding! node :warning
                (str "Event ::" nm " is declared but never referenced in render")
                :hyper.component/unused-event))))

;; ---------------------------------------------------------------------------
;; Require-alias validation
;; ---------------------------------------------------------------------------
;; Alias-qualified symbols in segments (d3/line) must refer to a declared
;; :require alias.  Lowercase namespaces only — capitalized namespaces
;; (Math/round, JSON/parse) are JS global interop and pass through, as does
;; js/*.

(defn- declared-aliases
  "Set of alias name strings from the defc opts map's :require entries."
  [opts-node]
  (when (and opts-node (api/map-node? opts-node))
    (let [m (try (api/sexpr opts-node) (catch Exception _ nil))]
      (when (map? m)
        (->> (:require m)
             (keep (fn [entry]
                     (when (and (vector? entry) (= 3 (count entry)))
                       (str (nth entry 2)))))
             set)))))

(defn- validate-aliases!
  [opts-node segments]
  (let [aliases (or (declared-aliases opts-node) #{})]
    (doseq [seg   segments
            node  (walk-nodes seg)
            :when (api/token-node? node)
            :let  [v (try (api/sexpr node) (catch Exception _ nil))]
            :when (and (symbol? v) (namespace v))
            :let  [ns-str (namespace v)]
            :when (and (re-matches #"[a-z][a-zA-Z0-9_$-]*" ns-str)
                       (not= "js" ns-str)
                       (not (contains? aliases ns-str)))]
      (finding! node :error
                (str ns-str "/" (name v) " — \"" ns-str
                     "\" is not a :require alias of this component"
                     (when (seq aliases)
                       (str " (declared: " (str/join ", " (sort aliases)) ")")))
                :hyper.component/unresolved-alias))))

;; ---------------------------------------------------------------------------
;; Component-level validation
;; ---------------------------------------------------------------------------

(defn- validate-component!
  [node binding-vec segments]
  (let [names        (keep segment-name segments)
        seg-counts   (frequencies names)]
    (when (and (zero? (get seg-counts 'render 0))
               (zero? (get seg-counts 'mount 0)))
      (finding! node :error
                "defc requires a (render ...) or (mount ...) segment"
                :hyper.component/missing-render))
    (doseq [seg ['render 'mount 'update 'unmount]
            :when (> (get seg-counts seg 0) 1)]
      (finding! node :error
                (str "defc allows only one (" seg " ...) segment")
                :hyper.component/multiple-segment))
    (when-not (and binding-vec
                   (api/vector-node? binding-vec)
                   (let [fst (first (:children binding-vec))]
                     (and fst
                          (api/map-node? fst)
                          (some #(and (api/keyword-node? %)
                                      (= :keys (api/sexpr %)))
                                (:children fst)))))
      (finding! (or binding-vec node) :error
                "defc requires a [{:keys [...]}] binding vector so attribute names are statically visible"
                :hyper.component/invalid-attrs))))

;; ---------------------------------------------------------------------------
;; Hook entry point
;; ---------------------------------------------------------------------------

(defn defc
  "Rewrite (defc name docstring? [{:keys [...]}] segments...) into

     (defn name docstring? [{:keys [...]} & children]
       (let [emit (fn [& _] nil)]
         <event segments as fns>
         <render segment as do>))

   and register findings for structural problems."
  [{:keys [node]}]
  (let [[_ cname & body]         (:children node)
        ;; Docstring before the binding vector is dropped from analysis
        ;; output; the opts map is captured for :require alias validation.
        prefix                   (take-while #(not (api/vector-node? %)) body)
        opts-node                (some #(when (api/map-node? %) %) prefix)
        [binding-vec & segments] (drop-while #(not (api/vector-node? %)) body)
        _                        (validate-component! node binding-vec segments)
        _                        (validate-events! segments)
        _                        (validate-aliases! opts-node segments)
        ;; Lint segments as CLJS, they run in the browser.
        let-node                 (vary-meta
                                  (api/list-node
                                   (list*
                                     (api/token-node 'let)
                                     (api/vector-node
                                       [(api/token-node 'emit)
                                        (api/list-node
                                          [(api/token-node 'fn)
                                           (api/vector-node [(api/token-node '&)
                                                             (api/token-node '_)])
                                           (api/token-node nil)])
                                        ;; ctx: stable per-instance JS object
                                        ;; in scope in every segment.
                                        (api/token-node 'ctx)
                                        (api/token-node nil)])
                                     ;; Reference all three once so components
                                     ;; that don't use them avoid unused-binding
                                     ;; noise.
                                     (list*
                                       (api/token-node 'emit)
                                       (api/token-node 'ctx)
                                       (api/token-node 'children)
                                       (map rewrite-segment segments))))
                                  assoc :clj-kondo/lang :cljs)
        arglist                  (api/vector-node
                                   (concat (:children (or binding-vec
                                                          (api/vector-node [])))
                                           [(api/token-node '&)
                                            (api/token-node 'children)]))
        new-node                 (api/list-node
                                   [(api/token-node 'defn)
                                    cname
                                    arglist
                                    let-node])]
    {:node new-node}))
