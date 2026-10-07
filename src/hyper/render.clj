(ns ^:no-doc hyper.render
  "Rendering pipeline.

   Handles rendering hiccup to HTML and formatting Datastar SSE events."
  (:require [clojure.string :as string]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.component.bundle :as component.bundle]
            [hyper.context :as context]
            [hyper.lifecycle :as lifecycle]
            [hyper.routes :as routes]
            [hyper.subview :as subview]
            [hyper.utils :as utils]
            [taoensso.telemere :as t]))

(defn apply-render-middleware
  "Wrap a render-fn with a middleware chain.

   Middleware are functions of the form `(fn [handler] (fn [req] ...))`,
   identical to Ring middleware.  The chain is a sequence of middleware
   where earlier entries wrap outermost (execute first).

   Returns the wrapped render-fn, or the original render-fn if the chain
   is empty."
  [render-fn middleware-chain]
  (if (seq middleware-chain)
    (reduce (fn [h mw] (mw h)) render-fn (reverse middleware-chain))
    render-fn))

(defn- resolve-render-middleware
  "Build the combined render middleware chain for a tab's current route.

   Handler-level middleware (from app-state :render-middleware) wraps
   outermost, route-level middleware (from route data :render-middleware)
   wraps innermost.  Returns a flat sequence, or nil."
  [app-state* route-index route]
  (let [handler-mw (get @app-state* :render-middleware)
        route-mw   (when-let [route-name (:name route)]
                     (:render-middleware (get route-index route-name)))
        chain      (seq (concat handler-mw route-mw))]
    chain))

(defn register-render-fn!
  "Register a render function for a tab."
  [app-state* tab-id render-fn]
  (swap! app-state* assoc-in [:tabs tab-id :render-fn] render-fn)
  nil)

(defn- register-route-watches!
  "Register the current route's framework watches as mount-scoped, full-render
   subviews so they route through the unified subview engine: global :watches,
   the route's :watches metadata, and the auto-watched :get Var (live reload).

   Runs every full render — idempotent and keyed by source identity, so the
   watches are wired by `subview/setup-new-watches!` and torn down on page-view
   remount (navigation) / tab disconnect, exactly like user `h/watch!`."
  [app-state* tab-id route-index route]
  (when-let [route-name (:name route)]
    (doseq [source (routes/find-route-watches route-index
                                              (get @app-state* :global-watches)
                                              route-name)]
      (subview/register-watch! app-state* tab-id source))))

(defn get-render-fn
  "Get the render function for a tab."
  [app-state* tab-id]
  (get-in @app-state* [:tabs tab-id :render-fn]))

(defn format-datastar-fragment
  "Format HTML as a Datastar patch-elements SSE event.

   Datastar expects Server-Sent Events in the format:
   event: datastar-patch-elements
   data: elements <html content>

   For multi-line HTML content, emits multiple 'data: elements' lines
   that Datastar will concatenate. This prevents \n in HTML from
   prematurely terminating the SSE event."
  [html]
  (let [lines (string/split-lines html)]
    (str "event: datastar-patch-elements\n"
         (->> lines
              (map (fn [line] (str "data: elements " line "\n")))
              (apply str))
         "\n")))

(defn format-datastar-fragments
  "Format multiple HTML strings as separate Datastar patch-elements SSE events.
   Used for partial reactive renders where each fragment targets a different element."
  [html-strings]
  (apply str (map format-datastar-fragment html-strings)))

(defn fingerprint
  "Compute a short hex fingerprint for a value using Clojure's `hash`.
   Used to give each head element a stable, content-based identity so the
   SSE head-update JS can diff by fingerprint rather than blindly removing
   and re-appending every element on each render cycle."
  [v]
  (let [h (hash v)]
    (Long/toHexString (bit-and h 0xFFFFFFFF))))

(defn mark-head-elements
  "Add `{:data-hyper-head \"<fingerprint>\"}` to each top-level hiccup element
   in a resolved :head value.  The fingerprint is a content-based hash of the
   element's hiccup (excluding the data-hyper-head attr itself), giving each
   element a stable identity.

   On SSE re-renders, the head-update JS uses these fingerprints to diff
   against what's already in the DOM: unchanged elements are left alone,
   stale ones are removed, and only genuinely new/changed elements are
   appended.  This avoids FOUC and prevents third-party scripts from
   re-executing when their content hasn't changed.

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
                                           (cons {} rest))
                      fp                 (fingerprint (into [tag attrs] children))]
                  (into [tag (assoc attrs :data-hyper-head fp)] children))
                el))]
      (cond
        ;; Single element like [:style "..."]
        (and (vector? head-hiccup) (keyword? (first head-hiccup)))
        (mark-one head-hiccup)

        ;; Sequence of elements
        (sequential? head-hiccup)
        (mapv mark-one head-hiccup)

        :else head-hiccup))))

(defn- head-elements
  "Normalize a resolved :head value to a vector of elements.
   Handles nil, a single element ([:style ...]), and a seq/vector of elements."
  [h]
  (cond
    (nil? h)                                    []
    (and (vector? h) (keyword? (first h)))      [h]
    :else                                       (vec h)))

(defn- resolve-full-head
  "Resolve the user :head for a request and append the client-components
   bundle script tag (when components are registered).  Injected here — in
   the shared render pipeline — so both initial page loads and SSE head
   updates carry it, and a component registry change rotates the script URL
   (fingerprint diffing then hot-swaps the module).  Returns marked head
   hiccup or nil."
  [app-state* req]
  (let [user-head (routes/resolve-head (get @app-state* :head) req)
        comp-tag  (component.bundle/head-script-tag (get @app-state* :base-path ""))
        els       (cond-> (head-elements user-head)
                    comp-tag (conj comp-tag))]
    (when (seq els)
      (mark-head-elements els))))

(defn format-head-update
  "Build a self-removing <script> SSE event that imperatively updates
   the document title and diffs user-provided <head> elements.

   Why not morph?  Morphing <head> inner content via idiomorph can
   disconnect <style>/<link> elements from the browser's CSSOM — the
   nodes stay in the DOM but styles stop applying.  By using JS to
   selectively remove/append we guarantee the browser re-evaluates only
   the elements that actually changed.

   Each user-managed head element carries a `data-hyper-head` attribute
   whose value is a content-based fingerprint (short SHA-256 hex).  On
   each SSE cycle the emitted JS:
   1. Collects existing fingerprints from `[data-hyper-head]` nodes in
      the DOM.
   2. Removes any DOM node whose fingerprint is NOT in the new set
      (stale element).
   3. Appends only elements whose fingerprint is NOT already in the DOM
      (new or changed element).

   This avoids FOUC and prevents third-party scripts from re-executing
   when their content hasn't changed (issue #42).

   The script tag uses Datastar's `mode append` + `selector body` pattern
   (the SDK's ExecuteScript convention) with `data-effect=\"el.remove()\"`
   so it auto-cleans after execution."
  [title extra-head-html]
  (let [js (str "(function(){"
                "document.title='" (utils/escape-js-string (or title "Hyper App")) "';"
                (when (seq extra-head-html)
                  (str
                    ;; Build a set of new fingerprints from the rendered elements
                    "var tmp=document.createElement('div');"
                    "tmp.innerHTML='" (utils/escape-js-string extra-head-html) "';"
                    "var newFps={};"
                    "for(var i=0;i<tmp.children.length;i++){"
                    "var fp=tmp.children[i].getAttribute('data-hyper-head');"
                    "if(fp)newFps[fp]=tmp.children[i];"
                    "}"
                    ;; Remove stale elements (fingerprint not in new set)
                    "document.querySelectorAll('[data-hyper-head]').forEach(function(el){"
                    "var fp=el.getAttribute('data-hyper-head');"
                    "if(!newFps[fp])el.remove();"
                    "else delete newFps[fp];"  ;; already in DOM, skip
                    "});"
                    ;; Append only genuinely new/changed elements.  Script
                    ;; elements must be recreated via createElement — nodes
                    ;; parsed from innerHTML are flagged non-executable, so a
                    ;; plain appendChild would silently never run them (e.g.
                    ;; the client-components bundle after a REPL redefine).
                    "Object.keys(newFps).forEach(function(fp){"
                    "var el=newFps[fp];"
                    "if(el.tagName==='SCRIPT'){"
                    "var s=document.createElement('script');"
                    "for(var j=0;j<el.attributes.length;j++){"
                    "s.setAttribute(el.attributes[j].name,el.attributes[j].value);"
                    "}"
                    "s.textContent=el.textContent;"
                    "el=s;"
                    "}"
                    "document.head.appendChild(el);"
                    "});"))
                "})();")]
    (str "event: datastar-patch-elements\n"
         "data: mode append\n"
         "data: selector body\n"
         "data: elements <script data-effect=\"el.remove()\">" js "</script>\n\n")))

(defn unwrap-body
  "Strip a top-level `[:body ...]` wrapper from user hiccup.

   Hyper owns the `<body>` tag — it carries `data-init` (for the Datastar
   SSE connection) and the SPA navigation scripts, with a `<div id=\"hyper-app\">`
   inside it as the morph target for SSE re-renders.

   When a render function returns `[:body ...]`, the nested `<body>` is
   tolerated by browsers on initial load, but on SSE re-renders idiomorph
   replaces `#hyper-app` content with HTML containing a `<body>`, which
   corrupts the DOM and stops further updates (see issue #40).

   This function detects the pattern, logs a warning, and returns only
   the children so downstream code always receives hiccup without `<body>`.
   Any attributes on the `[:body]` tag are discarded (the warning tells the
   developer to remove it)."
  [hiccup]
  (if (and (vector? hiccup)
           (= :body (first hiccup)))
    (do
      (t/log! {:level :warn
               :id    :hyper.warn/body-in-hiccup
               :msg   "Render function returned [:body ...] — Hyper owns the <body> tag. The [:body] wrapper has been stripped; return only the inner content."})
      (let [[_ maybe-attrs & more] hiccup
            children               (if (map? maybe-attrs)
                                     (vec more)
                                     (vec (cons maybe-attrs more)))
            children               (vec (remove nil? children))]
        (if (= 1 (count children))
          (first children)
          children)))
    hiccup))

(defn safe-render
  "Safely render a view with an error boundary.

   On exception, logs via telemere and delegates UI rendering to
   `render-error-fn`, a function `(fn [error req] -> hiccup)`.  The fn is
   invoked as an `IFn`, so a Var pointing at a renderer (e.g.
   `#'my.app/error-page`) works transparently and picks up redefinitions
   without restarting the server."
  [render-fn req render-error-fn]
  (try
    (render-fn req)
    (catch Exception e
      (t/error! {:id :hyper.error/render} e)
      (render-error-fn e req))))

(defn render-tab
  "Render the current view for a tab and return the rendered data.

   Returns nil when no render-fn is registered for the tab, or one of:

   - A Ring response map (when the render-fn returns a map with :status),
     passed through as-is for redirects, error responses, etc.

   - A render result map with pre-serialized HTML strings:
       :title             - resolved page title string, or nil
       :head-html         - HTML string of marked <head> elements, or nil
       :body-html         - HTML string of the rendered page body
       :url               - current route URL string, or nil
       :declared-signals  - vector of signal declaration maps for HTML injection

   Binds `context/*request*` and `context/*action-idx*` for the duration
   of both rendering and HTML serialization, so lazy hiccup sequences
   (from `for`, `map`, etc.) that read `*request*` see the correct
   bindings when realized by Chassis.

   An optional base-req (Ring request map) can be provided for initial
   page loads so the render function sees the full Ring request context
   (headers, cookies, query-params, etc.).  On SSE re-renders, base-req
   is nil and a synthetic request is built with only Hyper context keys.
   Accessing HTTP-only keys (e.g. :cookies, :headers) on the synthetic
   request returns nil and logs a warning, alerting developers to use
   middleware + cursors for data that must survive re-renders.

   On each render, re-resolves the render-fn from live routes so that:
   - Redefining the routes Var with new inline fns picks up the new function
   - Var-based :get handlers (e.g. #'my-page) automatically deref to the latest

   Title is resolved from route :title metadata via hyper.routes/resolve-title,
   supporting static strings, functions of the request, and deref-able values
   (cursors/atoms) so that title updates reactively with state changes."
  ([app-state* session-id tab-id]
   (render-tab app-state* session-id tab-id nil))
  ([app-state* session-id tab-id base-req]
   (when-let [stored-render-fn (get-render-fn app-state* tab-id)]
     (let [router      (get @app-state* :router)
           route       (get-in @app-state* [:tabs tab-id :route])
           route-index (routes/live-route-index app-state*)
           ;; Re-resolve render-fn from live routes so route Var redefs
           ;; and Var-based handlers always use the latest function.
           render-fn   (if-let [route-name (:name route)]
                         (let [fresh-fn (when (seq route-index)
                                          (routes/find-render-fn route-index route-name))]
                           (if fresh-fn
                             (do
                               (when (not= fresh-fn stored-render-fn)
                                 (register-render-fn! app-state* tab-id fresh-fn))
                               fresh-fn)
                             stored-render-fn))
                         stored-render-fn)
           url         (when route
                         (routes/route-url router route))
           tab-env     (get-in @app-state* [:tabs tab-id :env])
           base        (if base-req
                         base-req
                         (utils/warn-on-access-map {}))
           ;; Resolve env up front from base-req (regular map) rather than
           ;; reading it back off the synthetic request — reading a missing
           ;; key through the warn-on-access map would itself log a warning.
           env         (or (:hyper/env base-req) tab-env)
           req         (cond-> base
                         true    (assoc :hyper/session-id session-id
                                        :hyper/tab-id     tab-id
                                        :hyper/app-state  app-state*)
                         router  (assoc :hyper/router router)
                         route   (assoc :hyper/route route)
                         ;; Always seed :hyper/env (a reserved, framework-propagated
                         ;; key) so reading it via h/env never trips the
                         ;; warn-on-access map.  The value may be nil — that's
                         ;; fine, it matches the silent nil returned on initial
                         ;; page load.
                         true    (assoc :hyper/env env)
                         true    (dissoc :reitit.core/match))]
       (push-thread-bindings (context/render-bindings req app-state*))
       (try
         (let [mw-chain        (resolve-render-middleware app-state* route-index route)
               wrap-mw         #(apply-render-middleware % mw-chain)
               render-error-fn (get @app-state* :render-error)
               raw-body        (lifecycle/render-page app-state* tab-id render-fn req
                                                      render-error-fn wrap-mw)
               ;; Framework route-level watches (global :watches, route :watches,
               ;; auto-watched :get Var), registered AFTER render-page so they
               ;; survive a remount's mount-scoped teardown.  Idempotent, keyed
               ;; by source identity; wired later by setup-new-watches!.
               _               (register-route-watches! app-state* tab-id route-index route)]
           ;; Ring response passthrough - render-fn returned a redirect,
           ;; error, or other non-hiccup response; pass it through as-is.
           (if (and (map? raw-body) (:status raw-body))
             raw-body
             ;; Serialize body HTML first - this forces lazy hiccup
             ;; sequences (for, map, etc.) which may call h/action and
             ;; register actions during realization.  We must read
             ;; *registered-action-ids* AFTER serialization so the
             ;; accumulator captures every action the render produced.
             (let [body        (unwrap-body raw-body)
                   body-html   (if (vector? body)
                                 (c/html body)
                                 (apply str (map c/html body)))
                   title-spec  (when (and (seq route-index) route)
                                 (routes/find-route-title route-index (:name route)))
                   title       (routes/resolve-title title-spec req)
                   head        (resolve-full-head app-state* req)
                   declared    @context/*declared-signals*
                   action-ids  @context/*registered-action-ids*
                   subview-ids @context/*registered-subview-ids*]
               ;; Flush default-value inits and any other cursor writes
               ;; from the overlay to the live atom in a single swap.
               (context/flush-overlay! app-state*)
               {:title                  title
                :head-html              (some-> head c/html)
                :body-html              body-html
                :url                    url
                :declared-signals       declared
                :registered-action-ids  action-ids
                :registered-subview-ids subview-ids})))
         (finally
           (pop-thread-bindings)))))))

(defn format-connected-event
  "Format the initial SSE connected event for a tab."
  [tab-id]
  (str "event: connected\n"
       "data: {\"tab-id\":\"" tab-id "\"}\n\n"))

(defn format-heartbeat
  "Format an SSE keepalive as a comment line.  Lines beginning with ':' are
   comments per the SSE spec and are ignored by every conformant parser
   (including Datastar), so this carries no payload — its only job is to keep
   the stream warm through idle-timeout proxies and to surface a dead/half-open
   channel the next time the renderer writes."
  []
  ": hb\n\n")
