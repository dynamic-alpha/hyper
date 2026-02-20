(ns hyper.render
  "Rendering pipeline and SSE management.

   Handles rendering hiccup to HTML and sending updates via
   Server-Sent Events using Datastar fragment format."
  (:require [dev.onionpancakes.chassis.core :as c]
            [hyper.brotli :as br]
            [hyper.protocols :as proto]
            [hyper.state :as state]
            [org.httpkit.server :as http]
            [taoensso.telemere :as t]))

(defn register-sse-channel!
  "Register an SSE channel for a tab, optionally with a streaming brotli
   compressor. When compress? is true, creates a compressor pair
   (ByteArrayOutputStream + BrotliOutputStream) kept for the lifetime
   of the SSE connection so the LZ77 window is shared across fragments."
  [app-state* tab-id channel compress?]
  (let [tab-updates (cond-> {:sse-channel channel}
                      compress? (merge (let [out (br/byte-array-out-stream)]
                                         {:br-out    out
                                          :br-stream (br/compress-out-stream out :window-size 18)})))]
    (swap! app-state* update-in [:tabs tab-id] merge tab-updates))
  nil)

(defn unregister-sse-channel!
  "Unregister an SSE channel and close the brotli stream for a tab."
  [app-state* tab-id]
  (let [tab-data (get-in @app-state* [:tabs tab-id])
        channel  (:sse-channel tab-data)]
    (br/close-stream (:br-stream tab-data))
    (when (and channel (instance? org.httpkit.server.AsyncChannel channel))
      (t/catch->error! :hyper.error/close-sse-channel
                       (http/close channel))))
  (swap! app-state* update-in [:tabs tab-id]
         assoc :sse-channel nil :br-out nil :br-stream nil)
  nil)

(defn get-sse-channel
  "Get the SSE channel for a tab."
  [app-state* tab-id]
  (get-in @app-state* [:tabs tab-id :sse-channel]))

(defn register-render-fn!
  "Register a render function for a tab."
  [app-state* tab-id render-fn]
  (swap! app-state* assoc-in [:tabs tab-id :render-fn] render-fn)
  nil)

(defn get-render-fn
  "Get the render function for a tab."
  [app-state* tab-id]
  (get-in @app-state* [:tabs tab-id :render-fn]))

(defn format-datastar-fragment
  "Format HTML as a Datastar patch-elements SSE event.

   Datastar expects Server-Sent Events in the format:
   event: datastar-patch-elements
   data: elements <html content>

   (blank line to end event)"
  [html]
  (str "event: datastar-patch-elements\n"
       "data: elements " html "\n\n"))

(defn send-sse!
  "Send an SSE message to a tab's channel.
   If the tab has a streaming brotli compressor (client supports br),
   compresses through it and sends raw bytes. The Content-Encoding: br
   header is set once on the initial response — subsequent sends on the
   async channel are just data frames in the same compressed stream."
  [app-state* tab-id message]
  (let [tab-data  (get-in @app-state* [:tabs tab-id])
        channel   (:sse-channel tab-data)
        br-out    (:br-out tab-data)
        br-stream (:br-stream tab-data)]
    (when channel
      (or (t/catch->error! :hyper.error/send-sse
                           (if (and br-out br-stream)
                             (let [compressed (br/compress-stream br-out br-stream message)]
                               (http/send! channel compressed false))
                             (http/send! channel message false)))
          false))))

(defn render-error-fragment
  "Render an error message as a fragment."
  [error]
  (c/html
    [:div {:style "padding: 20px; font-family: sans-serif; background: #fee; border: 1px solid #fcc; border-radius: 4px; margin: 20px;"}
     [:h2 {:style "color: #c00; margin-top: 0;"} "Render Error"]
     [:p "An error occurred while rendering this view:"]
     [:pre {:style "background: #fff; padding: 10px; border-radius: 4px; overflow: auto;"}
      (str error)]]))

(defn safe-render
  "Safely render a view with error boundary."
  [render-fn req]
  (try
    (render-fn req)
    (catch Exception e
      (t/error! e {:id :hyper.error/render})
      (render-error-fragment e))))

(defn render-and-send!
  "Render the view for a tab and send it via SSE.
   Stamps the current route URL as a data-hyper-url attribute and the resolved
   page title as data-hyper-title on the #hyper-app div. The client-side
   MutationObserver syncs the browser URL bar via replaceState and updates
   document.title, keeping browser history entries accurate.

   On each render, re-resolves the render-fn from live routes so that:
   - Redefining the routes Var with new inline fns picks up the new function
   - Var-based :get handlers (e.g. #'my-page) automatically deref to the latest

   Title is resolved from route :title metadata via hyper.server/resolve-title,
   supporting static strings, functions of the request, and deref-able values
   (cursors/atoms) so that title updates reactively with state changes."
  [app-state* session-id tab-id request-var]
  (when-let [stored-render-fn (get-render-fn app-state* tab-id)]
    (let [router         (get @app-state* :router)
          route          (get-in @app-state* [:tabs tab-id :route])
          ;; Re-resolve render-fn from live routes so route Var redefs
          ;; and Var-based handlers always use the latest function.
          live-routes-fn (requiring-resolve 'hyper.server/live-routes)
          find-render-fn (requiring-resolve 'hyper.server/find-render-fn)
          current-routes (live-routes-fn app-state*)
          render-fn      (if-let [route-name (:name route)]
                           (let [fresh-fn (when current-routes
                                            (find-render-fn current-routes route-name))]
                             (if fresh-fn
                               (do
                                 ;; Update stored render-fn so navigate/actions
                                 ;; also use the latest
                                 (when (not= fresh-fn stored-render-fn)
                                   (register-render-fn! app-state* tab-id fresh-fn))
                                 fresh-fn)
                               stored-render-fn))
                           stored-render-fn)
          current-url    (when route
                           (state/build-url (:path route) (:query-params route)))
          req            (cond-> {:hyper/session-id session-id
                                  :hyper/tab-id     tab-id
                                  :hyper/app-state  app-state*}
                           router (assoc :hyper/router router))
          action-idx-var (requiring-resolve 'hyper.core/*action-idx*)]
      (push-thread-bindings {request-var    req
                             action-idx-var (atom 0)})
      (try
        (let [hiccup-result       (safe-render render-fn req)
              ;; Resolve title — requiring-resolve to avoid circular dep
              resolve-title-fn    (requiring-resolve 'hyper.server/resolve-title)
              find-route-title-fn (requiring-resolve 'hyper.server/find-route-title)
              title-spec          (when (and current-routes route)
                                    (find-route-title-fn current-routes (:name route)))
              title               (resolve-title-fn title-spec req)
              div-attrs           (cond-> {:id "hyper-app"}
                                    current-url (assoc :data-hyper-url current-url)
                                    title       (assoc :data-hyper-title title))
              html                (c/html [:div div-attrs hiccup-result])
              fragment            (format-datastar-fragment html)]
          (send-sse! app-state* tab-id fragment))
        (finally
          (pop-thread-bindings))))))

;; ---------------------------------------------------------------------------
;; Render dispatch
;; ---------------------------------------------------------------------------

(defn submit-render!
  "Submit a render task to the app's executor. Returns immediately so that
   watch callbacks (which may fire synchronously, e.g. atom add-watch)
   never block the caller's thread."
  [app-state* f]
  (when-let [^java.util.concurrent.ExecutorService executor (get @app-state* :executor)]
    (.submit executor ^Runnable f))
  nil)

;; ---------------------------------------------------------------------------
;; Render throttling — default 16ms (~60fps)
;; ---------------------------------------------------------------------------

(def ^:dynamic *render-throttle-ms* 16)

(defn should-render?
  "Check if enough time has passed since last render for this tab.
   Uses throttling to prevent render thrashing on rapid state updates.
   Stores last render time in app-state* at [:tabs tab-id :last-render-ms]."
  [app-state* tab-id]
  (let [now         (System/currentTimeMillis)
        last-render (get-in @app-state* [:tabs tab-id :last-render-ms] 0)
        elapsed     (- now last-render)]
    (when (>= elapsed *render-throttle-ms*)
      (swap! app-state* assoc-in [:tabs tab-id :last-render-ms] now)
      true)))

(defn throttled-render-and-send!
  "Render and send with throttling."
  [app-state* session-id tab-id request-var]
  (when (should-render? app-state* tab-id)
    (render-and-send! app-state* session-id tab-id request-var)))

;; ---------------------------------------------------------------------------
;; External source watching
;; ---------------------------------------------------------------------------

(defn watch-source!
  "Watch an external Watchable source for a specific tab. When the source
   changes, submits a throttled re-render to the executor. The watch key
   is unique per tab-id so that multiple tabs each get their own re-render.
   Idempotent — calling with the same source and tab is safe."
  [app-state* session-id tab-id request-var source]
  (let [watch-key (keyword (str "hyper-ext-" tab-id "-" (System/identityHashCode source)))]
    (proto/-add-watch source watch-key
                      (fn [_old _new]
                        (submit-render! app-state*
                                        #(throttled-render-and-send! app-state* session-id tab-id request-var))))
    ;; Track for cleanup
    (swap! app-state* update-in [:tabs tab-id :watches]
           (fnil assoc {}) watch-key source)
    nil))

(defn unwatch-source!
  "Remove a single external watch by key for a tab."
  [_app-state* source watch-key]
  (proto/-remove-watch source watch-key)
  nil)

(defn remove-external-watches!
  "Remove all external watches for a tab."
  [app-state* tab-id]
  (let [watches (get-in @app-state* [:tabs tab-id :watches])]
    (doseq [[watch-key source] watches]
      (unwatch-source! app-state* source watch-key))
    (swap! app-state* update-in [:tabs tab-id] dissoc :watches))
  nil)

;; ---------------------------------------------------------------------------
;; Route-level watches
;; ---------------------------------------------------------------------------
;; Managed separately from user watch! calls so that navigation can
;; tear down the old route's watches and set up the new route's watches
;; without disturbing anything the user registered via watch!.

(defn- watch-source-as-route!
  "Like watch-source! but tracks under :route-watches instead of :watches."
  [app-state* session-id tab-id request-var source]
  (let [watch-key (keyword (str "hyper-route-" tab-id "-" (System/identityHashCode source)))]
    (proto/-add-watch source watch-key
                      (fn [_old _new]
                        (submit-render! app-state*
                                        #(throttled-render-and-send! app-state* session-id tab-id request-var))))
    (swap! app-state* update-in [:tabs tab-id :route-watches]
           (fnil assoc {}) watch-key source)
    nil))

(defn teardown-route-watches!
  "Remove all route-level watches for a tab."
  [app-state* tab-id]
  (let [watches (get-in @app-state* [:tabs tab-id :route-watches])]
    (doseq [[watch-key source] watches]
      (unwatch-source! app-state* source watch-key))
    (swap! app-state* update-in [:tabs tab-id] dissoc :route-watches))
  nil)

(defn setup-route-watches!
  "Set up watches declared on the current route's :watches metadata and
   auto-watch the :get handler if it's a Var. Tears down any previous
   route-level watches first so that navigation swaps cleanly."
  [app-state* session-id tab-id request-var]
  (teardown-route-watches! app-state* tab-id)
  (let [find-route-watches-fn (requiring-resolve 'hyper.server/find-route-watches)
        live-routes-fn        (requiring-resolve 'hyper.server/live-routes)
        routes                (live-routes-fn app-state*)
        route-name            (get-in @app-state* [:tabs tab-id :route :name])]
    (when (and routes route-name)
      (when-let [watches (find-route-watches-fn routes route-name)]
        (doseq [source watches]
          (watch-source-as-route! app-state* session-id tab-id request-var source)))))
  nil)

(defn setup-watchers!
  "Setup watchers on global, session, tab, and route state to trigger re-renders.
   Route URL sync is handled client-side via MutationObserver on data-hyper-url."
  [app-state* session-id tab-id request-var]
  (let [watch-key      (keyword (str "render-" tab-id))
        global-path    [:global]
        session-path   [:sessions session-id :data]
        tab-path       [:tabs tab-id :data]
        route-path     [:tabs tab-id :route]
        trigger-render (fn [_k _r old-state new-state path]
                         (when (not= (get-in old-state path)
                                     (get-in new-state path))
                           (submit-render! app-state*
                                           #(throttled-render-and-send! app-state* session-id tab-id request-var))))]

    ;; Watch global data (shared across all sessions/tabs)
    (add-watch app-state* (keyword (str "global-" watch-key))
               (fn [k r old-state new-state]
                 (trigger-render k r old-state new-state global-path)))

    ;; Watch session data
    (add-watch app-state* (keyword (str "session-" watch-key))
               (fn [k r old-state new-state]
                 (trigger-render k r old-state new-state session-path)))

    ;; Watch tab data
    (add-watch app-state* (keyword (str "tab-" watch-key))
               (fn [k r old-state new-state]
                 (trigger-render k r old-state new-state tab-path)))

    ;; Watch route changes to trigger re-render and swap route-level watches.
    ;; The rendered fragment includes data-hyper-url on #hyper-app,
    ;; and the client-side MutationObserver handles replaceState.
    (add-watch app-state* (keyword (str "route-" watch-key))
               (fn [_k _r old-state new-state]
                 (let [old-route (get-in old-state route-path)
                       new-route (get-in new-state route-path)]
                   (when (and new-route (not= old-route new-route))
                     ;; Swap route-level watches when navigating to a new route
                     (when (not= (:name old-route) (:name new-route))
                       (setup-route-watches! app-state* session-id tab-id request-var))
                     (submit-render! app-state*
                                     #(throttled-render-and-send! app-state* session-id tab-id request-var)))))))
  nil)

(defn remove-watchers!
  "Remove watchers for a tab."
  [app-state* tab-id]
  (let [watch-key (keyword (str "render-" tab-id))]
    (remove-watch app-state* (keyword (str "global-" watch-key)))
    (remove-watch app-state* (keyword (str "session-" watch-key)))
    (remove-watch app-state* (keyword (str "tab-" watch-key)))
    (remove-watch app-state* (keyword (str "route-" watch-key))))
  nil)

(defn cleanup-tab!
  "Clean up all resources for a tab."
  [app-state* tab-id]
  (remove-watchers! app-state* tab-id)
  (remove-external-watches! app-state* tab-id)
  (teardown-route-watches! app-state* tab-id)
  (unregister-sse-channel! app-state* tab-id)
  ;; Use requiring-resolve for hyper.actions to avoid circular dependency
  ;; (actions requires nothing from render, but render is loaded first)
  (let [cleanup-actions! (requiring-resolve 'hyper.actions/cleanup-tab-actions!)]
    (cleanup-actions! app-state* tab-id))
  (state/cleanup-tab! app-state* tab-id)
  nil)
