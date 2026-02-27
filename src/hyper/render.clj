(ns hyper.render
  "Rendering pipeline and SSE management.

   Handles rendering hiccup to HTML and sending updates via
   Server-Sent Events using Datastar fragment format."
  (:require [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.brotli :as br]
            [hyper.context :as context]
            [hyper.protocols :as proto]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [org.httpkit.server :as http]
            [taoensso.telemere :as t])
  (:import (java.util.concurrent BlockingQueue LinkedBlockingQueue)))

;; ---------------------------------------------------------------------------
;; SSE writer actor (single-writer per tab)
;; ---------------------------------------------------------------------------

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"})

(def ^:private sse-headers-br
  {"Content-Type"     "text/event-stream"
   "Content-Encoding" "br"})

(def ^:private sse-close-sentinel ::close)

(def ^:private default-sse-max-batch-messages 32)
(def ^:private default-sse-max-batch-chars (* 64 1024)) ;; 64KiB

(defn- sse-batch-limits
  "Resolve batching limits for a new SSE writer actor.

   Optional app-state* overrides:
   - :sse-max-batch-messages
   - :sse-max-batch-chars"
  [app-state*]
  {:max-messages (long (or (get @app-state* :sse-max-batch-messages)
                           default-sse-max-batch-messages))
   :max-chars    (long (or (get @app-state* :sse-max-batch-chars)
                           default-sse-max-batch-chars))})

(defn- try-http-send!
  "Wrapper around http-kit send! that logs and returns boolean success."
  [tab-id channel data close?]
  (try
    (boolean (http/send! channel data close?))
    (catch Throwable e
      (t/error! e {:id   :hyper.error/send-sse
                   :data {:hyper/tab-id tab-id}})
      false)))

(defn- send-sse-initial-response!
  "Send the initial SSE response map (headers + body) for a channel.
   Must happen exactly once per SSE connection."
  [tab-id writer payload]
  (let [channel (:channel writer)
        headers (if (:br-stream writer) sse-headers-br sse-headers)]
    (try-http-send! tab-id channel {:headers headers
                                    :body    payload}
                    false)))

(defn- send-sse-chunk!
  "Send a subsequent SSE data chunk on an already-initialized channel."
  [tab-id writer payload]
  (try-http-send! tab-id (:channel writer) payload false))

(defn- close-sse-writer!
  [tab-id writer]
  ;; Close resources on the actor thread to avoid brotli races.
  (br/close-stream (:br-stream writer))
  (when-let [channel (:channel writer)]
    (when (instance? org.httpkit.server.AsyncChannel channel)
      (t/catch->error! :hyper.error/close-sse-channel
                       (http/close channel))))
  (t/log! {:level :debug
           :id    :hyper.event/sse-writer-close
           :data  {:hyper/tab-id tab-id}
           :msg   "SSE writer closed"})
  nil)

(defn- drop-queued-messages!
  "Drain and drop any remaining queued items (used after close)."
  [^BlockingQueue queue]
  (loop [m (.poll queue)]
    (when m
      (recur (.poll queue))))
  nil)

(defn- coalesce-sse-messages
  "Coalesce already-queued SSE messages into one string payload.

   - Never blocks (uses `.poll`).
   - Preserves ordering by returning a :pending message if a polled message
     would exceed the batch limits.
   - If a close sentinel is observed while polling, returns :close-after? true
     so the actor closes *after* sending this batch.

   Returns {:batch string :pending (or string nil) :close-after? boolean}."
  [first-msg ^BlockingQueue queue {:keys [max-messages max-chars]}]
  (let [first-str         (str first-msg)
        ^StringBuilder sb (StringBuilder.)]
    (.append sb first-str)
    (loop [msg-count  1
           char-count (count first-str)]
      (cond
        (>= msg-count max-messages)
        {:batch (.toString sb) :pending nil :close-after? false}

        (>= char-count max-chars)
        {:batch (.toString sb) :pending nil :close-after? false}

        :else
        (let [next (.poll queue)]
          (cond
            (nil? next)
            {:batch (.toString sb) :pending nil :close-after? false}

            (= next sse-close-sentinel)
            {:batch (.toString sb) :pending nil :close-after? true}

            :else
            (let [next-str (str next)
                  next-len (count next-str)]
              (if (and (pos? max-chars)
                       (> (+ char-count next-len) max-chars))
                {:batch (.toString sb) :pending next-str :close-after? false}
                (do
                  (.append sb next-str)
                  (recur (inc msg-count) (+ char-count next-len)))))))))))

(defn- sse-actor-loop!
  "Virtual-thread actor loop. Owns the channel + (optional) streaming brotli
   state, enforcing single-writer semantics by construction."
  [tab-id writer]
  (let [^BlockingQueue queue (:queue writer)
        limits               (:batch-limits writer)]
    (loop [started? false
           pending  nil]
      (let [next-state
            (try
              (let [msg (or pending (.take queue))]
                (cond
                  (= msg sse-close-sentinel)
                  nil

                  :else
                  (let [{batch        :batch
                         pending-next :pending
                         close-after? :close-after?}
                        (coalesce-sse-messages msg queue limits)
                        payload                      (if-let [br-stream (:br-stream writer)]
                                                       (br/compress-stream (:br-out writer) br-stream batch)
                                                       batch)
                        sent?                        (if started?
                                                       (send-sse-chunk! tab-id writer payload)
                                                       (send-sse-initial-response! tab-id writer payload))]
                    (when (and sent? (not close-after?))
                      {:started? true
                       :pending  pending-next}))))
              (catch InterruptedException _
                nil)
              (catch Throwable e
                (t/error! e {:id   :hyper.error/sse-writer
                             :data {:hyper/tab-id tab-id}})
                nil))]
        (if next-state
          (recur (:started? next-state) (:pending next-state))
          (do
            (close-sse-writer! tab-id writer)
            (drop-queued-messages! queue)
            nil))))))

(defn- start-sse-writer-thread!
  "Start the SSE writer actor as a single virtual thread."
  [tab-id writer]
  (-> (Thread/ofVirtual)
      (.name (str "hyper-sse-" tab-id))
      (.start ^Runnable #(sse-actor-loop! tab-id writer))))

(defn- new-sse-writer
  "Create and start a per-tab SSE writer actor.

   - Producers enqueue quickly (send-sse!).
   - The actor owns http-kit send!/close and the streaming brotli state."
  [app-state* tab-id channel compress?]
  (let [queue     (LinkedBlockingQueue.)
        out       (when compress? (br/byte-array-out-stream))
        br-stream (when out (br/compress-out-stream out :window-size 18))
        writer    (cond-> {:channel      channel
                           :queue        queue
                           :batch-limits (sse-batch-limits app-state*)}
                    compress? (assoc :br-out out
                                     :br-stream br-stream))
        thread    (start-sse-writer-thread! tab-id writer)]
    (assoc writer :thread thread)))

(defn- stop-sse-writer!
  "Signal an SSE writer actor to close via a sentinel."
  [writer]
  (when writer
    (.offer ^BlockingQueue (:queue writer) sse-close-sentinel))
  nil)

(defn register-sse-channel!
  "Register an SSE channel for a tab, optionally with a streaming brotli
   compressor. When compress? is true, creates a compressor pair
   (ByteArrayOutputStream + BrotliOutputStream) kept for the lifetime
   of the SSE connection so the LZ77 window is shared across fragments."
  [app-state* tab-id channel compress?]
  ;; Reconnect safety: close any previous writer actor for this tab-id.
  (when-let [old-writer (get-in @app-state* [:tabs tab-id :sse-writer])]
    (stop-sse-writer! old-writer))

  (let [writer (new-sse-writer app-state* tab-id channel compress?)]
    (swap! app-state* update-in [:tabs tab-id] merge
           {:sse-channel channel
            :sse-writer  writer}))
  nil)

(defn unregister-sse-channel!
  "Unregister an SSE channel for a tab.

   Enqueues a close sentinel so the *actor thread* performs:
   - brotli stream close (if present)
   - channel close

   This avoids closing the brotli stream concurrently with an in-flight write."
  [app-state* tab-id]
  (let [tab-data (get-in @app-state* [:tabs tab-id])
        writer   (:sse-writer tab-data)
        channel  (:sse-channel tab-data)]
    (stop-sse-writer! writer)

    ;; Best-effort: if we somehow have a channel but no writer, close it.
    (when (and (not writer)
               channel
               (instance? org.httpkit.server.AsyncChannel channel))
      (t/catch->error! :hyper.error/close-sse-channel
                       (http/close channel))))

  (swap! app-state* update-in [:tabs tab-id]
         assoc
         :sse-channel nil
         :sse-writer nil
         ;; Legacy keys from pre-writer-actor versions
         :br-out nil
         :br-stream nil)
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

(defn mark-head-elements
  "Add `{:data-hyper-head true}` to each top-level hiccup element in a
   resolved :head value.  The marker lets the SSE head-update JS identify
   which <head> children are framework-managed (vs. static meta/title/script
   from the initial page load) so it can remove-then-append on each cycle.

   Handles:
   - a single vector element  `[:style ...]`
   - a seq/list of elements   `([:link ...] [:style ...])`
   - a vector of elements     `[[:link ...] [:style ...]]`"
  [head-hiccup]
  (when head-hiccup
    (letfn [(mark-one [el]
              (if (and (vector? el) (keyword? (first el)))
                (let [[tag & rest]       el
                      [attrs & children] (if (map? (first rest))
                                           rest
                                           (cons {} rest))]
                  (into [tag (assoc attrs :data-hyper-head true)] children))
                el))]
      (cond
        ;; Single element like [:style "..."]
        (and (vector? head-hiccup) (keyword? (first head-hiccup)))
        (mark-one head-hiccup)

        ;; Sequence of elements
        (sequential? head-hiccup)
        (mapv mark-one head-hiccup)

        :else head-hiccup))))

(defn format-head-update
  "Build a self-removing <script> SSE event that imperatively updates
   the document title and swaps user-provided <head> elements.

   Why not morph?  Morphing <head> inner content via idiomorph can
   disconnect <style>/<link> elements from the browser's CSSOM — the
   nodes stay in the DOM but styles stop applying.  By using JS to
   remove-then-append we guarantee the browser re-evaluates them.

   User-managed head elements are tagged with `data-hyper-head` on the
   initial page load.  On each SSE cycle we remove all `[data-hyper-head]`
   nodes and insert the freshly-rendered set, supporting dynamic fns,
   cache-busted asset URLs, etc.

   The script tag uses Datastar's `mode append` + `selector body` pattern
   (the SDK's ExecuteScript convention) with `data-effect=\"el.remove()\"`
   so it auto-cleans after execution."
  [title extra-head-html]
  (let [js (str "(function(){"
                "document.title='" (utils/escape-js-string (or title "Hyper App")) "';"
                (when (seq extra-head-html)
                  (str "document.querySelectorAll('[data-hyper-head]').forEach(function(el){el.remove()});"
                       "var f=document.createRange().createContextualFragment('"
                       (utils/escape-js-string extra-head-html) "');"
                       "document.head.appendChild(f);"))
                "})();")]
    (str "event: datastar-patch-elements\n"
         "data: mode append\n"
         "data: selector body\n"
         "data: elements <script data-effect=\"el.remove()\">" js "</script>\n\n")))

(defn send-sse!
  "Enqueue an SSE message for a tab.

   This function is intentionally non-blocking:
   - it does *not* perform brotli compression inline
   - it does *not* call http-kit send! inline

   A per-tab SSE writer actor owns the channel + (optional) streaming brotli
   compressor, guaranteeing single-writer semantics."
  [app-state* tab-id message]
  (if-let [writer (get-in @app-state* [:tabs tab-id :sse-writer])]
    (boolean (.offer ^BlockingQueue (:queue writer) message))
    false))

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

   Sends two fragments per render cycle:
   1. The `<head>` — re-rendered with the current title, Datastar script, and
      any extra :head content (supporting dynamic fns). Uses `selector head`
      with `mode inner` so the browser picks up `<title>` changes natively.
   2. The `#hyper-app` div — the page body with a `data-hyper-url` attribute
      for client-side URL bar syncing via MutationObserver.

   On each render, re-resolves the render-fn from live routes so that:
   - Redefining the routes Var with new inline fns picks up the new function
   - Var-based :get handlers (e.g. #'my-page) automatically deref to the latest

   Title is resolved from route :title metadata via hyper.routes/resolve-title,
   supporting static strings, functions of the request, and deref-able values
   (cursors/atoms) so that title updates reactively with state changes."
  [app-state* session-id tab-id request-var]
  (when-let [stored-render-fn (get-render-fn app-state* tab-id)]
    (let [router      (get @app-state* :router)
          route       (get-in @app-state* [:tabs tab-id :route])
          ;; Re-resolve render-fn from live routes so route Var redefs
          ;; and Var-based handlers always use the latest function.
          route-index (routes/live-route-index app-state*)
          render-fn   (if-let [route-name (:name route)]
                        (let [fresh-fn (when (seq route-index)
                                         (routes/find-render-fn route-index route-name))]
                          (if fresh-fn
                            (do
                                   ;; Update stored render-fn so navigate/actions
                                   ;; also use the latest
                              (when (not= fresh-fn stored-render-fn)
                                (register-render-fn! app-state* tab-id fresh-fn))
                              fresh-fn)
                            stored-render-fn))
                        stored-render-fn)
          current-url (when route
                        (state/build-url (:path route) (:query-params route)))
          req         (cond-> {:hyper/session-id session-id
                               :hyper/tab-id     tab-id
                               :hyper/app-state  app-state*}
                        router (assoc :hyper/router router)
                        route  (assoc :hyper/route route))]
      ;; Clean slate — remove all actions for this tab before re-rendering
      ;; so that structurally dynamic renders (shrinking lists, conditional
      ;; branches) don't leave stale action closures behind.
      (actions/cleanup-tab-actions! app-state* tab-id)
      (push-thread-bindings {request-var            req
                             #'context/*action-idx* (atom 0)})
      (try
        (let [hiccup-result   (safe-render render-fn req)
              title-spec      (when (and (seq route-index) route)
                                (routes/find-route-title route-index (:name route)))
              title           (routes/resolve-title title-spec req)
              ;; Resolve head content — only user-provided :head, not the
              ;; static meta/viewport/datastar-script (those stay from
              ;; the initial page load and never need updating).
              head            (get @app-state* :head)
              extra-head      (some-> (routes/resolve-head head req)
                                      mark-head-elements)
              extra-head-html (when extra-head
                                (c/html extra-head))
              head-event      (format-head-update title extra-head-html)
              ;; Body fragment
              div-attrs       (cond-> {:id "hyper-app"}
                                current-url (assoc :data-hyper-url current-url))
              html            (c/html [:div div-attrs hiccup-result])
              body-fragment   (format-datastar-fragment html)]
          ;; Send head update (title + managed elements), then body
          (send-sse! app-state* tab-id (str head-event body-fragment)))
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

(defn- add-external-watch!
  "Watch an external Watchable source for a tab, tracking it under the
   given state-key (:watches or :route-watches). When the source changes,
   submits a throttled re-render to the executor."
  [app-state* session-id tab-id request-var source prefix state-key]
  (let [watch-key (keyword (str prefix tab-id "-" (System/identityHashCode source)))]
    (proto/-add-watch source watch-key
                      (fn [_old _new]
                        (submit-render! app-state*
                                        #(throttled-render-and-send! app-state* session-id tab-id request-var))))
    (swap! app-state* update-in [:tabs tab-id state-key]
           (fnil assoc {}) watch-key source)
    nil))

(defn- remove-external-watches-by-key!
  "Remove all external watches stored under state-key for a tab."
  [app-state* tab-id state-key]
  (let [watches (get-in @app-state* [:tabs tab-id state-key])]
    (doseq [[watch-key source] watches]
      (proto/-remove-watch source watch-key))
    (swap! app-state* update-in [:tabs tab-id] dissoc state-key))
  nil)

(defn watch-source!
  "Watch an external Watchable source for a specific tab. When the source
   changes, submits a throttled re-render to the executor. The watch key
   is unique per tab-id so that multiple tabs each get their own re-render.
   Idempotent — calling with the same source and tab is safe."
  [app-state* session-id tab-id request-var source]
  (add-external-watch! app-state* session-id tab-id request-var source "hyper-ext-" :watches))

(defn remove-external-watches!
  "Remove all external watches for a tab."
  [app-state* tab-id]
  (remove-external-watches-by-key! app-state* tab-id :watches))

;; ---------------------------------------------------------------------------
;; Route-level watches
;; ---------------------------------------------------------------------------
;; Managed separately from user watch! calls so that navigation can
;; tear down the old route's watches and set up the new route's watches
;; without disturbing anything the user registered via watch!.

(defn teardown-route-watches!
  "Remove all route-level watches for a tab."
  [app-state* tab-id]
  (remove-external-watches-by-key! app-state* tab-id :route-watches))

(defn setup-route-watches!
  "Set up watches declared on the current route's :watches metadata and
   auto-watch the :get handler if it's a Var. Tears down any previous
   route-level watches first so that navigation swaps cleanly."
  [app-state* session-id tab-id request-var]
  (teardown-route-watches! app-state* tab-id)
  (let [app-state  @app-state*
        route-name (get-in app-state [:tabs tab-id :route :name])]
    (when route-name
      (let [route-index    (routes/live-route-index app-state*)
            global-watches (:global-watches app-state)]
        (when-let [watches (routes/find-route-watches route-index global-watches route-name)]
          (doseq [source watches]
            (add-external-watch! app-state* session-id tab-id request-var source "hyper-route-" :route-watches))))))
  nil)

(defn setup-watchers!
  "Setup a single watcher on app-state that triggers re-renders when
   global, session, tab, or route state changes for this tab.
   Route URL sync is handled client-side via MutationObserver on data-hyper-url."
  [app-state* session-id tab-id request-var]
  (let [watch-key    (keyword (str "render-" tab-id))
        global-path  [:global]
        session-path [:sessions session-id :data]
        tab-path     [:tabs tab-id :data]
        route-path   [:tabs tab-id :route]]
    (add-watch app-state* watch-key
               (fn [_k _r old-state new-state]
                 (let [route-changed? (let [old-route (get-in old-state route-path)
                                            new-route (get-in new-state route-path)]
                                        (and new-route (not= old-route new-route)))]
                   ;; Swap route-level watches when navigating to a new named route
                   (when route-changed?
                     (let [old-name (get-in old-state (conj route-path :name))
                           new-name (get-in new-state (conj route-path :name))]
                       (when (not= old-name new-name)
                         (setup-route-watches! app-state* session-id tab-id request-var))))
                   ;; Re-render if any watched path changed
                   (when (or route-changed?
                             (not= (get-in old-state global-path)
                                   (get-in new-state global-path))
                             (not= (get-in old-state session-path)
                                   (get-in new-state session-path))
                             (not= (get-in old-state tab-path)
                                   (get-in new-state tab-path)))
                     (submit-render! app-state*
                                     #(throttled-render-and-send! app-state* session-id tab-id request-var)))))))
  nil)

(defn remove-watchers!
  "Remove the watcher for a tab."
  [app-state* tab-id]
  (remove-watch app-state* (keyword (str "render-" tab-id)))
  nil)

(defn cleanup-tab!
  "Clean up all resources for a tab."
  [app-state* tab-id]
  (remove-watchers! app-state* tab-id)
  (remove-external-watches! app-state* tab-id)
  (teardown-route-watches! app-state* tab-id)
  (unregister-sse-channel! app-state* tab-id)
  (actions/cleanup-tab-actions! app-state* tab-id)
  (state/cleanup-tab! app-state* tab-id)
  nil)
