(ns hyper.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.protocols :as proto]
            [hyper.render :as render]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.watch :as watch]))

;; A test source that tracks watches and disposal for assertions.
(deftype DisposableSource [watches* disposed*]
  proto/Watchable
  (-add-watch [_this key callback]
    (swap! watches* assoc key callback))
  (-remove-watch [_this key]
    (swap! watches* dissoc key))
  (-dispose [_this]
    (swap! disposed* inc)))

(defn- wire-watch!
  "Register a mount-scoped watch with a renderer present, so it wires
   immediately through the subview engine (as h/watch! does when a renderer
   exists).  Returns the subview id."
  ([app-state* tab-id source] (wire-watch! app-state* tab-id source (fn [])))
  ([app-state* tab-id source trigger-render!]
   (swap! app-state* update-in [:tabs tab-id :renderer]
          #(merge {:trigger-render! trigger-render! :trigger-partial! (fn [_])} %))
   (subview/register-watch! app-state* tab-id source)))

(deftest test-watchers
  (testing "Watchers trigger callback on state change"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-4"
          tab-id          "test_tab_4"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          render-fn       (fn [_req]
                            [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (watch/setup-watchers! app-state* tab-id trigger-render!)

      ;; Change tab state
      (swap! app-state* assoc-in [:tabs tab-id :data :count] 1)
      (Thread/sleep 50)

      (is (>= @trigger-count 1))

      ;; Change session state
      (swap! app-state* assoc-in [:sessions session-id :data :user] "Alice")
      (Thread/sleep 50)

      (is (>= @trigger-count 2))

      ;; Change global state
      (swap! app-state* assoc-in [:global :theme] "dark")
      (Thread/sleep 50)

      (is (>= @trigger-count 3))

      ;; Clean up watchers
      (watch/remove-watchers! app-state* tab-id))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-5"
          tab-id          "test_tab_5"
          stopped?        (atom false)
          trigger-render! (fn [])
          render-fn       (fn [_req] [:div "test"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Store a mock renderer handle with a stop! fn
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!
              :stop!           #(reset! stopped? true)})

      (watch/setup-watchers! app-state* tab-id trigger-render!)

      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))

      ;; Cleanup
      (server/cleanup-tab! app-state* tab-id)

      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is @stopped? "Renderer stop! should have been called"))))

(deftest test-external-watch
  (testing "a wired watch subview triggers the callback when its source changes"
    (let [app-state*    (atom (state/init-state))
          tab-id        "test_tab_ext"
          trigger-count (atom 0)
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* "test-session-ext" tab-id)

      (wire-watch! app-state* tab-id external-atom #(swap! trigger-count inc))

      ;; Mutate the external atom
      (swap! external-atom inc)
      (is (= 1 @trigger-count) "trigger-render! should have been called")

      ;; Clean up
      (subview/teardown-mount-scoped! app-state* tab-id))))

(deftest test-pending-watches-stash
  (testing "watch! with no renderer registers an unwired mount-scoped subview"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-pending"
          tab-id        "test_tab_pending"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; No renderer set up — trigger-render! will be nil, so the watch is
      ;; registered as a subview but not yet wired.
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Registered as a mount-scoped, full-render subview with the source as dep
      (let [subviews (get-in @app-state* [:tabs tab-id :subviews])]
        (is (= 1 (count subviews)) "should register exactly one watch subview")
        (let [sv (first (vals subviews))]
          (is (= :mount (:scope sv)))
          (is (= :full (:on-change sv)))
          (is (= [external-atom] (:deps sv)))))

      ;; Recoverable via watched-sources
      (is (= [external-atom] (subview/watched-sources app-state* tab-id)))

      ;; Should NOT have a real watch on the atom yet (unwired)
      (is (empty? (.getWatches external-atom))
          "external atom should have no real watches yet")
      (is (empty? (get-in @app-state* [:tabs tab-id :subview-watches]))
          "no dep watches wired without a renderer"))))

(deftest test-pending-watches-stash-idempotent
  (testing "watch! registering the same source twice is idempotent"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-idem"
          tab-id        "test_tab_idem"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom)
        (h/watch! external-atom))

      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews])))
          "duplicate watch! calls should not create multiple subviews"))))

(deftest test-promote-pending-watches
  (testing "setup-new-watches! wires an unwired watch subview and it fires"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-promote"
          tab-id          "test_tab_promote"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Register via watch! with no renderer (unwired subview)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Verify registered but unwired
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews]))))
      (is (empty? (.getWatches external-atom)))

      ;; Wire pending subviews (as the first SSE full render does)
      (subview/setup-new-watches! app-state* tab-id trigger-render! nil)

      ;; Real watch should exist on the atom
      (is (= 1 (count (.getWatches external-atom)))
          "external atom should have a real watch after wiring")

      ;; Dep watch should be tracked under :subview-watches
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subview-watches])))
          "wired watch should be tracked under :subview-watches")

      ;; Mutating the atom should trigger a full render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count) "trigger-render! should fire when source changes")

      ;; Wiring again is idempotent (dedup)
      (subview/setup-new-watches! app-state* tab-id trigger-render! nil)
      (is (= 1 (count (.getWatches external-atom)))
          "re-wiring should not add duplicate watches")

      ;; Clean up
      (subview/teardown-all! app-state* tab-id))))

(deftest test-watch-with-trigger-render-still-works
  (testing "watch! with trigger-render! present still creates real watches directly"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-direct"
          tab-id          "test_tab_direct"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Set up a renderer so trigger-render! is available
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!})

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Should NOT use pending-watches path
      (is (nil? (get-in @app-state* [:tabs tab-id :pending-watches]))
          "should not stash when trigger-render! is present")

      ;; Real watch should exist directly
      (is (= 1 (count (.getWatches external-atom)))
          "real watch should be on the atom")

      ;; Mutating the atom should trigger render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count))

      ;; Calling watch! again with the same source is idempotent
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (= 1 (count (.getWatches external-atom)))
          "duplicate watch! should not create additional watches")

      ;; Clean up
      (subview/teardown-mount-scoped! app-state* tab-id))))

(deftest test-navigation-tears-down-mount-watches-keeps-tab-watches
  (testing "navigation (mount-scoped teardown) removes :mount watches but keeps
            :tab framework watches"
    (let [app-state*    (atom (state/init-state))
          tab-id        "test_tab_nav"
          user-src      (atom 0)     ;; user h/watch! — :mount
          framework-src (atom 0)]    ;; routes/registry-style — :tab

      (state/get-or-create-tab! app-state* "test-session-nav" tab-id)

      ;; A user watch is mount-scoped; a framework watch is tab-scoped.  Both
      ;; wire immediately because wire-watch! installed a renderer.
      (wire-watch! app-state* tab-id user-src)
      (subview/register-watch! app-state* tab-id framework-src :tab)

      (is (= 2 (count (get-in @app-state* [:tabs tab-id :subviews])))
          "both watches registered")
      (is (= 1 (count (.getWatches user-src))))
      (is (= 1 (count (.getWatches framework-src))))

      ;; Navigation tears down mount-scoped subviews (page-view remount).
      (subview/teardown-mount-scoped! app-state* tab-id)

      (let [subs (vals (get-in @app-state* [:tabs tab-id :subviews]))]
        (is (= 1 (count subs)) "only the :tab framework watch survives")
        (is (= :tab (:scope (first subs))))
        (is (= [framework-src] (:deps (first subs)))))
      (is (empty? (.getWatches user-src))
          "user (mount) watch removed on navigation")
      (is (= 1 (count (.getWatches framework-src)))
          "framework (tab) watch survives navigation"))))

(deftest test-cleanup-clears-pending-watches
  (testing "cleanup-tab! tears down an unwired watch subview with the whole tab"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-cleanup-pw"
          tab-id        "test_tab_cleanup_pw"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Register an unwired watch subview (no renderer)
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews]))))

      ;; Cleanup the tab
      (server/cleanup-tab! app-state* tab-id)

      ;; Tab should be completely gone
      (is (not (contains? (:tabs @app-state*) tab-id))))))

;; ---------------------------------------------------------------------------
;; Disposal tests
;; ---------------------------------------------------------------------------

(deftest test-dispose-called-on-watch-removal
  (testing "dispose is called when a watch subview is torn down"
    (let [app-state* (atom (state/init-state))
          tab-id     "test_tab_dispose"
          disposed*  (atom 0)
          source     (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* "sess" tab-id)

      (wire-watch! app-state* tab-id source)
      (is (zero? @disposed*) "should not be disposed yet")

      (subview/teardown-mount-scoped! app-state* tab-id)
      (is (= 1 @disposed*) "should be disposed after teardown"))))

(deftest test-dispose-called-on-navigation
  (testing "dispose is called when navigating away (route watch, page-view remount)"
    (let [disposed*  (atom 0)
          source     (->DisposableSource (atom {}) disposed*)
          page-a     (fn [_req] [:div "A"])
          page-b     (fn [_req] [:div "B"])
          rts        [["/a" {:name :a :get page-a :watches [source]}]
                      ["/b" {:name :b :get page-b}]]
          app-state* (atom (assoc (state/init-state)
                                  :routes rts :global-watches []))]

      (state/get-or-create-tab! app-state* "s" "t")
      (state/set-tab-route! app-state* "t"
                            {:name :a :path "/a" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-a)
      (render/render-tab app-state* "s" "t")
      (subview/setup-new-watches! app-state* "t" (fn []) nil)  ;; wire the route watch
      (is (zero? @disposed*))

      ;; Navigate to /b (different handler -> page-view remount)
      (state/set-tab-route! app-state* "t"
                            {:name :b :path "/b" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-b)
      (render/render-tab app-state* "s" "t")

      (is (= 1 @disposed*) "should be disposed after navigation"))))

(deftest test-dispose-called-on-tab-disconnect
  (testing "dispose is called when tab disconnects"
    (let [app-state* (atom (state/init-state))
          tab-id     "test_tab_dispose_dc"
          disposed*  (atom 0)
          source     (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* "test-session-dispose-dc" tab-id)
      (wire-watch! app-state* tab-id source)
      (is (zero? @disposed*))

      (server/cleanup-tab! app-state* tab-id)
      (is (= 1 @disposed*) "should be disposed on tab disconnect"))))

(deftest test-dispose-refcounted-across-tabs
  (testing "shared source is only disposed when the last tab releases it"
    (let [app-state*    (atom (state/init-state))
          tab-id-a      "test_tab_rc_a"
          tab-id-b      "test_tab_rc_b"
          disposed*     (atom 0)
          shared-source (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* "test-session-refcount" tab-id-a)
      (state/get-or-create-tab! app-state* "test-session-refcount" tab-id-b)

      ;; Both tabs watch the same source
      (wire-watch! app-state* tab-id-a shared-source)
      (wire-watch! app-state* tab-id-b shared-source)
      (is (zero? @disposed*))

      ;; First tab tears down — source should NOT be disposed
      (subview/teardown-all! app-state* tab-id-a)
      (is (zero? @disposed*)
          "should not dispose while second tab still watches")

      ;; Second tab tears down — source should now be disposed
      (subview/teardown-all! app-state* tab-id-b)
      (is (= 1 @disposed*)
          "should dispose after last tab releases"))))

(deftest test-dispose-not-called-for-plain-atoms
  (testing "plain atoms (IRef) are not affected by dispose (no-op)"
    (let [app-state*    (atom (state/init-state))
          tab-id        "test_tab_atom_dispose"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* "sess" tab-id)
      (wire-watch! app-state* tab-id external-atom)

      ;; Should not throw — IRef -dispose is a no-op
      (subview/teardown-mount-scoped! app-state* tab-id)

      ;; Atom is still usable
      (is (= 0 @external-atom)))))

;; ---------------------------------------------------------------------------
;; Route-level :watches as mount-scoped subviews (unified via render-tab)
;; ---------------------------------------------------------------------------

(deftest test-route-watches-as-subviews
  (testing "route :watches + global :watches register as mount-scoped subviews,
            wire, and trigger full renders"
    (let [global-src (atom 0)
          route-src  (atom 0)
          page-a     (fn [_req] [:div "A"])
          rts        [["/a" {:name :a :get page-a :watches [route-src]}]]
          app-state* (atom (assoc (state/init-state)
                                  :routes rts
                                  :global-watches [global-src]))
          trigger    (atom 0)]
      (state/get-or-create-tab! app-state* "s" "t")
      (state/set-tab-route! app-state* "t"
                            {:name :a :path "/a" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-a)
      (render/render-tab app-state* "s" "t")

      ;; Both global + route sources registered as mount-scoped, full-render subviews
      (is (= #{global-src route-src}
             (set (subview/watched-sources app-state* "t"))))
      (is (every? #(= :mount (:scope %)) (vals (get-in @app-state* [:tabs "t" :subviews]))))

      ;; Wire them (as the first SSE full render does) and verify changes fire
      (subview/setup-new-watches! app-state* "t" #(swap! trigger inc) nil)
      (swap! route-src inc)
      (is (= 1 @trigger) "route watch triggers a full render once wired")
      (swap! global-src inc)
      (is (= 2 @trigger) "global watch triggers a full render"))))

(deftest test-route-watches-torn-down-on-nav
  (testing "navigating to a route without the watch tears it down (page-view remount)"
    (let [route-src  (atom 0)
          page-a     (fn [_req] [:div "A"])
          page-b     (fn [_req] [:div "B"])
          rts        [["/a" {:name :a :get page-a :watches [route-src]}]
                      ["/b" {:name :b :get page-b}]]
          app-state* (atom (assoc (state/init-state)
                                  :routes rts
                                  :global-watches []))]
      (state/get-or-create-tab! app-state* "s" "t")
      (state/set-tab-route! app-state* "t"
                            {:name :a :path "/a" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-a)
      (render/render-tab app-state* "s" "t")
      (is (= [route-src] (subview/watched-sources app-state* "t")))

      ;; Navigate to /b (different handler identity -> page-view remount)
      (state/set-tab-route! app-state* "t"
                            {:name :b :path "/b" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-b)
      (render/render-tab app-state* "s" "t")
      (is (empty? (subview/watched-sources app-state* "t"))
          "route-a watch torn down on navigation"))))
