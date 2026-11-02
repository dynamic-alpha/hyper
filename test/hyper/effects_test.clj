(ns hyper.effects-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.render.queue :as rq]
            [hyper.state :as state]
            [hyper.test :as ht]
            [hyper.watch :as watch]))

;; ---------------------------------------------------------------------------
;; Unit tests — effects namespace internals
;; ---------------------------------------------------------------------------

(deftest test-init-pending
  (testing "init-pending returns an atom with empty cookies and scripts"
    (let [p (effects/init-pending)]
      (is (instance? clojure.lang.Atom p))
      (is (= {} (:cookies @p)))
      (is (= [] (:scripts @p))))))

(deftest test-emit-outside-context-throws
  (testing "set-cookie! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/set-cookie! "foo" "bar"))))

  (testing "delete-cookie! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/delete-cookie! "foo"))))

  (testing "execute-script! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/execute-script! "alert(1)"))))

  (testing "navigate! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/navigate! :home)))))

(deftest test-set-cookie-accumulation
  (testing "set-cookie! accumulates cookies in *pending*"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/set-cookie! "session" "abc123" {:http-only true :max-age 3600})
      (effects/set-cookie! "theme" "dark")
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "abc123" :path "/" :http-only true :max-age 3600}
                "theme"   {:value "dark" :path "/"}}
               (:cookies pending)))))))

(deftest test-delete-cookie-accumulation
  (testing "delete-cookie! sets value to empty string and max-age 0"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/delete-cookie! "session")
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "" :max-age 0 :path "/"}}
               (:cookies pending))))))

  (testing "delete-cookie! with opts merges them"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/delete-cookie! "session" {:path "/app"})
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "" :max-age 0 :path "/app"}}
               (:cookies pending)))))))

(deftest test-execute-script-accumulation
  (testing "execute-script! accumulates scripts in *pending*"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/execute-script! "alert('hello')")
      (effects/execute-script! "console.log('test')")
      (let [pending (effects/collect-pending!)]
        (is (= ["alert('hello')" "console.log('test')"]
               (:scripts pending)))))))

(deftest test-apply-cookies-to-response
  (testing "merges cookies into response"
    (let [response {:status 204}
          pending  {:cookies {"auth" {:value "token123" :http-only true}}
                    :scripts []}]
      (is (= {:status  204
              :cookies {"auth" {:value "token123" :http-only true}}}
             (effects/apply-cookies-to-response response pending)))))

  (testing "no-op when no cookies"
    (let [response {:status 204}
          pending  {:cookies {} :scripts []}]
      (is (= {:status 204}
             (effects/apply-cookies-to-response response pending))))))

(deftest test-format-execute-script-event
  (testing "formats as Datastar patch-elements SSE event with self-removing script"
    (let [event (effects/format-execute-script-event "alert('hi')")]
      (is (str/includes? event "event: datastar-patch-elements"))
      (is (str/includes? event "data: mode append"))
      (is (str/includes? event "data: selector body"))
      (is (str/includes? event "data: elements <script"))
      (is (str/includes? event "alert('hi')")))))

(deftest test-format-pending-scripts
  (testing "formats all scripts as SSE events"
    (let [pending {:cookies {} :scripts ["alert(1)" "alert(2)"]}
          result  (effects/format-pending-scripts pending)]
      (is (string? result))
      (is (str/includes? result "alert(1)"))
      (is (str/includes? result "alert(2)"))))

  (testing "returns nil when no scripts"
    (is (nil? (effects/format-pending-scripts {:cookies {} :scripts []})))))

;; ---------------------------------------------------------------------------
;; Integration tests — effects via test-page + test-action
;; ---------------------------------------------------------------------------

(deftest test-action-returns-effects
  (testing "test-action result includes :effects key"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "noop"})}
                      "Nothing"]))
          after  (ht/test-action result "noop")]
      (is (contains? after :effects))
      (is (= {} (:cookies (:effects after))))
      (is (= [] (:scripts (:effects after)))))))

(deftest test-set-cookie-from-action
  (testing "set-cookie! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "login"}
                                         (effects/set-cookie! "auth" "jwt-token-123"
                                                              {:http-only true
                                                               :max-age   86400}))}
                      "Login"]))
          after  (ht/test-action result "login")]
      (is (= {"auth" {:value     "jwt-token-123"
                      :path      "/"
                      :http-only true
                      :max-age   86400}}
             (get-in after [:effects :cookies]))))))

(deftest test-delete-cookie-from-action
  (testing "delete-cookie! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "logout"}
                                         (effects/delete-cookie! "auth"))}
                      "Logout"]))
          after  (ht/test-action result "logout")]
      (is (= {"auth" {:value "" :max-age 0 :path "/"}}
             (get-in after [:effects :cookies]))))))

(deftest test-execute-script-from-action
  (testing "execute-script! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "focus"}
                                         (effects/execute-script! "document.getElementById('q').focus()"))}
                      "Focus"]))
          after  (ht/test-action result "focus")]
      (is (= ["document.getElementById('q').focus()"]
             (get-in after [:effects :scripts]))))))

(deftest test-navigate-from-action
  (testing "navigate! in action updates route state and queues pushState script"
    (let [home-page (fn [_req]
                      [:div
                       [:button {:data-on:click
                                 (h/action {:as "go-about"}
                                           (effects/navigate! :about))}
                        "Go About"]])
          routes    [["/" {:name  :home
                           :title "Home"
                           :get   home-page}]
                     ["/about" {:name  :about
                                :title "About"
                                :get   (fn [_req] [:div "About page"])}]]
          ;; create-handler sets up the router in app-state
          app-state (atom (state/init-state))
          _handler  (h/create-handler routes :app-state app-state)
          ;; Render the home page — pass app-state so the router is available
          result    (ht/test-page home-page {:app-state app-state})
          after     (ht/test-action result "go-about")]
      ;; Route state should be updated server-side
      (is (= :about (get-in after [:cursors :route :name])))
      (is (= "/about" (get-in after [:cursors :route :path])))
      ;; Should have a pushState script queued
      (is (seq (get-in after [:effects :scripts])))
      (is (some #(str/includes? % "pushState") (get-in after [:effects :scripts]))))))

(deftest test-navigate-marks-navigation-before-render
  (testing "navigate! enqueues startNavigation ahead of the render its route change triggers"
    (let [routes     [["/" {:name :home :get (fn [_] [:div "Home"])}]
                      ["/about" {:name :about :get (fn [_] [:div "About"])}]]
          app-state* (atom (state/init-state))
          _handler   (h/create-handler routes :app-state app-state*)
          session-id "s-nav"
          tab-id     "tab-nav"
          q          (rq/make-queue)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:enqueue-scripts! #(rq/enqueue-scripts! q %)})
      (watch/setup-watchers! app-state* session-id tab-id #(rq/enqueue-full-render! q))
      (try
        (binding [context/*request* {:hyper/app-state  app-state*
                                     :hyper/session-id session-id
                                     :hyper/tab-id     tab-id}
                  effects/*pending* (effects/init-pending)]
          (effects/navigate! :about))
        (let [{:keys [full-render? scripts-before-render]} (rq/drain! q)]
          (is full-render?)
          (is (= ["window.hyper.startNavigation('/about')"] scripts-before-render)))
        (finally
          (watch/remove-watchers! app-state* tab-id))))))

(deftest test-multiple-effects-in-one-action
  (testing "multiple effects accumulate together"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "multi"}
                                         (effects/set-cookie! "token" "abc" {:http-only true})
                                         (effects/execute-script! "showNotification()")
                                         (effects/execute-script! "scrollToTop()"))}
                      "Multi"]))
          after  (ht/test-action result "multi")]
      (is (= {"token" {:value "abc" :path "/" :http-only true}}
             (get-in after [:effects :cookies])))
      (is (= ["showNotification()" "scrollToTop()"]
             (get-in after [:effects :scripts]))))))

(deftest test-effects-with-cursor-mutations
  (testing "effects and cursor mutations coexist"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [_status* (h/tab-cursor :status "idle")]
                       [:button {:data-on:click
                                 (h/action {:as "save"}
                                           (reset! (h/tab-cursor :status) "saved")
                                           (effects/set-cookie! "last-save" "2024-01-01")
                                           (effects/execute-script! "flashSuccess()"))}
                        "Save"])))
          after  (ht/test-action result "save")]
      ;; Cursor mutation works
      (is (= "saved" (get-in after [:cursors :tab :status])))
      ;; Effects also captured
      (is (= {"last-save" {:value "2024-01-01" :path "/"}}
             (get-in after [:effects :cookies])))
      (is (= ["flashSuccess()"]
             (get-in after [:effects :scripts]))))))

(deftest test-effects-isolated-between-actions
  (testing "effects from one test-action don't leak to the next"
    (let [result (ht/test-page
                   (fn [_req]
                     [:div
                      [:button {:data-on:click
                                (h/action {:as "with-effect"}
                                          (effects/execute-script! "doSomething()"))}
                       "A"]
                      [:button {:data-on:click
                                (h/action {:as "no-effect"})}
                       "B"]]))
          after1 (ht/test-action result "with-effect")
          after2 (ht/test-action result "no-effect")]
      (is (= ["doSomething()"] (get-in after1 [:effects :scripts])))
      (is (= [] (get-in after2 [:effects :scripts]))))))
