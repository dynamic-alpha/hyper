(ns hyper.client-params-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.client-params :as params]))

(defmethod params/client-param '$mouse-x-offset
  [_]
  {:js  "evt.offsetX"
   :key "mouseXOffset"})

(deftest can-extend-params
  (testing "can access definition for custom symbol"
    (is (= {:js  "evt.offsetX"
            :key "mouseXOffset"}
           (params/client-param '$mouse-x-offset))))

  (testing "custom symbol is listed as defined"
    (is (= '#{$mouse-x-offset
              $checked
              $form-data
              $key
              $value}
           (params/defined-client-params)))))

(deftest unknown-param
  (when-let [e (is (thrown? Exception
                            (params/client-param '$xyzzyx)))]
    (is (= "Unknown client-param value: $xyzzyx - extend multi hyper.client-params/client-param to add"
           (ex-message e)))
    (is (=
          '{:defined-symbols #{$checked
                               $form-data
                               $key
                               $mouse-x-offset
                               $value}
            :symbol          $xyzzyx}
          (ex-data e)))))
