(ns hyper.state
  "State management for hyper applications.

   Manages session and tab-scoped state using atoms and cursors.
   Cursors implement IRef for familiar Clojure semantics.")

;; Global state stores
(defonce session-states (atom {})) ;; {session-id atom}
(defonce tab-states (atom {}))     ;; {tab-id atom}

(defn normalize-path
  "Convert keyword or vector to vector path."
  [path]
  (if (keyword? path)
    [path]
    (vec path)))

(defn get-or-create-session-state!
  "Get or create an atom for the given session-id."
  [session-id]
  (if-let [state-atom (get @session-states session-id)]
    state-atom
    (let [new-atom (atom {})]
      (swap! session-states assoc session-id new-atom)
      new-atom)))

(defn get-or-create-tab-state!
  "Get or create an atom for the given tab-id."
  [tab-id]
  (if-let [state-atom (get @tab-states tab-id)]
    state-atom
    (let [new-atom (atom {})]
      (swap! tab-states assoc tab-id new-atom)
      new-atom)))

(deftype Cursor [parent-atom path meta-data ^:volatile-mutable validator watches]
  clojure.lang.IRef
  (deref [_]
    (get-in @parent-atom (normalize-path path)))

  (setValidator [_ vf]
    (set! validator vf))

  (getValidator [_]
    validator)

  (getWatches [_]
    @watches)

  (addWatch [this key callback]
    (swap! watches assoc key callback)
    (add-watch parent-atom key
               (fn [k _r old-state new-state]
                 (let [old-val (get-in old-state (normalize-path path))
                       new-val (get-in new-state (normalize-path path))]
                   (when (not= old-val new-val)
                     (callback k this old-val new-val)))))
    this)

  (removeWatch [_this key]
    (swap! watches dissoc key)
    (remove-watch parent-atom key)
    _this)

  clojure.lang.IAtom
  (swap [_this f]
    (let [normalized-path (normalize-path path)]
      (swap! parent-atom update-in normalized-path f)
      (get-in @parent-atom normalized-path)))

  (swap [_this f arg]
    (let [normalized-path (normalize-path path)]
      (swap! parent-atom update-in normalized-path f arg)
      (get-in @parent-atom normalized-path)))

  (swap [_this f arg1 arg2]
    (let [normalized-path (normalize-path path)]
      (swap! parent-atom update-in normalized-path f arg1 arg2)
      (get-in @parent-atom normalized-path)))

  (swap [_this f arg1 arg2 args]
    (let [normalized-path (normalize-path path)]
      (apply swap! parent-atom update-in normalized-path f arg1 arg2 args)
      (get-in @parent-atom normalized-path)))

  (compareAndSet [_ oldv newv]
    (let [normalized-path (normalize-path path)]
      (loop []
        (let [current-state @parent-atom
              current-val (get-in current-state normalized-path)]
          (if (= current-val oldv)
            (if (compare-and-set! parent-atom
                                  current-state
                                  (assoc-in current-state normalized-path newv))
              true
              (recur))
            false)))))

  (reset [_this newv]
    (let [normalized-path (normalize-path path)]
      (swap! parent-atom assoc-in normalized-path newv)
      newv))

  clojure.lang.IMeta
  (meta [_] @meta-data)

  clojure.lang.IReference
  (alterMeta [_ f args]
    (apply swap! meta-data f args))

  (resetMeta [_ m]
    (reset! meta-data m)))

(defn create-cursor
  "Create a cursor pointing to a path in the parent atom."
  [parent-atom path]
  (->Cursor parent-atom path (atom {}) nil (atom {})))

(defn session-cursor
  "Create a cursor to session state at the given path.
   Requires session-id in the request context."
  [session-id path]
  (let [state-atom (get-or-create-session-state! session-id)]
    (create-cursor state-atom path)))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Requires tab-id in the request context."
  [tab-id path]
  (let [state-atom (get-or-create-tab-state! tab-id)]
    (create-cursor state-atom path)))

(defn get-session-atom
  "Get the atom for a session (for internal use, e.g., watchers)."
  [session-id]
  (get @session-states session-id))

(defn get-tab-atom
  "Get the atom for a tab (for internal use, e.g., watchers)."
  [tab-id]
  (get @tab-states tab-id))

(defn cleanup-session!
  "Remove session state and all associated tabs."
  [session-id]
  (swap! session-states dissoc session-id)
  ;; Note: tab cleanup will be handled separately
  nil)

(defn cleanup-tab!
  "Remove tab state."
  [tab-id]
  (swap! tab-states dissoc tab-id)
  nil)
