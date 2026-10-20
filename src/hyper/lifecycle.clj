(ns ^:no-doc hyper.lifecycle
  "View lifecycle vocabulary shared across page handlers (and, later,
   reactive sub-regions and async workers).

   Hyper page handlers climb a Reagent-style form ladder, chosen by the
   ownership question \"what does this view own?\":

   - **form-1** — owns nothing.  A pure `(fn [req] -> hiccup)`.  This is the
     default and needs nothing from this namespace.

   - **form-2** — owns only framework-managed subscriptions (auto-cleaned).
     The handler returns a *setup* closure that runs once per mount and
     returns the pure render fn:

       (fn [req]                 ;; setup — runs once, effects allowed
         (h/watch! some-source)
         (let [data* (h/tab-cursor :data)]
           (fn [req]             ;; render — runs every render, must be pure
             [:div @data*])))

   - **form-3** — owns an external resource that needs explicit teardown.
     The handler returns a `View` (via `view`) whose `:mount` allocates the
     resource and *returns* it; the framework threads that value into
     `:render` and `:unmount`:

       (h/view
         {:mount   (fn []         (open-conn! ...))
          :render  (fn [conn req] [:div (query conn)])
          :unmount (fn [conn]     (close! conn))})

   There is deliberately no per-view mutable `ctx` slot: the server is
   always in declarative-rerender mode, so a resource opened at mount,
   read during renders, and closed at unmount is simply a value threaded
   immutably through the lifecycle."
  (:require [hyper.context :as context]
            [hyper.subview :as subview]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; form-3 — the View record
;; ---------------------------------------------------------------------------

(defrecord View [mount render unmount])

(defn view
  "Construct a form-3 `View` from a map.

   Keys:
   - :render  (required) `(fn [resource req] -> hiccup)`.  `resource` is the
              value returned by `:mount` (nil when there is no `:mount`).
   - :mount   (optional) `(fn [] -> resource)`.  Runs once when the view
              mounts.  Its return value is the resource threaded into
              `:render` and `:unmount`.
   - :unmount (optional) `(fn [resource] -> any)`.  Runs once when the view
              unmounts (route change, re-mount, or tab disconnect).

   A `View` is a distinct record type — not a bare map — so page dispatch
   can tell it apart unambiguously from a hiccup vector, a form-2 render
   fn, or a Ring response map."
  [{:keys [mount render unmount] :as spec}]
  (when-not (ifn? render)
    (throw (ex-info "hyper.lifecycle/view requires a :render function"
                    {:spec spec})))
  (->View mount render unmount))

(defn view?
  "True when x is a form-3 `View`."
  [x]
  (instance? View x))

;; ---------------------------------------------------------------------------
;; Page-view lifecycle dispatch (form-1 / form-2 / form-3)
;; ---------------------------------------------------------------------------
;;
;; A page's resolved lifecycle is stored at [:tabs tab-id :page-view]:
;;
;;   {:form      :form-1 | :form-2 | :form-3
;;    :source-fn <the route handler at mount> ;; mount-boundary identity
;;    :render-fn (fn [req] -> hiccup)         ;; the pure per-render fn
;;    :resource  <form-3 mount return value>  ;; form-3 only
;;    :unmount   (fn [resource] ...)}         ;; form-3 only
;;
;; The store lives directly on app-state* (a raw-atom swap!, like the
;; :subviews store) under a key NOT watched by setup-watchers!,
;; so writing it during a render never triggers a re-render loop.

(defn- safe
  "Call a 1-arg render fn with an error boundary, delegating to render-error-fn
   (an IFn `(fn [error req] -> hiccup)`) on failure — mirrors render/safe-render."
  [f render-error-fn req]
  (try
    (f req)
    (catch Exception e
      (t/error! {:id :hyper.error/render} e)
      (render-error-fn e req))))

(defn- run-mount
  "Run a form-3 :mount (if any) with effects allowed (guard disabled),
   returning the resource value threaded into render/unmount."
  [^View v]
  (binding [context/*render-guard* nil]
    (when-let [m (:mount v)] (m))))

(defn run-unmount!
  "Run a stored page-view's :unmount (form-3 only), with effects allowed.
   Swallows and logs exceptions so teardown never breaks a render or
   disconnect cleanup."
  [stored]
  (when-let [u (:unmount stored)]
    (binding [context/*render-guard* nil]
      (try
        (u (:resource stored))
        (catch Throwable e
          (t/error! {:id :hyper.error/view-unmount} e))))))

(defn- store-page-view!
  [app-state* tab-id m]
  (swap! app-state* assoc-in [:tabs tab-id :page-view] m)
  nil)

(defn teardown-page-view!
  "Tear down a tab's page-view (running a form-3 :unmount) and remove it.
   Called from cleanup-tab! on disconnect."
  [app-state* tab-id]
  (when-let [stored (get-in @app-state* [:tabs tab-id :page-view])]
    (run-unmount! stored)
    (swap! app-state* update-in [:tabs tab-id] dissoc :page-view))
  nil)

(defn- resolve-and-render!
  "(Re)resolve a page handler: call it once (under the deferred guard already
   bound by render-bindings) to discover its form, then run the terminal
   render.  `wrap-mw` wraps a 1-arg render fn with the render-middleware
   chain so middleware runs on every render (form-1 body, form-2 inner,
   form-3 :render) just as it does today."
  [app-state* tab-id handler req render-error-fn wrap-mw]
  (let [;; Stamped onto the stored page-view; the mount boundary compares it
        ;; (see render-page).
        path-params (get-in @app-state* [:tabs tab-id :route :path-params])
        raw         (safe (wrap-mw handler) render-error-fn req)]
    (cond
      ;; form-3 — handler returned a View. The detection call merely
      ;; constructed it (pure); discard any buffered effects, run :mount
      ;; once to capture the resource, then render.
      (view? raw)
      (let [resource (run-mount raw)
            render'  (fn [r] ((:render raw) resource r))]
        (store-page-view! app-state* tab-id
                          {:form        :form-3
                           :source-fn   handler
                           :path-params path-params
                           :render-fn   render'
                           :resource    resource
                           :unmount     (:unmount raw)})
        (context/guard-discard!)
        (safe (wrap-mw render') render-error-fn req))

      ;; form-2 — handler returned its inner render fn. The detection call
      ;; WAS the setup phase (effects legal); discard buffered effects and
      ;; render via the inner fn.
      (fn? raw)
      (do
        (store-page-view! app-state* tab-id
                          {:form        :form-2
                           :source-fn   handler
                           :path-params path-params
                           :render-fn   raw})
        (context/guard-discard!)
        (safe (wrap-mw raw) render-error-fn req))

      ;; form-1 — handler returned hiccup (or a Ring map). The detection
      ;; call WAS the render body, so flush buffered effects as warnings.
      :else
      (do
        (store-page-view! app-state* tab-id
                          {:form :form-1 :source-fn handler :path-params path-params})
        (context/guard-flush-and-activate!)
        raw))))

(defn render-page
  "Resolve and render a page handler through the form-1/2/3 ladder, managing
   the per-tab page-view lifecycle and the render purity guard.

   Returns the raw render result — a hiccup vector/seq, or a Ring response
   map (when a handler returns `{:status …}`), which the caller passes
   through unchanged.

   Arguments:
   - app-state*      — the app-state atom
   - tab-id          — the tab being rendered
   - handler         — the route's render fn (form-1/2/3 producer), already
                       re-resolved from live routes by the caller
   - req             — the request/context map
   - render-error-fn — `(fn [error req] -> hiccup)` error boundary
   - wrap-mw         — `(fn [render-fn] -> render-fn)` applying the
                       render-middleware chain (use `identity` for none)

   The mount boundary is keyed on `[handler path-params]`: a page (re)mounts
   on first render, on handler-identity change (navigation, Var redefinition),
   and on a route **path-param** change (the same handler serving a different
   path-param value).  A superseded mount is unmounted first — its form-3 resource and
   mount-scoped subviews (`h/watch!`, `h/spawn!`, async) are torn down, and the
   new mount re-acquires whatever is scoped to the new path-params.  Query-param
   changes (filters, pagination — cursor-backed view state) re-render in place.
   form-1 re-runs its body every render."
  [app-state* tab-id handler req render-error-fn wrap-mw]
  (let [wrap-mw     (or wrap-mw identity)
        handler     (if (var? handler) @handler handler)
        stored      (get-in @app-state* [:tabs tab-id :page-view])
        path-params (get-in @app-state* [:tabs tab-id :route :path-params])
        same-mount? (and stored
                         (identical? handler (:source-fn stored))
                         (= path-params (:path-params stored)))]
    (if (and same-mount? (not= :form-1 (:form stored)))
      ;; Fast path — form-2/3 already mounted: render via the cached pure fn.
      (do
        (context/guard-discard!)
        (safe (wrap-mw (:render-fn stored)) render-error-fn req))
      ;; (Re)resolve — first render, form-1, or remount (handler/path-params
      ;; changed).
      (do
        (when (and stored (not same-mount?))
          ;; Remount: tear down the old mount's form-3 resource and mount-scoped
          ;; subviews (h/watch!, h/spawn!, async); the new mount re-registers.
          (run-unmount! stored)
          (subview/teardown-mount-scoped! app-state* tab-id))
        (resolve-and-render! app-state* tab-id handler req render-error-fn wrap-mw)))))
