(ns hyper.ring.middleware
  "Ring middleware that puts hyper's context on a request."
  (:require [hyper.state :as state]
            [ring.middleware.cookies :as cookies]
            [taoensso.telemere :as t]))

(defn wrap-hyper
  "Ring middleware that adds hyper's context to the request:

   - `:hyper/session-id` — the session cookie's id when it names a session
     the server holds, and a new id otherwise
   - `:hyper/tab-id`     — the `tab-id` query or form param, or a new id
   - `:hyper/app-state`  — `app-state*`

   It includes Ring's `wrap-cookies`, so `:cookies` is parsed on the request
   and a response's `:cookies` are sent as `Set-Cookie` headers.

   `create-handler` uses it for every request.  A route outside
   `create-handler` wraps itself in it, with the same atom passed to
   `create-handler` as `:app-state`, to use `hyper.ring`:

     (def app
       (ring/routes
         (-> auth-routes (h.ring.middleware/wrap-hyper app*))
         (h/create-handler routes :app-state app*)))"
  [handler app-state*]
  (cookies/wrap-cookies
    (fn [req]
      (let [cookie-name (:name (:session-cookie @app-state* state/default-session-cookie-opts))
            cookie-id   (get-in req [:cookies cookie-name :value])
            session-id  (if (contains? (:sessions @app-state*) cookie-id)
                          cookie-id
                          (state/generate-session-id))
            tab-id      (or (get-in req [:query-params "tab-id"])
                            (get-in req [:params :tab-id])
                            (state/generate-tab-id))
            req         (assoc req
                               :hyper/session-id session-id
                               :hyper/tab-id tab-id
                               :hyper/app-state app-state*)]
        ;; Add hyper context to telemere for all downstream logging
        (t/with-ctx+ {:hyper/session-id session-id
                      :hyper/tab-id     tab-id}
          (handler req))))))
