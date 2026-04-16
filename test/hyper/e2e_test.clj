(ns ^:e2e hyper.e2e-test
  "End-to-end browser tests for Hyper using Playwright (via wally).

   Tests cursor isolation across sessions/tabs, Var-based live reload of
   titles, and Var-based live reload of inline route handlers.

   Run with: clojure -M:test --focus :e2e"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyper.core :as h]
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

;; Shared atom for testing watch! bootstrap — mutated from test code
;; to verify that server-side changes trigger SSE re-renders.
(def ^:private watch-test-atom (atom "initial"))

(defn- watch-bootstrap-get [_]
  (h/watch! watch-test-atom)
  [:div
   [:h1 "Watch Bootstrap"]
   [:span#watch-value @watch-test-atom]])

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
   ["/watch-bootstrap"
    {:name  :watch-bootstrap
     :title "Watch Bootstrap"
     :get   #'watch-bootstrap-get}]])

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
  (let [handler (h/create-handler #'*test-routes* :app-state @test-state* :head #'test-head-var)]
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

(defn wait-for-text
  "Poll until an element's text content matches expected, with timeout."
  [selector expected & {:keys [timeout] :or {timeout 5000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (let [actual (try (w/text-content selector) (catch Exception _ nil))]
        (if (= expected actual)
          true
          (if (> (System/currentTimeMillis) deadline)
            (do (is (= expected actual)
                    (str "Timed out waiting for " selector " to equal " (pr-str expected)
                         ", last saw " (pr-str actual)))
                false)
            (do (Thread/sleep 100)
                (recur))))))))

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

(use-fixtures :each
  (fn [f]
     ;; Reset routes and app state before each test, preserving
     ;; infrastructure keys (:routes-source, :head, etc.)
     ;; that create-handler stored in the app-state atom.
    (alter-var-root #'*test-routes* (constantly (default-routes)))
    (reset! watch-test-atom "initial")
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
