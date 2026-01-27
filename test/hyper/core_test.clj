(ns hyper.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.core :as hy]
            [hyper.actions :as actions]))

(deftest session-cursor-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"session-cursor called outside request context"
                          (hy/session-cursor :user))))

  (testing "throws when request has no session-id"
    (binding [hy/*request* {:hyper/tab-id "some-tab"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No session-id in request"
                            (hy/session-cursor :user)))))

  (testing "creates cursor when request context is valid"
    (binding [hy/*request* {:hyper/session-id "test-session"
                            :hyper/tab-id "test-tab"}]
      (let [cursor (hy/session-cursor :user)]
        (is (some? cursor))
        (reset! cursor {:name "Alice"})
        (is (= {:name "Alice"} @cursor))))))

(deftest tab-cursor-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"tab-cursor called outside request context"
                          (hy/tab-cursor :count))))

  (testing "throws when request has no tab-id"
    (binding [hy/*request* {:hyper/session-id "some-session"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No tab-id in request"
                            (hy/tab-cursor :count)))))

  (testing "creates cursor when request context is valid"
    (binding [hy/*request* {:hyper/session-id "test-session-2"
                            :hyper/tab-id "test-tab-2"}]
      (let [cursor (hy/tab-cursor :count)]
        (is (some? cursor))
        (reset! cursor 42)
        (is (= 42 @cursor))))))

(deftest cursor-isolation-test
  (testing "session cursors are isolated per session"
    (binding [hy/*request* {:hyper/session-id "session-1"
                            :hyper/tab-id "tab-1"}]
      (let [cursor1 (hy/session-cursor :data)]
        (reset! cursor1 {:value 1})))
    (binding [hy/*request* {:hyper/session-id "session-2"
                            :hyper/tab-id "tab-2"}]
      (let [cursor2 (hy/session-cursor :data)]
        (is (nil? @cursor2)))))

  (testing "tab cursors are isolated per tab"
    (binding [hy/*request* {:hyper/session-id "session-3"
                            :hyper/tab-id "tab-3"}]
      (let [cursor1 (hy/tab-cursor :count)]
        (reset! cursor1 10)))
    (binding [hy/*request* {:hyper/session-id "session-3"
                            :hyper/tab-id "tab-4"}]
      (let [cursor2 (hy/tab-cursor :count)]
        (is (nil? @cursor2))))))

(deftest action-macro-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"action macro called outside request context"
                          (hy/action (println "test")))))

  (testing "throws when request has no tab-id"
    (binding [hy/*request* {:hyper/session-id "some-session"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No tab-id in request context"
                            (hy/action (println "test"))))))

  (testing "returns map with :data-on-click attribute"
    (binding [hy/*request* {:hyper/session-id "test-session"
                            :hyper/tab-id "test-tab"}]
      (let [action-attrs (hy/action (println "clicked!"))]
        (is (map? action-attrs))
        (is (contains? action-attrs :data-on-click))
        (is (string? (:data-on-click action-attrs))))))

  (testing "generates Datastar-compatible POST attributes"
    (binding [hy/*request* {:hyper/session-id "test-session"
                            :hyper/tab-id "test-tab"}]
      (let [action-attrs (hy/action (println "clicked!"))
            onclick-str (:data-on-click action-attrs)]
        (is (.contains onclick-str "$$post"))
        (is (.contains onclick-str "/hyper/actions"))
        (is (.contains onclick-str "action-id=")))))

  (testing "registers action that can be executed"
    (binding [hy/*request* {:hyper/session-id "test-session-exec"
                            :hyper/tab-id "test-tab-exec"}]
      (let [result-atom (atom nil)
            action-attrs (hy/action (reset! result-atom :executed))
            onclick-str (:data-on-click action-attrs)
            action-id (second (re-find #"action-id=([^']+)" onclick-str))]
        ;; Verify action was registered
        (is (some? (actions/get-action "test-session-exec" action-id)))
        ;; Execute the action
        (binding [hy/*request* {:hyper/session-id "test-session-exec"
                                :hyper/tab-id "test-tab-exec"}]
          (actions/execute-action! "test-session-exec" action-id))
        ;; Verify action executed
        (is (= :executed @result-atom)))))

  (testing "action can access cursors"
    (binding [hy/*request* {:hyper/session-id "test-session-cursor"
                            :hyper/tab-id "test-tab-cursor"}]
      (let [counter* (hy/tab-cursor :count)]
        (reset! counter* 0)
        (let [action-attrs (hy/action (swap! counter* inc))
              onclick-str (:data-on-click action-attrs)
              action-id (second (re-find #"action-id=([^']+)" onclick-str))]
          ;; Execute the action
          (binding [hy/*request* {:hyper/session-id "test-session-cursor"
                                  :hyper/tab-id "test-tab-cursor"}]
            (actions/execute-action! "test-session-cursor" action-id))
          ;; Verify counter was incremented
          (is (= 1 @counter*))
          ;; Execute again
          (binding [hy/*request* {:hyper/session-id "test-session-cursor"
                                  :hyper/tab-id "test-tab-cursor"}]
            (actions/execute-action! "test-session-cursor" action-id))
          (is (= 2 @counter*)))))))

(deftest multiple-actions-test
  (testing "multiple actions have unique IDs"
    (binding [hy/*request* {:hyper/session-id "session-multi"
                            :hyper/tab-id "tab-multi"}]
      (let [action-1 (hy/action (println "action 1"))
            action-2 (hy/action (println "action 2"))
            action-3 (hy/action (println "action 3"))]
        ;; All three should be different
        (is (not= action-1 action-2))
        (is (not= action-2 action-3))
        (is (not= action-1 action-3)))))

  (testing "multiple actions can modify different state"
    (binding [hy/*request* {:hyper/session-id "session-multi-state"
                            :hyper/tab-id "tab-multi-state"}]
      (let [counter* (hy/tab-cursor :count)
            name* (hy/session-cursor :name)]
        (reset! counter* 0)
        (reset! name* "Alice")

        (let [inc-action (hy/action (swap! counter* inc))
              dec-action (hy/action (swap! counter* dec))
              name-action (hy/action (reset! name* "Bob"))
              inc-id (second (re-find #"action-id=([^']+)" (:data-on-click inc-action)))
              dec-id (second (re-find #"action-id=([^']+)" (:data-on-click dec-action)))
              name-id (second (re-find #"action-id=([^']+)" (:data-on-click name-action)))]

          ;; Execute increment
          (binding [hy/*request* {:hyper/session-id "session-multi-state"
                                  :hyper/tab-id "tab-multi-state"}]
            (actions/execute-action! "session-multi-state" inc-id))
          (is (= 1 @counter*))

          ;; Execute decrement
          (binding [hy/*request* {:hyper/session-id "session-multi-state"
                                  :hyper/tab-id "tab-multi-state"}]
            (actions/execute-action! "session-multi-state" dec-id))
          (is (= 0 @counter*))

          ;; Execute name change
          (binding [hy/*request* {:hyper/session-id "session-multi-state"
                                  :hyper/tab-id "tab-multi-state"}]
            (actions/execute-action! "session-multi-state" name-id))
          (is (= "Bob" @name*)))))))
