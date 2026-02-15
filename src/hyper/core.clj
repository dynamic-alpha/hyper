(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - session-cursor and tab-cursor for state management
   - action macro for handling user interactions
   - navigate function for SPA navigation
   - create-handler for building ring handlers"
  (:require [hyper.state :as state]
            [hyper.actions :as actions]
            [hyper.server :as server]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [clojure.string]))

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

(defn session-cursor
  "Create a cursor to session state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (session-cursor :user)
     (session-cursor [:user :name])
     (session-cursor :counter 0)"
  ([path]
   (when-not *request*
     (throw (ex-info "session-cursor called outside request context" {})))
   (let [session-id (get *request* :hyper/session-id)
         app-state* (get *request* :hyper/app-state)]
     (when-not session-id
       (throw (ex-info "No session-id in request" {:request *request*})))
     (when-not app-state*
       (throw (ex-info "No app-state in request" {:request *request*})))
     (state/session-cursor app-state* session-id path)))
  ([path default-value]
   (when-not *request*
     (throw (ex-info "session-cursor called outside request context" {})))
   (let [session-id (get *request* :hyper/session-id)
         app-state* (get *request* :hyper/app-state)]
     (when-not session-id
       (throw (ex-info "No session-id in request" {:request *request*})))
     (when-not app-state*
       (throw (ex-info "No app-state in request" {:request *request*})))
     (state/session-cursor app-state* session-id path default-value))))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (tab-cursor :count)
     (tab-cursor [:todos :list])
     (tab-cursor :count 0)"
  ([path]
   (when-not *request*
     (throw (ex-info "tab-cursor called outside request context" {})))
   (let [tab-id (get *request* :hyper/tab-id)
         app-state* (get *request* :hyper/app-state)]
     (when-not tab-id
       (throw (ex-info "No tab-id in request" {:request *request*})))
     (when-not app-state*
       (throw (ex-info "No app-state in request" {:request *request*})))
     (state/tab-cursor app-state* tab-id path)))
  ([path default-value]
   (when-not *request*
     (throw (ex-info "tab-cursor called outside request context" {})))
   (let [tab-id (get *request* :hyper/tab-id)
         app-state* (get *request* :hyper/app-state)]
     (when-not tab-id
       (throw (ex-info "No tab-id in request" {:request *request*})))
     (when-not app-state*
       (throw (ex-info "No app-state in request" {:request *request*})))
     (state/tab-cursor app-state* tab-id path default-value))))

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
         tab-id# (get *request* :hyper/tab-id)
         app-state*# (get *request* :hyper/app-state)]
     (when-not session-id#
       (throw (ex-info "action macro called outside request context" {})))
     (when-not tab-id#
       (throw (ex-info "No tab-id in request context" {})))
     (when-not app-state*#
       (throw (ex-info "No app-state in request context" {})))

     (let [action-fn# (fn []
                        (binding [*request* {:hyper/session-id session-id#
                                            :hyper/tab-id tab-id#
                                            :hyper/app-state app-state*#}]
                          ~@body))
           action-id# (actions/register-action! app-state*# session-id# tab-id# action-fn#)]
       {:data-on:click (str "@post('/hyper/actions?action-id=" action-id# "')")})))

(defn navigate
  "Create a navigation action using reitit named routes.
   Returns a map with :data-on-click attribute for Datastar.

   route-name: Keyword name of the route
   params: Optional map of path/query parameters

   Example:
     [:a (navigate :home) \"Go Home\"]
     [:a (navigate :user-profile {:id \"123\"}) \"View User\"]"
  ([route-name]
   (navigate route-name nil))
  ([route-name params]
   (let [router (:hyper/router *request*)]
     (when-let [path (:path (reitit/match-by-name router route-name params))]
       {:href path
        :data-on:click__prevent (str "@get('" path "'); window.history.pushState({}, '', '" path "')")}))))

(defn create-handler
  "Create a Ring handler for a hyper application.

   routes: Vector of reitit routes (using route names for navigation)
   app-state*: Optional atom for application state (creates new one if not provided)

   Example:
     (def routes
       [[\"/\" {:name :home
               :get (fn [req] [:div [:h1 \"Home\"]])}]
        [\"/about\" {:name :about
                    :get (fn [req] [:div [:h1 \"About\"]])}]])

     (def handler (create-handler routes))
     (def server (start! handler {:port 3000}))"
  ([routes]
   (create-handler routes (atom (state/init-state))))
  ([routes app-state*]
   (server/create-handler routes app-state* #'*request*)))

(defn start!
  "Start the hyper application server.

   handler: Ring handler created with create-handler
   options:
   - :port - Port to run server on (default: 3000)

   Returns server instance. Call (stop! server) to stop.

   Example:
     (def router ...)
     (def handler (create-handler router))
     (def server (start! handler {:port 3000}))
     ;; Later...
     (stop! server)"
  [handler {:keys [port] :or {port 3000}}]
  (server/start! handler {:port port}))

(defn stop!
  "Stop the hyper application server.

   server: Server instance returned from start!"
  [server]
  (server/stop! server))
