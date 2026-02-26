(ns hyper.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.utils :as utils]))

(deftest escape-js-string-test
  (testing "returns nil for nil input"
    (is (nil? (utils/escape-js-string nil))))

  (testing "passes through plain strings"
    (is (= "hello" (utils/escape-js-string "hello"))))

  (testing "escapes backslashes"
    (is (= "a\\\\b" (utils/escape-js-string "a\\b"))))

  (testing "escapes single quotes"
    (is (= "it\\'s" (utils/escape-js-string "it's"))))

  (testing "escapes double quotes"
    (is (= "say \\\"hi\\\"" (utils/escape-js-string "say \"hi\""))))

  (testing "escapes newlines and carriage returns"
    (is (= "line1\\nline2" (utils/escape-js-string "line1\nline2")))
    (is (= "line1\\rline2" (utils/escape-js-string "line1\rline2"))))

  (testing "escapes unicode line/paragraph separators"
    (is (= "a\\u2028b" (utils/escape-js-string "a\u2028b")))
    (is (= "a\\u2029b" (utils/escape-js-string "a\u2029b"))))

  (testing "prevents script tag injection"
    (is (= "<\\/" (utils/escape-js-string "</")))))
