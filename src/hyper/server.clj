(ns hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [cheshire.core :as json]
            [clojure.string]
            [compact-uuids.core :as uuid]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.brotli :as br]
            [hyper.context :as context]
            [hyper.effects :as effects]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.render.error :as render.error]
            [hyper.render.queue :as rq]
            [hyper.routes :as routes]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [hyper.watch :as watch]
            [org.httpkit.server :as http-kit]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as malli]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.file :as file]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [taoensso.telemere :as t]))

(defn generate-session-id []
  (str "ses_" (uuid/str (java.util.UUID/randomUUID))))

(defn generate-tab-id []
  (str "tab_" (uuid/str (java.util.UUID/randomUUID))))

;; ---------------------------------------------------------------------------
;; Per-tab renderer thread
;; ---------------------------------------------------------------------------

(def ^:private default-render-throttle-ms
  "Minimum interval between renders for a single tab (~60fps)."
  16)

(defn- send-sse!
  "Compress (when br-stream is non-nil) and send an SSE payload over the channel.
   Returns true when sent, false when the channel is closed."
  [channel br-out br-stream sse-payload]
  (let [payload (if br-stream
                  (br/compress-stream br-out br-stream sse-payload)
                  sse-payload)]
    (boolean (http-kit/send! channel payload false))))

(defn- handle-partial-render
  "Render only the dirty reactive components and send targeted fragments.
   Returns true/nil on success, false when the channel is closed."
  [app-state* tab-id dirty-ids channel br-out br-stream]
  (let [fragments (keep (fn [component-id]
                          (reactive/partial-render app-state* tab-id component-id))
                        dirty-ids)]
    (when (seq fragments)
      (send-sse! channel br-out br-stream
                 (render/format-datastar-fragments fragments)))))

(defn- handle-ring-redirect
  "Convert a 3xx Ring redirect response into a client-side window.location
   redirect over SSE.  Throws for non-redirect Ring responses."
  [render-result tab-id channel br-out br-stream]
  (let [status   (:status render-result)
        location (get-in render-result [:headers "Location"])]
    (if (and (<= 300 status 399) location)
      (let [js (str "window.location.href='" (utils/escape-js-string location) "'")]
        (send-sse! channel br-out br-stream
                   (effects/format-execute-script-event js)))
      (throw (ex-info
               (str "Render middleware returned a Ring response over SSE that cannot "
                    "be delivered to the client. Only 3xx redirects with a Location "
                    "header are supported during SSE re-renders.")
               {:status  status
                :headers (:headers render-result)
                :tab-id  tab-id})))))

(defn- handle-full-render
  "Perform a full page render and send the assembled SSE events (head update,
   body fragment, signal patches).  Sweeps stale actions/reactive components
   and wires up watches for new reactive components.
   Returns true/nil on success, false when the channel is closed."
  [app-state* session-id tab-id sig-patches
   enqueue-partial! channel br-out br-stream]
  (when-let [render-result (render/render-tab app-state* session-id tab-id)]
    (if (:status render-result)
      ;; Ring response passthrough (e.g. redirect from render middleware)
      (handle-ring-redirect render-result tab-id channel br-out br-stream)
      ;; Normal render — assemble and send SSE payload
      (let [{:keys [title head-html body-html url
                    declared-signals registered-action-ids
                    registered-reactive-ids]}              render-result]
        ;; Sweep stale actions + reactive components
        (actions/sweep-stale-tab-actions! app-state* tab-id registered-action-ids)
        (reactive/sweep-stale-components! app-state* tab-id registered-reactive-ids)
        ;; Set up watches for new/changed reactive components
        (reactive/setup-new-component-watches! app-state* tab-id enqueue-partial!)
        (let [head-event   (render/format-head-update title head-html)
              sig-attrs    (signal/format-signal-attrs declared-signals)
              div-attrs    (cond-> {:id "hyper-app"}
                             url       (assoc :data-hyper-url url)
                             sig-attrs (merge sig-attrs))
              wrapped-html (c/html [:div div-attrs (c/raw body-html)])
              body-event   (render/format-datastar-fragment wrapped-html)
              sig-event    (when (seq sig-patches)
                             (signal/format-patch-signals-event sig-patches))
              sse-payload  (str head-event body-event sig-event)]
          (send-sse! channel br-out br-stream sse-payload))))))

(defn- send-pending-scripts!
  "Send drained scripts over SSE.
   Returns true/nil on success, false when the channel is closed."
  [scripts tab-id channel br-out br-stream]
  (try
    (when (seq scripts)
      (send-sse! channel br-out br-stream
                 (apply str (map effects/format-execute-script-event scripts))))
    (catch Throwable e
      (t/error! {:id   :hyper.error/renderer-scripts
                 :msg  "Error while sending pending scripts"
                 :data {:hyper/tab-id tab-id}}
                e)
      nil)))

(defn- -renderer-loop!
  "Virtual-thread render loop for a single tab.

   Owns the http-kit AsyncChannel and (optional) streaming Brotli state,
   guaranteeing single-writer semantics by construction.

   Blocks on the render queue until events are available, drains them into
   a consistent batch, then renders and sends.  The queue replaces the
   previous Semaphore + atoms approach, eliminating all shared mutable state.
   Exits when a :shutdown event is received."
  [app-state* session-id tab-id channel compress?
   render-queue]
  (let [br-out           (when compress? (br/byte-array-out-stream))
        br-stream        (when br-out (br/compress-out-stream br-out :window-size 18))
        headers          (cond-> {"Content-Type"      "text/event-stream"
                                  "Cache-Control"     "no-cache, no-transform"
                                  "X-Accel-Buffering" "no"}
                           compress? (assoc "Content-Encoding" "br"))
        throttle-ms      (long (or (get @app-state* :render-throttle-ms)
                                   default-render-throttle-ms))
        enqueue-partial! #(rq/enqueue-partial! render-queue %)]
    (try
      ;; Send the connected event as the initial SSE response (headers + body).
      (let [connected-msg (render/format-connected-event tab-id)
            sent?         (boolean
                            (http-kit/send! channel
                                            {:headers headers
                                             :body    (if br-stream
                                                        (br/compress-stream br-out br-stream connected-msg)
                                                        connected-msg)}
                                            false))]
        (when sent?
          ;; Main render loop — tracks the last-sent signal snapshot so
          ;; we only emit datastar-patch-signals for actual changes.
          (loop [last-sent-signals nil]
            (let [{:keys [shutdown? full-render? dirty-ids scripts]} (rq/drain! render-queue)]
              (when-not shutdown?
                (let [current-signals (get-in @app-state* [:tabs tab-id :signals])
                      sig-patches     (when (and current-signals
                                                 (not= current-signals last-sent-signals))
                                        (signal/changed-signals last-sent-signals current-signals))
                      sent?           (try
                                        (if (and (seq dirty-ids)
                                                 (not full-render?)
                                                 (not (seq sig-patches)))
                                          (handle-partial-render app-state* tab-id dirty-ids
                                                                 channel br-out br-stream)
                                          (handle-full-render app-state* session-id tab-id sig-patches
                                                              enqueue-partial!
                                                              channel br-out br-stream))
                                        (catch Throwable e
                                          (t/error! {:id   :hyper.error/renderer
                                                     :msg  "Error rendering page"
                                                     :data {:hyper/tab-id tab-id}}
                                                    e)
                                          nil))
                      script-sent?    (send-pending-scripts! scripts tab-id
                                                             channel br-out br-stream)]
                  ;; sent? is true (ok), nil (no render-fn or error), false (channel closed)
                  ;; script-sent? follows the same convention
                  (when-not (or (false? sent?) (false? script-sent?))
                    (Thread/sleep throttle-ms)
                    (recur (or current-signals last-sent-signals)))))))))
      (catch Throwable e
        (t/error! {:id   :hyper.error/renderer
                   :msg  "Error rendering page"
                   :data {:hyper/tab-id tab-id}}
                  e))
      (finally
        (br/close-stream br-stream)
        (when (instance? org.httpkit.server.AsyncChannel channel)
          (t/catch->error! :hyper.error/close-sse-channel
                           (http-kit/close channel)))
        (t/log! {:level :debug
                 :id    :hyper.event/renderer-close
                 :data  {:hyper/tab-id tab-id}
                 :msg   "Tab renderer closed"})))))

(defn- -start-renderer!
  "Start a per-tab renderer on a virtual thread.

   Returns a map with:
   - :trigger-render!  — zero-arg fn; call to signal a full re-render
   - :trigger-partial! — one-arg fn; call with component-id for partial re-render
   - :enqueue-scripts! — one-arg fn; call with seq of JS strings to execute
   - :stop!            — zero-arg fn; enqueues a :shutdown event to stop the renderer
   - :render-queue     — the underlying LinkedBlockingQueue (for testing)"
  [app-state* session-id tab-id channel compress?]
  (let [render-queue     (rq/make-queue)
        trigger-render!  #(rq/enqueue-full-render! render-queue)
        trigger-partial! #(rq/enqueue-partial! render-queue %)
        enqueue-scripts! #(rq/enqueue-scripts! render-queue %)
        stop!            #(rq/enqueue-shutdown! render-queue)
        thread           (-> (Thread/ofVirtual)
                             (.name (str "hyper-renderer-" tab-id))
                             (.start ^Runnable
                               #(-renderer-loop! app-state* session-id tab-id
                                                 channel compress?
                                                 render-queue)))]
    ;; Enqueue the initial full render to kick things off
    (rq/enqueue-full-render! render-queue)
    {:trigger-render!  trigger-render!
     :trigger-partial! trigger-partial!
     :enqueue-scripts! enqueue-scripts!
     :stop!            stop!
     :thread           thread
     :render-queue     render-queue}))

(defn wrap-hyper-context
  "Middleware that adds session-id and tab-id to the request."
  [app-state*]
  (fn [handler]
    (fn [req]
      (let [cookies          (get req :cookies {})
            existing-session (get-in cookies ["hyper-session" :value])
            session-id       (or existing-session (generate-session-id))
            tab-id           (or (get-in req [:query-params "tab-id"])
                                 (get-in req [:params :tab-id])
                                 (generate-tab-id))
            req              (assoc req
                                    :hyper/session-id session-id
                                    :hyper/tab-id tab-id
                                    :hyper/app-state app-state*)]

        ;; Add hyper context to telemere for all downstream logging
        (t/with-ctx+ {:hyper/session-id session-id
                      :hyper/tab-id     tab-id}
          (let [response (handler req)]
            ;; Add session cookie to response if new session
            (if (and response (not existing-session))
              (assoc-in response [:cookies "hyper-session"]
                        {:value     session-id
                         :path      "/"
                         :http-only true
                         :max-age   (* 60 60 24 7)}) ;; 7 days
              response)))))))

(defn default-datastar-script
  "Returns the default Datastar CDN script tag."
  []
  [:script {:type "module"
            :src  "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"}])

(defn cleanup-tab!
  "Clean up all resources for a tab: watchers, reactive components, renderer thread, actions, and state."
  [app-state* tab-id]
  (watch/remove-watchers! app-state* tab-id)
  (watch/remove-external-watches! app-state* tab-id)
  (watch/teardown-route-watches! app-state* tab-id)
  (reactive/teardown-all-components! app-state* tab-id)
  (when-let [stop! (get-in @app-state* [:tabs tab-id :renderer :stop!])]
    (stop!))
  (actions/cleanup-tab-actions! app-state* tab-id)
  (state/cleanup-tab! app-state* tab-id)
  nil)

(defn sse-events-handler
  "Handler for SSE event stream.
   Starts a per-tab renderer thread that owns the channel and optional
   brotli stream, then wires up watchers to trigger re-renders."
  [app-state*]
  (fn [req]
    (let [session-id (:hyper/session-id req)
          tab-id     (:hyper/tab-id req)
          compress?  (br/accepts-br? req)]

      (http-kit/as-channel req
                           {:on-open  (fn [channel]
                                        (state/get-or-create-tab! app-state* session-id tab-id)

                                        ;; Start the renderer — it sends the connected event
                                        ;; and then blocks until triggered.
                                        (let [{:keys [trigger-render!] :as renderer}
                                              (-start-renderer! app-state* session-id tab-id
                                                                channel compress?)]
                                          (swap! app-state* assoc-in [:tabs tab-id :renderer] renderer)

                                          (watch/setup-watchers! app-state* session-id tab-id trigger-render!)
                                          ;; Auto-watch any Var sources (routes, head) so re-defs
                                          ;; trigger re-renders for all connected tabs
                                          (let [{:keys [routes-source head]} @app-state*]
                                            (doseq [source (->>  [routes-source head]
                                                                 (filter var?))]
                                              (watch/watch-source! app-state* tab-id trigger-render! source)))
                                          ;; Set up route-level watches (:watches + Var :get handlers)
                                          (watch/setup-route-watches! app-state* tab-id trigger-render!)
                                          ;; Promote any watches stashed during the initial HTTP render
                                          (watch/promote-pending-watches! app-state* tab-id trigger-render!)))

                            :on-close (fn [_channel _status]
                                        (t/log! {:level :info
                                                 :id    :hyper.event/tab-disconnect
                                                 :data  {:hyper/tab-id tab-id}
                                                 :msg   "Tab disconnected"})
                                        (cleanup-tab! app-state* tab-id))}))))

(defn- parse-client-query-params
  "Extract client params from URL query parameters, JSON-decoding each
   value.  Skips the `action-id` key (which is a hyper routing param,
   not a client param).  Returns a keyword-keyed map, or nil when no
   client params are present.

   Values are JSON-encoded on the client by `hyper.encodeClientParams`, so a
   round-trip through `JSON.stringify` → URL-encode → URL-decode →
   `json/parse-string` preserves booleans, numbers, objects, etc."
  [req]
  (let [qp (get req :query-params {})]
    (when (> (count qp) 1)                 ;; more than just action-id
      (reduce-kv (fn [acc k v]
                   (if (= k "action-id")
                     acc
                     (assoc acc (keyword k)
                            (try (json/parse-string v)
                                 (catch Exception _ v)))))
                 {}
                 qp))))

(defn action-handler
  "Handler for action POST requests.

   All actions use Datastar's `@post()`, which sends all non-underscore
   signals as a JSON body.  Client params ($value, $checked, etc.) are
   passed as URL query parameters.

   - Parses the body as signal values and binds them to `context/*signals*`
   - Extracts client params from URL query parameters (JSON-decoded)
   - Binds `effects/*pending*` to collect side-effects (cookies, scripts)
   - Returns 204 (with any Set-Cookie headers) to prevent Datastar from
     merging the response into signals
   - Queues any pending scripts to the renderer for SSE delivery"
  [app-state*]
  (fn [req]
    (let [action-id (get-in req [:query-params "action-id"])]

      (if-not action-id
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing action-id\"}"}

        (let [req-with-state (assoc req
                                    :hyper/app-state app-state*
                                    :hyper/router (get @app-state* :router))
              raw-body       (try
                               (when-let [body (:body req)]
                                 (let [s (if (string? body) body (slurp body))]
                                   (when-not (clojure.string/blank? s) s)))
                               (catch Exception _ nil))
              signals        (when raw-body
                               (signal/parse-signals raw-body))
              client-params  (parse-client-query-params req)
              ;; Sync client signal values into server state so the
              ;; render loop can detect changes (e.g. when a user types
              ;; into a data-bind input, the server needs to know the
              ;; latest value for changed-signals diffing).
              action-data    (get-in @app-state* [:actions action-id])
              tab-id         (:tab-id action-data)]
          (when (and signals tab-id)
            (swap! app-state* update-in [:tabs tab-id :signals]
                   (fn [server-sigs]
                     (merge server-sigs signals))))
          (when-let [env (:hyper/env req)]
            (when tab-id
              (swap! app-state* assoc-in [:tabs tab-id :env] env)))
          (push-thread-bindings {#'context/*request*     req-with-state
                                 #'context/*signals*     signals
                                 #'context/*action-name* (:as action-data)
                                 #'effects/*pending*     (effects/init-pending)})
          (try
            (actions/execute-action! app-state* action-id client-params)

            ;; Collect accumulated effects
            (let [pending  (effects/collect-pending!)
                  ;; Queue pending scripts for SSE delivery via the renderer thread
                  _        (when-let [scripts (seq (:scripts pending))]
                             (when-let [renderer (get-in @app-state* [:tabs tab-id :renderer])]
                               ((:enqueue-scripts! renderer) scripts)))
                  ;; Build response — 204 with any cookies from effects
                  response {:status 204}]
              (effects/apply-cookies-to-response response pending))
            (catch Exception e
              (t/error! {:id   :hyper.error/action-handler
                         :msg  "Error executing action handler"
                         :data {:hyper/action-id action-id}}
                        e)
              {:status  500
               :headers {"Content-Type" "application/json"}
               :body    (json/generate-string {:error (.getMessage e)})})
            (finally
              (pop-thread-bindings))))))))

(defn- extract-route-info
  "Extract route info from a reitit match and Ring request.
   Uses coerced parameters from reitit's coercion middleware when available,
   falling back to raw query params for routes without parameter specs."
  [req]
  (let [match                (:reitit.core/match req)
        route-name           (get-in match [:data :name])
        path                 (:uri req)
        ;; Prefer coerced parameters (set by reitit coercion middleware)
        coerced-path-params  (get-in req [:parameters :path])
        coerced-query-params (get-in req [:parameters :query])
        ;; Fall back to raw params for routes without coercion specs
        raw-path-params      (:path-params match)
        raw-query-params     (into {}
                                   (map (fn [[k v]] [(keyword k) v]))
                                   (:query-params req))]
    {:name         route-name
     :path         path
     :path-params  (or coerced-path-params raw-path-params {})
     :query-params (or coerced-query-params raw-query-params {})}))

(defn- hyper-scripts
  "JavaScript for SPA navigation support and client param encoding:
   - hyper.encodeClientParams(obj): URL-encodes an object of client params as a
     query string fragment (e.g. \"value=hello&checked=true\") for use in @post() URLs.
   - MutationObserver on #hyper-app watches data-hyper-url attribute changes
     and syncs the browser URL bar via replaceState. Title syncing is handled
     server-side by re-rendering the full <head> (including <title>) via SSE.
   - pageshow listener reloads restored documents so stale tab/action state
     does not survive browser history restoration.
   - popstate listener handles browser back/forward by posting to /hyper/navigate
     and restoring document.title from history state.

   Uses c/raw to prevent Chassis from HTML-escaping the JavaScript content
   (e.g., && would become &amp;&amp; which breaks JS syntax)."
  [tab-id base-path]
  [:script
   (c/raw
     (str "
(function() {
  window.hyper = window.hyper || {};
  window.hyper.encodeClientParams = function(o) {
    var p = [];
    for (var k in o) {
      if (o.hasOwnProperty(k) && o[k] !== undefined) {
        p.push(encodeURIComponent(k) + '=' + encodeURIComponent(JSON.stringify(o[k])));
      }
    }
    return p.join('&');
  };
  var appEl = document.getElementById('hyper-app');
  if (appEl) {
    // Seed the initial history entry with the current title so back-navigation restores it
    window.history.replaceState({title: document.title}, '', window.location.href);
    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        if (mutations[i].attributeName === 'data-hyper-url') {
          var url = appEl.getAttribute('data-hyper-url');
          if (url && url !== window.location.pathname + window.location.search) {
            window.history.replaceState({title: document.title}, '', url);
          }
          break;
        }
      }
    });
    observer.observe(appEl, { attributes: true, attributeFilter: ['data-hyper-url'] });
  }
  window.addEventListener('pageshow', function(event) {
    var nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
    if (event.persisted || (nav && nav.type === 'back_forward')) {
      window.location.reload();
    }
  });
  window.addEventListener('popstate', function(e) {
    if (e.state && e.state.title) {
      document.title = e.state.title;
    }
    fetch('" base-path "/hyper/navigate?tab-id=" tab-id "&path=' + encodeURIComponent(window.location.pathname + window.location.search), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    });
  });
})();
"))])

(defn page-handler
  "Wrap a page render function to provide full HTML response.
   Delegates rendering to render/render-tab so initial page loads share
   the same render pipeline as SSE updates (error boundaries, live route
   re-resolution, title/head resolution).

   Options:
   - :datastar-script - Hiccup content for Datastar script added to document head, or nil.
   - :html-attrs      - Map of attributes merged onto the root <html> element
                        (e.g. {:lang \"en\"}).  Defaults to none."
  [app-state* {:keys [datastar-script html-attrs open-when-hidden? base-path]
               :or   {open-when-hidden? true
                      base-path         ""}}]
  (fn [render-fn]
    (fn [req]
      (let [tab-id     (:hyper/tab-id req)
            session-id (:hyper/session-id req)
            route-info (extract-route-info req)]

        (render/register-render-fn! app-state* tab-id render-fn)
        (state/set-tab-route! app-state* tab-id route-info)
        (when-let [env (:hyper/env req)]
          (swap! app-state* assoc-in [:tabs tab-id :env] env))

        (let [result (render/render-tab app-state* session-id tab-id req)]
          ;; Ring response passthrough (e.g. a 302 redirect)
          (if (:status result)
            result
            (let [{:keys [title head-html body-html declared-signals]} result
                  title                                                (or title "Hyper App")
                  sig-attrs                                            (signal/format-signal-attrs declared-signals)
                  div-attrs                                            (cond-> {:id "hyper-app"}
                                                                         sig-attrs (merge sig-attrs))
                  html                                                 (c/html
                                                                         [c/doctype-html5
                                                                          [:html (or html-attrs {})
                                                                           [:head
                                                                            [:meta {:charset "UTF-8"}]
                                                                            [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                                                            [:title title]
                                                                            datastar-script
                                                                            (when head-html (c/raw head-html))]
                                                                           [:body
                                                                            {:data-init (str "@get('" base-path "/hyper/events?tab-id=" tab-id "'"
                                                                                             (when open-when-hidden? ", {openWhenHidden: true}")
                                                                                             ")")}
                                                                            [:div div-attrs (c/raw body-html)]
                                                                            (hyper-scripts tab-id base-path)]]])]
              {:status  200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body    html})))))))

(defn- navigate-handler
  "Handler for popstate/navigation POST requests.
   Looks up the route for the given path and updates the tab's route + render fn."
  [app-state*]
  (fn [req]
    (let [path        (get-in req [:query-params "path"])
          tab-id      (:hyper/tab-id req)
          _session-id (:hyper/session-id req)
          router      (get @app-state* :router)
          route-index (routes/live-route-index app-state*)]
      (when-let [env (:hyper/env req)]
        (when tab-id
          (swap! app-state* assoc-in [:tabs tab-id :env] env)))
      (if-not (and path tab-id router)
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing path or tab-id\"}"}

        ;; Parse path and query string
        (let [[path-part query-string] (clojure.string/split path #"\?" 2)
              raw-query-params         (state/parse-query-string query-string)
              ;; Match the path using the reitit router
              match                    (reitit/match-by-path router path-part)]
          (if-not match
            {:status  404
             :headers {"Content-Type" "application/json"}
             :body    "{\"error\": \"Route not found\"}"}

            (let [route-name   (get-in match [:data :name])
                  ;; Coerce parameters using reitit's coercion if the route has specs
                  coerced      (try
                                 (coercion/coerce!
                                   (assoc match :query-params raw-query-params))
                                 (catch Exception _e nil))
                  query-params (or (:query coerced) raw-query-params {})
                  path-params  (or (:path coerced) (:path-params match) {})
                  render-fn    (routes/find-render-fn route-index route-name)]
              (when render-fn
                (render/register-render-fn! app-state* tab-id render-fn))
              ;; Setting the route triggers the route watcher,
              ;; which handles re-rendering and URL updates via SSE
              (state/set-tab-route! app-state* tab-id
                                    {:name         route-name
                                     :path         path-part
                                     :path-params  path-params
                                     :query-params query-params})
              {:status  200
               :headers {"Content-Type" "application/json"}
               :body    "{\"success\": true}"})))))))

(defn- build-ring-handler
  "Build the Ring handler for the given user routes.
   Wraps user :get handlers with page-handler, combines with hyper system routes,
   compiles the reitit router, and updates app-state with the current routes and router.
   Returns the compiled Ring handler (without outer middleware)."
  [user-routes app-state* page-wrapper system-routes]
  (let [flat-routes    (-> user-routes reitit/router reitit/routes)
        wrapped-routes (mapv (fn [[path route-data]]
                               (if (and (:get route-data) (not (:hyper/disabled? route-data)))
                                 [path (update route-data :get (fn [handler] (page-wrapper handler)))]
                                 [path route-data]))
                             flat-routes)
        all-routes     (concat system-routes wrapped-routes)
        router         (ring/router all-routes
                                    {:conflicts nil
                                     :data      {:coercion   malli/coercion
                                                 :middleware [ring-coercion/coerce-request-middleware]}})]
    ;; Store the flattened routes and router in app-state for access during actions/renders/navigation
    (swap! app-state* assoc
           :router router
           :routes flat-routes)
    (ring/ring-handler router (ring/create-default-handler))))

(defn- wrap-static
  "Optionally serve static assets.

   This is intentionally lightweight: it makes it easy for Hyper consumers to
   serve precompiled assets (e.g. Tailwind CSS output) without Hyper needing to
   know about the asset pipeline.

   Options:
   - :static-resources  Classpath resource root(s) (e.g. \"public\"), served by URI
   - :static-dir        Filesystem directory (or directories) to serve (useful in dev)

   When enabled, adds content-type + not-modified support."
  [handler {:keys [static-resources static-dir]}]
  (let [resource-roots (cond
                         (nil? static-resources) nil
                         (sequential? static-resources) static-resources
                         :else [static-resources])
        static-dirs    (cond
                         (nil? static-dir) nil
                         (sequential? static-dir) static-dir
                         :else [static-dir])
        handler'       (cond-> handler
                         (seq resource-roots) (as-> h
                                                    (reduce (fn [acc root]
                                                              (resource/wrap-resource acc root))
                                                            h
                                                            resource-roots))
                         (seq static-dirs) (as-> h
                                                 (reduce (fn [acc dir]
                                                           (file/wrap-file acc dir))
                                                         h
                                                         static-dirs)))]
    (if (or (seq resource-roots) (seq static-dirs))
      (-> handler'
          (content-type/wrap-content-type)
          (not-modified/wrap-not-modified))
      handler)))

(defn create-handler
  "Create a Ring handler for the hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.
   app-state*: Atom containing application state

   Options:
   - :head              Hiccup nodes appended to the <head> (e.g. stylesheet <link>),
                        or (fn [req] ...) -> hiccup nodes appended to the <head>
   - :html-attrs        Map of attributes merged onto the root <html> element
                        (e.g. {:lang \"en\"}).  Defaults to none.
   - :datastar-script   Hiccup nodes for the Datastar script (or nil to suppress)
   - :open-when-hidden? Keep the SSE connection open when the browser tab is hidden
                        (default true). When false, Datastar closes the connection
                        on tab hide and reopens it when the tab becomes visible.
   - :base-path         URL path prefix for reverse-proxy deployments where the app
                        is served under a subfolder (e.g. \"/my-app\"). When set,
                        all internal hyper endpoints (/hyper/events, /hyper/actions,
                        /hyper/navigate) are mounted and referenced under this prefix.
                        Must start with \"/\" and have no trailing slash.
   - :static-resources  Classpath resource root(s) to serve as static assets
   - :static-dir        Filesystem directory (or directories) to serve as static assets
   - :watches           Vector of Watchable sources added to every page route.
                        Useful for top-level atoms that should trigger a re-render
                        on any page (e.g. a global config or feature-flags atom).
   - :middleware        Vector of Ring middleware fns applied inside the HTTP stack.
                        Each is (fn [handler] (fn [req] ...)).  Runs after Hyper's
                        built-in cookie, params, and session middleware, so your
                        middleware sees parsed :cookies, :params, :hyper/session-id,
                        and :hyper/tab-id.  Use this for auth, :hyper/env setup, and
                        other request-level concerns.  Middleware can also be applied
                        outside create-handler, but will not have access to parsed
                        cookies/params.
   - :render-middleware Vector of middleware fns applied to every page render.
                        Each is (fn [handler] (fn [req] ...)), identical to Ring
                        middleware.  Runs outermost (before per-route middleware).
                        Applied on both initial HTTP page loads and SSE re-renders.
   - :render-error      Function `(fn [error req] -> hiccup)` rendered in place
                        of a view whose render-fn threw.  May be a Var (e.g.
                        `#'my.app/error-page`) to pick up redefinitions without
                        restarting the server.  Defaults to
                        `hyper.render.error/minimal` (generic, production-safe).
                        Use `hyper.render.error/explain` in development to see
                        the message, ex-data, and full stack trace.

   Routes should use :get handlers that return hiccup (Chassis vectors).
   Hyper will wrap them to provide full HTML responses and SSE connections."
  ([routes app-state*]
   (create-handler routes app-state* {:datastar-script (default-datastar-script)}))
  ([routes app-state* {:keys [watches head base-path middleware render-middleware render-error]
                       :or   {render-error render.error/minimal}
                       :as   opts}]
   (let [base-path       (or base-path "")
         page-wrapper    (page-handler app-state* (assoc opts :base-path base-path))
         system-routes   [[(str base-path "/hyper/events") {:get (sse-events-handler app-state*)}]
                          [(str base-path "/hyper/actions") {:post (action-handler app-state*)}]
                          [(str base-path "/hyper/navigate") {:post (navigate-handler app-state*)}]]
         ;; Store the routes source (Var or value) so title resolution can
         ;; always read the latest route metadata, even between router rebuilds.
         ;; Store global :watches so find-route-watches can prepend them to
         ;; every page route's watch list. Auto-watch :head if it's a Var.
         ;; Store base-path so actions and navigate can reference prefixed URLs.
         ;; :render-error is stored as-is (fn or Var); render-tab invokes it
         ;; via IFn so a Var picks up redefinitions on each call.
         _               (swap! app-state* assoc
                                :routes-source routes
                                :global-watches (vec watches)
                                :head head
                                :base-path base-path
                                :render-middleware (vec render-middleware)
                                :render-error render-error)
         initial-routes  (if (var? routes) @routes routes)
         initial-handler (build-ring-handler initial-routes app-state* page-wrapper system-routes)
         handler         (if (var? routes)
                           ;; Dynamic: rebuild router when the routes Var is redefined.
                           ;; Uses identical? since a re-def always creates a new object,
                           ;; avoiding deep equality checks on every request.
                           (let [cached (atom {:routes  initial-routes
                                               :handler initial-handler})]
                             (fn [req]
                               (let [current-routes @routes]

                                 (when-not (identical? current-routes (:routes @cached))
                                   (t/log! {:level :info
                                            :id    :hyper.event/routes-reload
                                            :msg   "Routes changed, rebuilding router"})
                                   (let [h (build-ring-handler current-routes app-state*
                                                               page-wrapper system-routes)]
                                     (reset! cached {:routes current-routes :handler h})))
                                 ((:handler @cached) req))))
                           ;; Static: use the compiled handler directly
                           initial-handler)
         handler-with-mw
         (as-> handler h
           ;; User :middleware runs innermost — after cookies/params/hyper-context
           ;; are parsed, before routing.  Earlier entries wrap outermost (execute first).
           (reduce (fn [h mw] (mw h)) h (reverse (vec middleware)))
           ((wrap-hyper-context app-state*) h)
           (br/wrap-brotli h)
           (keyword-params/wrap-keyword-params h)
           (params/wrap-params h)
           (cookies/wrap-cookies h))]
     ;; Static middleware should be outermost so static requests avoid params/cookies.
     ;; Attach app-state* as metadata so start! can build a stop fn that cleans up.
     (with-meta (wrap-static handler-with-mw opts)
       {::app-state app-state*}))))

(defn- -do-stop
  "Stop the HTTP server and clean up all tab resources.
   Tears down all watchers, renderer threads, SSE channels, and actions."
  [server app-state*]
  (when server
    (server :timeout 100))
  (when app-state*
    (let [tab-ids (keys (:tabs @app-state*))]
      (doseq [tab-id tab-ids]
        (cleanup-tab! app-state* tab-id))
      (t/log! {:level :info
               :id    :hyper.event/server-stop
               :data  {:hyper/tab-count (count tab-ids)}
               :msg   "Hyper server stopped"}))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   port: Port to run on (default: 3000)

   Returns a stop function. Call (stop-fn) or pass to stop! to shut down
   the server and clean up all tab resources (renderer threads, watchers, actions)."
  [handler {:keys [port] :or {port 3000}}]
  (let [server     (http-kit/run-server handler {:port port})
        app-state* (::app-state (meta handler))]
    (t/log! {:level :info
             :id    :hyper.event/server-start
             :data  {:hyper/port port}
             :msg   "Hyper server started"})
    (partial -do-stop server app-state*)))

(defn stop!
  "Stop the HTTP server and clean up all resources."
  [stop-fn]
  (when stop-fn
    (stop-fn)))
