(ns hyper.action-queue-test
  "The action queue `@hyperAction` posts: actions run once, in the order they
   were made, against the render they were made on."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.render :as render]
            [hyper.state :as state]))

(defn- page-handler
  "A hyper handler serving `page` at /, and its app-state."
  [page]
  (let [app* (atom (state/init-state))]
    [(h/create-handler [["/" {:name :home :get page}]] :app-state app*) app*]))

(defn- get-page [handler]
  (:body (handler {:request-method :get :uri "/" :query-string "tab-id=T" :headers {}})))

(defn- action-id
  "The id of the action on the element whose text is `label`."
  [html label]
  (second (re-find (re-pattern (str "hyperAction\\(&apos;(a_T_\\d+)&apos;[^\"]*\">" label "<")) html)))

(defn- render-version [html]
  (some-> (re-find #"id=\"hyper-app\"[^>]*data-hyper-render-version=\"(\d+)\"" html) second parse-long))

(defn- post-queue!
  "Post `actions` as the page's queue, as @hyperAction does."
  [handler base actions]
  (handler {:request-method :post
            :uri            "/hyper/actions"
            :query-string   "tab-id=T"
            :headers        {"content-type" "application/json"}
            :body           (json/generate-string {:base base :actions actions})}))

(defn- queued
  "An action as @hyperAction queues it."
  [{:keys [id seq v value signals]}]
  {:id      id
   :seq     seq
   :v       v
   :signals (json/generate-string (or signals {}))
   :params  (when value (json/generate-string {:value value}))})

(defn- rerender! [app*]
  (render/render-tab app* (get-in @app* [:tabs "T" :session-id]) "T"))

(deftest test-actions-run-in-the-order-they-were-made
  (let [[handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:select {:data-on:change (h/action (swap! (h/tab-cursor :picks []) conj $value))}]
                            [:span "picks"]]))
        html           (get-page handler)
        v              (render-version html)
        id             (second (re-find #"hyperAction\(&apos;(a_T_\d+)" html))
        bmw            (queued {:id id :seq 1 :v v :value "BMW"})
        kia            (queued {:id id :seq 2 :v v :value "Kia"})
        picks          #(get-in @app* [:tabs "T" :data :picks])]
    (testing "the later request arrives first, carrying both actions"
      (is (= 204 (:status (post-queue! handler v [bmw kia]))))
      (is (= ["BMW" "Kia"] (picks))))
    (testing "the earlier request arrives last: its action already ran"
      (is (= 204 (:status (post-queue! handler v [bmw]))))
      (is (= ["BMW" "Kia"] (picks))))
    (testing "a new action runs once, however often it is sent"
      (let [vw (queued {:id id :seq 3 :v v :value "VW"})]
        (post-queue! handler v [vw])
        (post-queue! handler v [vw])
        (is (= ["BMW" "Kia" "VW"] (picks)))))))

(deftest test-actions-run-against-the-render-they-were-made-on
  (let [[handler app*] (page-handler
                         (fn [_]
                           [:ul
                            (for [item @(h/tab-cursor :items ["Apples" "Bread" "Dates" "Eggs"])]
                              [:li [:button {:data-on:click (h/action (swap! (h/tab-cursor :items) #(vec (remove #{item} %))))}
                                    (str "Remove " item)]])]))
        html           (get-page handler)
        v1             (render-version html)
        items          #(get-in @app* [:tabs "T" :data :items])]
    (post-queue! handler v1 [(queued {:id (action-id html "Remove Apples") :seq 1 :v v1})])
    (let [v2 (:render-version (rerender! app*))]
      (is (not= v1 v2) "removing a row is a new render")
      (testing "a click made on the first render runs that render's action"
        (post-queue! handler v1 [(queued {:id (action-id html "Remove Dates") :seq 2 :v v1})])
        (is (= ["Bread" "Eggs"] (items)))))))

(deftest test-render-versions
  (let [[handler app*] (page-handler (fn [_] [:div "n=" @(h/tab-cursor :n 0)
                                              [:button {:data-on:click (h/action (swap! (h/tab-cursor :n) inc))} "+"]]))
        html           (get-page handler)
        v1             (render-version html)
        id             (action-id html "\\+")]
    (testing "a render of the same HTML keeps the version"
      (is (= v1 (:render-version (rerender! app*)))))
    (testing "a render of different HTML gets a new version"
      (post-queue! handler v1 [(queued {:id id :seq 1 :v v1})])
      (let [v2 (:render-version (rerender! app*))]
        (is (< v1 v2))
        (testing "and once the client shows it, earlier renders are dropped"
          (post-queue! handler v2 [])
          (is (= 1 (get-in @app* [:tabs "T" :data :n])))
          (post-queue! handler v2 [(queued {:id id :seq 2 :v v1})])
          (is (= 1 (get-in @app* [:tabs "T" :data :n])) "a click on a dropped render runs nothing")
          (post-queue! handler v2 [(queued {:id id :seq 3 :v v2})])
          (is (= 2 (get-in @app* [:tabs "T" :data :n]))))))))

(deftest test-server-writes-win-over-older-client-copies
  (let [!status        (atom nil)
        [handler app*] (page-handler (fn [_]
                                       (let [status (h/signal :status "idle")]
                                         (reset! !status status)
                                         [:div [:button {:data-on:click (h/action (reset! (h/tab-cursor :seen) @status))}
                                                "look"]])))
        html           (get-page handler)
        v              (render-version html)
        id             (action-id html "look")
        page-seq       (some-> (re-find #"data-signals:hyper-signal-seq=\"(\d+)\"" html) second parse-long)
        drain!         #(#'hyper.server/drain-client-signals! app* "T")
        status         #(get-in @app* [:tabs "T" :signals :status])
        seen           #(get-in @app* [:tabs "T" :data :seen])]
    (is (some? page-seq) "the page carries the signal patch number")
    (testing "the server writes while an action made before seeing the write is on its way"
      (reset! @!status "done")
      (post-queue! handler v [(queued {:id id :seq 1 :v v :signals {:status "uploading" :hyperSignalSeq page-seq}})])
      (is (= "done" (seen)) "the action sees the server's value")
      (is (= "done" (status)) "and the server keeps it")
      (let [[client-reported current] (drain!)]
        (is (= "uploading" (:status client-reported)) "the client is known to hold its own value")
        (is (= "done" (:status current)) "so the server's is patched to it")
        (testing "once the client applied that patch, its values count again"
          (post-queue! handler v [(queued {:id id :seq 2 :v v :signals {:status "typed" :hyperSignalSeq (:hyper-signal-seq current)}})])
          (is (= "typed" (seen)))
          (is (= "typed" (status))))))
    (testing "a client that sends no patch number is taken as is"
      (reset! @!status "done")
      (post-queue! handler v [(queued {:id id :seq 3 :v v :signals {:status "old"}})])
      (is (= "old" (seen))))))

(deftest test-unknown-tab
  (let [[handler _] (page-handler (fn [_] [:div]))]
    (is (= 404 (:status (post-queue! handler nil [(queued {:id "a_T_1" :seq 1})]))))))
