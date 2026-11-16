(ns hyper.ring
  "Hyper from plain Ring code: sign in and out from a route, and read a
   request's hyper session and endpoint from middleware.

   Every function takes a request that has been through hyper's handler, or
   through `hyper.ring.middleware/wrap-hyper`, and throws for any other."
  (:require [hyper.server :as server]))

(defn- app-state
  "The app-state atom `req` carries; throws when it carries none."
  [req fn-name]
  (or (:hyper/app-state req)
      (throw (ex-info (str "hyper.ring/" fn-name " needs a request that has been through hyper: "
                           "send it through hyper's handler, or wrap the route in "
                           "(hyper.ring.middleware/wrap-hyper handler app-state*).")
                      {:uri (:uri req)}))))

(defn- held-session-id
  "The session id of `req` when `app-state*` holds that session, else nil."
  [app-state* req]
  (let [id (:hyper/session-id req)]
    (when (contains? (:sessions @app-state*) id)
      id)))

(defn session-id
  "The id of the request's session when the server holds that session, else
   nil.

   Example:
     (defn wrap-log-session [handler]
       (fn [req]
         (log/info \"session\" (h.ring/session-id req))
         (handler req)))"
  [req]
  (held-session-id (app-state req "session-id") req))

(defn endpoint
  "The hyper endpoint the request is for: `:action`, `:upload`, `:navigate`
   or `:events`, or nil for page and app routes.

   Example:
     ;; Answer a refused action with a 401 rather than a sign-in page
     (defn wrap-require-sign-in [handler]
       (fn [req]
         (cond
           (signed-in? req)       (handler req)
           (h.ring/endpoint req)  {:status 401}
           :else                  (ring.util.response/redirect \"/sign-in\"))))"
  [req]
  (let [app-state* (app-state req "endpoint")]
    (#{:action :upload :navigate :events}
     (server/endpoint (:base-path @app-state*) (:uri req)))))

(defn rotate-session!
  "`response` with the request's session given a new id, keeping its data,
   and the session cookie set to the new id.  Every tab of the session
   reloads once the response has been written, so in the person's own
   browser it comes back in the session under the new id.  The old id names
   no session afterwards.  `response` as it is when the server holds no
   session for the request.

   Example:
     ;; OAuth callback
     (defn oauth-callback [req]
       (let [user (exchange-code! (get-in req [:params :code]))]
         (-> (ring.util.response/redirect \"/\")
             (set-auth-cookie user)
             (h.ring/rotate-session! req))))"
  [response req]
  (let [app-state* (app-state req "rotate-session!")]
    (if-let [{:keys [new-id reload-others!]}
             (when-let [id (held-session-id app-state* req)]
               (server/rotate-session! app-state* id nil))]
      ;; The response goes first, so the browser's tabs reload with the new
      ;; cookie.
      (-> response
          (server/with-session-cookie-change app-state* req new-id)
          (server/then-run reload-others!))
      response)))

(defn end-session!
  "`response` with the request's session ended: every open tab of the
   session reloads into a fresh session, the session's data is removed and
   the session cookie is deleted.  `response` as it is when the server holds
   no session for the request.

   Example:
     ;; Sign out from a form post
     (defn sign-out [req]
       (-> (ring.util.response/redirect \"/\")
           (clear-auth-cookie)
           (h.ring/end-session! req)))"
  [response req]
  (let [app-state* (app-state req "end-session!")]
    (if (when-let [id (held-session-id app-state* req)]
          (server/end-session! app-state* id))
      (server/with-session-cookie-change response app-state* req nil)
      response)))
