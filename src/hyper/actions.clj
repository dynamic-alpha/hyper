(ns hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [compact-uuids.core :as uuid]
            [taoensso.telemere :as t]))

(defn register-action!
  "Register an action function and return its ID.
   Stores in app-state under [:actions action-id].

   When action-id is provided it is used as-is (deterministic actions).
   When omitted a random UUID is generated (legacy / dynamic actions).

   opts may include:
   - :as  — a human-readable name for the action, useful for testing"
  ([app-state* session-id tab-id action-fn]
   (register-action! app-state* session-id tab-id action-fn
                     (str "action-" (uuid/str (java.util.UUID/randomUUID)))
                     nil))
  ([app-state* session-id tab-id action-fn action-id]
   (register-action! app-state* session-id tab-id action-fn action-id nil))
  ([app-state* session-id tab-id action-fn action-id opts]
   (swap! app-state* (fn [state]
                       (-> state
                           (assoc-in [:actions action-id]
                                     (cond-> {:fn         action-fn
                                              :session-id session-id
                                              :tab-id     tab-id}
                                       (:as opts) (assoc :as (:as opts))))
                           (update-in [:actions-by-tab tab-id]
                                      (fnil conj #{}) action-id))))
   action-id))

(defn execute-action!
  "Execute an action by ID with error handling.
   When client-params are provided they are passed to the action fn."
  ([app-state* tab-id action-id]
   (execute-action! app-state* tab-id  action-id nil))
  ([app-state* tab-id action-id client-params]
   (let [tab-read-lock (.readLock (get-in @app-state* [:tabs tab-id :renderer :rw-lock]))]
     (.lock tab-read-lock)
     (try
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
           (throw (ex-info "Action not found" {:hyper/action-id  action-id
                                               :hyper/action-ids (keys (get-in @app-state* [:actions]))}))))
       (finally
         (.unlock tab-read-lock))))))

(defn cleanup-tab-actions!
  "Remove all actions for a tab."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (get-in state [:actions-by-tab tab-id])]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)))))
  nil)
