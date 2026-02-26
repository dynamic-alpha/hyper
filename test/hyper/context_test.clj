(ns hyper.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]))

(deftest require-context-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? Exception #"global-cursor called outside request context"
                          (context/require-context! "global-cursor"))))

  (testing "throws when app-state is missing"
    (binding [context/*request* {:hyper/session-id "s1"
                                 :hyper/tab-id     "t1"}]
      (is (thrown-with-msg? Exception #"No app-state"
                            (context/require-context! "test")))))

  (testing "returns context map with all keys"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*
                                   :hyper/router     :mock-router}]
        (let [ctx (context/require-context! "test")]
          (is (= "s1" (:session-id ctx)))
          (is (= "t1" (:tab-id ctx)))
          (is (= app-state* (:app-state* ctx)))
          (is (= :mock-router (:router ctx)))))))

  (testing "router is nil when not in request"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (is (nil? (:router (context/require-context! "test"))))))))
