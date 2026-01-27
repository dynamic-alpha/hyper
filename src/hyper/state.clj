(ns hyper.state
  "State management for hyper applications.

   Manages session and tab-scoped state using atoms and cursors.
   Cursors implement IRef for familiar Clojure semantics.

   State structure:
   {:sessions {session-id {:data {} :tabs #{tab-id}}}
    :tabs {tab-id {:data {} :session-id session-id :render-fn fn :sse-channel ch}}
    :actions {action-id {:fn fn :session-id sid :tab-id tid}}}")

(defn normalize-path
  "Convert keyword or vector to vector path."
  [path]
  (if (keyword? path)
    [path]
    (vec path)))

(deftype Cursor [parent-atom path-prefix path meta-data ^:volatile-mutable validator watches]
  clojure.lang.IRef
  (deref [_]
    (get-in @parent-atom (concat path-prefix (normalize-path path))))

  (setValidator [_ vf]
    (set! validator vf))

  (getValidator [_]
    validator)

  (getWatches [_]
    @watches)

  (addWatch [this key callback]
    (swap! watches assoc key callback)
    (let [full-path (concat path-prefix (normalize-path path))]
      (add-watch parent-atom key
                 (fn [k _r old-state new-state]
                   (let [old-val (get-in old-state full-path)
                         new-val (get-in new-state full-path)]
                     (when (not= old-val new-val)
                       (callback k this old-val new-val))))))
    this)

  (removeWatch [_this key]
    (swap! watches dissoc key)
    (remove-watch parent-atom key)
    _this)

  clojure.lang.IAtom
  (swap [_this f]
    (let [full-path (concat path-prefix (normalize-path path))]
      (swap! parent-atom update-in full-path f)
      (get-in @parent-atom full-path)))

  (swap [_this f arg]
    (let [full-path (concat path-prefix (normalize-path path))]
      (swap! parent-atom update-in full-path f arg)
      (get-in @parent-atom full-path)))

  (swap [_this f arg1 arg2]
    (let [full-path (concat path-prefix (normalize-path path))]
      (swap! parent-atom update-in full-path f arg1 arg2)
      (get-in @parent-atom full-path)))

  (swap [_this f arg1 arg2 args]
    (let [full-path (concat path-prefix (normalize-path path))]
      (apply swap! parent-atom update-in full-path f arg1 arg2 args)
      (get-in @parent-atom full-path)))

  (compareAndSet [_ oldv newv]
    (let [full-path (concat path-prefix (normalize-path path))]
      (loop []
        (let [current-state @parent-atom
              current-val (get-in current-state full-path)]
          (if (= current-val oldv)
            (if (compare-and-set! parent-atom
                                  current-state
                                  (assoc-in current-state full-path newv))
              true
              (recur))
            false)))))

  (reset [_this newv]
    (let [full-path (concat path-prefix (normalize-path path))]
      (swap! parent-atom assoc-in full-path newv)
      newv))

  clojure.lang.IMeta
  (meta [_] @meta-data)

  clojure.lang.IReference
  (alterMeta [_ f args]
    (apply swap! meta-data f args))

  (resetMeta [_ m]
    (reset! meta-data m)))

(defn create-cursor
  "Create a cursor pointing to a path in the parent atom.
   path-prefix is the base path, path is relative to that."
  [parent-atom path-prefix path]
  (->Cursor parent-atom path-prefix path (atom {}) nil (atom {})))

(defn session-cursor
  "Create a cursor to session state at the given path."
  [app-state* session-id path]
  (create-cursor app-state* [:sessions session-id :data] path))

(defn tab-cursor
  "Create a cursor to tab state at the given path."
  [app-state* tab-id path]
  (create-cursor app-state* [:tabs tab-id :data] path))

(defn init-state
  "Create initial app state structure."
  []
  {:sessions {}
   :tabs {}
   :actions {}})

(defn get-or-create-session!
  "Ensure session exists in app-state."
  [app-state* session-id]
  (swap! app-state* update-in [:sessions session-id]
         #(or % {:data {} :tabs #{}}))
  nil)

(defn get-or-create-tab!
  "Ensure tab exists in app-state and is linked to session."
  [app-state* session-id tab-id]
  (get-or-create-session! app-state* session-id)
  (swap! app-state* (fn [state]
                      (-> state
                          (update-in [:sessions session-id :tabs] (fnil conj #{}) tab-id)
                          (update-in [:tabs tab-id]
                                     #(or % {:data {}
                                            :session-id session-id
                                            :render-fn nil
                                            :sse-channel nil})))))
  nil)

(defn cleanup-tab!
  "Remove tab state and unlink from session."
  [app-state* tab-id]
  (let [session-id (get-in @app-state* [:tabs tab-id :session-id])]
    (swap! app-state* (fn [state]
                        (-> state
                            (update-in [:sessions session-id :tabs] disj tab-id)
                            (update :tabs dissoc tab-id)))))
  nil)

(defn cleanup-session!
  "Remove session state and all associated tabs."
  [app-state* session-id]
  (let [tab-ids (get-in @app-state* [:sessions session-id :tabs])]
    (doseq [tab-id tab-ids]
      (cleanup-tab! app-state* tab-id))
    (swap! app-state* update :sessions dissoc session-id))
  nil)
