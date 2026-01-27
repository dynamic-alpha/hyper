(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - session-cursor and tab-cursor for state management
   - action macro for handling user interactions
   - start! and stop! for server lifecycle"
  (:require [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.server :as server]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

(defn session-cursor
  "Create a cursor to session state at the given path.
   Path can be a keyword or vector.

   Example:
     (session-cursor :user)
     (session-cursor [:user :name])"
  [path]
  (when-not *request*
    (throw (ex-info "session-cursor called outside request context" {})))
  (let [session-id (get *request* :hyper/session-id)]
    (when-not session-id
      (throw (ex-info "No session-id in request" {:request *request*})))
    (state/session-cursor session-id path)))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Path can be a keyword or vector.

   Example:
     (tab-cursor :count)
     (tab-cursor [:todos :list])"
  [path]
  (when-not *request*
    (throw (ex-info "tab-cursor called outside request context" {})))
  (let [tab-id (get *request* :hyper/tab-id)]
    (when-not tab-id
      (throw (ex-info "No tab-id in request" {:request *request*})))
    (state/tab-cursor tab-id path)))

(defmacro action
  "Create an action that executes the given body when triggered.
   Returns a map with :data-on-click attribute for Datastar.

   The action is registered with the current session/tab context
   and can access state via cursors.

   Example:
     [:button (action (swap! (tab-cursor :count) inc))
      \"Increment\"]"
  [& body]
  `(let [session-id# (get *request* :hyper/session-id)
         tab-id# (get *request* :hyper/tab-id)]
     (when-not session-id#
       (throw (ex-info "action macro called outside request context" {})))
     (when-not tab-id#
       (throw (ex-info "No tab-id in request context" {})))

     (let [action-fn# (fn []
                        (binding [*request* {:hyper/session-id session-id#
                                            :hyper/tab-id tab-id#}]
                          ~@body))
           action-id# (actions/register-action! session-id# tab-id# action-fn#)]
       {:data-on-click (str "$$post('/hyper/actions?action-id=" action-id# "')")})))

(defn start!
  "Start the hyper application server.

   Options:
   - :render-fn - Function that takes request and returns hiccup (required)
   - :port - Port to run server on (default: 3000)

   Example:
     (start! {:render-fn my-view
              :port 3000})"
  [{:keys [render-fn port] :or {port 3000}}]
  (when-not render-fn
    (throw (ex-info "render-fn is required" {})))

  (server/start-server! render-fn {:port port}))

(defn stop!
  "Stop the hyper application server."
  []
  (server/stop-server!))
