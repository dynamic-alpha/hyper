(ns hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions.")

(defn register-action!
  "Register an action function and return its ID.
   Stores in app-state under [:actions action-id]."
  [app-state* session-id tab-id action-fn]
  (let [action-id (str "action-" (java.util.UUID/randomUUID))]
    (swap! app-state* assoc-in [:actions action-id]
           {:fn action-fn
            :session-id session-id
            :tab-id tab-id})
    action-id))

(defn execute-action!
  "Execute an action by ID."
  [app-state* action-id]
  (if-let [action-data (get-in @app-state* [:actions action-id])]
    (let [{:keys [fn]} action-data]
      (fn)
      true)
    (throw (ex-info "Action not found" {:action-id action-id}))))

(defn cleanup-tab-actions!
  "Remove all actions for a tab."
  [app-state* tab-id]
  (swap! app-state* update :actions
         (fn [actions]
           (into {}
                 (remove (fn [[_k v]] (= (:tab-id v) tab-id))
                         actions))))
  nil)
