(ns hyper.file-watcher
  "Watchable extension for java.io.File using Beholder (native file watching).

   Requiring this namespace extends java.io.File with the
   hyper.protocols/Watchable protocol, so any File can be used directly as a
   watch source — no wrapper type required:

     (require '[clojure.java.io :as io]
              '[hyper.file-watcher])   ; side-effect: extends java.io.File

     ;; Watch a single file — only changes to that file trigger re-renders
     (def routes
       [[\"/settings\" {:name    :settings
                        :get     #'settings-page
                        :watches [(io/file \"config/settings.edn\")]}]])

     ;; Watch a directory — any change inside it triggers a re-render
     (def handler
       (h/create-handler #'routes :watches [(io/file \"target/public\")]))

   Uses Beholder (nextjournal/beholder) for native OS file-system events —
   near-instant on all platforms (FSEvents on macOS, inotify on Linux).

   Lifecycle is managed automatically:
   - A Beholder watcher is started when the first callback is registered for a
     given path (i.e. when Hyper sets up a route watch).
   - It is stopped and cleaned up when the last callback is removed (i.e.
     when the tab disconnects or navigates away).

   Multiple tabs watching the same path share one underlying Beholder watcher."
  (:require [hyper.protocols :as proto]
            [nextjournal.beholder :as beholder]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

;; Registry of active watches.
;; Shape: { canonical-path-string -> {:watcher  beholder-watcher
;;                                    :cbs*     (atom {key -> callback-fn}) } }
(def ^:private -registry* (atom {}))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- -file-key
  "Stable, canonical string key for a File."
  [^java.io.File f]
  (.getCanonicalPath f))

(defn- -start-watcher!
  "Start a Beholder watcher for `f`. If `f` is a directory, watches all
   changes within it. If `f` is a file, watches the parent directory and
   filters events to the target filename. Returns the registry entry map."
  [^java.io.File f cbs*]
  (let [file-path (-file-key f)
        abs-file  (.getAbsoluteFile f)
        dir?      (.isDirectory abs-file)
        watch-dir (if dir? (str abs-file) (str (.getParent (.toPath abs-file))))
        filename  (when-not dir? (.getFileName (.toPath abs-file)))
        watcher   (beholder/watch
                    (fn [{:keys [path]}]
                      (when (or dir? (= (.getFileName path) filename))
                        (t/log! {:level :debug
                                 :id    :hyper.event/file-changed
                                 :data  {:path (str path)}
                                 :msg   "File changed"})
                        (doseq [[_k cb] @cbs*]
                          (try
                            (cb nil nil)
                            (catch Exception e
                              (t/error! e {:id   :hyper.error/file-watcher-callback
                                           :data {:path file-path}}))))))
                    watch-dir)]
    {:watcher watcher :cbs* cbs*}))

;; ---------------------------------------------------------------------------
;; Protocol extension
;; ---------------------------------------------------------------------------

(extend-type java.io.File
  proto/Watchable

  (-add-watch [this key callback]
    (locking -registry*
      (let [k (-file-key this)]
        (if-let [entry (get @-registry* k)]
          ;; Watcher already running — add callback to the shared atom
          (swap! (:cbs* entry) assoc key callback)
          ;; First watcher for this path — start Beholder
          (let [cbs*  (atom {key callback})
                entry (-start-watcher! this cbs*)]
            (swap! -registry* assoc k entry))))))

  (-remove-watch [this key]
    (locking -registry*
      (let [k (-file-key this)]
        (when-let [entry (get @-registry* k)]
          (let [remaining (swap! (:cbs* entry) dissoc key)]
            (when (empty? remaining)
              (beholder/stop (:watcher entry))
              (swap! -registry* dissoc k))))))))
