(ns hyper.actions-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context :as context]
            [hyper.server :as server]
            [hyper.state :as state]))

(deftest register-action-test
  (testing "registers an action and returns ID"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-1"
          tab-id     "test_tab_1"
          action-fn  (fn [] :executed)
          action-id  (actions/register-action! app-state* session-id tab-id action-fn)]
      (is (string? action-id))
      (is (.startsWith action-id "act_"))
      (is (contains? (:actions @app-state*) action-id))
      (is (= session-id (get-in @app-state* [:actions action-id :session-id])))
      (is (= tab-id (get-in @app-state* [:actions action-id :tab-id])))
      (is (fn? (get-in @app-state* [:actions action-id :fn])))))

  (testing "generates unique IDs"
    (let [app-state* (atom (state/init-state))
          action-fn  (fn [] :executed)
          id1        (actions/register-action! app-state* "sess1" "tab1" action-fn)
          id2        (actions/register-action! app-state* "sess1" "tab1" action-fn)]
      (is (not= id1 id2)))))

(defn- render-actions!
  "Register `action-fns` as a render of tab tab1's page would, as
   {action-id action-fn}, and return the render's version."
  [app-state* action-fns]
  (state/get-or-create-tab! app-state* "sess1" "tab1")
  (binding [context/*rendered-actions* (atom {})]
    (doseq [[action-id action-fn] action-fns]
      (actions/register-action! app-state* "sess1" "tab1" action-fn action-id))
    (actions/record-render! app-state* "tab1" {:html    (pr-str (keys action-fns))
                                               :actions @context/*rendered-actions*})))

(defn- run-queued!
  "Post action `action-id` of render `v` of tab tab1, with `params`, as the
   tab's action queue."
  ([app-state* v action-id]
   (run-queued! app-state* v action-id nil))
  ([app-state* v action-id params]
   ((server/action-handler app-state*)
    {:query-params     {"tab-id" "tab1"}
     :hyper/session-id "sess1"
     :body             (json/generate-string
                         {:base    v
                          :actions [{:id      action-id
                                     :seq     (inc (get-in @app-state* [:tabs "tab1" :action-seq] 0))
                                     :v       v
                                     :signals "{}"
                                     :params  (some-> params json/generate-string)}]})})))

(deftest run-queued-action-test
  (testing "runs a rendered action"
    (let [app-state* (atom (state/init-state))
          executed   (atom false)
          v          (render-actions! app-state* {"a_tab1_1" (fn [_] (reset! executed true))})]
      (is (= 204 (:status (run-queued! app-state* v "a_tab1_1"))))
      (is @executed)))

  (testing "action can access closures"
    (let [app-state*     (atom (state/init-state))
          result         (atom nil)
          captured-value 42
          v              (render-actions! app-state* {"a_tab1_1" (fn [_] (reset! result captured-value))})]
      (run-queued! app-state* v "a_tab1_1")
      (is (= 42 @result))))

  (testing "a missing action runs nothing and is not an error"
    (let [app-state* (atom (state/init-state))
          v          (render-actions! app-state* {})]
      (is (= 204 (:status (run-queued! app-state* v "nonexistent-action"))))))

  (testing "passes client params through"
    (let [app-state* (atom (state/init-state))
          result     (atom nil)
          v          (render-actions! app-state* {"a_tab1_1" (fn [params] (reset! result (:value params)))})]
      (run-queued! app-state* v "a_tab1_1" {:value "test-value"})
      (is (= "test-value" @result)))))

(deftest cleanup-tab-actions-test
  (testing "removes all actions for a tab"
    (let [app-state*  (atom (state/init-state))
          tab-id      "test_tab_cleanup"
          action-fn   (fn [_] :executed)
          action-id-1 (actions/register-action! app-state* "sess1" tab-id action-fn)
          action-id-2 (actions/register-action! app-state* "sess1" tab-id action-fn)
          action-id-3 (actions/register-action! app-state* "sess1" "other_tab" action-fn)]
      (is (contains? (:actions @app-state*) action-id-1))
      (is (contains? (:actions @app-state*) action-id-2))
      (is (contains? (:actions @app-state*) action-id-3))

      (actions/cleanup-tab-actions! app-state* tab-id)

      (is (not (contains? (:actions @app-state*) action-id-1)))
      (is (not (contains? (:actions @app-state*) action-id-2)))
      (is (contains? (:actions @app-state*) action-id-3))))) ;; Other tab's action remains
