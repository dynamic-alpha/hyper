(ns hyper.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.actions :as actions]
            [hyper.context]
            [hyper.core :as hy]
            [hyper.render :as render]
            [hyper.state :as state]
            [hyper.watch :as watch]))

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

  (testing "render-tab returns render result with HTML strings"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-rt-2"
          tab-id     "test-tab-rt-2"
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
          tab-id     "test-tab-3"
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
          tab-id     "test-tab-ring"
          render-fn  (fn [_req] {:status 302 :headers {"Location" "/login"} :body ""})]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)
      (let [result (render/render-tab app-state* session-id tab-id)]
        (is (= 302 (:status result)))
        (is (= "/login" (get-in result [:headers "Location"]))))))

  (testing "Lazy sequences in hiccup see *request* bindings"
    (let [app-state* (atom (state/init-state))
          session-id "test-session-lazy"
          tab-id     "test-tab-lazy"
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
  (testing "safe-render catches errors and renders error fragment"
    (let [failing-render-fn (fn [_req] (throw (ex-info "Test error" {})))
          req               {:hyper/session-id "test-session"
                             :hyper/tab-id     "test-tab"}
          result            (render/safe-render failing-render-fn req)]
      ;; Should return hiccup, not throw
      (is (vector? result))
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

      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

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
