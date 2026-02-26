(ns hooks.hyper.core
  "clj-kondo hooks for hyper.core macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn action
  "Introduce $value, $checked, $key, and $form-data as implicit bindings
   so that clj-kondo does not report unresolved-symbol warnings inside
   the action macro body."
  [{:keys [node]}]
  (let [body     (rest (:children node))
        new-node (api/list-node
                   (list*
                     (api/token-node 'let)
                     (api/vector-node
                       [(api/token-node '$value)     (api/token-node nil)
                        (api/token-node '$checked)   (api/token-node nil)
                        (api/token-node '$key)       (api/token-node nil)
                        (api/token-node '$form-data) (api/token-node nil)])
                     body))]
    {:node new-node}))
