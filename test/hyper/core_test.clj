(ns hyper.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.core :as hy]))

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
