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
     :get   #'forms-get}]])

(def ^:dynamic *test-routes* (default-routes))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(def ^:private test-port 13020)
(def ^:private base-url (str "http://localhost:" test-port))
(def ^:private test-state* (atom nil))
(def ^:private test-server (atom nil))

(defn start-test-server! []
  (reset! test-state* (atom (state/init-state)))
  (let [handler (h/create-handler #'*test-routes* :app-state @test-state*)]
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
    ;; infrastructure keys (:routes-source, etc.)
    ;; that create-handler stored in the app-state atom.
    (alter-var-root #'*test-routes* (constantly (default-routes)))
    (when @test-state*
      (swap! @test-state*
             (fn [old-state]
               (merge (state/init-state)
                      (select-keys old-state
                                   [:routes-source
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
            (is (.contains result ":name"))
            (is (.contains result "Alice"))
            (is (.contains result ":email"))
            (is (.contains result "alice@example.com")))))

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
                 (w/text-content "#reloaded-marker")))))

      (finally
        (close-browser! browser-info)))))
