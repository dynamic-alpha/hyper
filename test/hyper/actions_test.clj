(ns hyper.actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.state :as state]))

(deftest register-action-test
  (testing "registers an action and returns ID"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"
          tab-id "test-tab-1"
          action-fn (fn [] :executed)
          action-id (actions/register-action! app-state* session-id tab-id action-fn)]
      (is (string? action-id))
      (is (.startsWith action-id "action-"))
      (is (contains? (:actions @app-state*) action-id))
      (is (= session-id (get-in @app-state* [:actions action-id :session-id])))
      (is (= tab-id (get-in @app-state* [:actions action-id :tab-id])))
      (is (fn? (get-in @app-state* [:actions action-id :fn])))))

  (testing "generates unique IDs"
    (let [app-state* (atom (state/init-state))
          action-fn (fn [] :executed)
          id1 (actions/register-action! app-state* "sess1" "tab1" action-fn)
          id2 (actions/register-action! app-state* "sess1" "tab1" action-fn)]
      (is (not= id1 id2)))))

(deftest execute-action-test
  (testing "executes registered action"
    (let [app-state* (atom (state/init-state))
          executed (atom false)
          action-fn (fn [] (reset! executed true))
          action-id (actions/register-action! app-state* "sess1" "tab1" action-fn)]
      (actions/execute-action! app-state* action-id)
      (is @executed)))

  (testing "action can access closures"
    (let [app-state* (atom (state/init-state))
          result (atom nil)
          captured-value 42
          action-fn (fn [] (reset! result captured-value))
          action-id (actions/register-action! app-state* "sess1" "tab1" action-fn)]
      (actions/execute-action! app-state* action-id)
      (is (= 42 @result))))

  (testing "throws exception for missing action"
    (let [app-state* (atom (state/init-state))]
      (is (thrown? Exception
                   (actions/execute-action! app-state* "nonexistent-action"))))))

(deftest cleanup-tab-actions-test
  (testing "removes all actions for a tab"
    (let [app-state* (atom (state/init-state))
          tab-id "test-tab-cleanup"
          action-fn (fn [] :executed)
          action-id-1 (actions/register-action! app-state* "sess1" tab-id action-fn)
          action-id-2 (actions/register-action! app-state* "sess1" tab-id action-fn)
          action-id-3 (actions/register-action! app-state* "sess1" "other-tab" action-fn)]
      (is (contains? (:actions @app-state*) action-id-1))
      (is (contains? (:actions @app-state*) action-id-2))
      (is (contains? (:actions @app-state*) action-id-3))

      (actions/cleanup-tab-actions! app-state* tab-id)

      (is (not (contains? (:actions @app-state*) action-id-1)))
      (is (not (contains? (:actions @app-state*) action-id-2)))
      (is (contains? (:actions @app-state*) action-id-3))))) ;; Other tab's action remains
