(ns hyper.actions
  "Action system for handling user interactions.

   Actions are anonymous functions registered with unique IDs.
   They are invoked via POST requests from the client.")

;; Action registry: {session-id {action-id {:fn f :session-id sid :tab-id tid}}}
(defonce actions (atom {}))

(defn generate-action-id
  "Generate a unique action ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn register-action!
  "Register an action function with session and tab context.
   Returns the action-id."
  [session-id tab-id action-fn]
  (let [action-id (generate-action-id)
        action-data {:fn action-fn
                     :session-id session-id
                     :tab-id tab-id}]
    (swap! actions assoc-in [session-id action-id] action-data)
    action-id))

(defn get-action
  "Get an action by session-id and action-id."
  [session-id action-id]
  (get-in @actions [session-id action-id]))

(defn execute-action!
  "Execute an action by looking it up and invoking it.
   Returns the result of the action function."
  [session-id action-id]
  (if-let [action-data (get-action session-id action-id)]
    (let [{:keys [fn]} action-data]
      ;; The action function will be invoked with *request* bound
      ;; in the calling context
      (fn))
    (throw (ex-info "Action not found"
                    {:session-id session-id
                     :action-id action-id}))))

(defn cleanup-session-actions!
  "Remove all actions for a session."
  [session-id]
  (swap! actions dissoc session-id)
  nil)

(defn get-session-actions
  "Get all actions for a session (for debugging/inspection)."
  [session-id]
  (get @actions session-id))

(defn action-count
  "Get total number of registered actions (for debugging)."
  []
  (reduce + (map (comp count val) @actions)))
