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
   - :as  — a human-readable name for the action, useful for testing"
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
                       (:as opts) (assoc :as (:as opts)))]
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
;; kept under it.  A click names the version of the HTML it was made on, so
;; it runs the action that HTML showed.
;;
;; Kept under [:tabs tab-id :renders]:
;;   :versions     {version {:actions {action-id action-data}}}
;;   :last-version the latest version handed out
;;   :page         {:html html :version v} of the latest full render
;;   :regions      {region-id {:html html :version v}} of the latest partial renders

(def max-renders-per-tab
  "The most render versions kept per tab; beyond it the oldest are dropped."
  64)

(defn- trim-versions [{:keys [versions page] :as renders}]
  (if (<= (count versions) max-renders-per-tab)
    renders
    (let [drop (->> (keys versions)
                    (remove #{(:version page)})
                    sort
                    (take (- (count versions) max-renders-per-tab)))]
      (update renders :versions #(apply dissoc % drop)))))

(defn record-render!
  "Store the `actions` a render registered under its version and return the
   version: the version of the previous render of the same place (`region`, or
   the page when nil) when it produced the same `html`, else a new one."
  [app-state* tab-id {:keys [region html actions]}]
  (let [k     (if region [:regions region] [:page])
        state (swap! app-state*
                     (fn [state]
                       (if-not (contains? (:tabs state) tab-id)
                         state
                         (update-in state [:tabs tab-id :renders]
                                    (fn [renders]
                                      (let [{last-html :html last-v :version} (get-in renders k)
                                            same? (and (= html last-html)
                                                       (contains? (:versions renders) last-v))
                                            v     (if same? last-v (inc (:last-version renders 0)))]
                                        (-> renders
                                            (assoc :last-version (max v (:last-version renders 0)))
                                            (assoc-in [:versions v :actions] actions)
                                            (assoc-in k {:html html :version v})
                                            trim-versions)))))))]
    (get-in state (into [:tabs tab-id :renders] (conj k :version)))))

(defn evict-renders-before!
  "Drop the render versions before `version`, the page version the client
   has applied: a full render replaces everything rendered before it, so no
   click can name them any more.  The latest page version is always kept."
  [app-state* tab-id version]
  (when version
    (swap! app-state*
           (fn [state]
             (if-not (contains? (:tabs state) tab-id)
               state
               (update-in state [:tabs tab-id :renders :versions]
                          (fn [versions]
                            (let [page-v (get-in state [:tabs tab-id :renders :page :version])]
                              (into {} (remove (fn [[v _]] (and (< v version) (not= v page-v))))
                                    versions))))))))
  nil)

(defn find-rendered-action
  "The action `action-id` named in render `version` of tab `tab-id`, or in its
   latest page render when version is nil.  nil when that render is gone."
  [app-state* tab-id version action-id]
  (let [renders (get-in @app-state* [:tabs tab-id :renders])
        version (or version (get-in renders [:page :version]))]
    (get-in renders [:versions version :actions action-id])))

(defn execute-action!
  "Execute an action by ID with error handling.
   When client-params are provided they are passed to the action fn."
  ([app-state* action-id]
   (execute-action! app-state* action-id nil))
  ([app-state* action-id client-params]
   (if-let [action-data (get-in @app-state* [:actions action-id])]
     (let [{:keys [fn]} action-data]
       (t/catch->error! :hyper.error/execute-action
                        (fn client-params))
       true)
     (do
       (t/log! {:level :warn
                :id    :hyper.error/action-not-found
                :data  {:hyper/action-id action-id}
                :msg   "Action not found"})
       (throw (ex-info "Action not found" {:hyper/action-id action-id}))))))

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
