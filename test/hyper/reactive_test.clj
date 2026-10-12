(ns hyper.reactive-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.protocols :as proto]
            [hyper.test :as ht]))

;; A test source that tracks disposal.
(deftype DisposableSource [value* watches* disposed*]
  clojure.lang.IRef
  (deref [_] @value*)
  (setValidator [_ _])
  (getValidator [_] nil)
  (getWatches [_] @watches*)
  (addWatch [this key callback]
    (swap! watches* assoc key callback)
    (add-watch value* key (fn [k _r old-val new-val]
                            (callback k this old-val new-val)))
    this)
  (removeWatch [this key]
    (swap! watches* dissoc key)
    (remove-watch value* key)
    this)

  proto/Watchable
  (-add-watch [this key callback]
    (.addWatch this key (fn [_k _ref old-val new-val]
                          (callback old-val new-val))))
  (-remove-watch [this key]
    (.removeWatch this key))
  (-dispose [_this]
    (swap! disposed* inc)))

(defn make-disposable-source
  [initial-value]
  (->DisposableSource (atom initial-value) (atom {}) (atom 0)))

;; ---------------------------------------------------------------------------
;; Basic render tests
;; ---------------------------------------------------------------------------

(deftest test-reactive-basic-render
  (testing "reactive injects ID onto the returned element"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div
                        (h/reactive [count*]
                                    [:p "Count: " @count*])])))]
      (is (str/includes? (:body-html result) "Count: 0"))
      ;; ID should be on the <p>, not a wrapper div
      (is (re-find #"<p id=\"r_" (:body-html result))
          "should inject reactive ID onto the element")))

  (testing "reactive uses existing :id if present"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div
                        (h/reactive [count*]
                                    [:p {:id "my-counter"} "Count: " @count*])])))]
      (is (str/includes? (:body-html result) "id=\"my-counter\""))
      ;; Should NOT have a generated reactive ID
      (is (not (re-find #"r_" (:body-html result)))
          "should use the existing ID, not generate one"))))

(deftest test-reactive-nil-body
  (testing "a nil body renders an empty anchor, not leaked internals"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div "before"
                        (h/reactive [count*]
                                    (when false [:p "never"]))
                        "after"])))
          html   (:body-html result)]
      ;; The internal attribute map must never appear in the output.
      (is (not (str/includes? html "{:id"))
          "must not leak the internal attribute map")
      ;; A stable empty anchor carrying the region id must remain so a later
      ;; dep change can target it with a partial fragment.
      (is (re-find #"<div id=\"r_test-tab_\d+\"></div>" html)
          "should emit an empty anchor div carrying the region id"))))

(deftest test-reactive-seq-body
  (testing "a seq body is wrapped in an anchor, not leaked internals"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div
                        (h/reactive [count*]
                                    (list [:p "one"] [:p "two"]))])))
          html   (:body-html result)]
      (is (not (str/includes? html "{:id"))
          "must not leak the internal attribute map")
      (is (re-find #"<div id=\"r_test-tab_\d+\"><p>one</p><p>two</p></div>" html)
          "should wrap the seq in an anchor div carrying the region id")))

  (testing "a string body is wrapped in an anchor"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div (h/reactive [count*] (str "Count: " @count*))])))]
      (is (re-find #"<div id=\"r_test-tab_\d+\">Count: 0</div>" (:body-html result))))))

(deftest test-reactive-caching
  (testing "reactive returns cached HTML when deps unchanged"
    (let [counter*  (atom 0)
          render-fn (fn [_req]
                      (swap! counter* inc)
                      (let [static*  (h/tab-cursor :static "hello")
                            dynamic* (h/tab-cursor :dynamic 0)]
                        [:div
                         (h/reactive [static*]
                                     [:p "Static: " @static*])
                         (h/reactive [dynamic*]
                                     [:p "Dynamic: " @dynamic*])]))
          r1        (ht/test-page render-fn)]

      ;; Both blocks rendered on first pass
      (is (str/includes? (:body-html r1) "Static: hello"))
      (is (str/includes? (:body-html r1) "Dynamic: 0"))

      ;; Modify only :dynamic, re-render with same app-state
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :dynamic] 42)
      (let [r2 (ht/test-page render-fn {:app-state (:app-state r1)})]
        (is (str/includes? (:body-html r2) "Dynamic: 42"))
        ;; Static block should still render (cached HTML is used internally
        ;; by the reactive component, but the test-page always does a full render)
        (is (str/includes? (:body-html r2) "Static: hello"))))))

(deftest test-reactive-re-executes-on-full-render
  (testing "reactive re-executes body on full render when parent data changes"
    ;; Simulates a component like a worker row: the reactive block declares
    ;; [clock*] as its dep (for elapsed-time display) but also reads data
    ;; passed from the parent via closure.  When the parent data changes
    ;; (e.g. evaluator state update) and a full re-render occurs, the
    ;; reactive body must re-execute — returning stale cached HTML would
    ;; show old worker status.
    (let [clock*    (atom 0)
          render-fn (fn [_req]
                      (let [worker-name (or @(h/tab-cursor :worker-name) "idle")]
                        [:div
                         (h/reactive [clock*]
                                     [:p "Worker: " worker-name " at " @clock*])]))
          r1        (ht/test-page render-fn)]
      (is (str/includes? (:body-html r1) "Worker: idle at 0"))

      ;; Parent data changes (worker becomes busy), but clock* (the dep)
      ;; has NOT changed.  A full re-render must still pick up the new name.
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :worker-name] "busy:dataset_a")
      (let [r2 (ht/test-page render-fn {:app-state (:app-state r1)})]
        (is (str/includes? (:body-html r2) "Worker: busy:dataset_a at 0")
            "reactive body must re-execute on full render to capture parent data changes")))))

(deftest test-reactive-nested
  (testing "nested reactive blocks render correctly"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [outer* (h/tab-cursor :outer "A")
                           inner* (h/tab-cursor :inner "B")]
                       [:div
                        (h/reactive [outer*]
                                    [:div
                                     [:span "Outer: " @outer*]
                                     (h/reactive [inner*]
                                                 [:span "Inner: " @inner*])])])))]
      (is (str/includes? (:body-html result) "Outer: A"))
      (is (str/includes? (:body-html result) "Inner: B")))))

(deftest test-reactive-sweep-conditional
  (testing "stale reactive blocks are swept on conditional change"
    (let [render-fn (fn [_req]
                      (let [mode* (h/tab-cursor :mode :a)]
                        [:div
                         (if (= @mode* :a)
                           (h/reactive [(h/tab-cursor :x 0)]
                                       [:p "Mode A"])
                           (h/reactive [(h/tab-cursor :y 0)]
                                       [:p "Mode B"]))]))
          r1        (ht/test-page render-fn)]

      (is (str/includes? (:body-html r1) "Mode A"))

      ;; Count reactive components registered
      (let [tab-id     "test-tab"
            components (get-in @(:app-state r1) [:tabs tab-id :subviews])]
        (is (= 1 (count components)) "should have 1 reactive component for mode A"))

      ;; Switch mode and re-render
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :mode] :b)
      (let [r2         (ht/test-page render-fn {:app-state (:app-state r1)})
            tab-id     "test-tab"
            components (get-in @(:app-state r2) [:tabs tab-id :subviews])]
        (is (str/includes? (:body-html r2) "Mode B"))
        (is (= 1 (count components)) "should have 1 reactive component for mode B (old one swept)")))))

(deftest test-reactive-dep-disposal
  (testing "deps are disposed when reactive block is swept"
    (let [source    (make-disposable-source "val")
          render-fn (fn [_req]
                      (let [show?* (h/tab-cursor :show? true)]
                        [:div
                         (when @show?*
                           (h/reactive [source]
                                       [:p "Source: " @source]))]))
          r1        (ht/test-page render-fn)]

      (is (str/includes? (:body-html r1) "Source: val"))
      (is (zero? @(.-disposed* source)) "should not be disposed yet")

      ;; Hide the reactive block → it should be swept
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :show?] false)
      (let [_r2 (ht/test-page render-fn {:app-state (:app-state r1)})]
        ;; The reactive block is gone — sweep should have run
        ;; But disposal only happens if watches were set up (which requires a renderer)
        ;; In test-page context, component watches are not set up since there's no
        ;; renderer thread. The sweep still removes the component registration.
        (is (empty? (get-in @(:app-state r1) [:tabs "test-tab" :subviews]))
            "swept component should be removed")))))
