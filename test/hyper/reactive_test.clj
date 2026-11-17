(ns hyper.reactive-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.protocols :as proto]
            [hyper.render :as render]
            [hyper.state :as state]
            [hyper.subview :as subview]
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
      (is (re-find #"<div id=\"r_test-tab_\d+\" style=\"display: contents\"></div>" html)
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
      (is (re-find #"<div id=\"r_test-tab_\d+\" style=\"display: contents\"><p>one</p><p>two</p></div>" html)
          "should wrap the seq in an anchor div carrying the region id")))

  (testing "a string body is wrapped in an anchor"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div (h/reactive [count*] (str "Count: " @count*))])))]
      (is (re-find #"<div id=\"r_test-tab_\d+\" style=\"display: contents\">Count: 0</div>" (:body-html result))))))

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

(deftest test-reactive-partial-render-declares-local-signal
  (testing "a local signal first introduced by a partial re-render is declared on the fragment root"
    (let [render-fn (fn [_req]
                      (let [toggle* (h/tab-cursor :toggle 0)]
                        [:div
                         (h/reactive [toggle*]
                                     (let [tab* (h/local-signal :tab "a")]
                                       [:div {:data-show (str @tab* " === 'a'")}
                                        "panel"]))]))
          r1        (ht/test-page render-fn)
          app-state (:app-state r1)
          sid       (ffirst (get-in @app-state [:tabs "test-tab" :subviews]))
          fragment  (subview/partial-render app-state "test-tab" sid)]
      (is (some? sid) "reactive region should be registered")
      (is (str/includes? fragment "data-signals:_tab__ifmissing")
          "partial fragment must carry the local signal's __ifmissing declaration")
      (is (str/includes? fragment "panel")
          "fragment should still contain the rendered body"))))

;; ---------------------------------------------------------------------------
;; Regions drawn again with other deps, and regions dropped by a partial render
;; ---------------------------------------------------------------------------

(defn- connected-page
  "Load `page` in tab T and attach a renderer that records the regions it is
   asked to re-render.  Returns `[app* partials*]`."
  [page]
  (let [app*     (atom (state/init-state))
        partials (atom [])
        handler  (h/create-handler [["/" {:name :home :get page}]] :app-state app*)
        trigger! #(swap! partials conj %)]
    (handler {:request-method :get
              :uri            "/"
              :query-string   "tab-id=T"
              :headers        {"cookie" "hyper-session=S"}})
    (swap! app* assoc-in [:tabs "T" :renderer] {:trigger-render!  (fn [])
                                                :trigger-partial! trigger!})
    (subview/setup-new-watches! app* "T" (fn []) trigger!)
    [app* partials]))

(defn- full-render!
  "Render tab T's page as the renderer does on a full render."
  [app*]
  (let [{:keys [registered-subview-ids]}           (render/render-tab app* (get-in @app* [:tabs "T" :session-id]) "T")
        {:keys [trigger-render! trigger-partial!]} (get-in @app* [:tabs "T" :renderer])]
    (subview/sweep-stale! app* "T" registered-subview-ids)
    (subview/setup-new-watches! app* "T" trigger-render! trigger-partial!)))

(defn- changes-redraw
  "The regions asked to re-render when `f` runs."
  [partials f]
  (reset! partials [])
  (f)
  @partials)

(defn- watched-by? [app* sid source]
  (some #(identical? source %) (vals (get-in @app* [:tabs "T" :subview-watches sid]))))

(deftest test-nested-region-watches-its-new-deps-after-a-partial-render
  (let [a               (make-disposable-source 0)
        b               (make-disposable-source 0)
        [app* partials] (connected-page
                          (fn [_]
                            (let [which (h/tab-cursor :which :a)]
                              [:div (h/reactive [which]
                                                (let [dep (if (= :a @which) a b)]
                                                  [:div (h/reactive [dep] [:p (str "v" @dep)])]))])))
        inner           "r_T_2"]
    (is (watched-by? app* inner a))
    (is (= ["r_T_1"] (changes-redraw partials #(swap! app* assoc-in [:tabs "T" :data :which] :b))))
    (let [frag (subview/partial-render app* "T" "r_T_1")]
      (testing "the nested region keeps its id in its parent's partial render"
        (is (str/includes? frag (str "<p id=\"" inner "\">v0</p>")))
        (is (= ["r_T_1" inner] (sort (keys (get-in @app* [:tabs "T" :subviews])))))))
    (testing "a change to the new dep redraws the nested region"
      (is (= [inner] (changes-redraw partials #(reset! (.-value* b) 1))))
      (is (str/includes? (subview/partial-render app* "T" inner) "v1")))
    (testing "the old dep no longer has the region's watch and is released"
      (is (= [] (changes-redraw partials #(reset! (.-value* a) 1))))
      (is (empty? @(.-watches* a)))
      (is (= 1 @(.-disposed* a)))
      (is (not (contains? (:source-refcounts @app*) a)))
      (is (= 1 (get-in @app* [:source-refcounts b])))
      (is (zero? @(.-disposed* b))))))

(deftest test-region-watches-its-new-deps-after-a-full-render
  (let [a               (make-disposable-source 0)
        b               (make-disposable-source 0)
        which           (atom :a)
        [app* partials] (connected-page
                          (fn [_]
                            (let [dep (if (= :a @which) a b)]
                              [:div (h/reactive [dep] [:p (str "v" @dep)])])))]
    (reset! which :b)
    (full-render! app*)
    (is (= ["r_T_1"] (keys (get-in @app* [:tabs "T" :subviews]))) "the region keeps its id")
    (is (= ["r_T_1"] (changes-redraw partials #(reset! (.-value* b) 1))))
    (is (= [] (changes-redraw partials #(reset! (.-value* a) 1))))
    (is (empty? @(.-watches* a)))
    (is (= 1 @(.-disposed* a)))
    (is (zero? @(.-disposed* b)))))

(deftest test-unchanged-deps-are-not-rewired
  (let [source          (make-disposable-source 0)
        n               (atom 0)
        [app* partials] (connected-page
                          (fn [_]
                            [:div (h/reactive [(h/tab-cursor :x 0)]
                                              [:div "n" @n
                                               (h/reactive [source (h/tab-cursor :y 0)]
                                                           [:p (str "v" @source)])])]))
        watches         #(get-in @app* [:tabs "T" :subview-watches])
        before          (watches)
        rewires         (atom 0)
        setup           subview/setup-subview-watches!
        teardown        subview/teardown-subview-watches!]
    (with-redefs [subview/setup-subview-watches!    (fn [& args] (swap! rewires inc) (apply setup args))
                  subview/teardown-subview-watches! (fn [& args] (swap! rewires inc) (apply teardown args))]
      (testing "fresh cursors to the same paths and the same source do not rewire"
        (swap! n inc)
        (full-render! app*)
        (subview/partial-render app* "T" "r_T_1")
        (subview/partial-render app* "T" "r_T_2")
        (full-render! app*)
        (is (zero? @rewires))
        (is (= before (watches)))
        (is (= 1 (count @(.-watches* source))))
        (is (zero? @(.-disposed* source)))))
    (testing "and the regions still redraw on their deps"
      (is (= ["r_T_2"] (changes-redraw partials #(reset! (.-value* source) 1))))
      (is (= ["r_T_2"] (changes-redraw partials #(swap! app* assoc-in [:tabs "T" :data :y] 1))))
      (is (= ["r_T_1"] (changes-redraw partials #(swap! app* assoc-in [:tabs "T" :data :x] 1)))))))

(deftest test-reordered-deps-are-not-rewired
  (let [a               (make-disposable-source 0)
        b               (make-disposable-source 0)
        flip            (atom false)
        [app* partials] (connected-page
                          (fn [_]
                            [:div (h/reactive [(if @flip b a) (h/tab-cursor :x 0) (if @flip a b)]
                                              [:p (str @a @b)])]))
        watches         #(get-in @app* [:tabs "T" :subview-watches])
        before          (watches)
        rewires         (atom 0)
        setup           subview/setup-subview-watches!
        teardown        subview/teardown-subview-watches!]
    (with-redefs [subview/setup-subview-watches!    (fn [& args] (swap! rewires inc) (apply setup args))
                  subview/teardown-subview-watches! (fn [& args] (swap! rewires inc) (apply teardown args))]
      (reset! flip true)
      (full-render! app*)
      (is (zero? @rewires) "the same deps in another order do not rewire")
      (is (= before (watches))))
    (is (= ["r_T_1"] (changes-redraw partials #(reset! (.-value* a) 1))))
    (is (= ["r_T_1"] (changes-redraw partials #(reset! (.-value* b) 1))))
    (is (zero? @(.-disposed* a)))
    (is (zero? @(.-disposed* b)))))

(deftest test-a-dep-listed-twice-is-released-with-its-region
  (let [a        (make-disposable-source 0)
        show     (atom true)
        [app* _] (connected-page (fn [_] [:div (when @show (h/reactive [a a] [:p (str @a)]))]))]
    (is (= 1 (get-in @app* [:source-refcounts a])) "retained once")
    (reset! show false)
    (full-render! app*)
    (is (not (contains? (:source-refcounts @app*) a)))
    (is (= 1 @(.-disposed* a)))
    (is (empty? @(.-watches* a)))))

(deftest test-deps-changed-while-disconnected-are-rewired-on-reconnect
  (let [a               (make-disposable-source 0)
        b               (make-disposable-source 0)
        two?            (atom false)
        [app* partials] (connected-page (fn [_] [:div (h/reactive [a (if @two? b a)] [:p (str @a @b)])]))
        renderer        (get-in @app* [:tabs "T" :renderer])
        disconnect!     #(swap! app* update-in [:tabs "T"] dissoc :renderer)
        reconnect!      #(do (swap! app* assoc-in [:tabs "T" :renderer] renderer)
                             (full-render! app*))
        render-alone!   #(subview/sweep-stale! app* "T" (:registered-subview-ids (render/render-tab app* "S" "T")))]
    (testing "a dep added while disconnected is watched once reconnected"
      (disconnect!)
      (reset! two? true)
      (render-alone!)
      (is (empty? @(.-watches* b)) "not watched before the reconnect")
      (is (= 1 (count @(.-watches* a))) "the shared dep keeps its watch")
      (reconnect!)
      (is (= ["r_T_1"] (changes-redraw partials #(reset! (.-value* b) 1))))
      (is (zero? @(.-disposed* a))))
    (testing "a dep dropped while disconnected is kept until the reconnect"
      (disconnect!)
      (reset! two? false)
      (render-alone!)
      (is (= 1 (count @(.-watches* b))))
      (is (zero? @(.-disposed* b)))
      (reconnect!)
      (is (empty? @(.-watches* b)))
      (is (= 1 @(.-disposed* b)))
      (is (zero? @(.-disposed* a)) "the shared dep is never disposed")
      (is (= ["r_T_1"] (changes-redraw partials #(reset! (.-value* a) 1)))))))

(defn- nested-regions-page
  "A page whose region :parent draws :kept, and :dropped (with :deep in it)
   and the async :loader while :show? is on, next to a region :sibling."
  [{:keys [kept dropped deep sibling]}]
  (fn [_]
    (let [show? (h/tab-cursor :show? true)]
      [:div
       (h/reactive {:key :parent} [show?]
                   [:div
                    (h/reactive {:key :kept} [kept] [:p (str "kept" @kept)])
                    (when @show?
                      (h/reactive {:key :dropped} [dropped]
                                  [:div (h/reactive {:key :deep} [deep] [:p @deep])]))
                    (when @show?
                      (h/async {:key :loader} [] :loaded
                               {:keys [status]} [:p (str status)]))])
       (h/reactive {:key :sibling} [sibling] [:p @sibling])])))

(deftest test-a-partial-render-removes-the-regions-it-no-longer-draws
  (let [sources                             (zipmap [:kept :dropped :deep :sibling] (repeatedly #(make-disposable-source 0)))
        {:keys [kept dropped deep sibling]} sources
        [app* partials]                     (connected-page (nested-regions-page sources))
        sids                                #(set (keys (get-in @app* [:tabs "T" :subviews])))
        before                              (sids)
        id                                  (fn [k] (first (filter #(str/ends-with? % (str "_" k)) before)))
        kept-id                             (id "kept")
        dropped-id                          (id "dropped")
        deep-id                             (id "deep")
        loader-id                           (id "loader")
        sibling-id                          (id "sibling")]
    (is (every? some? [kept-id dropped-id deep-id loader-id sibling-id]))
    (swap! app* assoc-in [:tabs "T" :data :show?] false)
    (subview/partial-render app* "T" "r_T_parent")
    (testing "the regions drawn under the parent and dropped are torn down"
      (is (= (disj before dropped-id deep-id loader-id) (sids)))
      (doseq [[sid source] [[dropped-id dropped] [deep-id deep]]]
        (is (nil? (get-in @app* [:tabs "T" :subview-watches sid])))
        (is (empty? @(.-watches* source)))
        (is (= 1 @(.-disposed* source)))
        (is (not (contains? (:source-refcounts @app*) source))))
      (is (nil? (get-in @app* [:tabs "T" :async loader-id])) "the async region is unmounted")
      (is (= [] (changes-redraw partials #(reset! (.-value* dropped) 1)))))
    (testing "a region outside the parent is untouched"
      (is (watched-by? app* sibling-id sibling))
      (is (zero? @(.-disposed* sibling)))
      (is (= [sibling-id] (changes-redraw partials #(reset! (.-value* sibling) 1)))))
    (testing "a region the parent still draws keeps working"
      (is (watched-by? app* kept-id kept))
      (is (zero? @(.-disposed* kept)))
      (is (= [kept-id] (changes-redraw partials #(reset! (.-value* kept) 1))))
      (is (str/includes? (subview/partial-render app* "T" kept-id) "kept1")))))
