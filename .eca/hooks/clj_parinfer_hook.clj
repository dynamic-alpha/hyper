#!/usr/bin/env bb

(ns clj-parinfer-hook
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))

;; ---------------- CONFIG ----------------

(def parinfer-mode
  (or (System/getenv "ECA_PARINFER_MODE") "smart"))

(def run-cljfmt?
  (let [v (some-> (System/getenv "ECA_CLJFMT") str/lower-case)]
    (contains? #{"1" "true" "yes" "on"} (or v "0"))))

(def debug?
  (let [v (some-> (System/getenv "ECA_HOOK_DEBUG") str/lower-case)]
    (contains? #{"1" "true" "yes" "on"} (or v "0"))))

(def log-path
  (or (System/getenv "ECA_HOOK_LOG")
      (str (System/getProperty "user.home")
           "/.cache/eca/clj_parinfer_hook.log")))

;; ---------------- UTIL ----------------

(def path-sep (System/getProperty "path.separator"))

(defn now []
  (str (java.time.ZonedDateTime/now)))

(defn log! [s]
  (try
    (let [f (io/file log-path)]
      (.mkdirs (.getParentFile f))
      (spit f (str (now) " " s "\n") :append true))
    (catch Throwable _)))

(defn respond! [m]
  (println (json/generate-string m)))

(defn which [cmd]
  (some (fn [dir]
          (let [f (io/file dir cmd)]
            (when (and (.exists f) (.canExecute f))
              (.getAbsolutePath f))))
        (str/split (or (System/getenv "PATH") "")
                   (re-pattern
                     (java.util.regex.Pattern/quote path-sep)))))

(defn sh!
  ([args] (sh! args {}))
  ([args {:keys [dir in]}]
   (let [pb (ProcessBuilder.
              (java.util.ArrayList. args))]
     (when dir (.directory pb (io/file dir)))
     (.redirectErrorStream pb false)
     (let [p (.start pb)]
       (when in
         (with-open [is (io/input-stream in)
                     os (.getOutputStream p)]
           (.transferTo is os)))
       (let [out  (future (slurp (.getInputStream p)))
             err  (future (slurp (.getErrorStream p)))
             exit (.waitFor p)]
         {:exit exit
          :out  @out
          :err  @err})))))

(defn clj-file? [path]
  (boolean
    (re-matches #".*\.(clj|cljs|cljc|cljd|edn)$"
                (or path ""))))

(defn parent-dir [path]
  (some-> path io/file .getParent))

;; ---------------- CORE LOGIC ----------------

(defn process-file! [file-path]
  (when-not (.exists (io/file file-path))
    (throw (ex-info "File does not exist"
                    {:file file-path})))

  (when-not (clj-file? file-path)
    (when debug?
      (log! (str "Skipping non-Clojure file: "
                 file-path)))
    :skipped)

  (let [cwd      (or (parent-dir file-path) ".")
        kondo    (which "clj-kondo")
        parinfer (which "parinfer-rust")
        cljfmt   (which "cljfmt")

        kondo-exit
        (when kondo
          (:exit (sh! ["clj-kondo"
                       "--lint"
                       file-path]
                      {:dir cwd})))

        should-fix?
        (if kondo
          (= 3 kondo-exit)
          true)]

    (when debug?
      (log! (str "DEBUG file=" file-path
                 " kondo=" (boolean kondo)
                 " exit=" kondo-exit
                 " should-fix=" should-fix?
                 " parinfer=" (boolean parinfer)
                 " cljfmt=" (boolean cljfmt)
                 " run-cljfmt=" run-cljfmt?)))

    (when (and should-fix? parinfer)
      (let [{:keys [exit out err]}
            (sh! ["parinfer-rust"
                  "--mode" parinfer-mode
                  "--language" "clojure"
                  "--output-format" "text"]
                 {:dir cwd
                  :in  file-path})]
        (if (zero? exit)
          (spit file-path out)
          (log! (str "parinfer-rust failed: "
                     err)))))

    (when (and should-fix?
               run-cljfmt?
               cljfmt)
      (let [{:keys [exit err]}
            (sh! ["cljfmt" "fix" file-path]
                 {:dir cwd})]
        (when-not (zero? exit)
          (log! (str "cljfmt failed: "
                     err)))))

    :done))

;; ---------------- ENTRY ----------------

(let [args *command-line-args*]

  (if (seq args)
    ;; CLI MODE
    (let [file-path (first args)]
      (process-file! file-path)
      (println "Processed" file-path))

    ;; ECA HOOK MODE
    (let [raw       (slurp *in*)
          payload   (json/parse-string raw true)
          file-path (get-in payload
                            [:tool_input :path])]
      (when file-path
        (process-file! file-path))
      (respond!
        {:suppressOutput true}))))
