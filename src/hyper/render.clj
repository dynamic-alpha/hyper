(ns hyper.render
  "Rendering pipeline.

   Handles rendering hiccup to HTML and formatting Datastar SSE events."
  (:require [dev.onionpancakes.chassis.core :as c]
            [hyper.actions :as actions]
            [hyper.context :as context]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [taoensso.telemere :as t]))

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

(defn render-tab
  "Render the current view for a tab and return the SSE payload string.

   Returns the SSE payload (head update + body fragment) as a string,
   or nil if no render-fn is registered for the tab.

   On each render, re-resolves the render-fn from live routes so that:
   - Redefining the routes Var with new inline fns picks up the new function
   - Var-based :get handlers (e.g. #'my-page) automatically deref to the latest

   Title is resolved from route :title metadata via hyper.routes/resolve-title,
   supporting static strings, functions of the request, and deref-able values
   (cursors/atoms) so that title updates reactively with state changes."
  [app-state* session-id tab-id]
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
      (push-thread-bindings {#'context/*request*    req
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
          (str head-event body-fragment))
        (finally
          (pop-thread-bindings))))))

(defn format-connected-event
  "Format the initial SSE connected event for a tab."
  [tab-id]
  (str "event: connected\n"
       "data: {\"tab-id\":\"" tab-id "\"}\n\n"))
