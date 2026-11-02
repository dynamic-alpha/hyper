(ns hyper.signal-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.expr :refer [->expr]]
            [hyper.signal :as signal]
            [hyper.state :as state]))

;; ---------------------------------------------------------------------------
;; Name conversion
;; ---------------------------------------------------------------------------

(deftest signal-js-name-test
  (testing "keyword → camelCase"
    (is (= "name" (signal/signal-js-name :name)))
    (is (= "userName" (signal/signal-js-name :user-name)))
    (is (= "myLongSignalName" (signal/signal-js-name :my-long-signal-name))))

  (testing "vector path → dot-notation camelCase"
    (is (= "user.name" (signal/signal-js-name [:user :name])))
    (is (= "userProfile.firstName" (signal/signal-js-name [:user-profile :first-name])))
    (is (= "a.b.c" (signal/signal-js-name [:a :b :c])))))

(deftest signal-html-name-test
  (testing "keyword → kebab-case (Datastar auto-converts)"
    (is (= "name" (signal/signal-html-name :name)))
    (is (= "user-name" (signal/signal-html-name :user-name))))

  (testing "vector path → dot-notation kebab-case"
    (is (= "user.name" (signal/signal-html-name [:user :name])))
    (is (= "user-profile.first-name" (signal/signal-html-name [:user-profile :first-name])))))

;; ---------------------------------------------------------------------------
;; Value encoding
;; ---------------------------------------------------------------------------

(deftest clj->js-literal-test
  (testing "strings wrapped in single quotes"
    (is (= "'hello'" (signal/clj->js-literal "hello")))
    (is (= "''" (signal/clj->js-literal ""))))

  (testing "strings with special characters are escaped"
    (is (= "'it\\'s'" (signal/clj->js-literal "it's")))
    (is (= "'line1\\nline2'" (signal/clj->js-literal "line1\nline2"))))

  (testing "numbers are bare"
    (is (= "42" (signal/clj->js-literal 42)))
    (is (= "3.14" (signal/clj->js-literal 3.14))))

  (testing "booleans"
    (is (= "true" (signal/clj->js-literal true)))
    (is (= "false" (signal/clj->js-literal false))))

  (testing "nil → null"
    (is (= "null" (signal/clj->js-literal nil))))

  (testing "keywords → single-quoted name"
    (is (= "'dark'" (signal/clj->js-literal :dark))))

  (testing "maps → JS object literal"
    (is (= "{name: 'John', age: 30}"
           (signal/clj->js-literal {:name "John" :age 30}))))

  (testing "vectors → JS array literal"
    (is (= "[1, 2, 3]" (signal/clj->js-literal [1 2 3])))))

;; ---------------------------------------------------------------------------
;; Signal type — render context
;; ---------------------------------------------------------------------------

(deftest signal-deref-render-context-test
  (testing "deref returns Datastar expression in render context (no *signals* bound)"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_1"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "$userName" @sig))))))

  (testing "vector path deref returns dot-notation expression"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_2"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal [:user :name] "")]
          (is (= "$user.name" @sig)))))))

;; ---------------------------------------------------------------------------
;; Signal type — action context
;; ---------------------------------------------------------------------------

(deftest signal-deref-action-context-test
  (testing "deref returns live value from *signals* in action context"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_3"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:user-name "Alice"}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "Alice" @sig))))))

  (testing "deref returns default when signal missing from *signals*"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_4"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:other "value"}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "default" @sig))))))

  (testing "deref reads nested signal from *signals*"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_5"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:user {:name "Bob"}}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal [:user :name] "")]
          (is (= "Bob" @sig)))))))

;; ---------------------------------------------------------------------------
;; Signal mutation
;; ---------------------------------------------------------------------------

(deftest signal-reset!-test
  (testing "reset! updates tab state"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_6"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 0)]
          (is (= 0 (get-in @app-state* [:tabs tab-id :signals :count])))
          (reset! sig 42)
          (is (= 42 (get-in @app-state* [:tabs tab-id :signals :count])))))))

  (testing "reset! to nil is preserved across re-renders (not overwritten by default)"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_6b"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      ;; First render — creates the signal with default "hello"
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :greeting "hello")]
          (is (= "hello" (get-in @app-state* [:tabs tab-id :signals :greeting])))
          ;; Simulate an action that sets the signal to nil
          (reset! sig nil)
          (is (nil? (get-in @app-state* [:tabs tab-id :signals :greeting])))))
      ;; Second render — create-signal should NOT overwrite nil with default
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (h/signal :greeting "hello")
        (is (nil? (get-in @app-state* [:tabs tab-id :signals :greeting]))
            "nil value should be preserved, not reset to default")))))

(deftest signal-swap!-test
  (testing "swap! in action context uses live signal value"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_7"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:count 10}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 0)]
          (swap! sig inc)
          (is (= 11 (get-in @app-state* [:tabs tab-id :signals :count])))))))

  (testing "swap! outside action context uses server-side value"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_8"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 5)]
          (swap! sig + 10)
          (is (= 15 (get-in @app-state* [:tabs tab-id :signals :count]))))))))

;; ---------------------------------------------------------------------------
;; Local signal
;; ---------------------------------------------------------------------------

(deftest local-signal-deref-test
  (testing "deref in render context returns Datastar expression"
    (binding [context/*declared-signals* (atom [])]
      (let [sig (h/local-signal :open false)]
        (is (= "$_open" @sig)))))

  (testing "deref in action context throws"
    (binding [context/*declared-signals* (atom [])
              context/*signals*          {}]
      (let [sig (h/local-signal :open false)]
        (is (thrown-with-msg? Exception #"Cannot deref local signal"
                              @sig))))))

(deftest local-signal-toString-test
  (testing "toString returns underscore-prefixed JS name"
    (binding [context/*declared-signals* (atom [])]
      (let [sig (h/local-signal :show-menu false)]
        (is (= "_showMenu" (str sig)))))))

;; ---------------------------------------------------------------------------
;; Signal declaration accumulator
;; ---------------------------------------------------------------------------

(deftest declared-signals-accumulation-test
  (testing "signal adds declaration to *declared-signals* during render"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_9"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (h/signal :name "")
        (h/local-signal :open false)
        (let [declared @context/*declared-signals*]
          (is (= 2 (count declared)))
          (is (= {:path :name :html-name "name" :default-val "" :local? false}
                 (first declared)))
          (is (= {:path :open :html-name "_open" :default-val false :local? true}
                 (second declared))))))))

;; ---------------------------------------------------------------------------
;; HTML signal attributes
;; ---------------------------------------------------------------------------

(deftest format-signal-attrs-test
  (testing "produces data-signals:NAME__ifmissing attributes"
    (let [attrs (signal/format-signal-attrs
                  [{:html-name "name" :default-val "" :local? false}
                   {:html-name "_open" :default-val false :local? true}])]
      (is (= "''" (get attrs (keyword "data-signals:name__ifmissing"))))
      (is (= "false" (get attrs (keyword "data-signals:_open__ifmissing"))))))

  (testing "returns nil for empty declarations"
    (is (nil? (signal/format-signal-attrs [])))
    (is (nil? (signal/format-signal-attrs nil)))))

;; ---------------------------------------------------------------------------
;; Chassis protocol — {:data-bind signal*} works directly
;; ---------------------------------------------------------------------------

(deftest chassis-attribute-value-test
  (testing "signal renders as attribute value via Chassis protocol"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_10"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig  (h/signal :user-name "")
              html (c/html [:input {:data-bind sig}])]
          (is (clojure.string/includes? html "data-bind=\"userName\""))))))

  (testing "local signal renders as attribute value via Chassis protocol"
    (binding [context/*declared-signals* (atom [])]
      (let [sig  (h/local-signal :show-menu false)
            html (c/html [:input {:data-bind sig}])]
        (is (clojure.string/includes? html "data-bind=\"_showMenu\"")))))

  (testing "signal deref in data-text attribute renders expression"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_11"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig  (h/signal :count 0)
              html (c/html [:span {:data-text @sig}])]
          (is (clojure.string/includes? html "data-text=\"$count\"")))))))

;; ---------------------------------------------------------------------------
;; SSE patch-signals event
;; ---------------------------------------------------------------------------

(deftest format-patch-signals-event-test
  (testing "formats flat signals as JSON patch event"
    (let [event (signal/format-patch-signals-event {:count 42})]
      (is (clojure.string/starts-with? event "event: datastar-patch-signals\n"))
      (is (clojure.string/includes? event "data: signals {\"count\":42}"))))

  (testing "formats string signal values as JSON"
    (let [event (signal/format-patch-signals-event {:name "Alice"})]
      (is (clojure.string/includes? event "data: signals {\"name\":\"Alice\"}"))))

  (testing "formats kebab-case keys as camelCase in JSON output"
    (let [event (signal/format-patch-signals-event {:user-name "Jane"})]
      (is (clojure.string/includes? event "\"userName\":\"Jane\"")))))

;; ---------------------------------------------------------------------------
;; changed-signals
;; ---------------------------------------------------------------------------

(deftest changed-signals-test
  (testing "returns changed signals"
    (is (= {:name "Bob"}
           (signal/changed-signals {:name "Alice" :count 0}
                                   {:name "Bob" :count 0}))))

  (testing "returns new signals"
    (is (= {:email "a@b.com"}
           (signal/changed-signals {:name "Alice"}
                                   {:name "Alice" :email "a@b.com"}))))

  (testing "returns empty map when nothing changed"
    (is (= {}
           (signal/changed-signals {:name "Alice"} {:name "Alice"}))))

  (testing "returns nil for removed signals"
    (is (= {:old-key nil}
           (signal/changed-signals {:name "Alice" :old-key "x"}
                                   {:name "Alice"})))))

;; ---------------------------------------------------------------------------
;; drop-ifmissing-covered-patches (issue #44)
;; ---------------------------------------------------------------------------

(deftest drop-ifmissing-covered-patches-test
  (let [declared [{:path :ids :html-name "ids" :default-val [] :local? false}]]
    (testing "first-load patch equal to declared default is suppressed"
      ;; This is the issue-#44 scenario: a checkbox-array signal declared as
      ;; [] would otherwise emit a redundant {\"ids\":[]} patch that clobbers
      ;; Datastar's DOM-materialized array.  sent-signals is nil on first load.
      (is (= {}
             (signal/drop-ifmissing-covered-patches {:ids []} declared nil))))

    (testing "patch differing from declared default is kept"
      (is (= {:ids ["a"]}
             (signal/drop-ifmissing-covered-patches {:ids ["a"]} declared nil))))

    (testing "patch equal to default but already sent is kept (reset-to-default)"
      ;; The client already has :ids (it was sent before, here as [\"a\"]), so
      ;; __ifmissing won't reset it — the patch back to the default [] must be
      ;; delivered explicitly.
      (is (= {:ids []}
             (signal/drop-ifmissing-covered-patches {:ids []} declared {:ids ["a"]}))))

    (testing "undeclared signals are always kept"
      (is (= {:other 0}
             (signal/drop-ifmissing-covered-patches {:other 0} declared nil))))

    (testing "nested-path patch equal to declared default is suppressed and pruned"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}]]
        (is (= {}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids []}} declared nil)))))

    (testing "nested-path drop preserves sibling leaves under the same branch"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}
                      {:path        [:form :name] :html-name "form.name"
                       :default-val ""            :local?    false}]]
        ;; :ids matches its default and is dropped; :name differs and stays,
        ;; so the :form branch survives with only :name.
        (is (= {:form {:name "x"}}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids [] :name "x"}} declared nil)))))

    (testing "nested-path patch already sent is kept"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}]]
        (is (= {:form {:ids []}}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids []}} declared {:form {:ids ["a"]}})))))

    (testing "mixed patch — suppresses default-matching, keeps the rest"
      (let [declared [{:path :ids :html-name "ids" :default-val [] :local? false}
                      {:path :count :html-name "count" :default-val 0 :local? false}]]
        (is (= {:count 5}
               (signal/drop-ifmissing-covered-patches
                 {:ids [] :count 5} declared nil)))))

    (testing "single-element vector path is treated as top-level"
      (let [declared [{:path [:ids] :html-name "ids" :default-val [] :local? false}]]
        (is (= {}
               (signal/drop-ifmissing-covered-patches {:ids []} declared nil)))))

    (testing "empty/nil patches pass through unchanged"
      (is (= {} (signal/drop-ifmissing-covered-patches {} declared nil)))
      (is (nil? (signal/drop-ifmissing-covered-patches nil declared nil))))))

;; ---------------------------------------------------------------------------
;; Connection status signals (static, client-only)
;; ---------------------------------------------------------------------------

(deftest connection-render-deref-test
  (testing "in render context, connection signals deref to their $-expression"
    (is (= "$_hyperConnected" @signal/connected?*))
    (is (= "$_hyperConnection" @signal/connection*))))

(deftest connection-action-deref-throws-test
  (testing "in action context, connection signals throw (client-only)"
    (binding [context/*signals* {}]
      (is (thrown? clojure.lang.ExceptionInfo @signal/connected?*))
      (is (thrown? clojure.lang.ExceptionInfo @signal/connection*)))))

(deftest connection-states-test
  (testing "the documented token set"
    (is (= #{:connecting :open :reconnecting :error :closed}
           signal/connection-states))))

(deftest connection-attrs-test
  (testing "connection-attrs declares both signals and the lifecycle handler"
    (let [attrs (signal/connection-attrs)]
      (is (= "'connecting'"
             (get attrs (keyword "data-signals:_hyper-connection__ifmissing"))))
      (is (= "true"
             (get attrs (keyword "data-signals:_hyper-connected__ifmissing"))))
      (let [js (get attrs (keyword "data-on:datastar-fetch"))]
        ;; isolates the SSE connection from action POSTs
        (is (clojure.string/includes? js "evt.detail.el === el"))
        ;; healthy stream
        (is (clojure.string/includes?
              js "evt.detail.type === 'started' ? ($_hyperConnection = 'open', $_hyperConnected = true)"))
        ;; transient drop
        (is (clojure.string/includes?
              js "'retrying' ? ($_hyperConnection = 'reconnecting', $_hyperConnected = false)"))
        ;; terminal failure
        (is (clojure.string/includes?
              js "'retries-failed' ? ($_hyperConnection = 'error', $_hyperConnected = false)"))))))

(deftest navigating-deref-test
  (testing "in render context, navigating?* derefs to its $-expression"
    (is (= "$_hyperNavigating" @signal/navigating?*)))
  (testing "in action context, navigating?* throws (client-only)"
    (binding [context/*signals* {}]
      (is (thrown? clojure.lang.ExceptionInfo @signal/navigating?*)))))

(deftest navigation-attrs-test
  (testing "navigation-attrs declares the signal and sets it from the DOM event"
    (let [attrs (signal/navigation-attrs)]
      (is (= "false"
             (get attrs (keyword "data-signals:_hyper-navigating__ifmissing"))))
      (is (= "$_hyperNavigating = evt.detail === true"
             (get attrs (keyword (str "data-on:" signal/navigating-event))))))))

;; ---------------------------------------------------------------------------
;; Optimistic — derived naming
;; ---------------------------------------------------------------------------

(def ^:private opt-session-id "ses_opt")
(def ^:private opt-tab-id "tab_opt")

(defmacro ^:private with-render-ctx
  "Bind a render context (request + declared-signals accumulator) for body."
  [app-state* & body]
  `(binding [context/*request*          {:hyper/session-id opt-session-id
                                         :hyper/tab-id     opt-tab-id
                                         :hyper/app-state  ~app-state*}
             context/*declared-signals* (atom [])]
     ~@body))

(defn- fresh-opt-state []
  (let [app-state* (atom (state/init-state))]
    (state/get-or-create-tab! app-state* opt-session-id opt-tab-id)
    app-state*))

(deftest derived-signal-path-test
  (testing "scope + logical path, flattened"
    (is (= :global-theme (signal/derived-signal-path :global [:theme])))
    (is (= :tab-count (signal/derived-signal-path :tab [:count])))
    (is (= :session-user-name (signal/derived-signal-path :session [:user :name])))
    (is (= :path-page (signal/derived-signal-path :path [:page]))))

  (testing "numeric segments round-trip the wire format"
    ;; The derived path must equal what parse-signals produces for its own
    ;; camelCase wire name, or client-reported values miss the store path.
    (is (= :session-cols-0-width (signal/derived-signal-path :session [:cols 0 :width])))
    (is (= "sessionCols0Width"
           (signal/signal-js-name (signal/derived-signal-path :session [:cols 0 :width]))))))

;; ---------------------------------------------------------------------------
;; Optimistic — creation, rendering, declaration
;; ---------------------------------------------------------------------------

(deftest optimistic-render-test
  (let [app-state* (fresh-opt-state)]
    (with-render-ctx app-state*
      (let [w* (h/optimistic (h/session-cursor [:cols 0 :width] 240))]
        (testing "render deref returns the Datastar expression string"
          (is (= "$sessionCols0Width" @w*)))

        (testing "renders as the signal name in attribute position"
          (is (clojure.string/includes? (c/html [:input {:data-bind w*}])
                                        "data-bind=\"sessionCols0Width\"")))

        (testing "expr treats it as a signal"
          (is (= "($sessionCols0Width = 5)" (->expr (reset! w* 5))))
          (is (= "($sessionCols0Width = ($sessionCols0Width + 1))" (->expr (swap! w* inc))))
          (is (= "$sessionCols0Width" (->expr @w*))))

        (testing "declares the signal with the current committed value"
          (is (some #(and (= :session-cols-0-width (:path %))
                          (= 240 (:default-val %)))
                    @context/*declared-signals*)))))

    (testing "declaration default follows the committed value across renders"
      (swap! app-state* assoc-in [:sessions opt-session-id :data :cols 0 :width] 300)
      (with-render-ctx app-state*
        (h/optimistic (h/session-cursor [:cols 0 :width] 240))
        (is (some #(and (= :session-cols-0-width (:path %))
                        (= 300 (:default-val %)))
                  @context/*declared-signals*))))))

(deftest optimistic-validation-test
  (let [app-state* (fresh-opt-state)]
    (with-render-ctx app-state*
      (testing "unscoped cursors are rejected"
        (is (thrown-with-msg?
              Exception #"scoped cursor"
              (h/optimistic (state/create-cursor app-state* [:custom] :x)))))

      (testing "unknown options are rejected"
        (is (thrown-with-msg?
              Exception #"unknown option"
              (h/optimistic (h/session-cursor :title "") {:as :nope}))))

      (testing "bad :on-conflict is rejected"
        (is (thrown-with-msg?
              Exception #"on-conflict"
              (h/optimistic (h/session-cursor :title "") {:on-conflict :merge})))))))

(deftest optimistic-duplicate-test
  (let [app-state* (fresh-opt-state)]
    (with-render-ctx app-state*
      (testing "the same cursor wrapped twice with the same opts is fine"
        (let [a* (h/optimistic (h/session-cursor :title ""))
              b* (h/optimistic (h/session-cursor :title ""))]
          (is (= (str a*) (str b*)))))

      (testing "the same cursor with different opts throws"
        (is (thrown-with-msg?
              Exception #"different options"
              (h/optimistic (h/session-cursor :title "") {:auto-commit? true})))))

    (testing "opts may change across renders (fresh declaration scope)"
      (with-render-ctx app-state*
        (is (some? (h/optimistic (h/session-cursor :title "") {:auto-commit? true})))))))

;; ---------------------------------------------------------------------------
;; Optimistic — render-time down-sync
;; ---------------------------------------------------------------------------

(deftest optimistic-sync-test
  (let [app-state* (fresh-opt-state)
        sig-path   [:session-cols-0-width]
        render!    #(with-render-ctx app-state*
                      (h/optimistic (h/session-cursor [:cols 0 :width] 240)))]
    (testing "first render records the committed value as synced, no patch state"
      (render!)
      (is (= 240 (get-in @app-state* [:tabs opt-tab-id :optimistic-synced sig-path])))
      (is (= 240 (get-in @app-state* (into [:tabs opt-tab-id :signals] sig-path)))))

    (testing "cursor unchanged — a client-side value in signal state is left alone"
      (swap! app-state* assoc-in (into [:tabs opt-tab-id :signals] sig-path) 275)
      (render!)
      (is (= 275 (get-in @app-state* (into [:tabs opt-tab-id :signals] sig-path)))
          "mid-gesture client state must not be stomped by a re-render")
      (is (= 240 (get-in @app-state* [:tabs opt-tab-id :optimistic-synced sig-path]))))

    (testing "server-side cursor change syncs signal state for patching"
      (swap! app-state* assoc-in [:sessions opt-session-id :data :cols 0 :width] 500)
      (render!)
      (is (= 500 (get-in @app-state* (into [:tabs opt-tab-id :signals] sig-path))))
      (is (= 500 (get-in @app-state* [:tabs opt-tab-id :optimistic-synced sig-path]))))

    (testing "a committed client value syncs without altering signal state"
      ;; Simulates a commit round trip: the POST merge already put 320 in
      ;; signal state, then the commit moved the cursor to 320.
      (swap! app-state* assoc-in (into [:tabs opt-tab-id :signals] sig-path) 320)
      (swap! app-state* assoc-in [:sessions opt-session-id :data :cols 0 :width] 320)
      (render!)
      (is (= 320 (get-in @app-state* (into [:tabs opt-tab-id :signals] sig-path))))
      (is (= 320 (get-in @app-state* [:tabs opt-tab-id :optimistic-synced sig-path]))))))

;; ---------------------------------------------------------------------------
;; Optimistic — action-context semantics
;; ---------------------------------------------------------------------------

(deftest optimistic-action-test
  (let [app-state* (fresh-opt-state)
        w*         (with-render-ctx app-state*
                     (h/optimistic (h/session-cursor [:cols 0 :width] 240)))]
    (testing "deref returns the client-reported value"
      (binding [context/*signals* {:session-cols-0-width 300}]
        (is (= 300 @w*))))

    (testing "deref falls back to the committed value when the signal is absent"
      (binding [context/*signals* {}]
        (is (= 240 @w*))))

    (testing "reset! writes the cursor, not the signal"
      (binding [context/*signals* {:session-cols-0-width 300}]
        (reset! w* 500)
        (is (= 500 (get-in @app-state* [:sessions opt-session-id :data :cols 0 :width])))
        (is (= 240 (get-in @app-state* [:tabs opt-tab-id :signals :session-cols-0-width]))
            "tab signal state is untouched — down-sync patches the client")))

    (testing "swap! operates on the committed value"
      (binding [context/*signals* {:session-cols-0-width 300}]
        (swap! w* + 10)
        (is (= 510 (get-in @app-state* [:sessions opt-session-id :data :cols 0 :width])))))))

;; ---------------------------------------------------------------------------
;; Optimistic — commit and conflict policies
;; ---------------------------------------------------------------------------

(deftest resolve-commit-test
  (let [ctx        {:base 100 :committed 100 :reported 150}
        conflicted (assoc ctx :committed 120)]
    (testing "default and :client-wins are last-write-wins"
      (is (= 150 (signal/resolve-commit nil conflicted)))
      (is (= 150 (signal/resolve-commit :client-wins conflicted))))

    (testing "clean commit (base = committed) always takes the reported value"
      (is (= 150 (signal/resolve-commit :server-wins ctx)))
      (is (= 150 (signal/resolve-commit (fn [_] (throw (ex-info "not called" {}))) ctx))))

    (testing ":server-wins keeps the committed value on conflict"
      (is (= 120 (signal/resolve-commit :server-wins conflicted))))

    (testing "a fn policy resolves the conflict"
      (is (= 135 (signal/resolve-commit (fn [{:keys [committed reported]}]
                                          (quot (+ committed reported) 2))
                                        conflicted))))

    (testing "nil base with a differing committed value counts as a conflict"
      (is (= 120 (signal/resolve-commit :server-wins (assoc conflicted :base nil))))
      (is (= :from-fn (signal/resolve-commit (fn [{:keys [base]}]
                                               (is (nil? base))
                                               :from-fn)
                                             (assoc conflicted :base nil)))))))

(deftest optimistic-commit-test
  (testing "commit! outside an action throws"
    (let [app-state* (fresh-opt-state)
          w*         (with-render-ctx app-state*
                       (h/optimistic (h/session-cursor :col-w 100)))]
      (is (thrown-with-msg? Exception #"inside an action" (h/commit! w*)))))

  (testing "commit! when the signal did not ride the request throws"
    (let [app-state* (fresh-opt-state)
          w*         (with-render-ctx app-state*
                       (h/optimistic (h/session-cursor :col-w 100)))]
      (binding [context/*signals* {:name "form-field"}]
        (is (thrown-with-msg? Exception #"did not accompany" (h/commit! w*))))))

  (testing "commit! writes the reported value (LWW default)"
    (let [app-state* (fresh-opt-state)
          w*         (with-render-ctx app-state*
                       (h/optimistic (h/session-cursor :col-w 100)))]
      (binding [context/*signals* {:session-col-w 150}]
        (is (= 150 (h/commit! w*)))
        (is (= 150 (get-in @app-state* [:sessions opt-session-id :data :col-w]))))))

  (testing ":server-wins declares a base signal and rejects stale commits"
    (let [app-state* (fresh-opt-state)]
      (with-render-ctx app-state*
        (let [w* (h/optimistic (h/session-cursor :col-w 100) {:on-conflict :server-wins})]
          (is (some #(= :session-col-w-base (:path %)) @context/*declared-signals*)
              "a base companion signal is declared")
          ;; Another writer moved the cursor to 120 after this client synced.
          (swap! app-state* assoc-in [:sessions opt-session-id :data :col-w] 120)
          (binding [context/*signals* {:session-col-w 150 :session-col-w-base 100}]
            (is (= 120 (h/commit! w*)) "stale base → committed value wins")
            (is (= 120 (get-in @app-state* [:sessions opt-session-id :data :col-w]))))
          ;; A fresh base commits cleanly.
          (binding [context/*signals* {:session-col-w 150 :session-col-w-base 120}]
            (is (= 150 (h/commit! w*))))))))

  (testing "a fn policy resolves conflicted commits"
    (let [app-state* (fresh-opt-state)]
      (with-render-ctx app-state*
        (let [w* (h/optimistic (h/session-cursor :col-w 100)
                               {:on-conflict (fn [{:keys [committed reported]}]
                                               (max committed reported))})]
          (swap! app-state* assoc-in [:sessions opt-session-id :data :col-w] 200)
          (binding [context/*signals* {:session-col-w 150 :session-col-w-base 100}]
            (is (= 200 (h/commit! w*)) "max of committed 200 and reported 150"))))))

  (testing "a pure rejection still corrects the client"
    ;; The cursor does not change on rejection, so the down-sync alone
    ;; would never patch — the commit path must write the correction.
    (let [app-state* (fresh-opt-state)]
      (with-render-ctx app-state*
        (let [w* (h/optimistic (h/session-cursor :col-w 100) {:on-conflict :server-wins})]
          (swap! app-state* assoc-in [:sessions opt-session-id :data :col-w] 120)
          (with-render-ctx app-state*
            (h/optimistic (h/session-cursor :col-w 100) {:on-conflict :server-wins}))
          ;; Client reports 150 off a stale base; the merge already put 150
          ;; into tab signal state, as the action handler would.
          (swap! app-state* assoc-in [:tabs opt-tab-id :signals :session-col-w] 150)
          (binding [context/*signals* {:session-col-w 150 :session-col-w-base 100}]
            (is (= 120 (h/commit! w*))))
          (is (= 120 (get-in @app-state* [:tabs opt-tab-id :signals :session-col-w]))
              "the rejected value is replaced in signal state → patch flows")
          (is (= 120 (get-in @app-state* [:tabs opt-tab-id :signals :session-col-w-base])))
          (is (= 120 (get-in @app-state* [:tabs opt-tab-id :optimistic-synced [:session-col-w]])))))))

  (testing "down-sync keeps the base signal in step with the committed value"
    (let [app-state* (fresh-opt-state)
          render!    #(with-render-ctx app-state*
                        (h/optimistic (h/session-cursor :col-w 100) {:on-conflict :server-wins}))]
      (render!)
      (swap! app-state* assoc-in [:sessions opt-session-id :data :col-w] 300)
      (render!)
      (is (= 300 (get-in @app-state* [:tabs opt-tab-id :signals :session-col-w])))
      (is (= 300 (get-in @app-state* [:tabs opt-tab-id :signals :session-col-w-base]))
          "value and base patch together"))))

(deftest optimistic-auto-commit-test
  (let [app-state* (fresh-opt-state)]
    (with-render-ctx app-state*
      (h/optimistic (h/session-cursor :col-w 100) {:auto-commit? true})
      (h/optimistic (h/session-cursor :title "untitled")))

    (testing "auto-commit persists a riding signal value"
      (signal/auto-commit! app-state* opt-tab-id {:session-col-w 175 :session-title "draft"})
      (is (= 175 (get-in @app-state* [:sessions opt-session-id :data :col-w]))))

    (testing "non-auto-commit optimistics are untouched"
      (is (= "untitled" (get-in @app-state* [:sessions opt-session-id :data :title]))))

    (testing "absent signals are skipped"
      (signal/auto-commit! app-state* opt-tab-id {:unrelated 1})
      (is (= 175 (get-in @app-state* [:sessions opt-session-id :data :col-w]))))))
