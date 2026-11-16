(ns ^:no-doc hyper.watch
  "Internal-state reactivity for hyper tabs.

   Two responsibilities live here:

   - The **app-state watcher** (`setup-watchers!`): a single watch on
     `app-state*` that triggers a re-render when this tab's global / session /
     tab / route / signal state changes.  This is the cursor & signal
     reactivity primitive.

   - **Source reference counting** (`retain-source!` / `release-source!`):
     shared by `hyper.subview`, which now owns ALL external-source watching
     (reactive regions, user `h/watch!`, route-level `:watches`, and the
     framework routes/head Var + component-registry watches).  When the last
     consumer releases a source its `-dispose` is called."
  (:require [hyper.protocols :as proto]))

;; ---------------------------------------------------------------------------
;; Source reference counting (shared with hyper.subview)
;; ---------------------------------------------------------------------------

(defn retain-source!
  "Increment the reference count for a watched source."
  [app-state* source]
  (swap! app-state* update-in [:source-refcounts source] (fnil inc 0))
  nil)

(defn release-source!
  "Decrement the reference count for a watched source.  When the count
   reaches zero, calls -dispose and removes the refcount entry."
  [app-state* source]
  (let [new-state (swap! app-state* (fn [state]
                                      (let [n (dec (get-in state [:source-refcounts source] 1))]
                                        (if (pos? n)
                                          (assoc-in state [:source-refcounts source] n)
                                          (-> state
                                              (update :source-refcounts dissoc source))))))]
    (when-not (contains? (:source-refcounts new-state) source)
      (proto/-dispose source)))
  nil)

;; ---------------------------------------------------------------------------
;; App-state watcher (global / session / tab / route / signals paths)
;; ---------------------------------------------------------------------------

(defn setup-watchers!
  "Setup a single watcher on app-state that triggers re-renders when
   global, session, tab, route, or signal state changes for this tab.
   The session is the tab's current one: a session that gets a new id is
   followed, and re-renders the tab so its actions use the new id.
   Route URL sync is handled client-side via MutationObserver on data-hyper-url.

   External-source watches (routes/head Vars, component registry, user
   `h/watch!`, route-level `:watches`) are not managed here — they are subviews
   (see `hyper.subview`), wired/torn down through the subview lifecycle."
  [app-state* tab-id trigger-render!]
  (let [watch-key    (keyword (str "render-" tab-id))
        global-path  [:global]
        session      (fn [state]
                       (let [session-id (get-in state [:tabs tab-id :session-id])]
                         [session-id (get-in state [:sessions session-id :data])]))
        tab-path     [:tabs tab-id :data]
        route-path   [:tabs tab-id :route]
        signals-path [:tabs tab-id :signals]]
    (add-watch app-state* watch-key
               (fn [_k _r old-state new-state]
                 (let [route-changed? (let [old-route (get-in old-state route-path)
                                            new-route (get-in new-state route-path)]
                                        (and new-route (not= old-route new-route)))]
                   ;; Re-render if the route or any watched path changed
                   ;; (including signals).  Watch swapping on navigation is
                   ;; handled by the subview engine (mount-scoped teardown on
                   ;; page-view remount), not here.
                   (when (or route-changed?
                             (not= (get-in old-state global-path)
                                   (get-in new-state global-path))
                             (not= (session old-state)
                                   (session new-state))
                             (not= (get-in old-state tab-path)
                                   (get-in new-state tab-path))
                             (not= (get-in old-state signals-path)
                                   (get-in new-state signals-path)))
                     (trigger-render!))))))
  nil)

(defn remove-watchers!
  "Remove the app-state watcher for a tab."
  [app-state* tab-id]
  (remove-watch app-state* (keyword (str "render-" tab-id)))
  nil)
