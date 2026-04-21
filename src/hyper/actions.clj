(ns hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [clojure.set]
            [compact-uuids.core :as uuid]
            [hyper.context :as context]
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
                     (str "act_" (uuid/str (java.util.UUID/randomUUID)))
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
   (when-let [acc context/*registered-action-ids*]
     (swap! acc conj action-id))
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

(defn sweep-stale-tab-actions!
  "Remove actions for a tab that were not re-registered during the last
   render cycle.  `live-action-ids` is the set of IDs collected by
   `*registered-action-ids*` during render.  Actions in that set are
   kept; any other action belonging to this tab is removed.

   This replaces the old cleanup-before-render pattern with a
   cleanup-after-render approach that eliminates the window where
   actions are missing."
  [app-state* tab-id live-action-ids]
  (swap! app-state* (fn [state]
                      (let [all-tab-ids (get-in state [:actions-by-tab tab-id] #{})
                            stale-ids   (clojure.set/difference all-tab-ids live-action-ids)]
                        (if (seq stale-ids)
                          (-> state
                              (update :actions #(apply dissoc % stale-ids))
                              (assoc-in [:actions-by-tab tab-id] live-action-ids))
                          (assoc-in state [:actions-by-tab tab-id] live-action-ids)))))
  nil)

(defn cleanup-tab-actions!
  "Remove all actions for a tab.  Used during tab disconnect."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (get-in state [:actions-by-tab tab-id])]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)))))
  nil)
