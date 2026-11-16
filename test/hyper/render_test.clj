(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context]
            [hyper.core :as hy]
            [hyper.render :as render]
            [hyper.render.error :as render.error]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [hyper.watch :as watch]
            [taoensso.telemere :as t]))

(deftest test-render-fn-registration
  (testing "Render function registration and retrieval"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-2"
          tab-id     "test_tab_2"
          render-fn  (fn [_req] [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Register render function
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Retrieve render function
      (is (= render-fn (render/get-render-fn app-state* tab-id))))))

(deftest test-datastar-fragment-format
  (testing "Datastar patch-elements formatting"
    (let [html     "<div><h1>Hello, Datastar!</h1></div>"
          fragment (render/format-datastar-fragment html)]
      ;; Should start with event type
      (is (.startsWith fragment "event: datastar-patch-elements\n"))
      ;; Should include data line with elements prefix
      (is (.contains fragment "data: elements "))
      ;; Should include html content
      (is (.contains fragment html))
      ;; Should end with double newline
      (is (.endsWith fragment "\n\n"))))

  (testing "Formats different HTML content"
    (let [html     "<span>test</span>"
          fragment (render/format-datastar-fragment html)]
      (is (.contains fragment html))
      (is (.startsWith fragment "event: datastar-patch-elements\n"))))

  (testing "HTML with 2 newlines emits multiple data lines"
    (let [html     "<pre>code\nwith\nnewline</pre>"
          fragment (render/format-datastar-fragment html)]
      (is (= 3 (count (re-seq #"data: elements" fragment))))
      (is (.contains fragment "<pre>code"))
      (is (.contains fragment "with"))
      (is (.contains fragment "newline</pre>"))
      (is (.endsWith fragment "\n\n"))))

  (testing "HTML with double newlines emits multiple data lines"
    (let [html     "<textarea>line1\n\nline2</textarea>"
          fragment (render/format-datastar-fragment html)]
      (is (= 3 (count (re-seq #"data: elements" fragment))))
      (is (.contains fragment "<textarea>line1"))
      (is (.contains fragment "line2</textarea>"))
      (is (.endsWith fragment "\n\n")))))

(deftest test-render-tab
  (testing "render-tab returns nil when no render-fn is registered"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-rt-1"
          tab-id     "test_tab_rt_1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (nil? (render/render-tab app-state* session-id tab-id)))))

  (testing "render-tab returns render result with HTML strings"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-rt-2"
          tab-id     "test_tab_rt_2"
          render-fn  (fn [_req] [:div "Hello World"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :body-html))
        (is (contains? result :head-html))
        (is (contains? result :url))
        (is (string? (:body-html result)))
        (is (.contains (:body-html result) "Hello World")))))

  (testing "Renders and formats content correctly"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id     "test_tab_3"
          render-fn  (fn [_req] [:div "Hello World"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (let [result   (render/render-tab app-state* session-id tab-id)
            fragment (render/format-datastar-fragment (:body-html result))]
        (is (.contains fragment "event: datastar-patch-elements"))
        (is (.contains fragment "Hello World")))))

  (testing "Ring response passthrough"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-ring"
          tab-id     "test_tab_ring"
          render-fn  (fn [_req] {:status 302 :headers {"Location" "/login"} :body ""})]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (= 302 (:status result)))
        (is (= "/login" (get-in result [:headers "Location"]))))))

  (testing "Lazy sequences in hiccup see *request* bindings"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-lazy"
          tab-id     "test_tab_lazy"
          ;; A render fn that returns lazy seqs which read *request*
          render-fn  (fn [_req]
                       [:ul
                        (for [i (range 3)]
                          [:li (str "item-" i "-"
                                    (:hyper/session-id hyper.context/*request*))])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; render-tab serializes to HTML internally, so lazy seqs from `for`
      ;; are realized while *request* bindings are still active.
      (let [{:keys [body-html]} (render/render-tab app-state* session-id tab-id)]
        (is (some? body-html))
        (is (.contains body-html (str "item-0-" session-id))
            "Lazy seq should see *request* bindings during serialization")
        (is (.contains body-html (str "item-2-" session-id))
            "All items in lazy seq should see *request* bindings")))))

(deftest test-error-boundary
  (testing "safe-render delegates to the supplied render-error-fn"
    (let [failing-render-fn (fn [_req] (throw (ex-info "Test error" {})))
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test_tab"}]
      (testing "with minimal renderer (production-safe)"
        (let [result (render/safe-render failing-render-fn req render.error/minimal)]
          (is (vector? result))
          (is (re-find #"Something went wrong" (str result)))
          ;; Minimal must NOT leak the exception message
          (is (not (re-find #"Test error" (str result))))))

      (testing "with explain renderer (dev-only)"
        (let [result (render/safe-render failing-render-fn req render.error/explain)]
          (is (vector? result))
          (is (re-find #"Render Error" (str result)))
          ;; Explain includes message and stack trace
          (is (re-find #"Test error" (str result)))
          (is (re-find #"at .+\(" (str result))
              "Should contain stack-trace frames")))

      (testing "with a custom renderer"
        (let [custom-fn (fn [e _req] [:div.custom-error (ex-message e)])
              result    (render/safe-render failing-render-fn req custom-fn)]
          (is (= [:div.custom-error "Test error"] result))))

      (testing "with a Var pointing at a renderer (REPL redefinition support)"
        (let [result (render/safe-render failing-render-fn req #'render.error/minimal)]
          (is (vector? result))
          (is (re-find #"Something went wrong" (str result)))))))

  (testing "safe-render returns result when render succeeds"
    (let [working-render-fn (fn [_req] [:div [:h1 "Success"]])
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test_tab"}
          result            (render/safe-render working-render-fn req render.error/minimal)]
      (is (= [:div [:h1 "Success"]] result)))))

(deftest test-fingerprint
  (testing "Returns a hex string"
    (let [fp (render/fingerprint "hello")]
      (is (string? fp))
      (is (re-matches #"[0-9a-f]+" fp))))

  (testing "Same input produces same fingerprint"
    (is (= (render/fingerprint "body{}")
           (render/fingerprint "body{}"))))

  (testing "Different inputs produce different fingerprints"
    (is (not= (render/fingerprint "body{}")
              (render/fingerprint "div{}"))))

  (testing "Works with hiccup data structures"
    (let [fp (render/fingerprint [:style {} "body{}"])]
      (is (string? fp))
      (is (re-matches #"[0-9a-f]+" fp)))))

(deftest test-mark-head-elements
  (testing "Single element gets a fingerprint string"
    (let [result (render/mark-head-elements [:style "body{}"])]
      (is (= :style (first result)))
      (is (string? (:data-hyper-head (second result))))
      (is (re-matches #"[0-9a-f]+" (:data-hyper-head (second result))))
      (is (= "body{}" (nth result 2)))))

  (testing "Single element with existing attrs gets fingerprint"
    (let [result (render/mark-head-elements [:link {:rel "stylesheet" :href "/a.css"}])]
      (is (= :link (first result)))
      (is (= "stylesheet" (:rel (second result))))
      (is (= "/a.css" (:href (second result))))
      (is (string? (:data-hyper-head (second result))))))

  (testing "Sequence of elements all get fingerprints"
    (let [result (render/mark-head-elements
                   [[:style "body{}"]
                    [:link {:rel "stylesheet" :href "/b.css"}]])]
      (is (= 2 (count result)))
      (is (string? (:data-hyper-head (second (first result)))))
      (is (string? (:data-hyper-head (second (second result)))))))

  (testing "Fingerprints are stable — same input always same fingerprint"
    (let [result1 (render/mark-head-elements [:style "body{}"])
          result2 (render/mark-head-elements [:style "body{}"])]
      (is (= (:data-hyper-head (second result1))
             (:data-hyper-head (second result2))))))

  (testing "Different elements get different fingerprints"
    (let [result (render/mark-head-elements
                   [[:style "body{}"]
                    [:style "div{}"]])]
      (is (not= (:data-hyper-head (second (first result)))
                (:data-hyper-head (second (second result)))))))

  (testing "nil returns nil"
    (is (nil? (render/mark-head-elements nil)))))

(deftest test-head-update-format
  (testing "Head update sends a self-removing script event with per-element diffing"
    (let [event (render/format-head-update "My Page" "<style data-hyper-head=\"abc123\">body{}</style>")]
      ;; Should be a patch-elements event
      (is (.startsWith event "event: datastar-patch-elements\n"))
      ;; Should append to body
      (is (.contains event "data: mode append\n"))
      (is (.contains event "data: selector body\n"))
      ;; Should contain a self-removing script tag
      (is (.contains event "data: elements <script data-effect=\"el.remove()\">"))
      ;; Should set document.title
      (is (.contains event "document.title='My Page'"))
      ;; Should include fingerprint-based diffing logic
      (is (.contains event "data-hyper-head"))
      (is (.contains event "newFps") "Should build a map of new fingerprints")
      (is (.contains event "el.remove()") "Should remove stale elements")
      (is (.contains event "appendChild") "Should append new elements")
      ;; Should end with double newline
      (is (.endsWith event "\n\n"))))

  (testing "Head update without extra head content only sets title"
    (let [event (render/format-head-update "Title Only" nil)]
      (is (.contains event "document.title='Title Only'"))
      ;; Should NOT contain head element diffing JS
      (is (not (.contains event "newFps")))))

  (testing "Head update JS correctly handles multiple elements"
    (let [;; Simulate two head elements with different fingerprints
          html  (str "<style data-hyper-head=\"aaa\">body{}</style>"
                     "<link data-hyper-head=\"bbb\" rel=\"stylesheet\" href=\"/app.css\">")
          event (render/format-head-update "Multi" html)]
      ;; Should contain the HTML with both fingerprints
      (is (.contains event "aaa"))
      (is (.contains event "bbb")))))

(deftest test-lazy-hiccup-actions-in-registered-ids
  (testing "Actions inside lazy sequences (for/map) are included in registered-action-ids"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-lazy-actions"
          tab-id     "test_tab_lazy_actions"
          ;; Render fn that produces actions inside a lazy `for`
          render-fn  (fn [_req]
                       [:div
                        (for [i (range 3)]
                          [:button {:data-on:click (hy/action (println "clicked" i))}
                           (str "Button " i)])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (= 3 (count (:registered-action-ids result)))
            "All 3 actions from the lazy `for` should appear in registered-action-ids")

        ;; Simulate what the server renderer loop does after render:
        ;; sweep stale actions using the returned live set.
        (actions/sweep-stale-tab-actions! app-state* tab-id (:registered-action-ids result))

        (is (= 3 (count (get-in @app-state* [:actions-by-tab tab-id])))
            "All 3 actions should survive the sweep")

        ;; Verify each action is still executable
        (doseq [action-id (:registered-action-ids result)]
          (is (some? (get-in @app-state* [:actions action-id]))
              (str "Action " action-id " should still exist after sweep")))))))

(deftest test-unwrap-body
  (testing "Strips [:body] wrapper with single child"
    (is (= [:div "Hello"]
           (render/unwrap-body [:body [:div "Hello"]]))))

  (testing "Strips [:body] wrapper with attrs — discards attrs, returns single child"
    (is (= [:div "Hello"]
           (render/unwrap-body [:body {:class "wrapper"} [:div "Hello"]]))))

  (testing "Strips [:body] wrapper with multiple children — returns vector of children"
    (is (= [[:h1 "Title"] [:p "Content"]]
           (render/unwrap-body [:body [:h1 "Title"] [:p "Content"]]))))

  (testing "Strips [:body] wrapper with attrs and multiple children — discards attrs"
    (is (= [[:h1 "Title"] [:p "Content"]]
           (render/unwrap-body [:body {:class "app"} [:h1 "Title"] [:p "Content"]]))))

  (testing "Passes through non-body hiccup unchanged"
    (is (= [:div "Hello"]
           (render/unwrap-body [:div "Hello"]))))

  (testing "Passes through nil unchanged"
    (is (nil? (render/unwrap-body nil))))

  (testing "Passes through non-vector unchanged"
    (is (= "string" (render/unwrap-body "string")))))

(deftest test-render-tab-strips-body-tag
  (testing "render-tab strips [:body] wrapper from render function output (issue #40)"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-body"
          tab-id     "test_tab_body"
          render-fn  (fn [_req] [:body [:div [:h1 "Count: 0"]]])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (string? (:body-html result)))
        ;; Should NOT contain a <body> tag
        (is (not (.contains (:body-html result) "<body")))
        ;; Should still contain the inner content
        (is (.contains (:body-html result) "Count: 0"))))))

(deftest test-actions-cleaned-between-renders
  (testing "Stale actions from a previous render are cleaned up when the next render produces fewer"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-actions"
          tab-id          "test_tab_actions"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          ;; Render fn whose action count depends on state
          render-fn       (fn [_req]
                            (let [n (or (get-in @app-state* [:tabs tab-id :data :item-count]) 3)]
                              (into [:div]
                                    (for [i (range n)]
                                      [:button {:data-on:click (hy/action #(println "action" i))}
                                       (str "Button " i)]))))]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (watch/setup-watchers! app-state* tab-id trigger-render!)

      ;; Do an initial render with 3 items
      (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 3)
      ;; Simulate what the renderer loop does: cleanup actions then render
      (actions/cleanup-tab-actions! app-state* tab-id)
      (render/render-tab app-state* session-id tab-id)

      (let [tab-actions (fn []
                          (->> (:actions @app-state*)
                               (filter (fn [[_k v]] (= (:tab-id v) tab-id)))
                               count))]

        (is (= 3 (tab-actions)) "Should have 3 actions after first render")

        ;; Shrink to 1 item and re-render
        (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 1)
        (actions/cleanup-tab-actions! app-state* tab-id)
        (render/render-tab app-state* session-id tab-id)

        (is (= 1 (tab-actions)) "Stale actions should be cleaned up, only 1 remaining"))

      (watch/remove-watchers! app-state* tab-id))))

(deftest test-warn-on-access-map
  (testing "Present keys work normally"
    (let [m (utils/warn-on-access-map {:hyper/session-id "s1" :hyper/tab-id "t1"})]
      (is (= "s1" (:hyper/session-id m)))
      (is (= "t1" (get m :hyper/tab-id)))
      (is (= "s1" (m :hyper/session-id)))))

  (testing "Absent keys return nil"
    (let [m (utils/warn-on-access-map {:hyper/session-id "s1"})]
      (is (nil? (:cookies m)))
      (is (nil? (:anything-else m)))))

  (testing "Accessing absent keys logs a warning"
    (let [m (utils/warn-on-access-map {:hyper/session-id "s1"})]
      (let [{:keys [signals]} (t/with-signals (:cookies m))]
        (is (= 1 (count (filter #(= :hyper.warn/http-key-in-render (:id %)) signals)))
            "Absent key access should log a warning"))
      (let [{:keys [signals]} (t/with-signals (:headers m))]
        (is (= 1 (count (filter #(= :hyper.warn/http-key-in-render (:id %)) signals)))
            "Different absent key should also log"))))

  (testing "Accessing present keys does NOT log a warning"
    (let [m                 (utils/warn-on-access-map {:hyper/session-id "s1"})
          {:keys [signals]} (t/with-signals (:hyper/session-id m))]
      (is (= 0 (count (filter #(= :hyper.warn/http-key-in-render (:id %)) signals)))
          "Present keys should not log")))

  (testing "get with not-found returns not-found for absent keys"
    (let [m (utils/warn-on-access-map {})]
      (is (= :default (get m :cookies :default)))))

  (testing "assoc produces a new WarnOnAccessMap"
    (let [m  (utils/warn-on-access-map {:hyper/session-id "s1"})
          m2 (assoc m :hyper/router :my-router)]
      (is (= :my-router (:hyper/router m2)))
      (is (= "s1" (:hyper/session-id m2)))
      (is (instance? hyper.utils.WarnOnAccessMap m2))))

  (testing "dissoc produces a new WarnOnAccessMap"
    (let [m  (utils/warn-on-access-map {:a 1 :b 2})
          m2 (dissoc m :a)]
      (is (= 2 (:b m2)))
      (is (instance? hyper.utils.WarnOnAccessMap m2))))

  (testing "count and seq work"
    (let [m (utils/warn-on-access-map {:a 1 :b 2})]
      (is (= 2 (count m)))
      (is (= #{[:a 1] [:b 2]} (set (seq m))))))

  (testing "contains? only reflects actual keys"
    (let [m (utils/warn-on-access-map {:hyper/session-id "s1"})]
      (is (true? (contains? m :hyper/session-id)))
      (is (false? (contains? m :cookies)))))

  (testing "prints like a regular map"
    (let [m (utils/warn-on-access-map {:a 1 :b 2})]
      (is (= (pr-str {:a 1 :b 2}) (pr-str m)))))

  (testing "SSE re-render uses warn-on-access map"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-warn"
          tab-id     "test_tab_warn"
          captured   (atom nil)
          render-fn  (fn [req]
                       (reset! captured req)
                       [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      ;; SSE re-render — no base-req
      (render/render-tab app-state* session-id tab-id)
      (is (instance? hyper.utils.WarnOnAccessMap @captured)
          "SSE re-render request should be a WarnOnAccessMap")))

  (testing "Initial HTTP render uses a regular map"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-http"
          tab-id     "test_tab_http"
          captured   (atom nil)
          render-fn  (fn [req]
                       (reset! captured req)
                       [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      ;; HTTP page load — with base-req
      (render/render-tab app-state* session-id tab-id {:uri "/" :request-method :get})
      (is (not (instance? hyper.utils.WarnOnAccessMap @captured))
          "HTTP page load request should be a regular map"))))

(deftest test-env-does-not-warn-on-re-render
  (testing "Reading :hyper/env on an SSE re-render never trips the warn map"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-env-warn"
          tab-id     "test_tab_env_warn"
          captured   (atom nil)
          render-fn  (fn [_req]
                       (reset! captured (hy/env))
                       [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      ;; SSE re-render — no base-req and no stashed env
      (let [{:keys [signals]} (t/with-signals
                                (render/render-tab app-state* session-id tab-id))]
        (is (= 0 (count (filter #(= :hyper.warn/http-key-in-render (:id %)) signals)))
            ":hyper/env is framework-managed and must not warn on re-render")
        (is (nil? @captured)
            "Unset env reads as nil, matching initial page load"))))

  (testing "Stashed env is visible on SSE re-render"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-env-stash"
          tab-id     "test_tab_env_stash"
          captured   (atom nil)
          render-fn  (fn [_req]
                       (reset! captured (hy/env))
                       [:div "test"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (swap! app-state* assoc-in [:tabs tab-id :env] {:db :test-db})
      ;; SSE re-render — no base-req, env stashed per-tab
      (let [{:keys [signals]} (t/with-signals
                                (render/render-tab app-state* session-id tab-id))]
        (is (= 0 (count (filter #(= :hyper.warn/http-key-in-render (:id %)) signals)))
            "Reading stashed env must not warn")
        (is (= {:db :test-db} @captured)
            "Stashed tab env should be propagated into the re-render request")))))

(deftest test-format-heartbeat
  (testing "heartbeat is an SSE comment line (ignored by conformant parsers)"
    (let [hb (render/format-heartbeat)]
      (is (string? hb))
      (is (.startsWith hb ":") "SSE comment lines start with ':'")
      (is (.endsWith hb "\n\n") "SSE messages end with a blank line"))))
