(ns ^:e2e hyper.e2e-test
  "End-to-end browser tests for Hyper using Playwright (via wally).

   Tests cursor isolation across sessions/tabs, Var-based live reload of
   titles, and Var-based live reload of inline route handlers.

   Run with: clojure -M:test --focus :e2e"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.server]
            [hyper.state :as state]
            [wally.main :as w])
  (:import (com.microsoft.playwright Playwright BrowserType$LaunchOptions)))

;; ---------------------------------------------------------------------------
;; Test routes — defined as a Var so we can redef for live-reload tests
;; ---------------------------------------------------------------------------

(defn counter-widget
  "Render a counter widget for any cursor."
  [label cursor*]
  [:div.counter {:id (str "counter-" label)}
   [:h2 label ": " @cursor*]
   [:button.inc {:data-on:click (h/action (swap! cursor* inc))} "+"]
   [:button.dec {:data-on:click (h/action (swap! cursor* dec))} "–"]
   [:button.reset {:data-on:click (h/action (reset! cursor* 0))} "Reset"]])

(defn default-counters-get []
  (let [external* (atom 0)]
    (fn [_]
      (h/watch! external*)
      (let [global*  (h/global-cursor :count 0)
            session* (h/session-cursor :count 0)
            tab*     (h/tab-cursor :count 0)
            url*     (h/path-cursor :count 0)]
        [:div
         [:h1 "Test Counters"]
         (counter-widget "Global" global*)
         (counter-widget "External" external*)
         (counter-widget "Session" session*)
         (counter-widget "Tab" tab*)
         (counter-widget "URL" url*)]))))

(defn forms-get [_]
  (let [text*    (h/tab-cursor :text "")
        checked* (h/tab-cursor :dark-mode false)
        key*     (h/tab-cursor :last-key "")
        select*  (h/tab-cursor :color "red")
        form*    (h/tab-cursor :form-data nil)]
    [:div
     [:h1 "Test Forms"]

     [:div#text-demo
      [:input#text-input {:type          "text"
                          :value         @text*
                          :data-on:input (h/action (reset! (h/tab-cursor :text) $value))}]
      [:span#text-result (if (seq @text*) @text* "empty")]]

     [:div#select-demo
      [:select#color-select {:data-on:change (h/action (reset! (h/tab-cursor :color) $value))}
       (for [c ["red" "green" "blue"]]
         [:option {:value c :selected (= c @select*)} c])]
      [:span#select-result @select*]]

     [:div#checkbox-demo
      [:input#dark-checkbox {:type           "checkbox"
                             :checked        @checked*
                             :data-on:change (h/action (reset! (h/tab-cursor :dark-mode) $checked))}]
      [:span#checkbox-result (if @checked* "ON" "OFF")]]

     [:div#key-demo
      [:input#key-input {:type            "text"
                         :data-on:keydown (h/action (reset! (h/tab-cursor :last-key) $key))}]
      [:span#key-result (if (seq @key*) @key* "none")]]

     [:div#form-demo
      [:form#test-form {:data-on:submit__prevent
                        (h/action (reset! (h/tab-cursor :form-data) $form-data))}
       [:input {:name "name" :id "form-name"}]
       [:input {:name "email" :id "form-email"}]
       [:button#form-submit {:type "submit"} "Submit"]]
      (when @form*
        [:pre#form-result (pr-str @form*)])]]))

(defn signals-get [_]
  (let [name*  (h/signal :user-name "")
        saved* (h/tab-cursor :saved-name "")]
    [:div
     [:h1 "Test Signals"]

     ;; data-bind + data-text — client-side reactivity
     [:div#bind-demo
      [:input#name-input {:data-bind name* :placeholder "Name"}]
      [:span#name-display {:data-text (str "$" name*)} ""]]

     ;; Read signal in action
     [:div#read-demo
      [:button#save-btn {:data-on:click (h/action
                                          (reset! (h/tab-cursor :saved-name) @name*))}
       "Save"]
      [:span#saved-result (if (seq @saved*) @saved* "empty")]]

     ;; Signal + client params together
     [:div#combined-demo
      [:input#combined-input {:type "text"
                              :data-on:change
                              (h/action
                                (reset! (h/tab-cursor :saved-name)
                                        (str "signal=" @name* ",input=" $value)))}]
      [:span#combined-result (if (seq @saved*) @saved* "empty")]]

     ;; Reset signal from server
     [:div#reset-demo
      [:button#clear-btn {:data-on:click (h/action (reset! name* ""))} "Clear"]
      [:span#reset-display {:data-text (str "$" name*)} ""]]

     ;; Async signal update — works outside action handlers
     [:div#async-demo
      [:button#async-btn {:data-on:click
                          (h/action
                            (let [n name*]
                              (future
                                (Thread/sleep 500)
                                (reset! n "async-update"))))}
       "Start"]
      [:span#async-display {:data-text (str "$" name*)} ""]]]))

(defn- checkbox-array-get
  "Faithful reproduction of issue #44: a checkbox group bound to a
   server-backed array signal initialized to []."
  [_]
  (let [ids* (h/signal :ids [])]
    [:main
     (for [v ["a" "b" "c"]]
       [:label {:id (str "label-" v)}
        [:input {:type      "checkbox"
                 :value     v
                 :data-bind ids*
                 :id        (str "cb-" v)}]
        (str " " v)])
     ;; Robust read-out of the live signal value, independent of the
     ;; data-json-signals attribute name.
     [:span {:id "ids-dump" :data-text "JSON.stringify($ids)"}]]))

(defn- cb-nested-get
  "Issue #44: checkbox group bound to a nested array signal [:form :ids]."
  [_]
  (let [ids* (h/signal [:form :ids] [])]
    [:main
     (for [v ["a" "b" "c"]]
       [:label {:id (str "nlabel-" v)}
        [:input {:type "checkbox" :value v :data-bind ids* :id (str "ncb-" v)}]
        (str " " v)])
     [:span {:id "nids-dump" :data-text "JSON.stringify($form.ids)"}]]))

(defn- select-multi-get
  "Issue #44 sibling case: <select multiple> bound to an array signal,
   another binding Datastar materializes from the DOM."
  [_]
  (let [colors* (h/signal :colors [])]
    [:main
     [:select {:id "color-multi" :multiple true :data-bind colors*}
      (for [c ["red" "green" "blue"]]
        [:option {:value c :id (str "opt-" c)} c])]
     [:span {:id "colors-dump" :data-text "JSON.stringify($colors)"}]]))

(defn- cb-prechecked-get
  "Issue #44 sibling case: checkbox array seeded with a value, so the signal
   value equals its declared default and one box starts checked."
  [_]
  (let [ids* (h/signal :ids ["a"])]
    [:main
     (for [v ["a" "b" "c"]]
       [:label {:id (str "plabel-" v)}
        [:input {:type "checkbox" :value v :data-bind ids* :id (str "pcb-" v)}]
        (str " " v)])
     [:span {:id "pids-dump" :data-text "JSON.stringify($ids)"}]]))

(defn- cb-rerender-get
  "Issue #44 sibling case: a checkbox array alongside an unrelated action
   that forces a full page re-render, to verify a later re-render does not
   stomp the array the client materialized from the DOM."
  [_]
  (let [ids* (h/signal :ids [])
        n*   (h/tab-cursor :n 0)]
    [:main
     (for [v ["a" "b" "c"]]
       [:label {:id (str "rlabel-" v)}
        [:input {:type "checkbox" :value v :data-bind ids* :id (str "rcb-" v)}]
        (str " " v)])
     [:button {:id            "rerender-btn"
               :data-on:click (h/action (swap! n* inc))}
      "re-render"]
     ;; Server-rendered cursor value (not a client signal) — its text
     ;; changes only when a full re-render morphs the page.
     [:span {:id "rn-dump"} @n*]
     [:span {:id "rids-dump" :data-text "JSON.stringify($ids)"}]]))

;; Shared atom for testing reactive components — mutated from test code
;; to verify partial re-renders via SSE.
(def ^:private reactive-clock* (atom "00:00"))

(defn- reactive-get [_]
  (h/watch! reactive-clock*)
  (let [count*  (h/tab-cursor :count 0)
        static* (h/tab-cursor :static "initial")]
    [:div
     [:h1 "Reactive Test"]
     ;; Non-reactive part — only updates on full re-render
     [:span#static-value @static*]
     ;; Reactive component watching the shared clock atom
     (h/reactive [reactive-clock*]
                 [:span#clock-value @reactive-clock*])
     ;; Counter to trigger full re-renders
     [:span#counter-value @count*]
     [:button#inc-btn {:data-on:click (h/action (swap! (h/tab-cursor :count) inc))} "+"]
     ;; Reactive component with user-provided ID
     [:div#user-id-section
      (h/reactive [count*]
                  [:span {:id "custom-reactive"} "Count: " @count*])]]))

;; Shared atom for testing watch! bootstrap — mutated from test code
;; to verify that server-side changes trigger SSE re-renders.
(def ^:private watch-test-atom (atom "initial"))

(defn- watch-bootstrap-get [_]
  (h/watch! watch-test-atom)
  [:div
   [:h1 "Watch Bootstrap"]
   [:span#watch-value @watch-test-atom]])

(defn- effects-get [req]
  (let [cookie-val (get-in req [:cookies "test-effect-cookie" :value])
        status*    (h/tab-cursor :effect-status "none")]
    [:div
     [:h1 "Effects Test"]

     ;; navigate! test — button that navigates to home
     [:button#nav-btn {:data-on:click (h/action (effects/navigate! :home))} "Navigate Home"]

     ;; set-cookie! test — button that sets a cookie
     [:button#set-cookie-btn
      {:data-on:click (h/action
                        (effects/set-cookie! "test-effect-cookie" "hyper-test-value"
                                             {:max-age 3600})
                        (reset! (h/tab-cursor :effect-status) "cookie-set"))}
      "Set Cookie"]

     ;; delete-cookie! test — button that deletes the cookie
     [:button#delete-cookie-btn
      {:data-on:click (h/action
                        (effects/delete-cookie! "test-effect-cookie")
                        (reset! (h/tab-cursor :effect-status) "cookie-deleted"))}
      "Delete Cookie"]

     ;; execute-script! test — button that runs JS
     [:button#script-btn
      {:data-on:click (h/action
                        (effects/execute-script!
                          "document.getElementById('script-result').textContent = 'executed'"))}
      "Run Script"]

     ;; Display areas
     [:span#effect-status @status*]
     [:span#cookie-display (or cookie-val "no-cookie")]
     [:span#script-result "pending"]

     ;; Combined: set cookie + execute-script
     [:button#combo-btn
      {:data-on:click (h/action
                        (effects/set-cookie! "test-effect-cookie" "combo-value" {:max-age 3600})
                        (effects/execute-script!
                          "document.getElementById('script-result').textContent = 'combo-executed'")
                        (reset! (h/tab-cursor :effect-status) "combo-done"))}
      "Cookie + Script"]]))

(defn optimistic-get [_]
  (let [w* (h/optimistic (h/session-cursor :col-w 100))
        n* (h/tab-cursor :n 0)]
    [:div
     [:h1 "Test Optimistic"]
     ;; Signal-driven style — the morph-survival subject.
     [:div#opt-box {:data-attr:style (h/expr (str "--col-w:" @w* "px"))} "box"]
     [:span#server-n (str @n*)]
     ;; Client-only write (simulated drag frame), no server traffic.
     [:button#drag-btn {:data-on:click (h/expr (reset! w* 555))} "drag"]
     ;; Unrelated server round trip → full re-render → morph.
     [:button#bump-btn {:data-on:click (h/action (swap! n* inc))} "bump"]
     [:button#commit-btn {:data-on:click (h/action (h/commit! w*))} "commit"]]))

(def ^:private truthiness-rows [{:name "alice"} {:name "bob"}])

(defn- expr-truthiness-get [_]
  (let [selected* (h/signal :selected nil)]
    [:div
     [:h1 "Test Expr Truthiness"]
     [:ul (for [[i row] (map-indexed vector truthiness-rows)]
            [:li {:id (str "row-" (:name row)) :data-on:click (h/expr (reset! selected* i))} (:name row)])]
     [:button#delete-btn
      {:data-on:click (h/expr (when @selected*
                                (h/action (reset! (h/tab-cursor :deleted)
                                                  (:name (nth truthiness-rows @selected*))))))}
      "Delete"]
     [:span#deleted @(h/tab-cursor :deleted "none")]]))

(defn- expr-nested-get [_]
  (let [a* (h/signal :a false)
        b* (h/signal :b 7)]
    [:div
     [:h1 "Test Expr Nested"]
     [:span#when-let {:data-text (h/expr (when-let [x (or @a* @b*)] x))}]
     [:span#re-find {:data-text (h/expr (let [s (or @a* "xa;by")] (re-find #"a;b" s)))}]]))

(defn- action-retry-get [_]
  (let [clicks* (h/tab-cursor :clicks [])]
    [:div
     [:h1 "Test Action Retry"]
     (for [label ["a" "b" "c"]]
       [:button {:id (str "click-" label) :data-on:click (h/action (swap! clicks* conj label))} label])
     [:span#clicks (str/join "," @clicks*)]]))

(defn- upload-get [_]
  (let [status* (h/signal :avatar-upload {:phase :idle :percent 0})]
    [:div
     [:h1 "Test Upload"]
     [:form {:data-on:submit__prevent (h/action {:upload status*}
                                                (reset! (h/tab-cursor :uploaded) (:filename (:avatar $form))))}
      [:input#avatar {:type "file" :name "avatar"}]
      [:button#upload-btn {:type "submit"} "Upload"]]
     [:span#uploaded @(h/tab-cursor :uploaded "none")]]))

(defn- data-init-get [_]
  [:div
   [:h1 "Test Data Init"]
   [:div#init {:data-init (h/action (reset! (h/tab-cursor :inited) "yes"))}]
   [:span#inited @(h/tab-cursor :inited "no")]])

(defn default-routes []
  [["/" {:name  :home
         :title "Home"
         :get   (fn [_]
                  [:div
                   [:h1 "Test Home"]
                   [:a (h/navigate :counters) "Go to counters"]])}]
   ["/counters"
    {:name  :counters
     :title (fn [_]
              (str "Counter: " @(h/session-cursor :count 0)))
     :get   (default-counters-get)}]
   ["/forms"
    {:name  :forms
     :title "Forms"
     :get   #'forms-get}]
   ["/signals"
    {:name  :signals
     :title "Signals"
     :get   #'signals-get}]
   ["/checkbox-array"
    {:name  :checkbox-array
     :title "Checkbox Array"
     :get   #'checkbox-array-get}]
   ["/cb-nested"     {:name :cb-nested :title "Nested Checkbox Array" :get #'cb-nested-get}]
   ["/select-multi"  {:name :select-multi :title "Select Multiple" :get #'select-multi-get}]
   ["/cb-prechecked" {:name :cb-prechecked :title "Prechecked Array" :get #'cb-prechecked-get}]
   ["/cb-rerender"   {:name :cb-rerender :title "Rerender Array" :get #'cb-rerender-get}]
   ["/watch-bootstrap"
    {:name  :watch-bootstrap
     :title "Watch Bootstrap"
     :get   #'watch-bootstrap-get}]
   ["/reactive"
    {:name  :reactive
     :title "Reactive"
     :get   #'reactive-get}]
   ["/effects"
    {:name  :effects
     :title "Effects"
     :get   #'effects-get}]
   ["/optimistic"
    {:name  :optimistic
     :title "Optimistic"
     :get   #'optimistic-get}]
   ["/expr-truthiness"
    {:name  :expr-truthiness
     :title "Expr Truthiness"
     :get   #'expr-truthiness-get}]
   ["/expr-nested"
    {:name  :expr-nested
     :title "Expr Nested"
     :get   #'expr-nested-get}]
   ["/action-retry"
    {:name  :action-retry
     :title "Action Retry"
     :get   #'action-retry-get}]
   ["/upload"
    {:name  :upload
     :title "Upload"
     :get   #'upload-get}]
   ["/data-init"
    {:name  :data-init
     :title "Data Init"
     :get   #'data-init-get}]])

(def ^:dynamic *test-routes* (default-routes))

;; Head var for hot-reload testing
(defn- test-head-var
  [_]
  [:style "v1"])

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(def ^:private test-port 13020)
(def ^:private base-url (str "http://localhost:" test-port))
(def ^:private test-state* (atom nil))
(def ^:private test-server (atom nil))

(defn start-test-server! []
  (reset! test-state* (atom (state/init-state)))
  (let [handler (h/create-handler #'*test-routes* :app-state @test-state* :head #'test-head-var
                                  :tree-shake? (= "true" (System/getProperty "hyper.tree-shake")))]
    (reset! test-server (h/start! handler {:port test-port}))))

(defn stop-test-server! []
  (when @test-server
    (h/stop! @test-server)
    (reset! test-server nil)
    (reset! test-state* nil)))

;; ---------------------------------------------------------------------------
;; Playwright helpers — for creating isolated browser contexts
;;
;; wally's make-page always uses a persistent context (shared data dir),
;; so we use the Playwright Java API directly for isolated sessions.
;; We still use wally's query/click/text-content etc. via with-page.
;; ---------------------------------------------------------------------------

(defn launch-browser
  "Launch a headless Chromium browser. Returns {:playwright pw :browser browser}.

   If PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH is set, we launch that Chromium
   binary instead of Playwright's downloaded browser bundle."
  []
  (let [pw              (Playwright/create)
        executable-path (some-> (System/getenv "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH")
                                (str/trim)
                                (not-empty))
        opts            (doto (BrowserType$LaunchOptions.)
                          (.setHeadless true))
        _               (when executable-path
                          (.setExecutablePath opts (java.nio.file.Path/of executable-path (into-array String []))))
        browser         (.. pw chromium (launch opts))]
    {:playwright pw :browser browser}))

(defn new-context
  "Create a new browser context (isolated session — no shared cookies)."
  [browser-info]
  (.newContext (:browser browser-info)))

(defn new-page
  "Create a new page in a browser context."
  [ctx]
  (let [page (.newPage ctx)]
    (.setDefaultTimeout page 10000)
    page))

(defn close-browser!
  "Close browser and Playwright process."
  [{:keys [playwright browser]}]
  (try
    (.close browser)
    (.close playwright)
    (catch Exception _)))

;; ---------------------------------------------------------------------------
;; Wally-compatible helpers
;; ---------------------------------------------------------------------------

(defn wait-for-sse
  "Wait for the SSE connection to establish and initial render to complete."
  []
  (w/wait-for "#hyper-app" {:state :visible :timeout 10000}))

(defn eval-js
  "Evaluate JavaScript in the current page."
  [js]
  (.evaluate (w/get-page) js))

(defn wait-for-pred
  "Poll a zero-arg predicate every 100ms until it returns truthy or the
   timeout elapses.  Exceptions from pred count as falsey (elements may not
   exist yet).  Returns true on success, false on timeout — callers decide
   how to assert/report failure."
  [pred & {:keys [timeout] :or {timeout 5000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (cond
        (try (boolean (pred)) (catch Exception _ false)) true
        (> (System/currentTimeMillis) deadline)          false
        :else (do (Thread/sleep 100) (recur))))))

(defn wait-for-text
  "Poll until an element's text content matches expected, with timeout."
  [selector expected & {:keys [timeout] :or {timeout 5000}}]
  (or (wait-for-pred #(= expected (w/text-content selector)) :timeout timeout)
      (let [actual (try (w/text-content selector) (catch Exception _ nil))]
        (is (= expected actual)
            (str "Timed out waiting for " selector " to equal " (pr-str expected)
                 ", last saw " (pr-str actual)))
        false)))

(defn wait-for-cookie
  "Poll until document.cookie contains (or no longer contains) a cookie name=value pair.
   When `absent?` is true, waits until the cookie name is NOT present."
  [cookie-name expected-value & {:keys [timeout absent?] :or {timeout 5000 absent? false}}]
  (let [pattern (str cookie-name "=" expected-value)
        pred    (if absent?
                  #(not (.contains (str (eval-js "document.cookie")) (str cookie-name "=")))
                  #(.contains (str (eval-js "document.cookie")) pattern))]
    (or (wait-for-pred pred :timeout timeout)
        (let [cookies (try (eval-js "document.cookie") (catch Exception _ ""))]
          (is false (if absent?
                      (str "Timed out waiting for cookie " cookie-name " to be absent, saw: " cookies)
                      (str "Timed out waiting for cookie " pattern " in: " cookies)))
          false))))

(defn counter-text
  "Get the text of a counter's h2 heading."
  [label]
  (w/text-content (str "#counter-" label " h2")))

(defn click-counter-button
  "Click a counter's +, –, or reset button."
  [label button-class]
  (w/click (str "#counter-" label " " button-class)))

(defn page-title
  "Get the current document.title via JS evaluation."
  []
  (eval-js "document.title"))

(defn current-url
  "Get the current page URL."
  []
  (eval-js "window.location.href"))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (start-test-server!)
    (try
      (f)
      (finally
        (stop-test-server!)))))

(defn- default-test-head-var [_] [:style "v1"])

(use-fixtures :each
  (fn [f]
     ;; Reset routes and app state before each test, preserving
     ;; infrastructure keys (:routes-source, :head, etc.)
     ;; that create-handler stored in the app-state atom.
    (alter-var-root #'*test-routes* (constantly (default-routes)))
    (alter-var-root #'test-head-var (constantly default-test-head-var))
    (reset! watch-test-atom "initial")
    (reset! reactive-clock* "00:00")
    (when @test-state*
      (swap! @test-state*
             (fn [old-state]
               (merge (state/init-state)
                      (select-keys old-state
                                   [:routes-source
                                    :head
                                    :router :routes])))))
    (f)))

;; ---------------------------------------------------------------------------
;; Test 1: Cursor isolation across sessions and tabs
;; ---------------------------------------------------------------------------

(deftest ^:e2e cursor-isolation-test
  (let [browser-info (launch-browser)
        ;; Session 1: browser context with its own cookies
        ctx1         (new-context browser-info)
        s1-tab1      (new-page ctx1)
        s1-tab2      (new-page ctx1) ;; same context = same session cookie
        ;; Session 2: separate browser context (different session)
        ctx2         (new-context browser-info)
        s2-tab1      (new-page ctx2)]
    (try
      ;; --- Navigate all pages to /counters ---
      (w/with-page s1-tab1
        (w/navigate (str base-url "/counters"))
        (wait-for-sse))

      (w/with-page s1-tab2
        (w/navigate (str base-url "/counters"))
        (wait-for-sse))

      (w/with-page s2-tab1
        (w/navigate (str base-url "/counters"))
        (wait-for-sse))

      ;; Verify initial state — all counters at 0
      (w/with-page s1-tab1
        (is (= "Global: 0" (counter-text "Global")))
        (is (= "Session: 0" (counter-text "Session")))
        (is (= "Tab: 0" (counter-text "Tab")))
        (is (= "URL: 0" (counter-text "URL"))))

      ;; ------------------------------------------------------------------
      ;; Test Global cursor: visible to ALL sessions and tabs
      ;; ------------------------------------------------------------------
      (testing "Global cursor is shared across all sessions and tabs"
        (w/with-page s1-tab1
          (click-counter-button "Global" ".inc"))

        (w/with-page s1-tab1
          (wait-for-text "#counter-Global h2" "Global: 1"))

        ;; Same session, different tab
        (w/with-page s1-tab2
          (wait-for-text "#counter-Global h2" "Global: 1"))

        ;; Different session entirely
        (w/with-page s2-tab1
          (wait-for-text "#counter-Global h2" "Global: 1")))

      ;; ------------------------------------------------------------------
      ;; Test Session cursor: shared within session, isolated across sessions
      ;; ------------------------------------------------------------------
      (testing "Session cursor is shared within a session but not across sessions"
        (w/with-page s1-tab1
          (click-counter-button "Session" ".inc"))

        (w/with-page s1-tab1
          (wait-for-text "#counter-Session h2" "Session: 1"))

        ;; Same session, different tab — should see the change
        (w/with-page s1-tab2
          (wait-for-text "#counter-Session h2" "Session: 1"))

        ;; Different session — should NOT see the change
        (w/with-page s2-tab1
          (Thread/sleep 500)
          (is (= "Session: 0" (counter-text "Session")))))

      ;; ------------------------------------------------------------------
      ;; Test Tab cursor: private to the specific tab
      ;; ------------------------------------------------------------------
      (testing "Tab cursor is private to a single tab"
        (w/with-page s1-tab1
          (click-counter-button "Tab" ".inc"))

        (w/with-page s1-tab1
          (wait-for-text "#counter-Tab h2" "Tab: 1"))

        ;; Same session, different tab — should NOT see the change
        (w/with-page s1-tab2
          (Thread/sleep 500)
          (is (= "Tab: 0" (counter-text "Tab"))))

        ;; Different session — should NOT see the change
        (w/with-page s2-tab1
          (Thread/sleep 500)
          (is (= "Tab: 0" (counter-text "Tab")))))

      ;; ------------------------------------------------------------------
      ;; Test URL/path cursor: updates the URL query string
      ;; ------------------------------------------------------------------
      (testing "URL cursor updates the query string"
        (w/with-page s1-tab1
          (click-counter-button "URL" ".inc")
          (wait-for-text "#counter-URL h2" "URL: 1")
          ;; Wait for MutationObserver to fire replaceState — poll for URL change
          (let [deadline (+ (System/currentTimeMillis) 3000)]
            (loop []
              (let [url (current-url)]
                (if (re-find #"count=1" url)
                  (is true)
                  (if (> (System/currentTimeMillis) deadline)
                    (is (re-find #"count=1" url)
                        (str "Expected count=1 in URL, got: " url))
                    (do (Thread/sleep 100)
                        (recur)))))))))

      ;; ------------------------------------------------------------------
      ;; Test External atom cursor (watched via watch!)
      ;; ------------------------------------------------------------------
      (testing "External atom counter works via watch!"
        (w/with-page s1-tab1
          (click-counter-button "External" ".inc")
          (wait-for-text "#counter-External h2" "External: 1")))

      ;; ------------------------------------------------------------------
      ;; Test Reset
      ;; ------------------------------------------------------------------
      (testing "Reset button works for global cursor and propagates everywhere"
        (w/with-page s1-tab1
          (click-counter-button "Global" ".reset"))

        (w/with-page s1-tab1
          (wait-for-text "#counter-Global h2" "Global: 0"))

        (w/with-page s2-tab1
          (wait-for-text "#counter-Global h2" "Global: 0")))

      ;; ------------------------------------------------------------------
      ;; Test multiple increments
      ;; ------------------------------------------------------------------
      (testing "Multiple increments on tab cursor accumulate"
        (w/with-page s1-tab1
          (click-counter-button "Tab" ".inc")
          (wait-for-text "#counter-Tab h2" "Tab: 2")
          (click-counter-button "Tab" ".inc")
          (wait-for-text "#counter-Tab h2" "Tab: 3"))

        ;; Other tabs still at their own values
        (w/with-page s1-tab2
          (is (= "Tab: 0" (counter-text "Tab"))))

        (w/with-page s2-tab1
          (is (= "Tab: 0" (counter-text "Tab")))))

      ;; ------------------------------------------------------------------
      ;; Test decrement
      ;; ------------------------------------------------------------------
      (testing "Decrement button works"
        (w/with-page s1-tab1
          (click-counter-button "Tab" ".dec")
          (wait-for-text "#counter-Tab h2" "Tab: 2")))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test 4: Forms & Inputs — client params ($value, $checked, $key, $form-data)
;; ---------------------------------------------------------------------------

(deftest ^:e2e forms-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/forms"))
        (wait-for-sse)

        (testing "Initial state"
          (is (= "Test Forms" (w/text-content "h1")))
          (is (= "empty" (w/text-content "#text-result")))
          (is (= "red" (w/text-content "#select-result")))
          (is (= "OFF" (w/text-content "#checkbox-result")))
          (is (= "none" (w/text-content "#key-result"))))

        ;; ----------------------------------------------------------------
        ;; $value — text input
        ;; ----------------------------------------------------------------
        (testing "$value text input sends keystrokes to server"
          (w/fill "#text-input" "hello")
          (wait-for-text "#text-result" "hello"))

        ;; ----------------------------------------------------------------
        ;; $value — select
        ;; ----------------------------------------------------------------
        (testing "$value select sends selected option to server"
          ;; Wait for any in-flight SSE morph from the previous test to
          ;; settle — a morph race (Playwright resolves the element, then
          ;; SSE replaces it before the event fires) can silently drop the
          ;; change event.
          (Thread/sleep 500)
          (w/select "#color-select" "blue")
          (wait-for-text "#select-result" "blue"))

        ;; ----------------------------------------------------------------
        ;; $checked — checkbox
        ;; ----------------------------------------------------------------
        (testing "$checked sends boolean to server"
          (is (= "OFF" (w/text-content "#checkbox-result")))
          (w/click "#dark-checkbox")
          (wait-for-text "#checkbox-result" "ON")
          ;; Toggle back
          (w/click "#dark-checkbox")
          (wait-for-text "#checkbox-result" "OFF"))

        ;; ----------------------------------------------------------------
        ;; $key — keyboard events
        ;; ----------------------------------------------------------------
        (testing "$key captures key name"
          (w/click "#key-input")
          (w/keyboard-press "ArrowUp")
          (wait-for-text "#key-result" "ArrowUp")
          ;; Re-focus: the SSE re-render morphs the DOM and the input
          ;; may lose focus, so click it again before the next keypress.
          (w/click "#key-input")
          (w/keyboard-press "Escape")
          (wait-for-text "#key-result" "Escape"))

        ;; ----------------------------------------------------------------
        ;; $form-data — form submission
        ;; ----------------------------------------------------------------
        (testing "$form-data sends all named fields as a map"
          (w/fill "#form-name" "Alice")
          (w/fill "#form-email" "alice@example.com")
          (w/click "#form-submit")
          (w/wait-for "#form-result" {:state :visible :timeout 5000})
          (let [result (w/text-content "#form-result")]
            (is (.contains result "name"))
            (is (.contains result "Alice"))
            (is (.contains result "email"))
            (is (.contains result "alice@example.com")))))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test 5: History restore reloads stale documents
;; ---------------------------------------------------------------------------

(deftest ^:e2e history-restore-reload-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/counters"))
        (wait-for-sse)
        (wait-for-text "#counter-Session h2" "Session: 0")

        (let [initial-action (eval-js "document.querySelector('#counter-Session .inc').getAttribute('data-on:click')")]
          (click-counter-button "Session" ".inc")
          (wait-for-text "#counter-Session h2" "Session: 1")

          ;; Leave the Hyper document, then use browser history to return.
          (.navigate page "data:text/html,<title>Away</title><h1>Away</h1>")
          (is (= "Away" (w/text-content "h1")))
          (.goBack page)

          ;; The restored document should reload and register fresh actions.
          (let [deadline (+ (System/currentTimeMillis) 10000)]
            (loop []
              (let [current-action (try (eval-js "document.querySelector('#counter-Session .inc') && document.querySelector('#counter-Session .inc').getAttribute('data-on:click')")
                                        (catch Exception _ nil))]
                (if (and current-action (not= initial-action current-action))
                  (is true)
                  (if (> (System/currentTimeMillis) deadline)
                    (is (and current-action (not= initial-action current-action))
                        (str "Expected Session increment action to change after history restore, but still saw " (pr-str current-action)))
                    (do (Thread/sleep 100)
                        (recur)))))))

          (w/wait-for "#hyper-app" {:state :visible :timeout 10000})
          (wait-for-text "#counter-Session h2" "Session: 1")
          (click-counter-button "Session" ".inc")
          (wait-for-text "#counter-Session h2" "Session: 2")))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test 2: Title changes when route Var is redefined
;; ---------------------------------------------------------------------------

(deftest ^:e2e title-redef-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/counters"))
        (wait-for-sse)

        ;; Verify initial title (session counter starts at 0)
        (testing "Initial title reflects session counter state"
          (Thread/sleep 500)
          (is (= "Counter: 0" (page-title))))

        ;; Redefine the routes Var with a different title fn
        (testing "Title updates after routes Var is redefined"
          (alter-var-root #'*test-routes*
                          (constantly
                            [["/" {:name  :home
                                   :title "Home"
                                   :get   (fn [_]
                                            [:div [:h1 "Test Home"]
                                             [:a (h/navigate :counters) "Go to counters"]])}]
                             ["/counters"
                              {:name  :counters
                               :title (fn [_] "Brand New Title")
                               :get   (default-counters-get)}]]))

          ;; Wait for SSE to re-render (Var watch triggers re-render)
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (let [title (page-title)]
                (when (and (not= "Brand New Title" title)
                           (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 100)
                  (recur)))))
          (is (= "Brand New Title" (page-title)))))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test 3: Content changes when route Var with inline fns is redefined
;; ---------------------------------------------------------------------------

(deftest ^:e2e content-redef-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/counters"))
        (wait-for-sse)

        ;; Verify initial content
        (testing "Initial content is present"
          (is (= "Test Counters" (w/text-content "h1"))))

        ;; Redefine routes with completely different inline content
        (testing "Content updates after routes Var is redefined with new inline fns"
          (alter-var-root #'*test-routes*
                          (constantly
                            [["/" {:name  :home
                                   :title "Home"
                                   :get   (fn [_]
                                            [:div [:h1 "Test Home"]
                                             [:a (h/navigate :counters) "Go to counters"]])}]
                             ["/counters"
                              {:name  :counters
                               :title "Reloaded Page"
                               :get   (fn [_]
                                        [:div
                                         [:h1 "Live Reloaded!"]
                                         [:p#reloaded-marker "This content was hot-swapped"]])}]]))

          ;; Wait for the new content to appear via SSE re-render
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (let [text (try (w/text-content "h1") (catch Exception _ nil))]
                (when (and (not= "Live Reloaded!" text)
                           (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 100)
                  (recur)))))

          (is (= "Live Reloaded!" (w/text-content "h1")))
          (is (= "This content was hot-swapped"
                 (w/text-content "#reloaded-marker"))))

        ;; Test that content with newlines renders correctly
        (testing "Content with newlines preserved in route handler"
          (alter-var-root #'*test-routes*
                          (constantly
                            [["/" {:name  :home
                                   :title "Newlines Test"
                                   :get   (fn [_]
                                            [:div
                                             [:textarea#newline-content "line1\nline2"]
                                             [:pre#pre-content "code\nwith\n\nnew\n\nlines\n\n"]])}]]))
          (w/navigate (str base-url "/"))
          (wait-for-sse)
          (is (= "line1\nline2" (w/text-content "#newline-content")))
          (is (= "code\nwith\n\nnew\n\nlines\n\n" (w/text-content "#pre-content")))))
      (finally
        (close-browser! browser-info)))))

(deftest ^:e2e head-redef-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/"))
        (wait-for-sse)

        ;; Verify initial head content
        (testing "Initial head content present"
          (Thread/sleep 500)
          (is (= "v1" (w/text-content "style"))))

        ;; Redefine head Var
        (testing "Head updates after head Var is redefined"
          (alter-var-root #'test-head-var (constantly (fn [_] [:style "v2"])))
          (Thread/sleep 500)

          ;; Wait for SSE to re-render
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (let [content (w/text-content "style")]
                (when (and (not= "v2" content)
                           (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 100)
                  (recur)))))
          (is (= "v2" (w/text-content "style")))))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test: Head element per-element diffing (issue #42)
;;
;; Verifies that static/unchanged head elements are NOT removed and
;; re-appended on SSE re-renders, preventing FOUC and script re-execution.
;; ---------------------------------------------------------------------------

(deftest ^:e2e head-element-diffing-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      ;; Start with a multi-element head: a style + a link
      (alter-var-root #'test-head-var
                      (constantly
                        (fn [_]
                          [[:style {:id "test-style"} ".v1{color:red}"]
                           [:meta {:id "test-meta" :name "test" :content "value1"}]])))

      (w/with-page page
        (w/navigate (str base-url "/"))
        (wait-for-sse)

        (testing "Initial head elements are present with fingerprint attributes"
          (Thread/sleep 500)
          (let [style-fp (eval-js "document.getElementById('test-style')?.getAttribute('data-hyper-head')")
                meta-fp  (eval-js "document.getElementById('test-meta')?.getAttribute('data-hyper-head')")]
            (is (some? style-fp) "Style element should have a data-hyper-head fingerprint")
            (is (some? meta-fp) "Meta element should have a data-hyper-head fingerprint")
            (is (not= style-fp meta-fp) "Different elements should have different fingerprints")))

        (testing "Unchanged head elements are not re-appended on re-render"
          ;; Stamp each head element with a marker attribute to detect re-insertion.
          ;; If the element is removed and re-appended, the marker will be lost.
          (eval-js "document.getElementById('test-style')?.setAttribute('data-marker', 'original')")
          (eval-js "document.getElementById('test-meta')?.setAttribute('data-marker', 'original')")

          ;; Trigger a re-render by incrementing a counter (navigate to counters and back)
          ;; Use a simpler approach: redef the routes var to force a re-render
          (alter-var-root #'*test-routes*
                          (constantly
                            [["/" {:name  :home
                                   :title "Diffing Test"
                                   :get   (fn [_]
                                            [:div [:h1 "Re-rendered"]])}]]))

          ;; Wait for the re-render to arrive
          (wait-for-text "h1" "Re-rendered")

          ;; The head elements should still have their marker — they were NOT removed/re-appended
          (let [style-marker (eval-js "document.getElementById('test-style')?.getAttribute('data-marker')")
                meta-marker  (eval-js "document.getElementById('test-meta')?.getAttribute('data-marker')")]
            (is (= "original" style-marker)
                "Style element should NOT have been re-appended (marker preserved)")
            (is (= "original" meta-marker)
                "Meta element should NOT have been re-appended (marker preserved)")))

        (testing "Changed head elements are swapped, unchanged ones stay"
          ;; Mark the meta element again
          (eval-js "document.getElementById('test-meta')?.setAttribute('data-marker', 'original2')")
          (eval-js "document.getElementById('test-style')?.setAttribute('data-marker', 'original2')")

          ;; Change ONLY the style, keep meta the same
          (alter-var-root #'test-head-var
                          (constantly
                            (fn [_]
                              [[:style {:id "test-style"} ".v2{color:blue}"]
                               [:meta {:id "test-meta" :name "test" :content "value1"}]])))

          ;; Wait for SSE re-render
          (Thread/sleep 1500)

          ;; The meta element should still have its marker (unchanged → not touched)
          (let [meta-marker (eval-js "document.getElementById('test-meta')?.getAttribute('data-marker')")]
            (is (= "original2" meta-marker)
                "Unchanged meta element should NOT have been re-appended"))

          ;; The style element should have new content (was removed + re-appended)
          (let [style-marker  (eval-js "document.getElementById('test-style')?.getAttribute('data-marker')")
                style-content (eval-js "document.getElementById('test-style')?.textContent")]
            (is (nil? style-marker)
                "Changed style element should have been re-appended (marker lost)")
            (is (= ".v2{color:blue}" style-content)
                "Style element should have the new content")))

        (testing "Removed head elements are cleaned up"
          ;; Change head to only have the style, dropping the meta
          (alter-var-root #'test-head-var
                          (constantly
                            (fn [_]
                              [:style {:id "test-style"} ".v3{color:green}"])))

          (Thread/sleep 1500)

          (let [meta-el       (eval-js "document.getElementById('test-meta')")
                style-content (eval-js "document.getElementById('test-style')?.textContent")]
            (is (nil? meta-el)
                "Removed meta element should no longer be in the DOM")
            (is (= ".v3{color:green}" style-content)
                "Style element should have updated content"))))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test 5: Signals — declaration, binding, action reads, reset, client params
;; ---------------------------------------------------------------------------

(deftest ^:e2e signals-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/signals"))
        (wait-for-sse)

        (testing "Initial state"
          (is (= "Test Signals" (w/text-content "h1")))
          (is (= "" (w/text-content "#name-display")))
          (is (= "empty" (w/text-content "#saved-result"))))

        (testing "Signal declaration renders data-signals attribute"
          (let [app-html (eval-js "document.getElementById('hyper-app').outerHTML")]
            (is (.contains app-html "data-signals"))
            (is (.contains app-html "ifmissing"))))

        ;; ----------------------------------------------------------------
        ;; data-bind: typing updates the signal client-side via data-text
        ;; ----------------------------------------------------------------
        (testing "data-bind updates signal, data-text reflects it"
          (w/fill "#name-input" "Alice")
          ;; data-text is pure client-side reactivity — should be instant
          (wait-for-text "#name-display" "Alice"))

        ;; ----------------------------------------------------------------
        ;; Reading signal in action: server receives the signal value
        ;; ----------------------------------------------------------------
        (testing "Action reads signal value from @post body"
          (w/click "#save-btn")
          (wait-for-text "#saved-result" "Alice"))

        ;; ----------------------------------------------------------------
        ;; Server reset: reset! signal pushes update to client
        ;; ----------------------------------------------------------------
        (testing "Server reset! pushes signal update to client"
          ;; First re-type the name after morph may have cleared it
          (w/fill "#name-input" "Bob")
          (wait-for-text "#name-display" "Bob")
          (w/click "#clear-btn")
          ;; The server resets the signal → SSE pushes datastar-patch-signals
          ;; → client signal updates → data-text re-evaluates
          (wait-for-text "#reset-display" "")
          ;; The name input should also be cleared since it's data-bind'd
          (wait-for-text "#name-display" ""))

        ;; ----------------------------------------------------------------
        ;; Signal + client params: both available in the same action
        ;; ----------------------------------------------------------------
        (testing "Signals and client params work together in same action"
          ;; Type a fresh name so we know the signal value
          (w/fill "#name-input" "Eve")
          (wait-for-text "#name-display" "Eve")
          ;; Type in the separate input and trigger change event
          (w/fill "#combined-input" "typed-val")
          (w/keyboard-press "Tab")
          (wait-for-text "#combined-result" "signal=Eve,input=typed-val"))

        ;; ----------------------------------------------------------------
        ;; Async update: reset! from a background thread (outside action)
        ;; ----------------------------------------------------------------
        (testing "Signal reset! from background thread pushes update to client"
          ;; Clear signal first so we can detect the async update
          (w/fill "#name-input" "")
          (wait-for-text "#async-display" "")
          (w/click "#async-btn")
          ;; The action kicks off a future that sleeps 500ms then resets.
          ;; Wait up to 3s for the update to arrive via SSE.
          (wait-for-text "#async-display" "async-update" :timeout 3000)))

      (finally
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Issue #44: checkbox group bound to an empty-array signal
;; ---------------------------------------------------------------------------

(deftest ^:e2e checkbox-array-bind-test
  (testing "checkbox group bound to (h/signal :ids []) updates on first click (issue #44)"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/checkbox-array"))
          (wait-for-sse)

          ;; The signal starts as an empty array.  We wait until Datastar has
          ;; evaluated the data-text expression at least once.
          (testing "initial signal value is an array"
            (is (wait-for-pred
                  #(let [t (w/text-content "#ids-dump")]
                     (and t (str/starts-with? (str/trim t) "[")))
                  :timeout 5000)
                (str "ids-dump never rendered an array, last saw "
                     (pr-str (try (w/text-content "#ids-dump") (catch Exception _ nil))))))

          ;; Clicking the first checkbox adds "a" to the bound array signal,
          ;; confirming Datastar keeps the checkbox-array setup it builds
          ;; from the DOM.
          (testing "clicking checkbox 'a' adds 'a' to the signal"
            (w/click "#cb-a")
            (is (wait-for-pred
                  #(let [t (w/text-content "#ids-dump")]
                     (and t (str/includes? t "\"a\"")))
                  :timeout 5000)
                (str "signal never included 'a' after clicking checkbox; last saw "
                     (pr-str (try (w/text-content "#ids-dump") (catch Exception _ nil)))))))

        (finally
          (close-browser! browser-info))))))

(defn- assert-array-bind-updates
  "Navigate to `path`, wait for SSE, click `click-id`, and assert the live
   signal dumped at `dump-id` includes `expect` — i.e. Datastar retained
   the DOM-materialized array binding and the click reached the signal."
  [path click-id dump-id expect]
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url path))
        (wait-for-sse)
        (w/click click-id)
        (is (wait-for-pred
              #(let [t (w/text-content dump-id)]
                 (and t (str/includes? t expect)))
              :timeout 5000)
            (str path " — signal never included " (pr-str expect)
                 "; last saw "
                 (pr-str (try (w/text-content dump-id) (catch Exception _ nil))))))
      (finally
        (close-browser! browser-info)))))

(deftest ^:e2e nested-checkbox-array-bind-test
  (testing "checkbox group bound to a nested array signal updates on click (issue #44)"
    (assert-array-bind-updates "/cb-nested" "#ncb-a" "#nids-dump" "\"a\"")))

(deftest ^:e2e select-multiple-bind-test
  (testing "<select multiple> bound to an array signal updates on selection (issue #44)"
    (assert-array-bind-updates "/select-multi" "#opt-red" "#colors-dump" "\"red\"")))

(deftest ^:e2e prechecked-checkbox-array-bind-test
  (testing "checkbox array seeded with a value (value == default) still toggles (issue #44)"
    ;; seeded with "a"; clicking "b" should add it alongside
    (assert-array-bind-updates "/cb-prechecked" "#pcb-b" "#pids-dump" "\"b\"")))

(deftest ^:e2e checkbox-array-survives-rerender-test
  (testing "a later full re-render does not stomp the client-materialized array (issue #44)"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/cb-rerender"))
          (wait-for-sse)

          ;; Materialize the array on the client by ticking a checkbox.
          (w/click "#rcb-a")
          (is (wait-for-pred
                #(str/includes? (str (w/text-content "#rids-dump")) "\"a\"")
                :timeout 5000)
              "checkbox click did not reach the signal")

          ;; Force an unrelated full page re-render via an action.
          (w/click "#rerender-btn")
          (wait-for-text "#rn-dump" "1")

          ;; The array the client built must survive the re-render.
          (is (str/includes? (str (w/text-content "#rids-dump")) "\"a\"")
              (str "array was clobbered by the re-render; saw "
                   (pr-str (w/text-content "#rids-dump")))))
        (finally
          (close-browser! browser-info))))))

;; ---------------------------------------------------------------------------
;; Test 6: watch! bootstrap — server-side atom mutation triggers SSE update
;; ---------------------------------------------------------------------------

(deftest ^:e2e watch-bootstrap-test
  (testing "h/watch! during initial HTTP render gets promoted on SSE connect,
            so server-side atom mutations trigger live updates"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        ;; Reset the shared atom to a known state
        (reset! watch-test-atom "initial")

        (w/with-page page
          (w/navigate (str base-url "/watch-bootstrap"))
          (wait-for-sse)

          ;; 1. Verify initial HTTP render shows the atom's value
          (testing "Initial page renders the watched atom value"
            (is (= "Watch Bootstrap" (w/text-content "h1")))
            (is (= "initial" (w/text-content "#watch-value"))))

          ;; 2. Mutate the atom from the server side (no user action involved)
          ;;    — this is the scenario that was broken before the fix.
          (testing "Server-side atom mutation triggers SSE re-render"
            (reset! watch-test-atom "updated-from-server")
            (wait-for-text "#watch-value" "updated-from-server"))

          ;; 3. Verify multiple server-side mutations continue to work
          (testing "Subsequent server-side mutations also trigger re-renders"
            (reset! watch-test-atom "second-update")
            (wait-for-text "#watch-value" "second-update")))

        (finally
          ;; Reset for other tests
          (reset! watch-test-atom "initial")
          (close-browser! browser-info)))))

  (testing "Multiple tabs each get their own watch on the same atom"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page1        (new-page ctx)
          page2        (new-page ctx)]
      (try
        (reset! watch-test-atom "start")

        (w/with-page page1
          (w/navigate (str base-url "/watch-bootstrap"))
          (wait-for-sse))

        (w/with-page page2
          (w/navigate (str base-url "/watch-bootstrap"))
          (wait-for-sse))

        ;; Both tabs should show initial value
        (w/with-page page1
          (is (= "start" (w/text-content "#watch-value"))))
        (w/with-page page2
          (is (= "start" (w/text-content "#watch-value"))))

        ;; Mutate from server — both tabs should update
        (reset! watch-test-atom "shared-update")

        (w/with-page page1
          (wait-for-text "#watch-value" "shared-update"))
        (w/with-page page2
          (wait-for-text "#watch-value" "shared-update"))

        (finally
          (reset! watch-test-atom "initial")
          (close-browser! browser-info))))))

;; ---------------------------------------------------------------------------
;; Test: Reactive components
;; ---------------------------------------------------------------------------

(deftest ^:e2e reactive-component-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/reactive"))
        (wait-for-sse)

        (testing "Initial render shows all values"
          (is (= "00:00" (w/text-content "#clock-value")))
          (is (= "initial" (w/text-content "#static-value")))
          (is (= "0" (w/text-content "#counter-value")))
          (is (= "Count: 0" (w/text-content "#custom-reactive"))))

        (testing "Reactive component updates when dep changes"
          (reset! reactive-clock* "12:34")
          (wait-for-text "#clock-value" "12:34")
          ;; Static value should still be "initial" — not re-rendered
          (is (= "initial" (w/text-content "#static-value"))))

        (testing "Reactive component with user-provided ID works"
          (w/click "#inc-btn")
          (wait-for-text "#counter-value" "1")
          (wait-for-text "#custom-reactive" "Count: 1"))

        (testing "No wrapper div — reactive ID is on the element itself"
          ;; The clock span should have the reactive ID directly
          (let [clock-el-tag (.evaluate page "document.getElementById('clock-value')?.tagName")]
            (is (= "SPAN" clock-el-tag)
                "clock element should be a SPAN, not wrapped in a DIV"))
          ;; The custom-reactive element should still be a SPAN
          (let [custom-el-tag (.evaluate page "document.getElementById('custom-reactive')?.tagName")]
            (is (= "SPAN" custom-el-tag)
                "custom-reactive element should be a SPAN")))

        (testing "Multiple rapid updates are coalesced"
          (reset! reactive-clock* "tick-1")
          (reset! reactive-clock* "tick-2")
          (reset! reactive-clock* "tick-3")
          (wait-for-text "#clock-value" "tick-3")))

      (finally
        (reset! reactive-clock* "00:00")
        (close-browser! browser-info)))))

;; ---------------------------------------------------------------------------
;; Test: Effects — navigate!, set-cookie!, delete-cookie!, execute-script!
;; ---------------------------------------------------------------------------

(deftest ^:e2e effects-navigate-test
  (testing "navigate! from an action changes URL and renders the target page"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/effects"))
          (wait-for-sse)

          (is (= "Effects Test" (w/text-content "h1")))

          ;; Click the navigate button — should navigate to home
          (w/click "#nav-btn")

          ;; Wait for the home page content to appear via SSE re-render
          (wait-for-text "h1" "Test Home")

          ;; URL should have changed via pushState
          (let [url (current-url)]
            (is (str/ends-with? url "/")
                (str "Expected URL to end with /, got: " url))))

        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e effects-cookie-test
  (testing "set-cookie! and delete-cookie! manage HTTP cookies"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/effects"))
          (wait-for-sse)

          (testing "initial state shows no cookie"
            (is (= "no-cookie" (w/text-content "#cookie-display"))))

          (testing "set-cookie! sets an HTTP cookie"
            (w/click "#set-cookie-btn")
            (wait-for-text "#effect-status" "cookie-set")
            ;; Verify cookie was set via document.cookie — this is more
            ;; reliable than checking the server-rendered #cookie-display
            ;; because SSE re-renders (which lack HTTP cookies) can
            ;; overwrite it before we read it.
            (wait-for-cookie "test-effect-cookie" "hyper-test-value"))

          (testing "delete-cookie! removes the cookie"
            (w/click "#delete-cookie-btn")
            (wait-for-text "#effect-status" "cookie-deleted")
            ;; Verify cookie was removed via document.cookie
            (wait-for-cookie "test-effect-cookie" "" :absent? true)))

        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e effects-execute-script-test
  (testing "execute-script! runs JavaScript on the client"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/effects"))
          (wait-for-sse)

          (testing "initial script-result is pending"
            (is (= "pending" (w/text-content "#script-result"))))

          (testing "execute-script! runs JS that modifies the DOM"
            (w/click "#script-btn")
            (wait-for-text "#script-result" "executed")))

        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e optimistic-morph-survival-test
  (testing "a client-side optimistic write survives a server re-render morph,
            and commit! persists it"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)
          box-style    #(str (eval-js "document.getElementById('opt-box').getAttribute('style')"))]
      (try
        (w/with-page page
          (w/navigate (str base-url "/optimistic"))
          (wait-for-sse)

          (testing "a client-side write applies without server traffic"
            (w/click "#drag-btn")
            (is (wait-for-pred #(str/includes? (box-style) "555px"))
                (str "client-side signal write should style the box, saw " (box-style))))

          (testing "an unrelated server re-render (morph) does not clobber it"
            (w/click "#bump-btn")
            (wait-for-text "#server-n" "1")
            ;; The morph has landed (n=1 rendered); the signal-driven style
            ;; must still hold the client value, not the committed 100.
            (is (wait-for-pred #(str/includes? (box-style) "555px"))
                (str "the mid-gesture value must survive the morph, saw " (box-style))))

          (testing "commit! persists the client value to the session cursor"
            (w/click "#commit-btn")
            (is (wait-for-pred
                  #(some (fn [s] (= 555 (get-in s [:data :col-w])))
                         (vals (:sessions @@test-state*))))
                "the committed value lands in the backing session cursor")))

        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e expr-truthiness-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/expr-truthiness"))
        (wait-for-sse)

        (testing "signal value 1 runs the embedded action"
          (w/click "#row-bob")
          (w/click "#delete-btn")
          (is (wait-for-text "#deleted" "bob")))

        (testing "signal value 0 runs the embedded action"
          (w/click "#row-alice")
          (w/click "#delete-btn")
          (is (wait-for-text "#deleted" "alice"))))
      (finally
        (close-browser! browser-info)))))

(deftest ^:e2e squint-core-version-reload-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)
        tree-shake?  (:tree-shake? @@test-state*)]
    (swap! @test-state* assoc :tree-shake? true)
    (try
      (w/with-page page
        (w/navigate (str base-url "/counters"))
        (wait-for-sse)
        (wait-for-text "#counter-Session h2" "Session: 0")
        (eval-js "window.__sameDocument = true")
        (with-redefs-fn {#'hyper.server/squint-core-version (constantly "changed")}
          (fn []
            (click-counter-button "Session" ".inc")

            (testing "a changed data-hyper-squint-version reloads the page"
              (is (wait-for-pred #(nil? (eval-js "window.__sameDocument")) :timeout 10000)))

            (testing "the reloaded page imports the changed squint core version"
              (is (wait-for-pred #(= "changed" (eval-js "window.hyper_squint_version")) :timeout 10000))))))
      (finally
        (swap! @test-state* assoc :tree-shake? tree-shake?)
        (close-browser! browser-info)))))

(deftest ^:e2e expr-nested-test
  (let [browser-info (launch-browser)
        ctx          (new-context browser-info)
        page         (new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/expr-nested"))
        (wait-for-sse)

        (testing "when-let over or renders the second operand"
          (is (wait-for-text "#when-let" "7")))

        (testing "re-find of #\"a;b\" inside let renders the match"
          (is (wait-for-text "#re-find" "a;b"))))
      (finally
        (close-browser! browser-info)))))

(deftest ^:e2e effects-combined-test
  (testing "multiple effects in one action all apply"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)]
      (try
        (w/with-page page
          (w/navigate (str base-url "/effects"))
          (wait-for-sse)

          (testing "combo button sets cookie and runs script"
            (w/click "#combo-btn")
            ;; Script should execute
            (wait-for-text "#script-result" "combo-executed")
            ;; Cursor mutation should have happened
            (wait-for-text "#effect-status" "combo-done")
            (is (= "combo-done" (w/text-content "#effect-status")))
            ;; Verify cookie was set via document.cookie
            (wait-for-cookie "test-effect-cookie" "combo-value")))

        (finally
          (close-browser! browser-info))))))

;; ---------------------------------------------------------------------------
;; Test: a failed action request is carried by the next one
;; ---------------------------------------------------------------------------

(deftest ^:e2e failed-action-request-is-resent-test
  (testing "actions whose request failed are sent again with the next action
            and run on the server in click order"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)
          requests     (atom 0)]
      (try
        (.route page (java.util.regex.Pattern/compile ".*/hyper/actions\\?tab-id=.*")
                (reify java.util.function.Consumer
                  (accept [_ route]
                    (let [^com.microsoft.playwright.Route route route]
                      (case (int (swap! requests inc))
                        1 (.abort route)
                        2 (.fulfill route (doto (com.microsoft.playwright.Route$FulfillOptions.)
                                            (.setStatus 500)))
                        (.resume route))))))
        (w/with-page page
          (w/navigate (str base-url "/action-retry"))
          (wait-for-sse)

          (w/click "#click-a")
          (is (wait-for-pred #(= 1 @requests)) "the first request is aborted")
          (w/click "#click-b")
          (is (wait-for-pred #(= 2 @requests)) "the second request gets a 500")
          (w/click "#click-c")
          (is (wait-for-text "#clicks" "a,b,c"))
          (is (= ["a" "b" "c"]
                 (some :clicks (map :data (vals (:tabs @@test-state*)))))
              "the server ran every action once, in click order"))
        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e upload-test
  (testing "an upload runs its action with the posted file"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)
          urls         (atom [])
          file         (doto (java.io.File/createTempFile "avatar" ".txt")
                         (.deleteOnExit)
                         (spit "hello"))]
      (try
        (w/with-page page
          (.onRequest page (reify java.util.function.Consumer
                             (accept [_ request]
                               (let [url (.url ^com.microsoft.playwright.Request request)]
                                 (when (str/includes? url "/hyper/upload")
                                   (swap! urls conj url))))))
          (w/navigate (str base-url "/upload"))
          (wait-for-sse)
          (.setInputFiles (.locator page "#avatar") (.toPath file))
          (w/click "#upload-btn")
          (is (wait-for-text "#uploaded" (.getName file)))
          (is (re-find #"\?action-id=a_[^&]+&tab-id=[^&]+&v=\d+$" (first @urls))
              "the upload names its tab and the render it was made on"))
        (finally
          (close-browser! browser-info))))))

(deftest ^:e2e data-init-action-test
  (testing "an action in the initial HTML's data-init runs on page load"
    (let [browser-info (launch-browser)
          ctx          (new-context browser-info)
          page         (new-page ctx)
          errors       (atom [])]
      (try
        (.onConsoleMessage page (reify java.util.function.Consumer
                                  (accept [_ msg]
                                    (let [msg ^com.microsoft.playwright.ConsoleMessage msg]
                                      (when (= "error" (.type msg))
                                        (swap! errors conj (.text msg)))))))
        (w/with-page page
          (w/navigate (str base-url "/data-init"))
          (wait-for-sse)
          (is (wait-for-text "#inited" "yes"))
          (is (= ["yes"] (keep (comp :inited :data) (vals (:tabs @@test-state*))))
              "the action ran once on the server")
          (is (empty? @errors) (str "console errors: " @errors)))
        (finally
          (close-browser! browser-info))))))
