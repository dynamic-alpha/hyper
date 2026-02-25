(ns hyper.file-watcher-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyper.file-watcher]
            [hyper.protocols :as proto]
            [nextjournal.beholder :as beholder])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private -test-root*
  (delay
    (let [d (io/file (str (Files/createTempDirectory "hyper_test" (make-array FileAttribute 0))))]
      (.mkdirs d)
      d)))

(defn- tmp-dir
  "Create a fresh empty directory under the test root."
  []
  (let [d (io/file @-test-root* (str (java.util.UUID/randomUUID)))]
    (.mkdirs d)
    d))

(defn- tmp-file
  "Create a temporary file inside a fresh isolated directory."
  []
  (let [d (tmp-dir)
        f (io/file d (str (java.util.UUID/randomUUID) ".tmp"))]
    (spit f "")
    f))

(defn- wait-for
  "Poll `pred` every 10 ms, returning true as soon as it passes or false after
   `timeout-ms` milliseconds."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                  true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

;; Beholder uses native FS events — near-instant on all platforms.
(def ^:private -wait-ms 2000)

;; Beholder's directory-watcher uses a non-daemon thread, which keeps the JVM
;; alive after tests finish. This fixture ensures every watcher is stopped so
;; Kaocha (and the JVM) can exit cleanly.
(use-fixtures :each
  (fn [test-fn]
    (try
      (test-fn)
      (finally
        (let [registry* #'hyper.file-watcher/-registry*]
          (doseq [[_ {:keys [watcher]}] @@registry*]
            (beholder/stop watcher))
          (reset! @registry* {})
          (doseq [f (file-seq @-test-root*)]
            (.delete f)))))))

;; ---------------------------------------------------------------------------
;; File watches
;; ---------------------------------------------------------------------------

(deftest test-file-watch
  (testing "Callback fires on file modification"
    (let [f       (tmp-file)
          called* (atom false)]
      (proto/-add-watch f ::test (fn [_old _new] (reset! called* true)))
      (spit f "changed")
      (is (wait-for #(deref called*) -wait-ms))
      (proto/-remove-watch f ::test)))

  (testing "Removing a watch stops callbacks from firing"
    (let [f       (tmp-file)
          called* (atom 0)]
      (proto/-add-watch f ::test (fn [_old _new] (swap! called* inc)))
      (spit f "first")
      (wait-for #(pos? @called*) -wait-ms)
      (proto/-remove-watch f ::test)
      (let [count-after-remove @called*]
        (spit f "second")
        (Thread/sleep 200)
        (is (= count-after-remove @called*)))))

  (testing "Multiple callbacks on the same file all fire"
    (let [f  (tmp-file)
          a* (atom false)
          b* (atom false)]
      (proto/-add-watch f ::a (fn [_old _new] (reset! a* true)))
      (proto/-add-watch f ::b (fn [_old _new] (reset! b* true)))
      (spit f "update")
      (is (wait-for #(and @a* @b*) -wait-ms))
      (proto/-remove-watch f ::a)
      (proto/-remove-watch f ::b)))

  (testing "Removing one of several callbacks leaves the others active"
    (let [f  (tmp-file)
          a* (atom 0)
          b* (atom 0)]
      (proto/-add-watch f ::a (fn [_old _new] (swap! a* inc)))
      (proto/-add-watch f ::b (fn [_old _new] (swap! b* inc)))
      (proto/-remove-watch f ::a)
      (spit f "update")
      (is (wait-for #(pos? @b*) -wait-ms))
      (Thread/sleep 100)
      (is (zero? @a*))
      (proto/-remove-watch f ::b)))

  (testing "Does not fire for sibling files in the same directory"
    (let [dir (tmp-dir)
          f1  (io/file dir "target.txt")
          f2  (io/file dir "other.txt")
          a*  (atom false)
          b*  (atom false)]
      (spit f1 "init")
      (spit f2 "init")
      (proto/-add-watch f1 ::a (fn [_old _new] (reset! a* true)))
      ;; Modify the sibling, not the watched file
      (spit f2 "changed")
      (Thread/sleep 500)
      (is (false? @a*))
      ;; Now modify the watched file
      (spit f1 "changed")
      (is (wait-for #(deref a*) -wait-ms))
      (proto/-remove-watch f1 ::a))))

;; ---------------------------------------------------------------------------
;; Directory watches
;; ---------------------------------------------------------------------------

(deftest test-directory-watch
  (testing "Fires on any file change within watched directory"
    (let [dir     (tmp-dir)
          called* (atom false)]
      (proto/-add-watch dir ::test (fn [_old _new] (reset! called* true)))
      (spit (io/file dir "new-file.css") "body{}")
      (is (wait-for #(deref called*) -wait-ms))
      (proto/-remove-watch dir ::test)))

  (testing "Fires for multiple different files within directory"
    (let [dir    (tmp-dir)
          count* (atom 0)]
      (proto/-add-watch dir ::test (fn [_old _new] (swap! count* inc)))
      (spit (io/file dir "a.css") "a")
      (wait-for #(pos? @count*) -wait-ms)
      (spit (io/file dir "b.css") "b")
      (is (wait-for #(>= @count* 2) -wait-ms))
      (proto/-remove-watch dir ::test))))

;; ---------------------------------------------------------------------------
;; Registry lifecycle
;; ---------------------------------------------------------------------------

(deftest test-registry-lifecycle
  (testing "Watcher is cleaned up from registry after last callback removed"
    (let [f (tmp-file)
          k (#'hyper.file-watcher/-file-key f)]
      (proto/-add-watch f ::test (fn [_ _]))
      (is (contains? @@#'hyper.file-watcher/-registry* k))
      (proto/-remove-watch f ::test)
      (is (not (contains? @@#'hyper.file-watcher/-registry* k)))))

  (testing "Watcher stays in registry while callbacks remain"
    (let [f (tmp-file)
          k (#'hyper.file-watcher/-file-key f)]
      (proto/-add-watch f ::a (fn [_ _]))
      (proto/-add-watch f ::b (fn [_ _]))
      (proto/-remove-watch f ::a)
      (is (contains? @@#'hyper.file-watcher/-registry* k))
      (proto/-remove-watch f ::b)
      (is (not (contains? @@#'hyper.file-watcher/-registry* k))))))
