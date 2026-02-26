(ns hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [taoensso.telemere :as t]))

(defn register-action!
  "Register an action function and return its ID.
   Stores in app-state under [:actions action-id].

   When action-id is provided it is used as-is (deterministic actions).
   When omitted a random UUID is generated (legacy / dynamic actions)."
  ([app-state* session-id tab-id action-fn]
   (register-action! app-state* session-id tab-id action-fn
                     (str "action-" (java.util.UUID/randomUUID))))
  ([app-state* session-id tab-id action-fn action-id]
   (swap! app-state* (fn [state]
                       (-> state
                           (assoc-in [:actions action-id]
                                     {:fn         action-fn
                                      :session-id session-id
                                      :tab-id     tab-id})
                           (update-in [:actions-by-tab tab-id]
                                      (fnil conj #{}) action-id))))
   action-id))

(defn execute-action!
  "Execute an action by ID with error handling.
   When client-params are provided they are passed to the action fn."
  ([app-state* action-id]
   (execute-action! app-state* action-id nil))
  ([app-state* action-id client-params]
   (if-let [action-data (get-in @app-state* [:actions action-id])]
     (let [{:keys [fn]} action-data]
       (t/catch->error! :hyper.error/execute-action
                        (fn client-params))
       true)
     (do
       (t/log! {:level :warn
                :id    :hyper.error/action-not-found
                :data  {:hyper/action-id action-id}
                :msg   "Action not found"})
       (throw (ex-info "Action not found" {:hyper/action-id action-id}))))))

(defn cleanup-tab-actions!
  "Remove all actions for a tab."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (get-in state [:actions-by-tab tab-id])]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)))))
  nil)
