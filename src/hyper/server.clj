(ns ^:no-doc hyper.server
  "HTTP server, routing, and middleware.

   Provides Ring handler creation for hyper applications."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string]
            [compact-uuids.core :as uuid]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.brotli :as br]
            [hyper.component :as component]
            [hyper.component.bundle :as component.bundle]
            [hyper.context :as context]
            [hyper.effects :as effects]
            [hyper.lifecycle :as lifecycle]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.render.error :as render.error]
            [hyper.render.queue :as rq]
            [hyper.routes :as routes]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.uploads :as uploads]
            [hyper.utils :as utils]
            [hyper.watch :as watch]
            [reitit.coercion.malli :as malli]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [ring.adapter.jetty9 :as jetty]
            [ring.core.protocols :as ring-protocols]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.file :as file]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.multipart-params :as multipart-params]
            [ring.middleware.multipart-params.temp-file :as temp-file]
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

(def ^:private default-disconnect-grace-ms
  "How long a tab's state, subviews, watchers, and background workers are kept
   alive after its SSE connection drops, so a reconnect within the window
   re-attaches a fresh renderer instead of rebuilding from scratch.  Default
   3 minutes."
  (* 3 60 1000))

(def ^:private default-heartbeat-ms
  "How often an idle SSE connection emits a keepalive comment.  Kept under the
   ~30-60s idle timeout common to reverse proxies so connections aren't culled
   mid-stream; also surfaces dead/half-open channels on the next write.  Default
   25 seconds."
  25000)

;; ---------------------------------------------------------------------------
;; Stable per-tab render triggers
;; ---------------------------------------------------------------------------
;;
;; Watchers (the app-state watcher) and subview watches capture a render
;; trigger when they are installed.  To survive a reconnect — where the old
;; renderer (and its render queue) is replaced by a new one — these triggers
;; must NOT close over a specific queue.  Instead they indirect through
;; app-state to the *current* renderer's queue, so a watch installed during
;; one connection drives whatever renderer is attached now.  When no renderer
;; is attached (during the disconnect grace window) the trigger is a no-op;
;; accumulated state is reflected by the full render the next renderer runs on
;; re-attach.

(defn- tab-trigger-render!
  "A stable zero-arg full-render trigger for a tab: enqueues onto whichever
   renderer is currently attached, or no-ops when detached."
  [app-state* tab-id]
  (fn []
    (when-let [q (get-in @app-state* [:tabs tab-id :renderer :render-queue])]
      (rq/enqueue-full-render! q))))

(defn- tab-trigger-partial!
  "A stable one-arg partial-render trigger for a tab: enqueues a targeted
   re-render for `component-id` onto the currently attached renderer, or
   no-ops when detached."
  [app-state* tab-id]
  (fn [component-id]
    (when-let [q (get-in @app-state* [:tabs tab-id :renderer :render-queue])]
      (rq/enqueue-partial! q component-id))))

(defn- send-sse!
  "Compress (when br-stream is non-nil) and write an SSE payload to the response
   output stream, flushing so the client sees the event immediately.
   Returns true when written, false when the connection is closed (the write or
   flush throws an IOException — e.g. Jetty's EofException on client disconnect)."
  [^java.io.OutputStream os br-out br-stream sse-payload]
  (try
    (let [^bytes payload (if br-stream
                           (br/compress-stream br-out br-stream sse-payload)
                           (.getBytes ^String sse-payload "UTF-8"))]
      (.write os payload)
      (.flush os)
      true)
    (catch java.io.IOException _ false)))

(defn- handle-partial-render
  "Render only the dirty reactive components and send targeted fragments.
   Returns true/nil on success, false when the connection is closed."
  [app-state* tab-id dirty-ids os br-out br-stream]
  (let [fragments (keep (fn [component-id]
                          (reactive/partial-render app-state* tab-id component-id))
                        dirty-ids)]
    (when (seq fragments)
      (send-sse! os br-out br-stream
                 (render/format-datastar-fragments fragments)))))

(defn- handle-ring-redirect
  "Convert a 3xx Ring redirect response into a client-side window.location
   redirect over SSE.  Throws for non-redirect Ring responses."
  [render-result tab-id os br-out br-stream]
  (let [status   (:status render-result)
        location (get-in render-result [:headers "Location"])]
    (if (and (<= 300 status 399) location)
      (let [js (str "window.location.href='" (utils/escape-js-string location) "'")]
        (send-sse! os br-out br-stream
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

   `known-signals` is what the client already holds (last sent merged with
   client-reported; nil on first render) — only patches beyond it are sent.
   Returns true/nil on success, false when the connection is closed."
  [app-state* session-id tab-id sig-patches known-signals
   trigger-render! enqueue-partial! os br-out br-stream]
  (when-let [render-result (render/render-tab app-state* session-id tab-id)]
    (if (:status render-result)
      ;; Ring response passthrough (e.g. redirect from render middleware)
      (handle-ring-redirect render-result tab-id os br-out br-stream)
      ;; Normal render — assemble and send SSE payload
      (let [{:keys [title head-html body-html url
                    declared-signals registered-action-ids
                    registered-subview-ids]}               render-result]
        ;; Sweep stale actions + render-scoped subviews (reactive regions)
        (actions/sweep-stale-tab-actions! app-state* tab-id registered-action-ids)
        (subview/sweep-stale! app-state* tab-id registered-subview-ids)
        ;; Catch-up wiring for subviews registered before this renderer existed
        ;; (mount-scoped h/watch! in the initial HTTP render's form-2 setup,
        ;; which does not re-register on SSE renders).  Subviews registered
        ;; during an SSE render are already wired by register-subview!, so for
        ;; them this is a no-op.
        (subview/setup-new-watches! app-state* tab-id trigger-render! enqueue-partial!)
        (let [head-event   (render/format-head-update title head-html)
              sig-attrs    (signal/format-signal-attrs declared-signals)
              div-attrs    (cond-> {:id "hyper-app"}
                             url       (assoc :data-hyper-url url)
                             sig-attrs (merge sig-attrs))
              wrapped-html (c/html [:div div-attrs (c/raw body-html)])
              body-event   (render/format-datastar-fragment wrapped-html)
              ;; Keep only patches that add information beyond the body
              ;; fragment's __ifmissing declarations, so Datastar retains
              ;; client-side state it builds from the DOM (e.g. checkbox-array
              ;; signals, issue #44).
              sig-patches  (signal/drop-ifmissing-covered-patches
                             sig-patches declared-signals known-signals)
              sig-event    (when (seq sig-patches)
                             (signal/format-patch-signals-event sig-patches))
              sse-payload  (str head-event body-event sig-event)]
          (send-sse! os br-out br-stream sse-payload))))))

(defn- send-pending-scripts!
  "Send drained scripts over SSE.
   Returns true/nil on success, false when the connection is closed."
  [scripts tab-id os br-out br-stream]
  (try
    (when (seq scripts)
      (send-sse! os br-out br-stream
                 (apply str (map effects/format-execute-script-event scripts))))
    (catch Throwable e
      (t/error! {:id   :hyper.error/renderer-scripts
                 :msg  "Error while sending pending scripts"
                 :data {:hyper/tab-id tab-id}}
                e)
      nil)))

(defn- drain-client-signals!
  "Atomically remove and return this tab's client-reported signals together
   with its current signal state.  Returns [client-reported current-signals]."
  [app-state* tab-id]
  ;; Consumed, not kept — a stale report must not suppress a later server
  ;; write back to the same value.
  (let [[old new] (swap-vals! app-state*
                              (fn [state]
                                (if (get-in state [:tabs tab-id :client-signals])
                                  (update-in state [:tabs tab-id] dissoc :client-signals)
                                  state)))]
    [(get-in old [:tabs tab-id :client-signals])
     (get-in new [:tabs tab-id :signals])]))

(defn- -renderer-loop!
  "Render loop for a single tab, run inside the SSE response's
   `write-body-to-stream` on a (virtual) Jetty thread.

   Owns the response `OutputStream` and (optional) streaming brotli state,
   guaranteeing single-writer semantics by construction.

   Blocks on the render queue until events are available, drains them into
   a consistent batch, then renders and writes.  The queue replaces the
   previous Semaphore + atoms approach, eliminating all shared mutable state.
   Exits when a :shutdown event is received or the connection closes (a write
   throws, so `send-sse!` returns false).

   The SSE response headers are set by `sse-events-handler` on the response
   map; here we only write the body (connected event, then render payloads)."
  [app-state* session-id tab-id ^java.io.OutputStream os compress?
   render-queue]
  (let [br-out           (when compress? (br/byte-array-out-stream))
        br-stream        (when br-out (br/compress-out-stream br-out :window-size 18))
        throttle-ms      (long (or (get @app-state* :render-throttle-ms)
                                   default-render-throttle-ms))
        ;; Heartbeat keepalive interval (ms).  nil / non-positive disables it,
        ;; restoring the plain blocking drain.
        heartbeat-ms     (let [hb (get @app-state* :heartbeat-ms default-heartbeat-ms)]
                           (when (and hb (pos? (long hb))) (long hb)))
        ;; The same stable triggers subview watches were wired with — resolved
        ;; from the renderer published before this loop runs.
        renderer         (get-in @app-state* [:tabs tab-id :renderer])
        enqueue-partial! (:trigger-partial! renderer)
        trigger-render!  (:trigger-render! renderer)]
    (try
      ;; Write the connected event as the first chunk of the SSE body.
      (let [connected-msg (render/format-connected-event tab-id)
            sent?         (send-sse! os br-out br-stream connected-msg)]
        (when sent?
          ;; Main render loop — tracks the last-sent signal snapshot so
          ;; we only emit datastar-patch-signals for actual changes.
          (loop [last-sent-signals nil]
            (let [{:keys [idle? shutdown? full-render? dirty-ids scripts]}
                  (if heartbeat-ms
                    (rq/drain! render-queue heartbeat-ms)
                    (rq/drain! render-queue))]
              (cond
                shutdown? nil

                ;; No events within the heartbeat window — send a keepalive.
                ;; The write keeps idle connections warm through proxies and
                ;; surfaces a dead/half-open connection (send-sse! returns
                ;; false), which exits the loop -> detach -> grace window.
                idle?
                (let [sent? (send-sse! os br-out br-stream (render/format-heartbeat))]
                  (when-not (false? sent?)
                    (recur last-sent-signals)))

                :else
                (let [[client-reported
                       current-signals] (drain-client-signals! app-state* tab-id)
                      ;; Diff against what the client already holds (last sent
                      ;; + client-reported) so round-trips aren't echoed back.
                      known-signals     (if client-reported
                                          (merge last-sent-signals client-reported)
                                          last-sent-signals)
                      sig-patches       (when (and current-signals
                                                   (not= current-signals known-signals))
                                          (signal/changed-signals known-signals current-signals))
                      sent?             (try
                                          (if (and (seq dirty-ids)
                                                   (not full-render?)
                                                   (not (seq sig-patches)))
                                            (handle-partial-render app-state* tab-id dirty-ids
                                                                   os br-out br-stream)
                                            (handle-full-render app-state* session-id tab-id sig-patches
                                                                known-signals
                                                                trigger-render! enqueue-partial!
                                                                os br-out br-stream))
                                          (catch Throwable e
                                            (t/error! {:id   :hyper.error/renderer
                                                       :msg  "Error rendering page"
                                                       :data {:hyper/tab-id tab-id}}
                                                      e)
                                            nil))
                      script-sent?      (send-pending-scripts! scripts tab-id
                                                               os br-out br-stream)]
                  ;; sent? is true (ok), nil (no render-fn or error), false (connection closed)
                  ;; script-sent? follows the same convention
                  (when-not (or (false? sent?) (false? script-sent?))
                    (Thread/sleep throttle-ms)
                    (recur (or current-signals known-signals)))))))))
      (catch Throwable e
        (t/error! {:id   :hyper.error/renderer
                   :msg  "Error rendering page"
                   :data {:hyper/tab-id tab-id}}
                  e))
      (finally
        (br/close-stream br-stream)
        (t/log! {:level :debug
                 :id    :hyper.event/renderer-close
                 :data  {:hyper/tab-id tab-id}
                 :msg   "Tab renderer closed"})))))

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
            :src  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"}])

(def ^:private webkit-sse-shim-js
  "Inline JS injected into <head> for WebKit/Safari clients: routes the GET SSE
   render stream through a native EventSource (which does not strand) instead of
   fetch + ReadableStream.  See resources/hyper/webkit_sse_shim.js.  nil if the
   resource is missing (injection is then skipped)."
  (some-> (io/resource "hyper/webkit_sse_shim.js") slurp))

(defn safari-request?
  "True when the request's User-Agent is WebKit-on-Apple (desktop Safari or any
   iOS browser — all of which use WebKit and are affected by the fetch-streaming
   strand), and not a desktop Blink/Gecko browser that merely carries the legacy
   `Safari` token (Chrome, Edge, Opera, Android WebView)."
  [req]
  (let [ua (get-in req [:headers "user-agent"] "")]
    (boolean
      (and (re-find #"Safari/" ua)
           (not (re-find #"Chrome/|Chromium|Edg/|OPR/|Android|SamsungBrowser" ua))))))

(defn cleanup-tab!
  "Clean up all resources for a tab: watchers, reactive components, renderer thread, actions, and state."
  [app-state* tab-id]
  ;; Teardown runs outside any live request; bind a context so :unmount fns
  ;; resolve cursors, h/env, and h/route.
  (binding [context/*request* (context/teardown-request app-state* tab-id)]
    (watch/remove-watchers! app-state* tab-id)
    ;; teardown-all-components! (-> subview/teardown-all!) tears down every
    ;; subview for the tab, including framework :tab watches and user/route
    ;; :mount watches.
    (reactive/teardown-all-components! app-state* tab-id)
    (lifecycle/teardown-page-view! app-state* tab-id)
    (when-let [stop! (get-in @app-state* [:tabs tab-id :renderer :stop!])]
      (stop!))
    (actions/cleanup-tab-actions! app-state* tab-id)
    (state/cleanup-tab! app-state* tab-id))
  nil)

(defn detach-tab!
  "Disconnect a tab's renderer/channel while preserving everything else.

   Called when an SSE connection drops.  Unlike `cleanup-tab!`, this is a
   *graceful* disconnect: the renderer loop is stopped (its channel is closed)
   but the tab's cursor state, signals, subviews, watchers, and background
   workers are left intact so a reconnect within the grace window can
   re-attach a fresh renderer.  A `:disconnected-at` timestamp arms the reaper.

   No-op when the tab no longer exists."
  [app-state* tab-id]
  (when (contains? (:tabs @app-state*) tab-id)
    (when-let [stop! (get-in @app-state* [:tabs tab-id :renderer :stop!])]
      (stop!))
    (swap! app-state* (fn [state]
                        (-> state
                            (update-in [:tabs tab-id] dissoc :renderer)
                            (assoc-in [:tabs tab-id :disconnected-at]
                                      (System/currentTimeMillis))))))
  nil)

(defn reap-disconnected-tabs!
  "Fully tear down every tab whose disconnect grace window has elapsed.

   A tab is eligible when it carries a `:disconnected-at` stamp older than the
   configured `:disconnect-grace-ms` (default 3 minutes).  Reconnecting clears
   the stamp, so a tab that came back is never reaped.  `now` is the current
   epoch-millis (injectable for testing)."
  [app-state* now]
  (let [grace-ms (long (get @app-state* :disconnect-grace-ms
                            default-disconnect-grace-ms))]
    (doseq [[tab-id tab] (:tabs @app-state*)
            :let         [disconnected-at (:disconnected-at tab)]
            :when        (and disconnected-at
                              (>= (- now disconnected-at) grace-ms))]
      (t/log! {:level :info
               :id    :hyper.event/tab-reap
               :data  {:hyper/tab-id tab-id}
               :msg   "Reaping disconnected tab after grace period"})
      (cleanup-tab! app-state* tab-id))
    nil))

(defn- start-reaper!
  "Start a background virtual thread that periodically reaps tabs whose
   disconnect grace window has elapsed.  Returns a handle with a :stop! fn."
  [app-state*]
  (let [grace-ms (long (get @app-state* :disconnect-grace-ms
                            default-disconnect-grace-ms))
        interval (-> grace-ms (quot 4) (max 500) (min 30000))
        running? (atom true)
        thread   (Thread/startVirtualThread
                   (fn []
                     (while @running?
                       (try
                         (Thread/sleep (long interval))
                         (reap-disconnected-tabs! app-state* (System/currentTimeMillis))
                         (catch InterruptedException _
                           (reset! running? false))
                         (catch Throwable e
                           (t/error! {:id  :hyper.error/reaper
                                      :msg "Error reaping disconnected tabs"}
                                     e))))))]
    {:stop! (fn []
              (reset! running? false)
              (.interrupt ^Thread thread))}))

(defn- run-sse-loop!
  "Run a tab's SSE render loop on the response output stream, for the lifetime
   of the connection.  Invoked from the SSE response's `write-body-to-stream`
   (a virtual Jetty thread), it folds together what used to be http-kit's
   `:on-open` setup, the renderer thread, and `:on-close` teardown:

   1. Publishes a renderer handle (with its render queue) under
      `[:tabs tab-id :renderer]` BEFORE the loop runs, so the stable render
      triggers can resolve its queue immediately.
   2. On the first connection for a tab, wires reactivity once (user/route
      watchers + framework :tab subviews); a reconnect within the grace window
      re-attaches a renderer without rebuilding reactivity.
   3. Runs the blocking render loop (cheap on a virtual thread).
   4. On exit — a genuine disconnect (a write threw) or shutdown — detaches the
      tab, unless a newer connection already took over its renderer (guarded by
      `conn-id`).

   `conn-id` uniquely identifies this connection so a stale half-open
   connection can't detach a tab whose renderer a newer one already replaced."
  [app-state* session-id tab-id ^java.io.OutputStream os compress? conn-id]
  (let [;; A tab whose SSE watchers were already set up is a reconnect: its
        ;; state/subviews/workers survived the disconnect grace window, so we
        ;; only re-attach a renderer rather than rebuild reactivity.
        reconnect?      (boolean (get-in @app-state* [:tabs tab-id :connection-initialized?]))
        render-queue    (rq/make-queue)
        renderer        {:trigger-render!  (tab-trigger-render! app-state* tab-id)
                         :trigger-partial! (tab-trigger-partial! app-state* tab-id)
                         :enqueue-scripts! #(rq/enqueue-scripts! render-queue %)
                         :stop!            #(rq/enqueue-shutdown! render-queue)
                         :render-queue     render-queue
                         :conn-id          conn-id
                         :thread           (Thread/currentThread)}
        trigger-render! (:trigger-render! renderer)]
    (state/get-or-create-tab! app-state* session-id tab-id)
    ;; Take over from any stale/half-open renderer still attached (a new
    ;; connection opened before the old one's body write unwound).
    (when-let [stop! (get-in @app-state* [:tabs tab-id :renderer :stop!])]
      (stop!))
    ;; Publish the renderer (with its queue) before wiring watches so the
    ;; stable triggers and setup-new-watches! resolve it.
    (swap! app-state* assoc-in [:tabs tab-id :renderer] renderer)
    ;; Clear any pending disconnect — we're attached again.
    (swap! app-state* update-in [:tabs tab-id] dissoc :disconnected-at)
    (if reconnect?
      (t/log! {:level :info
               :id    :hyper.event/tab-reconnect
               :data  {:hyper/tab-id tab-id}
               :msg   "Tab reconnected within grace window"})
      ;; First connection for this tab: wire reactivity once.  Stable triggers
      ;; (resolved from app-state) keep these watches driving whatever renderer
      ;; is attached later.
      (do
        (swap! app-state* assoc-in [:tabs tab-id :connection-initialized?] true)
        (watch/setup-watchers! app-state* session-id tab-id trigger-render!)
        ;; Framework watches as :tab-scoped subviews — set up once at connect,
        ;; survive navigation, torn down on full disconnect:
        ;; - routes/head Vars: re-defs trigger re-renders for all tabs
        ;; - component registry: REPL redefinition hot-swaps the client-components
        ;;   bundle over SSE (the head update carries the new content-hashed URL).
        (let [{:keys [routes-source head]} @app-state*]
          (doseq [source (conj (filterv var? [routes-source head])
                               component/registry*)]
            (subview/register-watch! app-state* tab-id source :tab)))))
    ;; Kick the initial full render, then run the loop until shutdown/disconnect.
    (rq/enqueue-full-render! render-queue)
    (try
      (-renderer-loop! app-state* session-id tab-id os compress? render-queue)
      (finally
        ;; Only the connection that is currently attached may detach the tab —
        ;; a stale half-open connection whose renderer was already replaced by a
        ;; newer one must not tear it down.
        (when (= conn-id (get-in @app-state* [:tabs tab-id :renderer :conn-id]))
          (t/log! {:level :info
                   :id    :hyper.event/tab-disconnect
                   :data  {:hyper/tab-id tab-id}
                   :msg   "Tab disconnected — starting grace window"})
          (detach-tab! app-state* tab-id))))))

(defn sse-events-handler
  "Handler for the SSE event stream.

   Returns a Ring response whose body is a `StreamableResponseBody`; Jetty
   invokes `write-body-to-stream` on a (virtual) thread, where `run-sse-loop!`
   owns the output stream for the connection's lifetime — sending the connected
   event, wiring reactivity, and streaming re-renders.  The SSE headers are set
   on the response map here so they are committed before the body streams."
  [app-state*]
  (fn [req]
    (let [session-id (:hyper/session-id req)
          tab-id     (:hyper/tab-id req)
          compress?  (br/accepts-br? req)
          ;; Identity for THIS SSE connection, so a stale half-open connection
          ;; can't detach a tab whose renderer a newer connection took over.
          conn-id    (str (random-uuid))
          headers    (cond-> {"Content-Type"      "text/event-stream"
                              "Cache-Control"     "no-cache, no-transform"
                              "X-Accel-Buffering" "no"}
                       compress? (assoc "Content-Encoding" "br"))]
      {:status  200
       :headers headers
       :body    (reify ring-protocols/StreamableResponseBody
                  (write-body-to-stream [_ _response os]
                    (run-sse-loop! app-state* session-id tab-id os compress? conn-id)))})))

(defn- parse-client-query-params
  "Extract client params from URL query parameters, JSON-decoding each
   value.  Skips the `action-id` key (which is a hyper routing param,
   not a client param).  Returns a keyword-keyed map, or nil when no
   client params are present.

   Values are JSON-encoded on the client by `hyper.encodeClientParams`, so a
   round-trip through `JSON.stringify` → URL-encode → URL-decode →
   `json/parse-string` preserves booleans, numbers, objects, etc.

   Object keys are keywordized so map-shaped params ($form-data, $detail)
   arrive as idiomatic Clojure maps: {:name \"Alice\"} rather than
   {\"name\" \"Alice\"}.  Note that keyword *values* do not round-trip —
   the client side is JSON-typed (Squint has no keyword type), so a
   keyword emitted from a component arrives as a string."
  [req]
  (let [qp (get req :query-params {})]
    (when (> (count qp) 1)                 ;; more than just action-id
      (reduce-kv (fn [acc k v]
                   (if (= k "action-id")
                     acc
                     (assoc acc (keyword k)
                            (try (json/parse-string v true)
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
              ;; latest value for changed-signals diffing).  Recording
              ;; them under :client-signals too keeps the render loop
              ;; from echoing them back as patches.
              action-data    (get-in @app-state* [:actions action-id])
              tab-id         (:tab-id action-data)]
          (when (and signals tab-id)
            (swap! app-state* update-in [:tabs tab-id]
                   (fn [tab]
                     (-> tab
                         (update :signals merge signals)
                         (update :client-signals merge signals))))
            ;; Persist auto-commit optimistics before the action body runs,
            ;; so cursor reads in the action see the committed value.
            (signal/auto-commit! app-state* tab-id signals))
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

(defn- trigger-tab-render!
  "Enqueue a full render for `tab-id` on its currently attached renderer (if
   any).  Used after an upload status transition so a plain-atom `:upload` ref
   — which the app-state watcher does not observe — is still reflected; signal
   and cursor refs already trigger via the watcher, so this is a harmless
   coalesced extra render for them."
  [app-state* tab-id]
  (when-let [trigger! (get-in @app-state* [:tabs tab-id :renderer :trigger-render!])]
    (trigger!)))

(defn upload-handler
  "Handler for multipart file-upload POST requests (`/hyper/upload`).

   Uploads are `h/action`s whose body uses a file param ($form/$files); the
   client posts a real multipart/form-data request here (rather than Datastar's
   JSON @post()).  Wrapped with Ring's multipart middleware, which streams each
   part to a temp file on disk, this:

   - Looks up the registered action + its `:upload-ref` by action-id.
   - Parses the multipart body into the `{:form :files}` client-params and
     folds non-file fields into `context/*signals*` (so `@signal*` reads resolve
     to submitted field values by matching input name).
   - Transitions the status ref :processing -> :done (handler return as
     `:result`) / :error, pushing each change to the client over SSE.
   - Returns 204 (with any cookies from effects), like the action handler."
  [app-state*]
  (fn [req]
    (let [action-id   (get-in req [:query-params "action-id"])
          action-data (when action-id (get-in @app-state* [:actions action-id]))]
      (cond
        (not action-id)
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Missing action-id\"}"}

        (not (:fn action-data))
        {:status  404
         :headers {"Content-Type" "application/json"}
         :body    "{\"error\": \"Upload action not found\"}"}

        :else
        (let [{:keys [tab-id upload-ref as]} action-data
              multipart-params               (:multipart-params req)
              client-params                  (uploads/parse-multipart multipart-params)
              signals                        (uploads/form-fields->signals multipart-params)
              req-with-state                 (assoc req
                                                    :hyper/app-state app-state*
                                                    :hyper/router (get @app-state* :router))]
          (when-let [env (:hyper/env req)]
            (when tab-id
              (swap! app-state* assoc-in [:tabs tab-id :env] env)))
          (push-thread-bindings {#'context/*request*     req-with-state
                                 #'context/*signals*     signals
                                 #'context/*action-name* as
                                 #'effects/*pending*     (effects/init-pending)})
          (try
            ;; Body fully received: transfer is complete, processing begins.
            (uploads/set-status! upload-ref {:phase :processing :percent 100 :error nil})
            (trigger-tab-render! app-state* tab-id)
            (let [result (try
                           ((:fn action-data) client-params)
                           (catch Throwable e
                             (uploads/set-status! upload-ref {:phase :error :error (.getMessage e)})
                             (trigger-tab-render! app-state* tab-id)
                             (throw e)))]
              (uploads/set-status! upload-ref {:phase :done :percent 100 :result result :error nil})
              (trigger-tab-render! app-state* tab-id)
              (let [pending (effects/collect-pending!)]
                (when-let [scripts (seq (:scripts pending))]
                  (when-let [renderer (get-in @app-state* [:tabs tab-id :renderer])]
                    ((:enqueue-scripts! renderer) scripts)))
                (effects/apply-cookies-to-response {:status 204} pending)))
            (catch Throwable e
              (t/error! {:id   :hyper.error/upload-handler
                         :msg  "Error executing upload handler"
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
  // Multipart upload via XMLHttpRequest so we get real upload progress
  // (fetch does not expose request-body progress). fd is a FormData; opts has
  // {url, start?, progress?(0-100), error?}. The signal callbacks are supplied
  // by hyper.uploads/build-upload-expr so progress/phase land in a Datastar
  // signal without a server round-trip; the server pushes processing/done over
  // SSE. Same-origin, so the session cookie rides along automatically.
  window.hyper.upload = function(fd, opts) {
    opts = opts || {};
    try { if (opts.start) opts.start(); } catch (e) {}
    var xhr = new XMLHttpRequest();
    xhr.open('POST', opts.url, true);
    if (xhr.upload && opts.progress) {
      xhr.upload.onprogress = function(e) {
        if (e.lengthComputable) {
          opts.progress(Math.round((e.loaded / e.total) * 100));
        }
      };
    }
    xhr.onreadystatechange = function() {
      if (xhr.readyState === 4) {
        if (xhr.status >= 400) { if (opts.error) opts.error(xhr.status); }
        else if (opts.progress) { opts.progress(100); }
      }
    };
    xhr.onerror = function() { if (opts.error) opts.error(0); };
    xhr.send(fd);
    return undefined;
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

(defn sse-connect-expr
  "The Datastar `@get` expression that opens (or re-opens) this tab's SSE
   connection.  Shared by the initial `<body>` `data-init` and `h/reconnect`
   so the two can never drift in endpoint, base-path, or fetch options."
  [base-path tab-id open-when-hidden?]
  (str "@get('" base-path "/hyper/events?tab-id=" tab-id "'"
       (when open-when-hidden? ", {openWhenHidden: true}")
       ")"))

(defn- page-response
  "Assemble a full HTML document Ring response from a render-tab result.

   Shared by page-handler and not-found-handler so both emit identical document
   scaffolding, differing only in `status` and `fallback-title` (the <title>
   used when the result has no :title)."
  [result {:keys [datastar-script open-when-hidden? base-path webkit-sse-shim?]
           :or   {open-when-hidden? true
                  base-path         ""
                  webkit-sse-shim?  true}}
   req tab-id status fallback-title]
  (let [{:keys [title head-html body-html declared-signals]} result
        title                                                (or title fallback-title)
        sig-attrs                                            (signal/format-signal-attrs declared-signals)
        div-attrs                                            (cond-> {:id "hyper-app"}
                                                               sig-attrs (merge sig-attrs))
        ;; WebKit/Safari fetch-streaming strand workaround: inject the
        ;; EventSource shim only for affected user agents, before the datastar
        ;; script so window.fetch is patched before datastar opens the stream.
        webkit-shim                                          (when (and webkit-sse-shim?
                                                                        webkit-sse-shim-js
                                                                        (safari-request? req))
                                                               [:script (c/raw webkit-sse-shim-js)])
        html                                                 (c/html
                                                               [c/doctype-html5
                                                                [:html
                                                                 [:head
                                                                  [:meta {:charset "UTF-8"}]
                                                                  [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                                                  [:title title]
                                                                  webkit-shim
                                                                  datastar-script
                                                                  (when head-html (c/raw head-html))]
                                                                 [:body
                                                                  (merge
                                                                    {:data-init (sse-connect-expr base-path tab-id open-when-hidden?)}
                                                                    ;; Declare + maintain the client-only connection signals
                                                                    ;; ($_hyperConnected / $_hyperConnection) from Datastar's
                                                                    ;; fetch lifecycle.  Lives on <body> so it persists across
                                                                    ;; SSE re-renders (only #hyper-app and <head> are morphed).
                                                                    (signal/connection-attrs))
                                                                  [:div div-attrs (c/raw body-html)]
                                                                  (hyper-scripts tab-id base-path)]]])]
    {:status  status
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    html}))

(defn page-handler
  "Wrap a page render function to provide full HTML response.
   Delegates rendering to render/render-tab so initial page loads share
   the same render pipeline as SSE updates (error boundaries, live route
   re-resolution, title/head resolution).

   Options:
   - :datastar-script - Hiccup content for Datastar script added to document head, or nil."
  [app-state* opts]
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
            (page-response result opts req tab-id 200 "Hyper App")))))))

(defn- not-found-handler
  "Reitit default-handler for unmatched routes: renders the configured
   :not-found view as a full Hyper page with HTTP 404.

   Registers the not-found render-fn and a nameless route from the request URI;
   render-tab falls back to the stored render-fn when the route has no :name,
   so no reitit route is needed and the SSE connection re-renders the view."
  [app-state* opts]
  (fn [req]
    (let [tab-id       (:hyper/tab-id req)
          session-id   (:hyper/session-id req)
          not-found-fn (get @app-state* :not-found)
          route-info   {:path         (:uri req)
                        :query-params (into {}
                                            (map (fn [[k v]] [(keyword k) v]))
                                            (:query-params req))}]
      (render/register-render-fn! app-state* tab-id not-found-fn)
      (state/set-tab-route! app-state* tab-id route-info)
      (when-let [env (:hyper/env req)]
        (swap! app-state* assoc-in [:tabs tab-id :env] env))
      (let [result (render/render-tab app-state* session-id tab-id req)]
        ;; Ring response passthrough (e.g. a redirect from render middleware)
        (if (:status result)
          result
          (page-response result opts req tab-id 404 "Not Found"))))))

(defn- components-js-handler
  "Serve the assembled client-components ES module bundle.

   The bundle URL carries the current content hash in its query string
   (`?v=<hash>`, see hyper.component.bundle/head-script-tag), so a registry
   change rotates the URL rather than invalidating a cached response.

   This endpoint always serves the *current* bundle, so it only promises
   `immutable` caching when the requested `?v=` matches the hash actually
   being served.  Honouring `immutable` for a stale `?v=` would poison the
   browser cache — it would store the new content under the old, immutable
   URL forever, so a later URL reuse (e.g. reverting an edit) would serve
   that stale entry until a hard refresh.  A mismatched (or missing) `?v=`
   is therefore served `no-cache` so the browser always revalidates."
  [app-state*]
  (fn [req]
    (if-let [{:keys [js hash]} (component.bundle/bundle
                                 {:squint-core-url (get @app-state* :squint-core-url)})]
      (let [requested (get-in req [:query-params "v"])
            matched?  (= requested hash)]
        {:status  200
         :headers {"Content-Type"  "text/javascript; charset=utf-8"
                   "Cache-Control" (if matched?
                                     "public, max-age=31536000, immutable"
                                     "no-cache")
                   "ETag"          (str "\"" hash "\"")}
         :body    js})
      {:status  404
       :headers {"Content-Type" "text/plain"}
       :body    "No components registered"})))

(defn- -navigation-parameters
  "Coerces navigation parameters with the matched GET route's compiled validator."
  [match request]
  (when-let [wrap (some (fn [middleware]
                          (when (= :reitit.ring.coercion/coerce-request (:name middleware))
                            (:wrap middleware)))
                        (get-in match [:result :get :middleware]))]
    ((wrap :parameters) (assoc request :path-params (:path-params match)))))

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
            ;; No route matched — when :not-found is set, register it and set
            ;; the route so the route watcher re-renders the 404 over SSE and
            ;; syncs the URL bar, matching an initial-load 404.  When disabled
            ;; (nil), reply with the plain JSON 404.
            (if-let [not-found-fn (get @app-state* :not-found)]
              (do
                (render/register-render-fn! app-state* tab-id not-found-fn)
                (state/set-tab-route! app-state* tab-id
                                      {:path         path-part
                                       :query-params raw-query-params})
                {:status  200
                 :headers {"Content-Type" "application/json"}
                 :body    "{\"success\": true}"})
              {:status  404
               :headers {"Content-Type" "application/json"}
               :body    "{\"error\": \"Route not found\"}"})

            (let [route-name   (get-in match [:data :name])
                  ;; Coerce parameters using reitit's coercion if the route has specs
                  coerced      (-navigation-parameters match (assoc req :query-params raw-query-params))
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

(defn- -coercion-response
  "Encodes a coercion error response as JSON with its HTTP content type."
  [response]
  (-> response
      (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8")
      (update :body json/generate-string)))

(defn- -wrap-coercion-errors
  "Returns sendable validation responses and propagates unrelated failures."
  [handler]
  (fn
    ([request]
     (try
       (handler request)
       (catch Exception error
         (ring-coercion/handle-coercion-exception error -coercion-response #(throw %)))))
    ([request respond raise]
     (let [fail (fn [error]
                  (ring-coercion/handle-coercion-exception
                    error (comp respond -coercion-response) raise))]
       (try
         (handler request respond fail)
         (catch Exception error (fail error)))))))

(defn- build-ring-handler
  "Build the Ring handler for the given user routes.
   Wraps user :get handlers with page-handler, combines with hyper system routes,
   compiles the reitit router, and updates app-state with the current routes and router.
   The supplied default-handler renders the :not-found view when no route matches.
   Returns the compiled Ring handler (without outer middleware)."
  [user-routes app-state* page-wrapper system-routes default-handler]
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
                                                 :middleware [-wrap-coercion-errors
                                                              ring-coercion/coerce-request-middleware]}})]
    ;; Store the flattened routes and router in app-state for access during actions/renders/navigation
    (swap! app-state* assoc
           :router router
           :routes flat-routes)
    (ring/ring-handler router default-handler)))

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
   - :datastar-script   Hiccup nodes for the Datastar script (or nil to suppress)
   - :open-when-hidden? Keep the SSE connection open when the browser tab is hidden
                        (default true). When false, Datastar closes the connection
                        on tab hide and reopens it when the tab becomes visible.
   - :webkit-sse-shim?  Inject a small client shim (into <head>, only for
                        WebKit/Safari user agents) that routes the GET render
                        stream through a native EventSource instead of
                        fetch+ReadableStream, working around a WebKit bug where a
                        large isolated SSE patch is held back until the next
                        write.  Other browsers are unaffected and never receive
                        it.  Default true; pass false to disable.
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
   - :not-found         Function `(fn [req] -> hiccup)` rendered when no route
                        matches, served as a full page with HTTP 404 (and over
                        SSE for client-side navigation).  May be a Var to pick
                        up redefinitions.  Defaults to
                        `hyper.render.error/not-found`; pass `nil` to disable
                        and fall back to reitit's plain-text 404.
   - :disconnect-grace-ms
                        How long (ms) a tab's state, signals, subviews,
                        watchers, and background workers are kept alive after
                        its SSE connection drops.  A reconnect within this
                        window seamlessly re-attaches a fresh renderer with no
                        state loss and no mount/unmount churn; only after it
                        elapses is the tab fully torn down.  Defaults to
                        180000 (3 minutes).
   - :heartbeat-ms      How often (ms) an idle SSE connection emits a keepalive
                        comment.  Keeps connections warm through reverse-proxy
                        idle timeouts and surfaces dead/half-open channels on
                        the next write (so the disconnect grace window can
                        start).  Defaults to 25000 (25s); pass nil or 0 to
                        disable.
   - :max-file-size     Max bytes allowed per uploaded file on the /hyper/upload
                        route; a larger file gets a 413.  Default: no limit.
   - :max-file-count    Max number of files allowed per upload request.
                        Default: no limit.
   - :upload-expires-in TTL (seconds) for upload temp files before they are
                        reaped.  Default 3600 (1 hour).  Move/stream :tempfile
                        to permanent storage in your handler.

   Routes should use :get handlers that return hiccup (Chassis vectors).
   Hyper will wrap them to provide full HTML responses and SSE connections."
  ([routes app-state*]
   (create-handler routes app-state* {:datastar-script (default-datastar-script)}))
  ([routes app-state* {:keys [watches head base-path middleware render-middleware render-error not-found render-guard
                              disconnect-grace-ms heartbeat-ms
                              max-file-size max-file-count upload-expires-in]
                       :or   {render-error        render.error/minimal
                              not-found           render.error/not-found
                              render-guard        :warn
                              disconnect-grace-ms default-disconnect-grace-ms
                              heartbeat-ms        default-heartbeat-ms
                              upload-expires-in   3600}
                       :as   opts}]
   (let [base-path       (or base-path "")
         opts            (assoc opts :base-path base-path)
         page-wrapper    (page-handler app-state* opts)
         ;; Override only the genuine "no route matched" (404) case, leaving
         ;; reitit's 405/406 discrimination intact.  When :not-found is nil the
         ;; feature is disabled and reitit's plain-text default handler is used.
         default-handler (if not-found
                           (ring/create-default-handler
                             {:not-found (not-found-handler app-state* opts)})
                           (ring/create-default-handler))
         ;; Multipart middleware is scoped to the upload route only — action
         ;; POSTs and SSE must not be run through multipart parsing.  The
         ;; temp-file store streams each part to disk (Jetty streams the body),
         ;; so large uploads never buffer in heap.
         upload-mw       {:store          (temp-file/temp-file-store {:expires-in upload-expires-in})
                          :max-file-size  max-file-size
                          :max-file-count max-file-count}
         upload-route    (multipart-params/wrap-multipart-params
                           (upload-handler app-state*)
                           (into {} (remove (comp nil? val)) upload-mw))
         system-routes   [[(str base-path "/hyper/events") {:get (sse-events-handler app-state*)}]
                          [(str base-path "/hyper/actions") {:post (action-handler app-state*)}]
                          [(str base-path "/hyper/upload") {:post upload-route}]
                          [(str base-path "/hyper/navigate") {:post (navigate-handler app-state*)}]
                          [(str base-path "/hyper/components.js") {:get (components-js-handler app-state*)}]]
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
                                :render-error render-error
                                :render-guard render-guard
                                :not-found not-found
                                :disconnect-grace-ms disconnect-grace-ms
                                :heartbeat-ms heartbeat-ms
                                :open-when-hidden? (get opts :open-when-hidden? true)
                                :squint-core-url (:squint-core-url opts))
         initial-routes  (if (var? routes) @routes routes)
         initial-handler (build-ring-handler initial-routes app-state* page-wrapper system-routes default-handler)
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
                                                               page-wrapper system-routes
                                                               default-handler)]
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
   Tears down the reaper, all watchers, renderer threads, SSE channels, and actions.

   Order matters: each tab's render loop runs *inside* a Jetty request's
   write-body-to-stream, so we must stop those loops (cleanup-tab! enqueues
   :shutdown, letting the loop return and its SSE request complete) BEFORE
   stopping Jetty.  Otherwise Jetty's graceful stop blocks on the still-open
   SSE requests and throws a TimeoutException."
  [server app-state*]
  (when app-state*
    (when-let [reaper (:reaper @app-state*)]
      ((:stop! reaper))
      (swap! app-state* dissoc :reaper))
    (let [tab-ids (keys (:tabs @app-state*))]
      (doseq [tab-id tab-ids]
        (cleanup-tab! app-state* tab-id))
      (t/log! {:level :info
               :id    :hyper.event/server-stop
               :data  {:hyper/tab-count (count tab-ids)}
               :msg   "Hyper server stopped"})))
  (when server
    ;; Open SSE connections can make graceful stop hit its StopTimeout and throw;
    ;; the server is stopped regardless, so swallow it (:catch-val) instead of
    ;; letting a benign stop timeout propagate out of stop!.
    (t/catch->error! {:id :hyper.error/server-stop :catch-val nil}
                     (jetty/stop-server server))))

(defn start!
  "Start the HTTP server with the given handler.

   handler: Ring handler (created with create-handler)
   options:
   - :port            - Port to run on (default: 3000)
   - :stop-timeout-ms - Jetty graceful-stop timeout (default 1000).

   A tab's SSE render loop blocks its thread in write-body-to-stream for the
   connection's whole lifetime, so the threading setup matters:

   - Sync mode (not :async?): Jetty dispatches each request off the selector
     onto a worker thread.  In async mode the streaming body-write is driven on
     a selector thread, and Jetty has only ~cores/2 of them, so a handful of
     open SSE tabs pins every selector and new connections can't be read (page
     loads hang).  We flush the OutputStream ourselves in send-sse!, so sync
     mode still streams incrementally.
   - QueuedThreadPool + a virtual-threads executor: Jetty's I/O
     (acceptors/selectors) stays on platform threads, while request handling
     (and thus the blocking render loop) is dispatched onto a virtual thread —
     so each connected tab costs one virtual thread, not a platform one.

   Returns a stop function. Call (stop-fn) or pass to stop! to shut down
   the server and clean up all tab resources (renderer threads, watchers, actions)."
  [handler {:keys [port stop-timeout-ms]
            :or   {port 3000 stop-timeout-ms 1000}}]
  (let [app-state* (::app-state (meta handler))
        pool       (doto (org.eclipse.jetty.util.thread.QueuedThreadPool.)
                     (.setName "hyper-jetty")
                     (.setVirtualThreadsExecutor
                       (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)))
        server     (jetty/run-jetty handler
                                    {:port         port
                                     :async?       false
                                     :join?        false
                                     :thread-pool  pool
                                     ;; Shorten Jetty's 30s default StopTimeout so stop!
                                     ;; returns promptly (matters for REPL restarts).
                                     :configurator (fn [^org.eclipse.jetty.server.Server s]
                                                     (.setStopTimeout s (long stop-timeout-ms)))})]
    ;; Start the disconnect-grace reaper so tabs detached past their grace
    ;; window are fully torn down.
    (when app-state*
      (swap! app-state* assoc :reaper (start-reaper! app-state*)))
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
