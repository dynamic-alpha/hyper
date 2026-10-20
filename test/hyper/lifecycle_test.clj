(ns hyper.lifecycle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.lifecycle :as lifecycle]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.test :as ht]))

;; ---------------------------------------------------------------------------
;; View record (form-3 constructor)
;; ---------------------------------------------------------------------------

(deftest view-record
  (testing "view returns a View record, distinct from bare maps/hiccup"
    (let [v (h/view {:render (fn [_ _] [:div])})]
      (is (lifecycle/view? v))
      (is (not (lifecycle/view? {:render (fn [_ _] [:div])})))
      (is (not (lifecycle/view? [:div])))))

  (testing "view requires a :render fn"
    (is (thrown? clojure.lang.ExceptionInfo (h/view {:mount (fn [] :x)})))))

;; ---------------------------------------------------------------------------
;; form-2 — setup closure runs once per mount
;; ---------------------------------------------------------------------------

(deftest form-2-setup-runs-once
  (testing "the outer setup closure runs once across re-renders; inner is pure"
    (let [setup   (atom 0)
          ;; bound to a stable identity so the page-view fast-path engages
          handler (fn [_req]
                    (swap! setup inc)                  ;; setup effect (once)
                    (let [n* (h/tab-cursor :n 0)]
                      (fn [_req] [:div "n=" @n*])))    ;; pure inner render
          r1      (ht/test-page handler)
          app     (:app-state r1)
          _r2     (ht/test-page handler {:app-state app})
          r3      (ht/test-page handler {:app-state app})]
      (is (= 1 @setup) "setup ran exactly once across three renders")
      (is (= :form-2 (get-in @app [:tabs "test-tab" :page-view :form])))
      (is (str/includes? (:body-html r1) "n=0"))
      (is (str/includes? (:body-html r3) "n=0"))))

  (testing "a different handler identity re-runs setup (re-mount)"
    (let [setup    (atom 0)
          mk       (fn [] (fn [_req]
                            (swap! setup inc)
                            (fn [_req] [:div "x"])))
          handler1 (mk)
          handler2 (mk)
          r1       (ht/test-page handler1)
          app      (:app-state r1)
          _        (ht/test-page handler1 {:app-state app})  ;; fast-path
          _        (ht/test-page handler2 {:app-state app})] ;; re-mount
      (is (= 2 @setup)))))

(defn- redef-page [_req] (fn [_req] [:div "old"]))

(deftest form-2-var-redefinition-remounts
  (testing "a Var handler keeps its mount until the Var is redefined"
    (let [original @#'redef-page
          setup    (atom 0)
          mk       (fn [label] (fn [_req]
                                 (swap! setup inc)
                                 (fn [_req] [:div label])))]
      (try
        (alter-var-root #'redef-page (constantly (mk "old")))
        (let [r1  (ht/test-page #'redef-page)
              app (:app-state r1)
              r2  (ht/test-page #'redef-page {:app-state app})]
          (is (= 1 @setup) "unchanged Var takes the fast path")
          (is (str/includes? (:body-html r2) "old"))
          (alter-var-root #'redef-page (constantly (mk "new")))
          (let [r3 (ht/test-page #'redef-page {:app-state app})]
            (is (= 2 @setup) "redefined Var remounts")
            (is (str/includes? (:body-html r3) "new"))))
        (finally
          (alter-var-root #'redef-page (constantly original)))))))

;; ---------------------------------------------------------------------------
;; form-3 — mount/render/unmount lifecycle
;; ---------------------------------------------------------------------------

(deftest form-3-mount-render-unmount
  (testing "mount runs once, render reads the resource, unmount disposes it"
    (let [mounts   (atom 0)
          unmounts (atom 0)
          handler  (fn [_req]
                     (h/view
                       {:mount   (fn [] (swap! mounts inc) {:conn :open})
                        :render  (fn [res _req] [:div "conn=" (str (:conn res))])
                        :unmount (fn [_res] (swap! unmounts inc))}))
          r1       (ht/test-page handler)
          app      (:app-state r1)
          r2       (ht/test-page handler {:app-state app})]
      (is (= 1 @mounts) "mount ran once across two renders")
      (is (= 0 @unmounts))
      (is (str/includes? (:body-html r1) "conn=:open"))
      (is (str/includes? (:body-html r2) "conn=:open"))
      (is (= :form-3 (get-in @app [:tabs "test-tab" :page-view :form])))
      ;; teardown (as cleanup-tab! does on disconnect)
      (lifecycle/teardown-page-view! app "test-tab")
      (is (= 1 @unmounts) "unmount ran once on teardown")
      (is (nil? (get-in @app [:tabs "test-tab" :page-view])))))

  (testing "a view without :mount renders with a nil resource"
    (let [r (ht/test-page (fn [_req]
                            (h/view {:render (fn [res _req]
                                               [:div "res=" (pr-str res)])})))]
      (is (str/includes? (:body-html r) "res=nil"))))

  (testing "unmount can read session cursors and h/env during disconnect teardown"
    (let [seen    (atom ::unset)
          seen-db (atom ::unset)
          handler (fn [_req]
                    (h/view
                      {:render  (fn [_res _req] [:div "ok"])
                       :unmount (fn [_res]
                                  (reset! seen (some-> @(h/session-cursor :user) :name))
                                  (reset! seen-db (h/env :db)))}))
          r       (ht/test-page handler {:cursors {:session {:user {:name "alice"}}}})
          app     (:app-state r)]
      ;; Mirror the per-tab env the framework stashes on each HTTP request.
      (swap! app assoc-in [:tabs "test-tab" :env] {:db :prod})
      (server/cleanup-tab! app "test-tab")
      (is (= "alice" @seen) "unmount saw the session-cursor value")
      (is (= :prod @seen-db) "unmount saw :hyper/env via h/env")
      (is (nil? (get-in @app [:tabs "test-tab" :page-view]))))))

;; ---------------------------------------------------------------------------
;; Render purity guard
;; ---------------------------------------------------------------------------

(defn- error-app []
  (doto (atom (state/init-state))
    (swap! assoc :render-guard :error)))

(deftest render-guard
  (testing "default-init (default-value arg) is NOT a mutation — no error"
    (let [r (ht/test-page (fn [_req]
                            (let [n* (h/tab-cursor :n 0)]
                              [:div (str @n*)]))
                          {:app-state (error-app)})]
      (is (str/includes? (:body-html r) "0"))))

  (testing "cursor mutation in a render body throws at :render-guard :error"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Effect during render"
          (ht/test-page (fn [_req]
                          (reset! (h/tab-cursor :n 0) 5)
                          [:div])
                        {:app-state (error-app)}))))

  (testing "at the default :warn level the mutation is allowed (still applied)"
    (let [r (ht/test-page (fn [_req]
                            (reset! (h/tab-cursor :n 0) 5)
                            [:div]))]
      (is (= 5 (get-in r [:cursors :tab :n]))))))

(deftest mount-watch-derived-cursor
  (testing "a form-3 mount that creates a defaulted source cursor and a watch
            deriving into a second cursor renders without a guard error, and
            the derived value lands (missionary-style DAG on cursors)"
    (let [app     (error-app)
          handler (fn [_req]
                    (h/view
                      {:mount   (fn []
                                  (let [_entry* (h/tab-cursor :entry {:side :buy})
                                        alloc*  (h/tab-cursor :alloc)]
                                    (add-watch app ::derive
                                               (fn [_ _ old new]
                                                 (let [p [:tabs "test-tab" :data :entry]]
                                                   (when (not= (get-in old p) (get-in new p))
                                                     (reset! alloc* :computed)))))
                                    nil))
                       :render  (fn [_res _req] [:div "ok"])
                       :unmount (fn [_res] (remove-watch app ::derive))}))
          r       (ht/test-page handler {:app-state app})]
      (is (str/includes? (:body-html r) "ok"))
      (is (= {:side :buy} (get-in r [:cursors :tab :entry])))
      (is (= :computed (get-in r [:cursors :tab :alloc]))
          "derived cursor written by the watch during the mount flush"))))

;; ---------------------------------------------------------------------------
;; watch! as a mount-scoped subview (Phase C)
;; ---------------------------------------------------------------------------

(deftest form-2-watch-persists-across-renders
  (testing "a form-2 setup watch! registers one mount-scoped subview that
            survives per-render sweeps"
    (let [src     (atom 0)
          handler (fn [_req]
                    (h/watch! src)                    ;; setup — runs once
                    (fn [_req] [:div "v=" @src]))     ;; pure render
          r1      (ht/test-page handler)
          app     (:app-state r1)
          _       (ht/test-page handler {:app-state app})
          r3      (ht/test-page handler {:app-state app})
          subs    (get-in @app [:tabs "test-tab" :subviews])]
      (is (= 1 (count subs)) "one watch subview after three renders")
      (let [sv (first (vals subs))]
        (is (= :mount (:scope sv)) "watch subview is mount-scoped")
        (is (= :full (:on-change sv)) "watch subview triggers full renders"))
      (is (= [src] (subview/watched-sources app "test-tab")))
      (is (= [src] (:watches r3)) "test-page reports the watched source"))))

(deftest form-2-watch-torn-down-on-nav
  (testing "navigating (handler identity change) tears down the old watch and
            registers the new one"
    (let [src-a     (atom 0)
          src-b     (atom 0)
          handler-a (fn [_req] (h/watch! src-a) (fn [_req] [:div "A"]))
          handler-b (fn [_req] (h/watch! src-b) (fn [_req] [:div "B"]))
          r1        (ht/test-page handler-a)
          app       (:app-state r1)]
      (is (= [src-a] (subview/watched-sources app "test-tab")))
      ;; "navigate" — a different handler identity remounts the page-view
      (ht/test-page handler-b {:app-state app})
      (is (= [src-b] (subview/watched-sources app "test-tab"))
          "old watch (src-a) torn down on remount; new watch (src-b) live"))))

(deftest reactive-swept-while-watch-survives
  (testing "a disappearing reactive subview is swept while a sibling watch
            subview survives"
    (let [src     (atom 0)
          handler (fn [_req]
                    (h/watch! src)                    ;; mount-scoped
                    (let [show?* (h/tab-cursor :show? true)]
                      (fn [_req]
                        [:div
                         (when @show?*
                           (h/reactive [(h/tab-cursor :x 0)]
                                       [:p "shown"]))])))
          r1      (ht/test-page handler)
          app     (:app-state r1)]
      (is (= 2 (count (get-in @app [:tabs "test-tab" :subviews])))
          "watch + reactive subviews both present")
      ;; Hide the reactive block and re-render
      (swap! app assoc-in [:tabs "test-tab" :data :show?] false)
      (ht/test-page handler {:app-state app})
      (let [subs (get-in @app [:tabs "test-tab" :subviews])]
        (is (= 1 (count subs)) "reactive swept, watch survives")
        (is (= :mount (:scope (first (vals subs)))))
        (is (= [src] (subview/watched-sources app "test-tab")))))))

;; ---------------------------------------------------------------------------
;; Mount boundary keyed on [handler path-params]
;; ---------------------------------------------------------------------------

(defn- detail-route
  ([id] (detail-route id nil))
  ([id q] {:name         :detail
           :path         (str "/detail/" id)
           :path-params  {:id id}
           :query-params (if q {:q q} {})}))

(deftest path-param-change-remounts-form-2-and-resubscribes-watch
  (testing "a path-param change remounts a form-2 page-view, re-running setup so
            a path-param-derived watch re-subscribes to the new source"
    (let [sources {"a" (atom {:id "a"}) "b" (atom {:id "b"})}
          handler (fn [req]
                    (let [id  (get-in req [:hyper/route :path-params :id])
                          src (get sources id)]
                      (h/watch! src)                          ;; form-2 setup
                      (fn [_req] [:div "id=" (:id @src)])))   ;; pure render
          r1      (ht/test-page handler {:route (detail-route "a")})
          app     (:app-state r1)]
      (is (str/includes? (:body-html r1) "id=a"))
      (is (= [(sources "a")] (subview/watched-sources app "test-tab")))
      ;; same handler, different :id path-param => remount
      (let [r2 (ht/test-page handler {:app-state app :route (detail-route "b")})]
        (is (str/includes? (:body-html r2) "id=b")
            "form-2 setup re-ran on the path-param change (render is current)")
        (is (= [(sources "b")] (subview/watched-sources app "test-tab"))
            "old watch torn down; only the new path-param's source is watched")))))

(deftest path-param-change-tears-down-stale-form-1-watch
  (testing "a form-1 body watch keyed on a path-param does not accumulate stale
            subscriptions across a path-param change (the remount tears down the old)"
    (let [sources {"a" (atom {:id "a"}) "b" (atom {:id "b"})}
          handler (fn [req]
                    (let [id  (get-in req [:hyper/route :path-params :id])
                          src (get sources id)]
                      (h/watch! src)                          ;; form-1 body
                      [:div "id=" (:id @src)]))
          r1      (ht/test-page handler {:route (detail-route "a")})
          app     (:app-state r1)
          r2      (ht/test-page handler {:app-state app :route (detail-route "b")})]
      (is (str/includes? (:body-html r2) "id=b"))
      (is (= [(sources "b")] (subview/watched-sources app "test-tab"))
          "only the current path-param's source is watched — no [a b] accumulation"))))

(deftest query-param-change-does-not-remount
  (testing "a query-param-only change re-renders without remounting (form-3
            resource preserved); a path-param change remounts (resource rebuilt)"
    (let [mounts   (atom 0)
          unmounts (atom 0)
          handler  (fn [req]
                     (let [id (get-in req [:hyper/route :path-params :id])]
                       (h/view
                         {:mount   (fn [] (swap! mounts inc) {:id id})
                          :render  (fn [res req]
                                     [:div "id=" (:id res)
                                      " q=" (str (get-in req [:hyper/route :query-params :q]))])
                          :unmount (fn [_] (swap! unmounts inc))})))
          r1       (ht/test-page handler {:route (detail-route "a" "x")})
          app      (:app-state r1)]
      (is (= 1 @mounts))
      (is (str/includes? (:body-html r1) "id=a"))
      ;; query-param-only change: MUST NOT remount
      (let [r2 (ht/test-page handler {:app-state app :route (detail-route "a" "y")})]
        (is (= 1 @mounts) "query-param change did not remount")
        (is (= 0 @unmounts) "form-3 resource preserved across the query-param change")
        (is (str/includes? (:body-html r2) "id=a"))
        (is (str/includes? (:body-html r2) "q=y") "render updated without a remount"))
      ;; path-param change: MUST remount and rebuild the resource
      (let [r3 (ht/test-page handler {:app-state app :route (detail-route "b" "y")})]
        (is (= 2 @mounts) "path-param change remounted")
        (is (= 1 @unmounts) "old resource unmounted")
        (is (str/includes? (:body-html r3) "id=b"))))))
