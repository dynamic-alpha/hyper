(ns hyper.effects
  "Side-effects for hyper actions.

   Provides escape hatches for actions that need to do more than mutate
   cursors/signals.  Most action logic should use cursors (the UI is a
   pure function of state), but some operations genuinely require
   side-effects that can't be expressed as state:

   - `navigate!`       — client-side route change (pushState) from an action
   - `set-cookie!`     — set an HTTP cookie on the action response
   - `delete-cookie!`  — remove an HTTP cookie
   - `execute-script!` — run arbitrary JS on the client via SSE

   These are intentionally few.  If you're reaching for execute-script!
   to update UI, consider whether a cursor would be more appropriate.

   Effects are accumulated during action execution in a dynamic var and
   processed by the action handler after the action completes.  Cookies
   are applied to the HTTP response; scripts are sent via SSE.

   Example:
     (require '[hyper.effects :as effects])

     ;; Navigate after saving
     (h/action
       (save-post! data)
       (effects/navigate! :post-detail {:id 123}))

     ;; Set a cookie
     (h/action
       (when (authenticate! user pass)
         (effects/set-cookie! \"auth\" jwt {:http-only true :max-age 86400})
         (effects/navigate! :dashboard)))

     ;; Run client-side JS (use sparingly)
     (h/action
       (effects/execute-script! \"document.getElementById('search').focus()\"))"
  (:require [hyper.context :as context]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Pending effects accumulator
;; ---------------------------------------------------------------------------

(def ^:no-doc ^:dynamic *pending*
  "Accumulator for effects emitted during action execution.

   Bound to (atom {:cookies {} :scripts []}) by the action handler.
   nil outside action execution."
  nil)

(defn- require-pending!
  "Ensure we're inside an action context with effects support.
   Returns the pending atom."
  [caller]
  (or *pending*
      (throw (ex-info (str caller " called outside action context. "
                           "Effects can only be emitted during action execution.")
                      {:caller caller}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn navigate!
  "Navigate to a named route from within an action.

   Performs the server-side route transition (updates render-fn and route
   state, which triggers a re-render via SSE) and queues a pushState
   call to update the browser URL bar.

   route-name: Keyword name of the route
   params: Optional map of path parameters
   query-params: Optional map of query parameters

   Example:
     (h/action
       (let [post (save-post! data)]
         (effects/navigate! :post-detail {:id (:id post)})))

     (h/action
       (effects/navigate! :search {} {:q \"clojure\"}))"
  ([route-name]
   (navigate! route-name nil nil))
  ([route-name params]
   (navigate! route-name params nil))
  ([route-name params query-params]
   (let [pending*   (require-pending! "navigate!")
         req        context/*request*
         app-state* (:hyper/app-state req)
         tab-id     (:hyper/tab-id req)
         router     (or (:hyper/router req) (get @app-state* :router))]
     (when-not router
       (throw (ex-info "No router available for navigate!" {:route-name route-name})))
     (when-let [route (routes/tab-route router route-name params query-params)]
       (let [href        (routes/route-url router route)
             route-index (routes/live-route-index app-state*)
             render-fn   (routes/find-render-fn route-index route-name)
             title-spec  (routes/find-route-title route-index route-name)
             title       (routes/resolve-title title-spec req)]
         ;; Server-side state transition
         (when render-fn
           (render/register-render-fn! app-state* tab-id render-fn))
         (state/set-tab-route! app-state* tab-id route)
         ;; Queue client-side URL update
         (let [escaped-title (or (utils/escape-js-string title) "")
               escaped-href  (utils/escape-js-string href)]
           (swap! pending* update :scripts conj
                  (str "window.history.pushState("
                       "{title:'" escaped-title "'},"
                       "'',"
                       "'" escaped-href "');"
                       (when title
                         (str "document.title='" escaped-title "'"))))))))))

(defn set-cookie!
  "Set an HTTP cookie from within an action.

   The cookie will be added to the action's HTTP response via Set-Cookie
   headers.  This is the only way to set cookies from hyper — the SSE
   channel cannot carry Set-Cookie headers.

   name: Cookie name string
   value: Cookie value string
   opts: Optional map of cookie options:
     :path      — Cookie path (default \"/\")
     :max-age   — Max age in seconds
     :http-only — Boolean, prevent JS access (default false)
     :secure    — Boolean, HTTPS only (default false)
     :same-site — :strict, :lax, or :none

   Example:
     (h/action
       (when-let [token (authenticate! user pass)]
         (effects/set-cookie! \"auth\" token {:http-only true
                                             :secure true
                                             :max-age (* 60 60 24 7)})))"
  ([name value]
   (set-cookie! name value {}))
  ([name value opts]
   (let [pending* (require-pending! "set-cookie!")]
     (swap! pending* assoc-in [:cookies name]
            (merge {:value value :path "/"} opts))
     nil)))

(defn delete-cookie!
  "Delete an HTTP cookie from within an action.

   Sets the cookie with an empty value and max-age 0, which instructs
   the browser to remove it.

   name: Cookie name string
   opts: Optional map — typically :path must match the original cookie's path

   Example:
     (h/action
       (effects/delete-cookie! \"auth\")
       (effects/navigate! :login))"
  ([name]
   (delete-cookie! name {}))
  ([name opts]
   (let [pending* (require-pending! "delete-cookie!")]
     (swap! pending* assoc-in [:cookies name]
            (merge {:value "" :max-age 0 :path "/"} opts))
     nil)))

(defn execute-script!
  "Execute JavaScript on the client from within an action.

   The script is sent via SSE as a Datastar execute-script event.
   Use sparingly — most UI updates are better expressed as cursor
   mutations that the render function responds to.

   Legitimate uses: focusing an element, scrolling to a position,
   triggering a file download, clipboard operations.

   js: JavaScript string to execute in the browser

   Example:
     (h/action
       (effects/execute-script! \"document.getElementById('search').focus()\"))

     (h/action
       (effects/execute-script! \"window.scrollTo(0, 0)\"))"
  [js]
  (let [pending* (require-pending! "execute-script!")]
    (swap! pending* update :scripts conj js)
    nil))

;; ---------------------------------------------------------------------------
;; Internal — used by action-handler in server.clj
;; ---------------------------------------------------------------------------

(defn ^:no-doc init-pending
  "Create a fresh pending effects atom.  Called by action-handler before
   binding *pending*."
  []
  (atom {:cookies {} :scripts []}))

(defn ^:no-doc collect-pending!
  "Drain and return the accumulated effects.  Returns a map with
   :cookies and :scripts.  Called by action-handler after action execution."
  []
  (when *pending*
    @*pending*))

(defn ^:no-doc apply-cookies-to-response
  "Merge pending cookies into an HTTP response map.
   Returns the response with :cookies added/merged."
  [response pending-effects]
  (let [cookies (:cookies pending-effects)]
    (if (seq cookies)
      (update response :cookies merge cookies)
      response)))

(defn ^:no-doc format-execute-script-event
  "Format a JavaScript string as a Datastar patch-elements SSE event
   that appends a self-removing <script> tag to the body.

   Datastar doesn't have a dedicated execute-script event type.  Instead,
   script execution is done by patching a <script> element into the DOM
   using the 'append' mode and 'body' selector.  The script removes itself
   after execution via a data-effect attribute."
  [js]
  (str "event: datastar-patch-elements\n"
       "data: mode append\n"
       "data: selector body\n"
       "data: elements <script data-effect=\"el.remove()\">" js "</script>\n\n"))

(defn ^:no-doc format-pending-scripts
  "Format all pending scripts as SSE events.  Returns a single string
   of concatenated SSE events, or nil if there are no scripts."
  [pending-effects]
  (let [scripts (:scripts pending-effects)]
    (when (seq scripts)
      (apply str (map format-execute-script-event scripts)))))
