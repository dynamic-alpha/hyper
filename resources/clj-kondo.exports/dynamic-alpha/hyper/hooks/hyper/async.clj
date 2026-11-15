(ns hooks.hyper.async
  "clj-kondo hook for the hyper.core/async macro.

   Does two jobs:

   1. Validates the call shape — `(async deps fetch binding & render-body)`:
      deps that are not a map, keyword, string, number, boolean or nil
      literal, a fetch expression, a binding form, and a non-empty
      render body — registering findings with precise locations.

   2. Rewrites the call into an analyzable `let` so clj-kondo's normal linting
      applies: the deps and fetch expression are analyzed (their
      symbols resolve / count as used), and the binding form scopes over the
      render body so destructured keys (`status`, `result`, `error`) are
      visible and unused-binding warnings fire.

   3. Warns on *impossible* `:status` comparisons — a `case` on the status
      local, or `(= status :kw)`, that tests a keyword h/async never produces
      (the only statuses are :loading, :ready, :error, :reloading)."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]
            [hooks.hyper.lint :as lint]))

(def ^:private valid-statuses #{:loading :ready :error :reloading})

(defn- finding!
  [node level message type]
  (api/reg-finding!
   (assoc (meta node)
          :level level
          :message message
          :type type)))

(defn- walk-nodes
  "Depth-first seq of node and all its descendants."
  [node]
  (tree-seq (fn [n] (seq (:children n))) :children node))

;; ---------------------------------------------------------------------------
;; Impossible-status detection
;; ---------------------------------------------------------------------------

(defn- status-local
  "The symbol bound to the :status key in the binding form, or nil.
   Handles `{:keys [status ...]}` and `{sym :status}`."
  [binding-node]
  (when (and binding-node (api/map-node? binding-node))
    (let [pairs (partition 2 (:children binding-node))]
      (or
       ;; {sym :status}
       (some (fn [[k v]]
               (when (and (api/keyword-node? v) (= :status (api/sexpr v))
                          (api/token-node? k))
                 (let [s (api/sexpr k)] (when (symbol? s) s))))
             pairs)
       ;; {:keys [status ...]}
       (some (fn [[k v]]
               (when (and (api/keyword-node? k) (= :keys (api/sexpr k))
                          (api/vector-node? v))
                 (some (fn [el]
                         (when (and (api/token-node? el) (= 'status (api/sexpr el)))
                           'status))
                       (:children v))))
             pairs)))))

(defn- check-kw!
  [kw-node]
  (let [kw (api/sexpr kw-node)]
    (when (and (keyword? kw) (not (contains? valid-statuses kw)))
      (finding! kw-node :warning
                (str "Impossible :status comparison — h/async never produces "
                     kw " (statuses are "
                     (str/join ", " (sort valid-statuses)) ")")
                :hyper.async/impossible-status))))

;; (render-body purity scanning lives in hooks.hyper.lint)

(defn- check-case-test!
  "A `case` test value is a keyword, or a list of keywords for multiple matches."
  [test-node]
  (cond
    (api/keyword-node? test-node) (check-kw! test-node)
    (api/list-node? test-node)    (doseq [c (:children test-node)
                                          :when (api/keyword-node? c)]
                                    (check-kw! c))))

(defn- status-token?
  [status-sym node]
  (and (api/token-node? node) (= status-sym (api/sexpr node))))

(defn- check-status-comparisons!
  "Scan the render body for comparisons of the status local against keywords
   that h/async never yields."
  [status-sym body-nodes]
  (when status-sym
    (doseq [seg  body-nodes
            n    (walk-nodes seg)
            :when (api/list-node? n)
            :let [children (:children n)
                  head     (first children)]
            :when (api/token-node? head)
            :let [h (api/sexpr head)]]
      (cond
        ;; (case status :ready .. :nope .. default)
        (= 'case h)
        (let [[expr & clauses] (rest children)]
          (when (status-token? status-sym expr)
            ;; partition 2 drops a trailing default (odd element) — good.
            (doseq [[test _] (partition 2 clauses)]
              (check-case-test! test))))

        ;; (= status :nope) / (= :nope status) / (not= ...)
        (#{'= 'not=} h)
        (let [args (rest children)]
          (when (some #(status-token? status-sym %) args)
            (doseq [a args :when (api/keyword-node? a)]
              (check-kw! a))))))))

;; ---------------------------------------------------------------------------
;; Hook entry point
;; ---------------------------------------------------------------------------

(defn async
  "Validate + rewrite (async {opts}? deps fetch binding & render-body)."
  [{:keys [node]}]
  (let [[_ & args]                          (:children node)
        opts                                (when (and (first args) (api/map-node? (first args)))
                                              (first args))
        [deps fetch binding & render-body]  (if opts (rest args) args)]
    ;; --- shape validation ---
    (when (lint/invalid-deps? deps)
      (finding! (or deps node) :error
                "h/async requires deps as its first argument: a vector, or an expression returning one (use [] for fetch-once)"
                :hyper.async/invalid-deps))
    (when (nil? fetch)
      (finding! node :error
                "h/async requires a fetch expression"
                :hyper.async/missing-fetch))
    (when (nil? binding)
      (finding! node :error
                "h/async requires a binding form for the status map (e.g. {:keys [status result error]})"
                :hyper.async/missing-binding))
    (when (empty? render-body)
      (finding! node :error
                "h/async requires a render body"
                :hyper.async/missing-render-body))

    ;; --- impossible-status detection ---
    (check-status-comparisons! (status-local binding) render-body)

    ;; --- render-body purity (the fetch runs off-render, so it is not scanned) ---
    (lint/check-render-effects! render-body)

    ;; --- analyzable rewrite ---
    (let [new-node (api/list-node
                    (list*
                     (api/token-node 'let)
                     ;; The binding comes last so it scopes over the render
                     ;; body only, not over deps or fetch.
                     (api/vector-node
                      (conj (cond-> [(api/token-node '_deps)  (or deps (api/vector-node []))
                                     (api/token-node '_fetch) (api/list-node
                                                               [(api/token-node 'fn)
                                                                (api/vector-node [])
                                                                (or fetch (api/token-node nil))])]
                              opts (conj (api/token-node '_opts) opts))
                            (or binding (api/token-node '_)) (api/token-node nil)))
                     render-body))]
      {:node new-node})))
