(ns ^:no-doc hyper.render.queue
  "Render queue — single coordination primitive for the per-tab renderer.

   Replaces the previous atoms (pending-partials*, full-render-needed*,
   pending-scripts*) and Semaphore with a single LinkedBlockingQueue.

   Writers (HTTP handler threads, reactive watch callbacks) enqueue typed
   events; the renderer fiber drains them in batches via `drain!`, which
   blocks until at least one event is available and then atomically drains
   all pending events into a categorized map.

   This eliminates all shared mutable state between producer and consumer
   threads — the queue IS the coordination primitive."
  (:import [java.util ArrayList]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn make-queue
  "Create a new render queue."
  []
  (LinkedBlockingQueue.))

;; ---------------------------------------------------------------------------
;; Producer API (thread-safe, non-blocking)
;; ---------------------------------------------------------------------------

(defn enqueue-full-render!
  "Signal that a full page render is needed."
  [^LinkedBlockingQueue q]
  (.offer q {:type :full-render})
  nil)

(defn enqueue-partial!
  "Signal that a reactive component needs re-rendering."
  [^LinkedBlockingQueue q component-id]
  (.offer q {:type :partial :component-id component-id})
  nil)

(defn enqueue-scripts!
  "Queue one or more scripts for SSE delivery to the client."
  [^LinkedBlockingQueue q scripts]
  (doseq [s scripts]
    (.offer q {:type :script :js s}))
  nil)

(defn enqueue-shutdown!
  "Signal the renderer to shut down.  Unblocks a pending `drain!` call."
  [^LinkedBlockingQueue q]
  (.offer q {:type :shutdown})
  nil)

;; ---------------------------------------------------------------------------
;; Consumer API (blocking, single-consumer)
;; ---------------------------------------------------------------------------

(defn- categorize
  "Reduce a drained buffer of events into the categorized result map.
   Scripts enqueued before the batch's first render event (full or partial)
   are returned as :scripts-before-render; when the batch has no render event
   all scripts are returned as :scripts."
  [^ArrayList buf]
  (let [n (.size buf)]
    (loop [i        0
           shutdown false
           full?    false
           ids      (transient #{})
           before   (transient [])
           scripts  (transient [])
           render?  false]
      (if (< i n)
        (let [evt (.get buf i)]
          (case (:type evt)
            :shutdown    (recur (inc i) true     full? ids                             before scripts render?)
            :full-render (recur (inc i) shutdown true  ids                             before scripts true)
            :partial     (recur (inc i) shutdown full? (conj! ids (:component-id evt)) before scripts true)
            :script      (if render?
                           (recur (inc i) shutdown full? ids before (conj! scripts (:js evt)) render?)
                           (recur (inc i) shutdown full? ids (conj! before (:js evt)) scripts render?))
            ;; Unknown event types are silently skipped
            (recur (inc i) shutdown full? ids before scripts render?)))
        (let [before  (persistent! before)
              scripts (persistent! scripts)]
          {:idle?                 false
           :shutdown?             shutdown
           :full-render?          full?
           :dirty-ids             (persistent! ids)
           :scripts-before-render (if render? before [])
           :scripts               (if render? scripts (into before scripts))})))))

(defn drain!
  "Block until at least one event is available, then drain all pending events.

   Returns a categorized map:
     {:shutdown?    boolean   — true if a :shutdown event was present
      :full-render? boolean   — true if any :full-render event was present
      :dirty-ids   #{...}    — set of component IDs needing partial render
      :scripts-before-render [...] — JS strings enqueued before the batch's
                                     first render event, in FIFO order
      :scripts     [...]}    — the remaining JS strings in FIFO order

   When `timeout-ms` is given, blocks at most that long for the first event;
   if none arrives the result is flagged `:idle? true` (used to drive the
   heartbeat keepalive).  The blocking single-arity never returns `:idle?`.

   This is the only function that removes events from the queue.
   Designed for single-consumer use (the renderer fiber)."
  ([^LinkedBlockingQueue q]
   (let [buf (ArrayList.)]
     (.add buf (.take q))
     (.drainTo q buf)
     (categorize buf)))
  ([^LinkedBlockingQueue q timeout-ms]
   (let [first-event (.poll q (long timeout-ms) TimeUnit/MILLISECONDS)]
     (if (nil? first-event)
       {:idle?                 true
        :shutdown?             false
        :full-render?          false
        :dirty-ids             #{}
        :scripts-before-render []
        :scripts               []}
       (let [buf (ArrayList.)]
         (.add buf first-event)
         (.drainTo q buf)
         (categorize buf))))))
