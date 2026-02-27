(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.core :as hy]
            [hyper.render :as render]
            [hyper.state :as state]))

(deftest test-render-fn-registration
  (testing "Render function registration and retrieval"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-2"
          tab-id     "test-tab-2"
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
      (is (.startsWith fragment "event: datastar-patch-elements\n")))))

(deftest test-render-tab
  (testing "render-tab returns nil when no render-fn is registered"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-rt-1"
          tab-id     "test-tab-rt-1"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (is (nil? (render/render-tab app-state* session-id tab-id)))))

  (testing "render-tab returns SSE payload string when render-fn is registered"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-rt-2"
          tab-id     "test-tab-rt-2"
          render-fn  (fn [_req] [:div "Hello World"])]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (string? result))
        ;; Should contain head update event
        (is (.contains result "document.title="))
        ;; Should contain body fragment
        (is (.contains result "event: datastar-patch-elements"))
        (is (.contains result "Hello World")))))

  (testing "Renders and formats content correctly"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-3"
          tab-id     "test-tab-3"
          render-fn  (fn [_req] [:div "Hello World"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Manually test formatting since we can't actually send with mock
      (let [hiccup-result (render-fn {})
            html-str      (c/html hiccup-result)
            fragment      (render/format-datastar-fragment html-str)]
        (is (.contains fragment "event: datastar-patch-elements"))
        (is (.contains fragment "Hello World"))))))

(deftest test-watchers
  (testing "Watchers trigger callback on state change"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-4"
          tab-id          "test-tab-4"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          render-fn       (fn [_req]
                            [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (render/setup-watchers! app-state* session-id tab-id trigger-render!)

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
      (render/remove-watchers! app-state* tab-id))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-5"
          tab-id          "test-tab-5"
          stopped?        (atom false)
          trigger-render! (fn [])
          render-fn       (fn [_req] [:div "test"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Store a mock renderer handle with a stop! fn
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!
              :stop!           #(reset! stopped? true)})

      (render/setup-watchers! app-state* session-id tab-id trigger-render!)

      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))

      ;; Cleanup
      (render/cleanup-tab! app-state* tab-id)

      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is @stopped? "Renderer stop! should have been called"))))

(deftest test-error-boundary
  (testing "safe-render catches errors and renders error fragment"
    (let [failing-render-fn (fn [_req] (throw (ex-info "Test error" {})))
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test-tab"}
          result            (render/safe-render failing-render-fn req)]
      ;; Should return HTML string or hiccup, not throw
      (is (or (string? result) (vector? result)))
      ;; Should contain error information
      (is (re-find #"Render Error" (str result)))))

  (testing "safe-render returns result when render succeeds"
    (let [working-render-fn (fn [_req] [:div [:h1 "Success"]])
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test-tab"}
          result            (render/safe-render working-render-fn req)]
      (is (= [:div [:h1 "Success"]] result)))))

(deftest test-mark-head-elements
  (testing "Single element gets marked"
    (is (= [:style {:data-hyper-head true} "body{}"]
           (render/mark-head-elements [:style "body{}"]))))

  (testing "Single element with existing attrs gets marked"
    (is (= [:link {:rel "stylesheet" :href "/a.css" :data-hyper-head true}]
           (render/mark-head-elements [:link {:rel "stylesheet" :href "/a.css"}]))))

  (testing "Sequence of elements all get marked"
    (is (= [[:style {:data-hyper-head true} "body{}"]
            [:link {:rel "stylesheet" :href "/b.css" :data-hyper-head true}]]
           (render/mark-head-elements
             [[:style "body{}"]
              [:link {:rel "stylesheet" :href "/b.css"}]]))))

  (testing "nil returns nil"
    (is (nil? (render/mark-head-elements nil)))))

(deftest test-head-update-format
  (testing "Head update sends a self-removing script event"
    (let [event (render/format-head-update "My Page" "<style data-hyper-head>body{}</style>")]
      ;; Should be a patch-elements event
      (is (.startsWith event "event: datastar-patch-elements\n"))
      ;; Should append to body
      (is (.contains event "data: mode append\n"))
      (is (.contains event "data: selector body\n"))
      ;; Should contain a self-removing script tag
      (is (.contains event "data: elements <script data-effect=\"el.remove()\">"))
      ;; Should set document.title
      (is (.contains event "document.title='My Page'"))
      ;; Should include head element swap logic
      (is (.contains event "[data-hyper-head]"))
      ;; Should end with double newline
      (is (.endsWith event "\n\n"))))
  (testing "Head update without extra head content only sets title"
    (let [event (render/format-head-update "Title Only" nil)]
      (is (.contains event "document.title='Title Only'"))
      ;; Should NOT contain head element removal/insertion JS
      (is (not (.contains event "[data-hyper-head]"))))))

(deftest test-actions-cleaned-between-renders
  (testing "Stale actions from a previous render are cleaned up when the next render produces fewer"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-actions"
          tab-id          "test-tab-actions"
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

      (render/setup-watchers! app-state* session-id tab-id trigger-render!)

      ;; Do an initial render with 3 items
      (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 3)
      ;; Render directly (watchers fire trigger-render! which in the real
      ;; system wakes the renderer thread, but here we call render-tab manually)
      (render/render-tab app-state* session-id tab-id)

      (let [tab-actions (fn []
                          (->> (:actions @app-state*)
                               (filter (fn [[_k v]] (= (:tab-id v) tab-id)))
                               count))]

        (is (= 3 (tab-actions)) "Should have 3 actions after first render")

        ;; Shrink to 1 item and re-render
        (swap! app-state* assoc-in [:tabs tab-id :data :item-count] 1)
        (render/render-tab app-state* session-id tab-id)

        (is (= 1 (tab-actions)) "Stale actions should be cleaned up, only 1 remaining"))

      (render/remove-watchers! app-state* tab-id))))

(deftest test-external-watch
  (testing "watch-source! triggers callback when source changes"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-ext"
          tab-id          "test-tab-ext"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (render/watch-source! app-state* tab-id trigger-render! external-atom)

      ;; Mutate the external atom
      (swap! external-atom inc)
      (Thread/sleep 50)

      (is (= 1 @trigger-count) "trigger-render! should have been called")

      ;; Clean up
      (render/remove-external-watches! app-state* tab-id))))
