(ns hyper.render
  "Rendering pipeline and SSE management.

   Handles rendering hiccup to HTML and sending updates via
   Server-Sent Events using Datastar fragment format."
  (:require [hiccup.core :as hiccup]
            [org.httpkit.server :as http]))

(defn register-sse-channel!
  "Register an SSE channel for a tab."
  [app-state* tab-id channel]
  (swap! app-state* assoc-in [:tabs tab-id :sse-channel] channel)
  nil)

(defn unregister-sse-channel!
  "Unregister an SSE channel for a tab."
  [app-state* tab-id]
  (when-let [channel (get-in @app-state* [:tabs tab-id :sse-channel])]
    (when (and channel (instance? org.httpkit.server.AsyncChannel channel))
      (try
        (http/close channel)
        (catch Exception e
          (println "Error closing channel:" e)))))
  (swap! app-state* assoc-in [:tabs tab-id :sse-channel] nil)
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
  "Format HTML as a Datastar fragment event.

   Datastar expects Server-Sent Events in the format:
   event: datastar-fragment
   data: <html content>

   (blank line to end event)"
  [html selector]
  (let [fragment (str "fragment " html " " selector)]
    (str "event: datastar-fragment\n"
         "data: " fragment "\n\n")))

(defn send-sse!
  "Send an SSE message to a tab's channel."
  [app-state* tab-id message]
  (when-let [channel (get-sse-channel app-state* tab-id)]
    (try
      (http/send! channel message false)
      true
      (catch Exception e
        (println "Error sending SSE to tab" tab-id ":" e)
        false))))

(defn render-and-send!
  "Render the view for a tab and send it via SSE."
  [app-state* session-id tab-id request-var]
  (when-let [render-fn (get-render-fn app-state* tab-id)]
    (try
      (let [req {:hyper/session-id session-id
                 :hyper/tab-id tab-id
                 :hyper/app-state app-state*}]
        (push-thread-bindings {request-var req})
        (try
          (let [hiccup-result (render-fn req)
                html (hiccup/html hiccup-result)
                fragment (format-datastar-fragment html "body innerHTML")]
            (send-sse! app-state* tab-id fragment))
          (finally
            (pop-thread-bindings))))
      (catch Exception e
        (println "Error rendering for tab" tab-id ":" e)
        (.printStackTrace e)))))

(defn setup-watchers!
  "Setup watchers on session and tab state to trigger re-renders."
  [app-state* session-id tab-id request-var]
  (let [watch-key (keyword (str "render-" tab-id))
        session-path [:sessions session-id :data]
        tab-path [:tabs tab-id :data]]

    ;; Watch session data
    (add-watch app-state* (keyword (str "session-" watch-key))
               (fn [_k _r old-state new-state]
                 (when (not= (get-in old-state session-path)
                            (get-in new-state session-path))
                   (future
                     (render-and-send! app-state* session-id tab-id request-var)))))

    ;; Watch tab data
    (add-watch app-state* (keyword (str "tab-" watch-key))
               (fn [_k _r old-state new-state]
                 (when (not= (get-in old-state tab-path)
                            (get-in new-state tab-path))
                   (future
                     (render-and-send! app-state* session-id tab-id request-var))))))
  nil)

(defn remove-watchers!
  "Remove watchers for a tab."
  [app-state* tab-id]
  (let [watch-key (keyword (str "render-" tab-id))]
    (remove-watch app-state* (keyword (str "session-" watch-key)))
    (remove-watch app-state* (keyword (str "tab-" watch-key))))
  nil)

(defn cleanup-tab!
  "Clean up all resources for a tab."
  [app-state* tab-id]
  (remove-watchers! app-state* tab-id)
  (unregister-sse-channel! app-state* tab-id)
  (let [actions (requiring-resolve 'hyper.actions/cleanup-tab-actions!)
        state-cleanup (requiring-resolve 'hyper.state/cleanup-tab!)]
    (actions app-state* tab-id)
    (state-cleanup app-state* tab-id))
  nil)
