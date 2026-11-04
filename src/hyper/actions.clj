(ns ^:no-doc hyper.actions
  "Action handling for hyper applications.

   Actions are server-side functions triggered by client interactions."
  (:require [clojure.set]
            [clojure.string :as str]
            [compact-uuids.core :as uuid]
            [hyper.context :as context]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Action expression building (client side of the boundary)
;; ---------------------------------------------------------------------------
;; Used by the hyper.core/action macro expansion at render time.

(defn sanitize-guard
  "Validate an action :when guard at runtime.  Returns the guard string,
   nil for nil/blank guards, and throws for anything else — a guard must
   evaluate to a Datastar expression string (e.g. from `expr` or a raw
   string)."
  [g]
  (cond
    (nil? g)    nil
    (string? g) (when-not (str/blank? g) g)
    :else       (throw (ex-info "action :when guard must evaluate to a string Datastar expression"
                                {:guard g}))))

(defn warn-deprecated-when!
  "Emit a deprecation warning for the `action :when` guard, steering callers to
   the `h/expr` composition.  Called at macro-expansion time."
  []
  (t/log! {:level :warn
           :id    :hyper.warn/action-when-deprecated
           :msg   (str "action :when is deprecated — gate the action with h/expr instead: "
                       "(h/expr (when <cond> (h/action ...))). "
                       "Client-param symbols like $key/$value work inside h/expr.")}))

(defn build-action-expr
  "Build the Datastar/JS expression string for an action: hyper's
   `@hyperAction(id, clientParams)` (see `hyper.server/action-script`), which
   queues the action with the page's signals, the render it was made on and
   the next sequence number, and posts the tab's queued actions so the
   server runs each once, in the order they were made.
   Optionally injects a custom Datastar expression to conditionally prevent
   the action.  base-path is unused: the client script knows the endpoint."
  [action-id used-params js _base-path]
  (let [js-injection (when js (str js " && "))]
    (if (empty? used-params)
      (str js-injection "@hyperAction('" action-id "')")
      (let [obj-entries (->> used-params
                             vals
                             (map (fn [{:keys [js key]}]
                                    (str key ":" js)))
                             (str/join ","))]
        (str js-injection "@hyperAction('" action-id "', {" obj-entries "})")))))

(defn register-action!
  "Register an action function and return its ID.
   Stores in app-state under [:actions action-id].

   When action-id is provided it is used as-is (deterministic actions).
   When omitted a random UUID is generated (legacy / dynamic actions).

   opts may include:
   - :as         — a human-readable name for the action, useful for testing
   - :upload-ref — the status ref of an upload action (see hyper.uploads)"
  ([app-state* session-id tab-id action-fn]
   (register-action! app-state* session-id tab-id action-fn
                     (str "act_" (uuid/str (java.util.UUID/randomUUID)))
                     nil))
  ([app-state* session-id tab-id action-fn action-id]
   (register-action! app-state* session-id tab-id action-fn action-id nil))
  ([app-state* session-id tab-id action-fn action-id opts]
   (let [action-data (cond-> {:fn         action-fn
                              :session-id session-id
                              :tab-id     tab-id}
                       (:as opts)         (assoc :as (:as opts))
                       (:upload-ref opts) (assoc :upload-ref (:upload-ref opts)))]
     (swap! app-state* (fn [state]
                         (-> state
                             (assoc-in [:actions action-id] action-data)
                             (update-in [:actions-by-tab tab-id]
                                        (fnil conj #{}) action-id))))
     (when-let [acc context/*registered-action-ids*]
       (swap! acc conj action-id))
     (when-let [acc context/*rendered-actions*]
       (swap! acc assoc action-id action-data)))
   action-id))

;; ---------------------------------------------------------------------------
;; Render versions
;; ---------------------------------------------------------------------------
;; Action ids count the actions in a render, so the same id means different
;; actions in different renders.  Each render that changes the page gets a
;; version, sent with the HTML (`data-hyper-render-version` on #hyper-app, or
;; on a reactive region's root for a partial render), and its actions are
;; kept under it.  A click names the version of the HTML it was made on, and
;; the region it was read from, so it runs the action that HTML showed.
;;
;; The page and each region keep their own renders, so one view's renders
;; never push out another's.  Versions are numbered across the tab.
;;
;; Kept under [:tabs tab-id :renders]:
;;   :last-version the latest version handed out
;;   :page         the full renders
;;   :regions      {region-id renders} the partial renders of each region
;; where the renders of a view are
;;   {:html html :version v :versions {version {:actions {action-id action-data}}}}
;; with :html and :version those of its latest render.
;;
;; Renders are dropped once the client reports having moved past them: the
;; page version it shows (`evict-renders-before!`), or a click on a newer
;; render of the same region (`evict-region-renders-before!`).

(def max-renders-per-view
  "The most render versions kept for one view (the page, or one region);
   beyond it the oldest are dropped, except the view's latest."
  64)

(defn- trim-versions [{:keys [versions version] :as view}]
  (if (<= (count versions) max-renders-per-view)
    view
    (let [drop (->> (keys versions)
                    (remove #{version})
                    sort
                    (take (- (count versions) max-renders-per-view)))]
      (update view :versions #(apply dissoc % drop)))))

(defn- drop-versions-before
  "`view` without its versions before `version`, keeping `keep-v`.  nil when
   no version is left."
  [view version keep-v]
  (let [view (update view :versions
                     #(into {} (remove (fn [[v _]] (and (< v version) (not= v keep-v)))) %))]
    (when (seq (:versions view))
      view)))

(defn- drop-renders-before
  "`renders` without the page's and the regions' versions before `version`.
   The page's latest version is kept."
  [{:keys [page regions] :as renders} version]
  (cond-> renders
    page    (update :page drop-versions-before version (:version page))
    regions (update :regions
                    #(into {}
                           (keep (fn [[region view]]
                                   (some->> (drop-versions-before view version nil)
                                            (vector region))))
                           %))))

(defn- update-renders
  "Apply `f` to tab `tab-id`'s renders, unless the tab is gone."
  [app-state* tab-id f]
  (swap! app-state*
         (fn [state]
           (if (contains? (:tabs state) tab-id)
             (update-in state [:tabs tab-id :renders] f)
             state))))

(defn record-render!
  "Store the `actions` a render registered under its version and return the
   version.  A render of the page (`region` nil) keeps the version of the
   page's previous render when it produced the same `html`; a render of a
   region keeps the region's previous version for the same `html` only while
   that version is newer than the page's.  Otherwise it gets a new version.
   A render of the page also drops the region renders made before the oldest
   page render still kept."
  [app-state* tab-id {:keys [region html actions]}]
  (let [k     (if region [:regions region] [:page])
        state (update-renders
                app-state* tab-id
                (fn [renders]
                  (let [{last-html :html last-v :version :as view} (get-in renders k)
                        page-v                                     (get-in renders [:page :version] 0)
                        last-version                               (:last-version renders 0)
                        same?                                      (and (= html last-html)
                                                                        (contains? (:versions view) last-v)
                                                                        (or (nil? region) (< page-v last-v)))
                        v                                          (if same? last-v (inc last-version))
                        renders                                    (-> renders
                                                                       (assoc :last-version (max v last-version))
                                                                       (assoc-in k (-> view
                                                                                       (assoc :html html :version v)
                                                                                       (assoc-in [:versions v :actions] actions)
                                                                                       trim-versions)))]
                    (if region
                      renders
                      ;; A region's render is only ever shown on top of an
                      ;; older page render, so once every page render before
                      ;; it is gone no click can name it.  This bounds what a
                      ;; tab that never reports its page version keeps.
                      (drop-renders-before renders (apply min (keys (get-in renders [:page :versions]))))))))]
    (get-in state (into [:tabs tab-id :renders] (conj k :version)))))

(defn evict-renders-before!
  "Drop the render versions before `version`, the page version the client
   has applied: a full render replaces the page and every region rendered
   before it, so no click can name them any more.  The latest page version
   is always kept."
  [app-state* tab-id version]
  (when version
    (update-renders app-state* tab-id #(drop-renders-before % version)))
  nil)

(defn evict-region-renders-before!
  "Drop region `region`'s render versions before `version`, which the client
   has applied to that region."
  [app-state* tab-id region version]
  (update-renders
    app-state* tab-id
    (fn [renders]
      (if-let [view (get-in renders [:regions region])]
        (assoc-in renders [:regions region] (drop-versions-before view version version))
        renders)))
  nil)

(defn find-rendered-action
  "The action `action-id` named in render `version` of region `region` of
   tab `tab-id`, or of its page when `region` is nil, or in its latest page
   render when `version` is nil.  nil when that render is gone."
  [app-state* tab-id region version action-id]
  (let [renders (get-in @app-state* [:tabs tab-id :renders])]
    (if (nil? version)
      (get-in renders [:page :versions (get-in renders [:page :version]) :actions action-id])
      (get-in renders (-> (if region [:regions region] [:page])
                          (into [:versions version :actions action-id]))))))

(defn sweep-stale-tab-actions!
  "Remove actions for a tab that were not re-registered during the last
   render cycle.  `live-action-ids` is the set of IDs collected by
   `*registered-action-ids*` during render.  Actions in that set are
   kept; any other action belonging to this tab is removed.

   This replaces the old cleanup-before-render pattern with a
   cleanup-after-render approach that eliminates the window where
   actions are missing."
  [app-state* tab-id live-action-ids]
  (swap! app-state* (fn [state]
                      (let [all-tab-ids (get-in state [:actions-by-tab tab-id] #{})
                            stale-ids   (clojure.set/difference all-tab-ids live-action-ids)]
                        (if (seq stale-ids)
                          (-> state
                              (update :actions #(apply dissoc % stale-ids))
                              (assoc-in [:actions-by-tab tab-id] live-action-ids))
                          (assoc-in state [:actions-by-tab tab-id] live-action-ids)))))
  nil)

(defn cleanup-tab-actions!
  "Remove all actions for a tab.  Used during tab disconnect."
  [app-state* tab-id]
  (swap! app-state* (fn [state]
                      (let [action-ids (get-in state [:actions-by-tab tab-id])]
                        (-> state
                            (update :actions #(apply dissoc % action-ids))
                            (update :actions-by-tab dissoc tab-id)))))
  nil)
