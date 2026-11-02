(ns hyper.render.queue-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.render.queue :as rq])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---------------------------------------------------------------------------
;; Basic categorization
;; ---------------------------------------------------------------------------

(deftest test-drain-categorizes-events
  (testing "drain! returns correctly categorized events"
    (let [q (rq/make-queue)]
      (rq/enqueue-full-render! q)
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-partial! q "comp-2")
      (rq/enqueue-scripts! q ["alert('a')" "alert('b')"])
      (let [result (rq/drain! q)]
        (is (false? (:shutdown? result)))
        (is (true? (:full-render? result)))
        (is (= #{"comp-1" "comp-2"} (:dirty-ids result)))
        (is (= ["alert('a')" "alert('b')"] (:scripts result)))))))

(deftest test-drain-partial-only
  (testing "drain! with only partial events returns full-render? false"
    (let [q (rq/make-queue)]
      (rq/enqueue-partial! q "comp-x")
      (let [result (rq/drain! q)]
        (is (false? (:full-render? result)))
        (is (= #{"comp-x"} (:dirty-ids result)))
        (is (= [] (:scripts result)))))))

(deftest test-drain-scripts-only
  (testing "drain! with only script events"
    (let [q (rq/make-queue)]
      (rq/enqueue-scripts! q ["console.log('hi')"])
      (let [result (rq/drain! q)]
        (is (false? (:full-render? result)))
        (is (= #{} (:dirty-ids result)))
        (is (= ["console.log('hi')"] (:scripts result)))))))

(deftest test-drain-full-render-only
  (testing "drain! with only full render events"
    (let [q (rq/make-queue)]
      (rq/enqueue-full-render! q)
      (let [result (rq/drain! q)]
        (is (true? (:full-render? result)))
        (is (= #{} (:dirty-ids result)))
        (is (= [] (:scripts result)))))))

(deftest test-drain-deduplicates-partials
  (testing "multiple partials for same component are deduplicated"
    (let [q (rq/make-queue)]
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-partial! q "comp-1")
      (let [result (rq/drain! q)]
        (is (= #{"comp-1"} (:dirty-ids result)))))))

(deftest test-drain-multiple-full-renders-coalesce
  (testing "multiple full-render events coalesce into one boolean"
    (let [q (rq/make-queue)]
      (rq/enqueue-full-render! q)
      (rq/enqueue-full-render! q)
      (rq/enqueue-full-render! q)
      (let [result (rq/drain! q)]
        (is (true? (:full-render? result)))))))

;; ---------------------------------------------------------------------------
;; Blocking semantics
;; ---------------------------------------------------------------------------

(deftest test-drain-blocks-until-event
  (testing "drain! blocks until at least one event is enqueued"
    (let [q       (rq/make-queue)
          started (CountDownLatch. 1)
          result  (promise)]
      ;; Consumer thread blocks on drain
      (-> (Thread/ofVirtual)
          (.start (reify Runnable
                    (run [_]
                      (.countDown started)
                      (deliver result (rq/drain! q))))))
      ;; Wait for consumer to be running
      (.await started 1 TimeUnit/SECONDS)
      ;; Queue should still be empty, result not yet delivered
      (Thread/sleep 50)
      (is (not (realized? result)) "drain! should block when queue is empty")
      ;; Now enqueue something
      (rq/enqueue-partial! q "comp-1")
      ;; Result should arrive
      (is (= #{"comp-1"} (:dirty-ids (deref result 1000 :timeout)))))))

;; ---------------------------------------------------------------------------
;; Independence of successive drains
;; ---------------------------------------------------------------------------

(deftest test-successive-drains-are-independent
  (testing "events from one drain don't leak into the next"
    (let [q (rq/make-queue)]
      ;; First batch
      (rq/enqueue-full-render! q)
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-scripts! q ["script-1"])
      (let [r1 (rq/drain! q)]
        (is (true? (:full-render? r1)))
        (is (= #{"comp-1"} (:dirty-ids r1)))
        (is (= ["script-1"] (:scripts r1))))
      ;; Second batch — completely fresh
      (rq/enqueue-partial! q "comp-2")
      (let [r2 (rq/drain! q)]
        (is (false? (:full-render? r2)))
        (is (= #{"comp-2"} (:dirty-ids r2)))
        (is (= [] (:scripts r2)))))))

;; ---------------------------------------------------------------------------
;; Script FIFO ordering
;; ---------------------------------------------------------------------------

(deftest test-scripts-maintain-fifo-order
  (testing "scripts are returned in the order they were enqueued"
    (let [q       (rq/make-queue)
          scripts (mapv #(str "script-" %) (range 100))]
      (rq/enqueue-scripts! q scripts)
      (let [result (rq/drain! q)]
        (is (= scripts (:scripts result)))))))

(deftest test-scripts-before-render
  (testing "scripts enqueued before the first render event are split out"
    (let [q (rq/make-queue)]
      (rq/enqueue-scripts! q ["before-1" "before-2"])
      (rq/enqueue-full-render! q)
      (rq/enqueue-scripts! q ["after"])
      (rq/enqueue-full-render! q)
      (rq/enqueue-scripts! q ["after-2"])
      (let [result (rq/drain! q)]
        (is (= ["before-1" "before-2"] (:scripts-before-render result)))
        (is (= ["after" "after-2"] (:scripts result))))))

  (testing "a partial render event also marks the split"
    (let [q (rq/make-queue)]
      (rq/enqueue-scripts! q ["before"])
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-scripts! q ["after"])
      (let [result (rq/drain! q)]
        (is (= ["before"] (:scripts-before-render result)))
        (is (= ["after"] (:scripts result))))))

  (testing "without a render event every script is in :scripts"
    (let [q (rq/make-queue)]
      (rq/enqueue-scripts! q ["a" "b"])
      (let [result (rq/drain! q)]
        (is (= [] (:scripts-before-render result)))
        (is (= ["a" "b"] (:scripts result)))))))

;; ---------------------------------------------------------------------------
;; Concurrent writer stress test — no data loss
;; ---------------------------------------------------------------------------

(deftest test-no-data-loss-under-concurrent-writes
  (testing "all events from concurrent writers are captured across drains"
    (let [q            (rq/make-queue)
          n-writers    20
          events-per   100
          total-events (* n-writers events-per)
          latch        (CountDownLatch. n-writers)
          ;; Each writer enqueues unique component IDs
          writer-fn    (fn [writer-id]
                         (fn []
                           (dotimes [i events-per]
                             (rq/enqueue-partial! q (str "w" writer-id "-c" i)))
                           (.countDown latch)))]
      ;; Start all writers
      (dotimes [w n-writers]
        (-> (Thread/ofVirtual)
            (.start (reify Runnable
                      (run [_] ((writer-fn w)))))))
      ;; Wait for all writers to finish
      (.await latch 5 TimeUnit/SECONDS)
      ;; Drain everything (may take multiple drains if writer timing varies,
      ;; but since they all finished by now, one drain should get everything)
      (loop [all-ids   #{}
             remaining 10]  ;; safety limit
        (if (and (pos? remaining) (< (count all-ids) total-events))
          (if (.peek q)
            (let [result (rq/drain! q)]
              (recur (into all-ids (:dirty-ids result))
                     (dec remaining)))
            ;; Queue is empty, we've got everything
            (do
              (is (= total-events (count all-ids))
                  (str "Expected " total-events " unique component IDs, got " (count all-ids)))
              ;; Verify every expected ID is present
              (doseq [w (range n-writers)
                      i (range events-per)]
                (is (contains? all-ids (str "w" w "-c" i))))))
          (is (= total-events (count all-ids))
              (str "Expected " total-events " unique IDs after draining, got " (count all-ids))))))))

;; ---------------------------------------------------------------------------
;; Consistent snapshot — drain returns a coherent batch
;; ---------------------------------------------------------------------------

(deftest test-consistent-snapshot
  (testing "a full-render enqueued before partials appears in same drain"
    (let [q (rq/make-queue)]
      ;; Simulate a navigation: full render + watch firing partials
      (rq/enqueue-full-render! q)
      (rq/enqueue-partial! q "comp-nav")
      (rq/enqueue-scripts! q ["location.push()"])
      (let [result (rq/drain! q)]
        ;; All three types are in the same batch
        (is (true? (:full-render? result)))
        (is (contains? (:dirty-ids result) "comp-nav"))
        (is (= ["location.push()"] (:scripts result))))))

  (testing "interleaved events from rapid-fire writers all land in one drain"
    (let [q     (rq/make-queue)
          latch (CountDownLatch. 3)]
      ;; Three threads enqueue simultaneously
      (doseq [event-fn [#(do (rq/enqueue-full-render! q) (.countDown latch))
                        #(do (rq/enqueue-partial! q "comp-fast") (.countDown latch))
                        #(do (rq/enqueue-scripts! q ["fast.js"]) (.countDown latch))]]
        (-> (Thread/ofVirtual)
            (.start (reify Runnable (run [_] (event-fn))))))
      (.await latch 1 TimeUnit/SECONDS)
      ;; Give a tiny moment for all offers to complete
      (Thread/sleep 10)
      (let [result (rq/drain! q)]
        ;; Everything should be in one batch since all enqueued before drain
        (is (true? (:full-render? result)))
        (is (contains? (:dirty-ids result) "comp-fast"))
        ;; Enqueue order is nondeterministic, so the script may precede a render
        (is (= ["fast.js"] (into (:scripts-before-render result) (:scripts result))))))))

;; ---------------------------------------------------------------------------
;; Shutdown event
;; ---------------------------------------------------------------------------

(deftest test-shutdown-event
  (testing "shutdown event is surfaced in drain result"
    (let [q (rq/make-queue)]
      (rq/enqueue-shutdown! q)
      (let [result (rq/drain! q)]
        (is (true? (:shutdown? result))))))

  (testing "shutdown mixed with other events preserves all data"
    (let [q (rq/make-queue)]
      (rq/enqueue-partial! q "comp-1")
      (rq/enqueue-scripts! q ["final.js"])
      (rq/enqueue-shutdown! q)
      (let [result (rq/drain! q)]
        (is (true? (:shutdown? result)))
        (is (= #{"comp-1"} (:dirty-ids result)))
        (is (= ["final.js"] (:scripts result))))))

  (testing "shutdown unblocks a waiting drain"
    (let [q      (rq/make-queue)
          result (promise)]
      (-> (Thread/ofVirtual)
          (.start (reify Runnable
                    (run [_] (deliver result (rq/drain! q))))))
      (Thread/sleep 50)
      (is (not (realized? result)) "drain! should be blocking")
      (rq/enqueue-shutdown! q)
      (let [r (deref result 1000 :timeout)]
        (is (not= :timeout r))
        (is (true? (:shutdown? r)))))))

;; ---------------------------------------------------------------------------
;; Producer API returns nil (fire-and-forget)
;; ---------------------------------------------------------------------------

(deftest test-producer-fns-return-nil
  (testing "all enqueue functions return nil"
    (let [q (rq/make-queue)]
      (is (nil? (rq/enqueue-full-render! q)))
      (is (nil? (rq/enqueue-partial! q "x")))
      (is (nil? (rq/enqueue-scripts! q ["y"])))
      (is (nil? (rq/enqueue-shutdown! q))))))

;; ---------------------------------------------------------------------------
;; Timeout arity (heartbeat)
;; ---------------------------------------------------------------------------

(deftest test-drain-timeout-idle
  (testing "drain! with a timeout returns :idle? when no events arrive"
    (let [q      (rq/make-queue)
          result (rq/drain! q 10)]
      (is (true? (:idle? result)))
      (is (false? (:shutdown? result)))
      (is (false? (:full-render? result)))
      (is (= #{} (:dirty-ids result)))
      (is (= [] (:scripts result))))))

(deftest test-drain-timeout-returns-events
  (testing "drain! with a timeout categorizes events present within the window"
    (let [q (rq/make-queue)]
      (rq/enqueue-full-render! q)
      (rq/enqueue-partial! q "comp-1")
      (let [result (rq/drain! q 1000)]
        (is (false? (:idle? result)))
        (is (true? (:full-render? result)))
        (is (= #{"comp-1"} (:dirty-ids result)))))))

(deftest test-blocking-drain-not-idle
  (testing "the blocking arity never reports :idle?"
    (let [q (rq/make-queue)]
      (rq/enqueue-full-render! q)
      (is (false? (:idle? (rq/drain! q)))))))
