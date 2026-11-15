(ns hooks.hyper.reactive
  "clj-kondo hook for the hyper.core/reactive macro.

   Validates the call shape — `(reactive deps & body)`: deps that are not a
   map, keyword, string, number, boolean or nil literal, and a non-empty
   body — and rewrites the call into an analyzable `let` so
   clj-kondo's normal linting applies: the dep symbols resolve and count as
   used, and the body is linted in place with the surrounding lexical scope
   (so a nested `reactive` and any closed-over locals analyze normally).

   It also flags effects in the render body (see hooks.hyper.lint)."
  (:require [clj-kondo.hooks-api :as api]
            [hooks.hyper.lint :as lint]))

(defn- finding!
  [node level message type]
  (api/reg-finding!
   (assoc (meta node)
          :level level
          :message message
          :type type)))

(defn reactive
  "Validate + rewrite (reactive {opts}? deps & body)."
  [{:keys [node]}]
  (let [[_ & args]      (:children node)
        opts            (when (and (first args) (api/map-node? (first args)))
                          (first args))
        [deps & body]   (if opts (rest args) args)]
    (when (lint/invalid-deps? deps)
      (finding! (or deps node) :error
                "h/reactive requires deps as its first argument: a vector, or an expression returning one (e.g. [clock*])"
                :hyper.reactive/invalid-deps))
    (when (empty? body)
      (finding! node :error
                "h/reactive requires a body"
                :hyper.reactive/missing-body))
    (lint/check-render-effects! body)
    (let [new-node (api/list-node
                    (list*
                     (api/token-node 'let)
                     (api/vector-node
                      (cond-> [(api/token-node '_deps) (or deps (api/vector-node []))]
                        opts (conj (api/token-node '_opts) opts)))
                     body))]
      {:node new-node})))
