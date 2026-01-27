(ns hyper.actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]))

(deftest generate-action-id-test
  (testing "generates unique action IDs"
    (let [id1 (actions/generate-action-id)
          id2 (actions/generate-action-id)]
      (is (string? id1))
      (is (string? id2))
      (is (not= id1 id2))))

  (testing "action IDs are UUIDs"
    (let [id (actions/generate-action-id)]
      ;; UUID format: 8-4-4-4-12 hex digits
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id)))))

(deftest register-action-test
  (testing "registers action and returns action-id"
    (let [session-id "test-session-1"
          tab-id "test-tab-1"
          action-fn (fn [] :result)
          action-id (actions/register-action! session-id tab-id action-fn)]
      (is (string? action-id))
      (is (some? (actions/get-action session-id action-id)))))

  (testing "stores action with session and tab context"
    (let [session-id "test-session-2"
          tab-id "test-tab-2"
          action-fn (fn [] :result)
          action-id (actions/register-action! session-id tab-id action-fn)
          action-data (actions/get-action session-id action-id)]
      (is (= session-id (:session-id action-data)))
      (is (= tab-id (:tab-id action-data)))
      (is (fn? (:fn action-data))))))

(deftest get-action-test
  (testing "returns nil for non-existent action"
    (is (nil? (actions/get-action "non-existent" "non-existent"))))

  (testing "returns action data for existing action"
    (let [session-id "test-session-3"
          tab-id "test-tab-3"
          action-fn (fn [] :result)
          action-id (actions/register-action! session-id tab-id action-fn)
          action-data (actions/get-action session-id action-id)]
      (is (some? action-data))
      (is (map? action-data))
      (is (contains? action-data :fn))
      (is (contains? action-data :session-id))
      (is (contains? action-data :tab-id)))))

(deftest execute-action-test
  (testing "executes action and returns result"
    (let [session-id "test-session-4"
          tab-id "test-tab-4"
          result-atom (atom nil)
          action-fn (fn [] (reset! result-atom :executed) :return-value)
          action-id (actions/register-action! session-id tab-id action-fn)
          result (actions/execute-action! session-id action-id)]
      (is (= :executed @result-atom))
      (is (= :return-value result))))

  (testing "throws exception for non-existent action"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Action not found"
                          (actions/execute-action! "bad-session" "bad-action")))))

(deftest action-isolation-test
  (testing "actions are isolated per session"
    (let [session-1 "session-1"
          session-2 "session-2"
          tab-id "tab-1"
          action-fn (fn [] :result)
          action-id-1 (actions/register-action! session-1 tab-id action-fn)
          action-id-2 (actions/register-action! session-2 tab-id action-fn)]
      ;; Action from session-1 should not be accessible from session-2
      (is (some? (actions/get-action session-1 action-id-1)))
      (is (nil? (actions/get-action session-2 action-id-1)))
      (is (some? (actions/get-action session-2 action-id-2)))
      (is (nil? (actions/get-action session-1 action-id-2))))))

(deftest cleanup-session-actions-test
  (testing "removes all actions for a session"
    (let [session-id "test-session-cleanup"
          tab-id "test-tab"
          action-fn (fn [] :result)
          action-id-1 (actions/register-action! session-id tab-id action-fn)
          action-id-2 (actions/register-action! session-id tab-id action-fn)]
      ;; Verify actions exist
      (is (some? (actions/get-action session-id action-id-1)))
      (is (some? (actions/get-action session-id action-id-2)))
      ;; Cleanup
      (actions/cleanup-session-actions! session-id)
      ;; Verify actions are gone
      (is (nil? (actions/get-action session-id action-id-1)))
      (is (nil? (actions/get-action session-id action-id-2)))))

  (testing "only removes actions for specified session"
    (let [session-1 "session-cleanup-1"
          session-2 "session-cleanup-2"
          tab-id "tab"
          action-fn (fn [] :result)
          action-id-1 (actions/register-action! session-1 tab-id action-fn)
          action-id-2 (actions/register-action! session-2 tab-id action-fn)]
      ;; Cleanup session-1
      (actions/cleanup-session-actions! session-1)
      ;; Verify only session-1 actions are gone
      (is (nil? (actions/get-action session-1 action-id-1)))
      (is (some? (actions/get-action session-2 action-id-2))))))

(deftest get-session-actions-test
  (testing "returns all actions for a session"
    (let [session-id "test-session-5"
          tab-id "test-tab-5"
          action-fn (fn [] :result)
          action-id-1 (actions/register-action! session-id tab-id action-fn)
          action-id-2 (actions/register-action! session-id tab-id action-fn)
          session-actions (actions/get-session-actions session-id)]
      (is (map? session-actions))
      (is (= 2 (count session-actions)))
      (is (contains? session-actions action-id-1))
      (is (contains? session-actions action-id-2))))

  (testing "returns nil for session with no actions"
    (is (nil? (actions/get-session-actions "non-existent-session")))))

(deftest action-count-test
  (testing "counts total actions across all sessions"
    (let [initial-count (actions/action-count)
          session-1 "count-session-1"
          session-2 "count-session-2"
          tab-id "tab"
          action-fn (fn [] :result)]
      ;; Register actions
      (actions/register-action! session-1 tab-id action-fn)
      (actions/register-action! session-1 tab-id action-fn)
      (actions/register-action! session-2 tab-id action-fn)
      ;; Count should increase by 3
      (is (= (+ initial-count 3) (actions/action-count)))
      ;; Cleanup one session
      (actions/cleanup-session-actions! session-1)
      ;; Count should decrease by 2
      (is (= (+ initial-count 1) (actions/action-count))))))
