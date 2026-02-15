(ns hyper.render
  "Rendering pipeline and SSE management.

   Handles rendering hiccup to HTML and sending updates via
   Server-Sent Events using Datastar fragment format."
  (:require [hiccup.core :as hiccup]
            [org.httpkit.server :as http]
            [hyper.state :as state]
            [taoensso.telemere :as t]))

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
      (t/catch->error! :hyper.error/close-sse-channel
        (http/close channel))))
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
  "Format HTML as a Datastar patch-elements SSE event.

   Datastar expects Server-Sent Events in the format:
   event: datastar-patch-elements
   data: elements <html content>

   (blank line to end event)"
  [html]
  (str "event: datastar-patch-elements\n"
       "data: elements " html "\n\n"))

(defn send-sse!
  "Send an SSE message to a tab's channel."
  [app-state* tab-id message]
  (when-let [channel (get-sse-channel app-state* tab-id)]
    (or (t/catch->error! :hyper.error/send-sse
          (http/send! channel message false))
        false)))

(defn render-error-fragment
  "Render an error message as a fragment."
  [error]
  (hiccup/html
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
   Stamps the current route URL as a data-hyper-url attribute on the
   #hyper-app div so the client-side MutationObserver can sync the
   browser URL bar via replaceState."
  [app-state* session-id tab-id request-var]
  (when-let [render-fn (get-render-fn app-state* tab-id)]
    (let [router (get @app-state* :router)
          route (get-in @app-state* [:tabs tab-id :route])
          current-url (when route
                        (state/build-url (:path route) (:query-params route)))
          req (cond-> {:hyper/session-id session-id
                       :hyper/tab-id tab-id
                       :hyper/app-state app-state*}
                router (assoc :hyper/router router))]
      (push-thread-bindings {request-var req})
      (try
        (let [hiccup-result (safe-render render-fn req)
              div-attrs (cond-> {:id "hyper-app"}
                          current-url (assoc :data-hyper-url current-url))
              html (hiccup/html [:div div-attrs hiccup-result])
              fragment (format-datastar-fragment html)]
          (send-sse! app-state* tab-id fragment))
        (finally
          (pop-thread-bindings))))))

;; Render throttling to prevent excessive re-renders
;; Default to 16ms (~60fps)
(def ^:dynamic *render-throttle-ms* 16)

(defn should-render?
  "Check if enough time has passed since last render for this tab.
   Uses throttling to prevent render thrashing on rapid state updates.
   Stores last render time in app-state* at [:tabs tab-id :last-render-ms]."
  [app-state* tab-id]
  (let [now (System/currentTimeMillis)
        last-render (get-in @app-state* [:tabs tab-id :last-render-ms] 0)
        elapsed (- now last-render)]
    (when (>= elapsed *render-throttle-ms*)
      (swap! app-state* assoc-in [:tabs tab-id :last-render-ms] now)
      true)))

(defn throttled-render-and-send!
  "Render and send with throttling."
  [app-state* session-id tab-id request-var]
  (when (should-render? app-state* tab-id)
    (render-and-send! app-state* session-id tab-id request-var)))

(defn setup-watchers!
  "Setup watchers on global, session, tab, and route state to trigger re-renders.
   Route URL sync is handled client-side via MutationObserver on data-hyper-url."
  [app-state* session-id tab-id request-var]
  (let [watch-key (keyword (str "render-" tab-id))
        global-path [:global]
        session-path [:sessions session-id :data]
        tab-path [:tabs tab-id :data]
        route-path [:tabs tab-id :route]
        trigger-render (fn [_k _r old-state new-state path]
                         (when (not= (get-in old-state path)
                                     (get-in new-state path))
                           (future
                             (throttled-render-and-send! app-state* session-id tab-id request-var))))]

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

    ;; Watch route changes to trigger re-render.
    ;; The rendered fragment includes data-hyper-url on #hyper-app,
    ;; and the client-side MutationObserver handles replaceState.
    (add-watch app-state* (keyword (str "route-" watch-key))
               (fn [_k _r old-state new-state]
                 (let [old-route (get-in old-state route-path)
                       new-route (get-in new-state route-path)]
                   (when (and new-route (not= old-route new-route))
                     (future
                       (throttled-render-and-send! app-state* session-id tab-id request-var)))))))
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
  (unregister-sse-channel! app-state* tab-id)
  ;; Use requiring-resolve for hyper.actions to avoid circular dependency
  ;; (actions requires nothing from render, but render is loaded first)
  (let [cleanup-actions! (requiring-resolve 'hyper.actions/cleanup-tab-actions!)]
    (cleanup-actions! app-state* tab-id))
  (state/cleanup-tab! app-state* tab-id)
  nil)
