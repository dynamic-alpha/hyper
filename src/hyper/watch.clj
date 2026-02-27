(ns hyper.watch
  "Reactive watch infrastructure for hyper tabs.

   Manages watchers on app-state (global, session, tab, route paths) and
   external Watchable sources.  When a watched value changes the tab's
   `trigger-render!` callback is invoked, signalling the renderer thread
   to produce a fresh SSE frame."
  (:require [hyper.protocols :as proto]
            [hyper.routes :as routes]))

;; ---------------------------------------------------------------------------
;; External source watching
;; ---------------------------------------------------------------------------

(defn- add-external-watch!
  "Watch an external Watchable source for a tab, tracking it under the
   given state-key (:watches or :route-watches). When the source changes,
   calls trigger-render! to signal the tab's renderer."
  [app-state* tab-id trigger-render! source prefix state-key]
  (let [watch-key (keyword (str prefix tab-id "-" (System/identityHashCode source)))]
    (proto/-add-watch source watch-key
                      (fn [_old _new]
                        (trigger-render!)))
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
   changes, signals the tab's renderer to re-render. The watch key
   is unique per tab-id so that multiple tabs each get their own re-render.
   Idempotent — calling with the same source and tab is safe."
  [app-state* tab-id trigger-render! source]
  (add-external-watch! app-state* tab-id trigger-render! source "hyper-ext-" :watches))

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
  [app-state* tab-id trigger-render!]
  (teardown-route-watches! app-state* tab-id)
  (let [app-state  @app-state*
        route-name (get-in app-state [:tabs tab-id :route :name])]
    (when route-name
      (let [route-index    (routes/live-route-index app-state*)
            global-watches (:global-watches app-state)]
        (when-let [watches (routes/find-route-watches route-index global-watches route-name)]
          (doseq [source watches]
            (add-external-watch! app-state* tab-id trigger-render! source "hyper-route-" :route-watches))))))
  nil)

;; ---------------------------------------------------------------------------
;; App-state watcher (global / session / tab / route paths)
;; ---------------------------------------------------------------------------

(defn setup-watchers!
  "Setup a single watcher on app-state that triggers re-renders when
   global, session, tab, or route state changes for this tab.
   Route URL sync is handled client-side via MutationObserver on data-hyper-url."
  [app-state* session-id tab-id trigger-render!]
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
                         (setup-route-watches! app-state* tab-id trigger-render!))))
                   ;; Re-render if any watched path changed
                   (when (or route-changed?
                             (not= (get-in old-state global-path)
                                   (get-in new-state global-path))
                             (not= (get-in old-state session-path)
                                   (get-in new-state session-path))
                             (not= (get-in old-state tab-path)
                                   (get-in new-state tab-path)))
                     (trigger-render!))))))
  nil)

(defn remove-watchers!
  "Remove the watcher for a tab."
  [app-state* tab-id]
  (remove-watch app-state* (keyword (str "render-" tab-id)))
  nil)
