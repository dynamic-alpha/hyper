(ns hyper.render
  "Rendering pipeline and SSE management.

   Handles rendering hiccup to HTML and sending updates via
   Server-Sent Events using Datastar fragment format."
  (:require [hiccup.core :as hiccup]
            [hyper.state :as state]
            [org.httpkit.server :as http]))

;; Forward declare to avoid circular dependency with hyper.core
(declare ^:dynamic *request*)

;; SSE channels: {tab-id channel}
(defonce sse-channels (atom {}))

;; Render function registry: {tab-id render-fn}
(defonce render-fns (atom {}))

(defn register-sse-channel!
  "Register an SSE channel for a tab."
  [tab-id channel]
  (swap! sse-channels assoc tab-id channel)
  nil)

(defn unregister-sse-channel!
  "Unregister an SSE channel for a tab."
  [tab-id]
  (when-let [channel (get @sse-channels tab-id)]
    ;; Close the channel if it has a close method
    (when (and channel (instance? org.httpkit.server.AsyncChannel channel))
      (try
        (http/close channel)
        (catch Exception e
          (println "Error closing channel:" e)))))
  (swap! sse-channels dissoc tab-id)
  nil)

(defn get-sse-channel
  "Get the SSE channel for a tab."
  [tab-id]
  (get @sse-channels tab-id))

(defn register-render-fn!
  "Register a render function for a tab."
  [tab-id render-fn]
  (swap! render-fns assoc tab-id render-fn)
  nil)

(defn get-render-fn
  "Get the render function for a tab."
  [tab-id]
  (get @render-fns tab-id))

(defn format-datastar-fragment
  "Format HTML as a Datastar fragment event.

   Datastar expects Server-Sent Events in the format:
   event: datastar-fragment
   data: <html content>
   data: more content if needed

   (blank line to end event)"
  [html selector]
  (let [;; Datastar fragment format with merge strategy
        fragment (str "fragment " html " " selector)]
    (str "event: datastar-fragment\n"
         "data: " fragment "\n\n")))

(defn send-sse!
  "Send an SSE message to a tab's channel."
  [tab-id message]
  (when-let [channel (get-sse-channel tab-id)]
    (try
      (http/send! channel message false)
      true
      (catch Exception e
        (println "Error sending SSE to tab" tab-id ":" e)
        false))))

(defn render-and-send!
  "Render the view for a tab and send it via SSE.

   Constructs a request map with session and tab context,
   calls the render function, converts to HTML, and sends
   as a Datastar fragment."
  [session-id tab-id]
  (when-let [render-fn (get-render-fn tab-id)]
    (try
      ;; Build request context
      (let [req {:hyper/session-id session-id
                 :hyper/tab-id tab-id}
            ;; Get the actual *request* var from hyper.core at runtime
            request-var (requiring-resolve 'hyper.core/*request*)]
        ;; Use push-thread-bindings to set dynamic var at runtime
        (push-thread-bindings {request-var req})
        (try
          ;; Call render function with request context
          (let [hiccup-result (render-fn req)
                ;; Convert hiccup to HTML
                html (hiccup/html hiccup-result)
                ;; Format as Datastar fragment (replace entire body)
                fragment (format-datastar-fragment html "body")]
            ;; Send to client
            (send-sse! tab-id fragment))
          (finally
            (pop-thread-bindings))))
      (catch Exception e
        (println "Error rendering for tab" tab-id ":" e)
        (.printStackTrace e)))))

(defn setup-watchers!
  "Setup watchers on session and tab state to trigger re-renders.

   When state changes, re-render all connected tabs that use that state."
  [session-id tab-id]
  (let [watch-key (keyword (str "render-" tab-id))]

    ;; Watch session state
    (when-let [session-atom (state/get-session-atom session-id)]
      (add-watch session-atom watch-key
                 (fn [_k _r old-state new-state]
                   (when (not= old-state new-state)
                     (future
                       (render-and-send! session-id tab-id))))))

    ;; Watch tab state
    (when-let [tab-atom (state/get-tab-atom tab-id)]
      (add-watch tab-atom watch-key
                 (fn [_k _r old-state new-state]
                   (when (not= old-state new-state)
                     (future
                       (render-and-send! session-id tab-id)))))))
  nil)

(defn remove-watchers!
  "Remove watchers for a tab."
  [session-id tab-id]
  (let [watch-key (keyword (str "render-" tab-id))]
    (when-let [session-atom (state/get-session-atom session-id)]
      (remove-watch session-atom watch-key))
    (when-let [tab-atom (state/get-tab-atom tab-id)]
      (remove-watch tab-atom watch-key)))
  nil)

(defn cleanup-tab!
  "Clean up all resources for a tab."
  [session-id tab-id]
  (remove-watchers! session-id tab-id)
  (unregister-sse-channel! tab-id)
  (swap! render-fns dissoc tab-id)
  (state/cleanup-tab! tab-id)
  nil)

(defn get-connected-tabs
  "Get all currently connected tab IDs."
  []
  (keys @sse-channels))

(defn tab-count
  "Get the number of connected tabs."
  []
  (count @sse-channels))
