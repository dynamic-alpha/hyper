(ns hyper.action-queue-test
  "The action queue `@hyperAction` posts: actions run once, in the order they
   were made, against the render they were made on."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.render :as render]
            [hyper.state :as state]
            [hyper.subview :as subview]))

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
  [{:keys [id seq v region value]}]
  {:id      id
   :seq     seq
   :v       v
   :region  region
   :signals "{}"
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

(deftest test-a-throwing-action-does-not-stop-the-ones-after-it
  (let [page (fn [_]
               [:div
                [:button {:data-on:click (h/action (throw (ex-info "boom" {})))} "boom"]
                [:button {:data-on:click (h/action (effects/set-cookie! "ok" "yes")
                                                   (swap! (h/tab-cursor :oks 0) inc))}
                 "ok"]])
        oks  (fn [app*] (get-in @app* [:tabs "T" :data :oks]))]
    (testing "in one request"
      (let [[handler app*] (page-handler page)
            html           (get-page handler)
            v              (render-version html)
            boom           (queued {:id (action-id html "boom") :seq 1 :v v})
            ok             (queued {:id (action-id html "ok") :seq 2 :v v})
            response       (post-queue! handler v [boom ok])]
        (is (= 1 (oks app*)) "the action after the throwing one runs")
        (is (= 500 (:status response)))
        (is (= "boom" (:error (json/parse-string (:body response) true))))
        (is (some #(str/starts-with? % "ok=yes") (get-in response [:headers "Set-Cookie"]))
            "the cookies of the actions that ran are kept")
        (testing "and a later request is not blocked"
          (let [ok-again (queued {:id (action-id html "ok") :seq 3 :v v})]
            (is (= 204 (:status (post-queue! handler v [boom ok ok-again]))))
            (is (= 2 (oks app*)))))))
    (testing "when the earlier request arrives first"
      (let [[handler app*] (page-handler page)
            html           (get-page handler)
            v              (render-version html)
            boom           (queued {:id (action-id html "boom") :seq 1 :v v})
            ok             (queued {:id (action-id html "ok") :seq 2 :v v})]
        (is (= 500 (:status (post-queue! handler v [boom]))))
        (is (= 204 (:status (post-queue! handler v [boom ok]))))
        (is (= 1 (oks app*)))))))

(deftest test-a-failed-assertion-does-not-stop-the-actions-after-it
  (let [[handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:button {:data-on:click (h/action (assert false "nope"))} "assert"]
                            [:button {:data-on:click (h/action (throw (AssertionError.)))} "bare"]
                            [:button {:data-on:click (h/action (swap! (h/tab-cursor :oks 0) inc))} "ok"]]))
        html           (get-page handler)
        v              (render-version html)
        ok             #(queued {:id (action-id html "ok") :seq % :v v})
        oks            #(get-in @app* [:tabs "T" :data :oks])
        response       (post-queue! handler v [(queued {:id (action-id html "assert") :seq 1 :v v}) (ok 2)])]
    (is (= 1 (oks)) "the action after the failed assertion runs")
    (is (= 500 (:status response)))
    (is (str/includes? (:error (json/parse-string (:body response) true)) "nope"))
    (testing "an error without a message still gets a JSON error body"
      (let [response (post-queue! handler v [(queued {:id (action-id html "bare") :seq 3 :v v}) (ok 4)])]
        (is (= 2 (oks)))
        (is (= 500 (:status response)))
        (is (contains? (json/parse-string (:body response) true) :error))))
    (testing "a later request still runs a new action"
      (is (= 204 (:status (post-queue! handler v [(ok 5)]))))
      (is (= 3 (oks))))))

(defn- partial-render!
  "Re-render region `sid` on its own, as the renderer does when its deps
   change.  Returns the HTML and its render version."
  [app* sid]
  (let [html (subview/partial-render app* "T" sid)]
    [html (some-> (re-find #"^<[^>]*data-hyper-render-version=\"(\d+)\"" html) second parse-long)]))

(defn- without-version [html]
  (str/replace html #" data-hyper-render-version=\"\d+\"" ""))

(defn- region-renders [app* sid]
  (get-in @app* [:tabs "T" :renders :regions sid]))

(deftest test-a-region-keeps-its-renders-whatever-other-regions-do
  (let [a*             (atom 0)
        b*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div
                            (h/reactive {:key :a} [a*]
                                        [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))}
                                               (str "a" @a*)]])
                            (h/reactive {:key :b} [b*] [:div (str "b" @b*)])]))
        base           (render-version (get-page handler))
        clicks         #(get-in @app* [:tabs "T" :data :clicks])
        [a-html va]    (do (swap! a* inc) (partial-render! app* "r_T_a"))
        id             (action-id a-html "a1")]
    (post-queue! handler base [(queued {:id id :seq 1 :v va :region "r_T_a"})])
    (is (= 1 (clicks)))
    (dotimes [_ 70]
      (swap! b* inc)
      (partial-render! app* "r_T_b"))
    (testing "another region's renders do not push out a region's current one"
      (post-queue! handler base [(queued {:id id :seq 2 :v va :region "r_T_a"})])
      (is (= 2 (clicks))))
    (testing "a region that re-renders constantly keeps at most the cap"
      (is (= actions/max-renders-per-view (count (:versions (region-renders app* "r_T_b")))))
      (is (contains? (:versions (region-renders app* "r_T_b"))
                     (:version (region-renders app* "r_T_b")))))))

(deftest test-a-region-render-after-a-full-render-gets-a-new-version
  (let [open*          (atom false)
        n*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:span "n=" @n*]
                            (h/reactive {:key :r} [open*]
                                        (if @open*
                                          [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))} "open"]]
                                          [:div "closed"]))]))
        _v1            (render-version (get-page handler))
        [open-html v2] (do (reset! open* true) (partial-render! app* "r_T_r"))
        v3             (do (reset! open* false)
                           (swap! n* inc)
                           (:render-version (rerender! app*)))
        [again v4]     (do (reset! open* true) (partial-render! app* "r_T_r"))]
    (is (= (without-version open-html) (without-version again))
        "the region renders the same HTML again")
    (is (< v3 v4) "but after a newer full render it gets a new version")
    (testing "so dropping the renders before the page's does not drop it"
      (post-queue! handler v3 [])
      (post-queue! handler v3 [(queued {:id (action-id again "open") :seq 1 :v v4 :region "r_T_r"})])
      (is (= 1 (get-in @app* [:tabs "T" :data :clicks]))))))

(deftest test-a-removed-region-frees-its-renders
  (let [show*          (atom true)
        x*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:span "show=" (str @show*)]
                            (when @show*
                              (h/reactive {:key :x} [x*]
                                          [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))}
                                                 (str "x" @x*)]]))]))
        v1             (render-version (get-page handler))
        full-render!   #(let [result (rerender! app*)]
                          (subview/sweep-stale! app* "T" (:registered-subview-ids result))
                          result)
        clicks         #(get-in @app* [:tabs "T" :data :clicks])]
    (testing "a region only rendered with the page keeps no renders of its own"
      (dotimes [_ 3] (full-render!))
      (is (nil? (region-renders app* "r_T_x"))))
    (let [[html vx] (do (swap! x* inc) (partial-render! app* "r_T_x"))
          id        (action-id html "x1")]
      (testing "a region that re-rendered once keeps one render"
        (dotimes [_ 3] (full-render!))
        (is (= 1 (count (:versions (region-renders app* "r_T_x"))))))
      (reset! show* false)
      (let [v-removed (:render-version (full-render!))]
        (testing "a click made on it before the client shows the page without it still runs"
          (post-queue! handler v1 [(queued {:id id :seq 1 :v vx :region "r_T_x"})])
          (is (= 1 (clicks))))
        (testing "once the client shows the page without it, its renders are dropped"
          (post-queue! handler v-removed [])
          (is (nil? (region-renders app* "r_T_x")))
          (post-queue! handler v-removed [(queued {:id id :seq 2 :v vx :region "r_T_x"})])
          (is (= 1 (clicks))))))))

(deftest test-region-renders-go-with-the-page-renders-they-were-shown-on
  (let [n*             (atom 0)
        x*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:span "n=" @n*]
                            (h/reactive {:key :x} [x*] [:div "x=" @x*])]))
        _              (get-page handler)
        new-page!      #(do (swap! n* inc) (rerender! app*))]
    (swap! x* inc)
    (partial-render! app* "r_T_x")
    (testing "a tab that never reports its page version keeps a region's render
              while a page render it could be shown on is kept"
      (dotimes [_ (dec actions/max-renders-per-view)] (new-page!))
      (is (= actions/max-renders-per-view (count (get-in @app* [:tabs "T" :renders :page :versions]))))
      (is (some? (region-renders app* "r_T_x"))))
    (testing "and drops it with the last of those page renders"
      (new-page!)
      (is (nil? (region-renders app* "r_T_x"))))))

(deftest test-a-click-on-a-region-render-in-flight-across-a-full-render
  (let [x*             (atom 0)
        n*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div
                            [:span "n=" @n*]
                            (h/reactive {:key :x} [x*]
                                        [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))}
                                               (str "x" @x*)]])]))
        v1             (render-version (get-page handler))
        [html vx]      (do (swap! x* inc) (partial-render! app* "r_T_x"))
        id             (action-id html "x1")
        v3             (do (swap! n* inc) (:render-version (rerender! app*)))
        clicks         #(get-in @app* [:tabs "T" :data :clicks])]
    (is (< vx v3))
    (testing "runs while the client still shows the older page"
      (post-queue! handler v1 [(queued {:id id :seq 1 :v vx :region "r_T_x"})])
      (is (= 1 (clicks))))
    (testing "and no longer once the client shows the newer full render"
      (post-queue! handler v3 [])
      (post-queue! handler v3 [(queued {:id id :seq 2 :v vx :region "r_T_x"})])
      (is (= 1 (clicks))))))

(deftest test-a-click-on-a-region-render-drops-its-earlier-renders
  (let [x*             (atom 0)
        [handler app*] (page-handler
                         (fn [_]
                           [:div (h/reactive {:key :x} [x*]
                                             [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :clicks 0) inc))}
                                                    (str "x" @x*)]])]))
        base           (render-version (get-page handler))
        [_ v-old]      (do (swap! x* inc) (partial-render! app* "r_T_x"))
        [html v-new]   (do (swap! x* inc) (partial-render! app* "r_T_x"))]
    (is (= #{v-old v-new} (set (keys (:versions (region-renders app* "r_T_x"))))))
    (post-queue! handler base [(queued {:id (action-id html "x2") :seq 1 :v v-new :region "r_T_x"})])
    (is (= 1 (get-in @app* [:tabs "T" :data :clicks])))
    (is (= #{v-new} (set (keys (:versions (region-renders app* "r_T_x")))))
        "the client has applied the newer render, so the older one is dropped")))

(deftest test-a-reloaded-tab-numbers-on-from-the-server
  (let [[handler app*] (page-handler (fn [_] [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :n 0) inc))} "+"]]))
        html           (get-page handler)
        v              (render-version html)
        id             (action-id html "\\+")
        n              #(get-in @app* [:tabs "T" :data :n])]
    (is (str/includes? html "let lastSeq = 0;"))
    (post-queue! handler v [(queued {:id id :seq 1 :v v}) (queued {:id id :seq 2 :v v})])
    (is (= 2 (n)))
    (testing "loading the page again with the same tab id"
      (let [html (get-page handler)
            v    (render-version html)]
        (is (str/includes? html "let lastSeq = 2;")
            "the client numbers its actions on from the ones the server ran")
        (post-queue! handler v [(queued {:id (action-id html "\\+") :seq 3 :v v})])
        (is (= 3 (n)) "so its first action runs")))))

(deftest test-unknown-tab
  (let [[handler _] (page-handler (fn [_] [:div]))]
    (is (= 404 (:status (post-queue! handler nil [(queued {:id "a_T_1" :seq 1})]))))))

(deftest test-a-single-action-cannot-be-posted-by-id
  (let [[handler app*] (page-handler (fn [_] [:div [:button {:data-on:click (h/action (swap! (h/tab-cursor :n 0) inc))} "+"]]))
        id             (action-id (get-page handler) "\\+")]
    (is (= 400 (:status (handler {:request-method :post
                                  :uri            "/hyper/actions"
                                  :query-string   (str "action-id=" id)
                                  :headers        {}}))))
    (is (nil? (get-in @app* [:tabs "T" :data :n])))))
