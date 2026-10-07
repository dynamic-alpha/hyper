(ns hyper.test-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.routes :as routes]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.test :as ht]
            [malli.transform :as mt]
            [reitit.coercion.malli :as reitit.malli]
            [reitit.core :as reitit]
            [reitit.ring :as ring]))

;; ---------------------------------------------------------------------------
;; test-page
;; ---------------------------------------------------------------------------

(deftest test-page-returns-expected-shape
  (testing "result contains all expected keys"
    (let [result (ht/test-page (fn [_req] [:div "Hello"]))]
      (is (contains? result :body))
      (is (contains? result :body-html))
      (is (contains? result :title))
      (is (contains? result :url))
      (is (contains? result :signals))
      (is (contains? result :actions))
      (is (contains? result :cursors))
      (is (contains? result :watches))
      (is (contains? result :app-state)))))

(deftest test-page-body-and-body-html
  (testing ":body is raw hiccup"
    (let [result (ht/test-page (fn [_req] [:div [:h1 "Hello"]]))]
      (is (vector? (:body result)))
      (is (= :div (first (:body result))))))

  (testing ":body-html is a serialized HTML string"
    (let [result (ht/test-page (fn [_req] [:div [:h1 "Hello"]]))]
      (is (string? (:body-html result)))
      (is (str/includes? (:body-html result) "<div>"))
      (is (str/includes? (:body-html result) "<h1>Hello</h1>")))))

(deftest test-page-url
  (testing "default route produces \"/\""
    (let [result (ht/test-page (fn [_req] [:div]))]
      (is (= "/" (:url result)))))

  (testing "custom route with query params"
    (let [result (ht/test-page (fn [_req] [:div])
                               {:route {:name         :search
                                        :path         "/search"
                                        :path-params  {}
                                        :query-params {:q "clojure"}}})]
      (is (= "/search?q=clojure" (:url result))))))

(deftest test-page-cursors
  (testing "tab-cursor values appear in :cursors :tab"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div (str @count*)])))]
      (is (= 0 (get-in result [:cursors :tab :count])))))

  (testing "session-cursor values appear in :cursors :session"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [user* (h/session-cursor :user "alice")]
                       [:div (str @user*)])))]
      (is (= "alice" (get-in result [:cursors :session :user])))))

  (testing "global-cursor values appear in :cursors :global"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [theme* (h/global-cursor :theme "light")]
                       [:div (str @theme*)])))]
      (is (= "light" (get-in result [:cursors :global :theme])))))

  (testing ":cursors :route reflects the route info"
    (let [route  {:name :home :path "/" :path-params {} :query-params {}}
          result (ht/test-page (fn [_req] [:div]) {:route route})]
      (is (= route (get-in result [:cursors :route]))))))

(deftest test-page-cursors-option
  (testing "seed tab cursor state"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div (str @count*)]))
                   {:cursors {:tab {:count 42}}})]
      (is (= 42 (get-in result [:cursors :tab :count])))
      (is (str/includes? (:body-html result) "42"))))

  (testing "seed session cursor state"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [user* (h/session-cursor :user)]
                       [:div (str @user*)]))
                   {:cursors {:session {:user "alice"}}})]
      (is (= "alice" (get-in result [:cursors :session :user])))
      (is (str/includes? (:body-html result) "alice"))))

  (testing "seed global cursor state"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [theme* (h/global-cursor :theme)]
                       [:div (str @theme*)]))
                   {:cursors {:global {:theme "dark"}}})]
      (is (= "dark" (get-in result [:cursors :global :theme])))
      (is (str/includes? (:body-html result) "dark"))))

  (testing "seed multiple scopes at once"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)
                           user*  (h/session-cursor :user)
                           theme* (h/global-cursor :theme)]
                       [:div (str @theme* " " @user* " " @count*)]))
                   {:cursors {:tab     {:count 10}
                              :session {:user "bob"}
                              :global  {:theme "light"}}})]
      (is (= 10 (get-in result [:cursors :tab :count])))
      (is (= "bob" (get-in result [:cursors :session :user])))
      (is (= "light" (get-in result [:cursors :global :theme])))))

  (testing "seeded values are not overwritten by cursor defaults"
    (let [result (ht/test-page
                   (fn [_req]
                     ;; default is 0, but we seeded 99
                     (let [count* (h/tab-cursor :count 0)]
                       [:div (str @count*)]))
                   {:cursors {:tab {:count 99}}})]
      (is (= 99 (get-in result [:cursors :tab :count]))))))

(deftest test-page-actions-with-as
  (testing "actions with :as are keyed by their name"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "increment"}
                                                        (swap! (h/tab-cursor :count 0) inc))}
                      "Inc"]))]
      (is (contains? (:actions result) "increment"))
      (is (fn? (get-in result [:actions "increment" :fn]))))))

(deftest test-page-actions-without-as
  (testing "actions without :as are keyed by auto-generated action-id"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action
                                                (swap! (h/tab-cursor :count 0) inc))}
                      "Inc"]))]
      (is (= 1 (count (:actions result))))
      (let [[k v] (first (:actions result))]
        (is (string? k))
        (is (str/starts-with? k "a_"))
        (is (fn? (:fn v)))))))

(deftest test-page-signals
  (testing "declared signals appear in :signals keyed by path"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [name* (h/signal :user-name "")]
                       [:input {:data-bind name*}])))]
      (is (= 1 (count (:signals result))))
      (is (contains? (:signals result) :user-name))
      (is (= "" (get-in result [:signals :user-name :default-val])))
      (is (false? (get-in result [:signals :user-name :local?])))))

  (testing "local signals keyed by path and marked as local"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [open?* (h/local-signal :open false)]
                       [:div {:data-show @open?*} "Content"])))]
      (is (= 1 (count (:signals result))))
      (is (contains? (:signals result) :open))
      (is (true? (get-in result [:signals :open :local?])))))

  (testing "vector path signals keyed by vector"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [name* (h/signal [:user :name] "")]
                       [:input {:data-bind name*}])))]
      (is (contains? (:signals result) [:user :name]))
      (is (= "" (get-in result [:signals [:user :name] :default-val]))))))

(deftest test-page-watches
  (testing "watched atoms appear in :watches"
    (let [external (atom [])
          result   (ht/test-page
                     (fn [_req]
                       (h/watch! external)
                       [:div "watching"]))]
      (is (= 1 (count (:watches result))))
      (is (identical? external (first (:watches result)))))))

(deftest test-page-ring-response-passthrough
  (testing "Ring response map is returned as-is"
    (let [result (ht/test-page
                   (fn [_req]
                     {:status 302 :headers {"Location" "/login"} :body ""}))]
      (is (= 302 (:status result)))
      (is (= "/login" (get-in result [:headers "Location"]))))))

(deftest test-page-app-state-threading
  (testing "passing :app-state preserves state across renders"
    (let [r1 (ht/test-page
               (fn [_req]
                 (let [c* (h/tab-cursor :count 0)]
                   (swap! c* inc)
                   [:div (str @c*)])))
          r2 (ht/test-page
               (fn [_req]
                 (let [c* (h/tab-cursor :count 0)]
                   [:div (str @c*)]))
               {:app-state (:app-state r1)})]
      (is (= 1 (get-in r1 [:cursors :tab :count])))
      (is (= 1 (get-in r2 [:cursors :tab :count])))
      (is (str/includes? (:body-html r2) "1")))))

;; ---------------------------------------------------------------------------
;; test-action
;; ---------------------------------------------------------------------------

(deftest test-action-basic
  (testing "executes action and returns updated cursors"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "increment"}
                                                        (swap! (h/tab-cursor :count 0) inc))}
                      "Inc"]))
          after  (ht/test-action result "increment")]
      (is (= 1 (get-in after [:cursors :tab :count])))
      (is (some? (:app-state after))))))

(deftest test-action-multiple-invocations
  (testing "calling test-action multiple times accumulates state"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "inc"}
                                                        (swap! (h/tab-cursor :n 0) inc))}
                      "+1"]))
          a1     (ht/test-action result "inc")
          a2     (ht/test-action result "inc")
          a3     (ht/test-action result "inc")]
      (is (= 1 (get-in a1 [:cursors :tab :n])))
      (is (= 2 (get-in a2 [:cursors :tab :n])))
      (is (= 3 (get-in a3 [:cursors :tab :n]))))))

(deftest test-action-with-client-params
  (testing "$value client param is passed through"
    (let [result (ht/test-page
                   (fn [_req]
                     [:input {:data-on:change (h/action {:as "search"}
                                                        (reset! (h/tab-cursor :query) $value))}]))
          after  (ht/test-action result "search" {:value "clojure"})]
      (is (= "clojure" (get-in after [:cursors :tab :query]))))))

(deftest test-action-modifies-global-cursors
  (testing "action can modify global state"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "set-theme"}
                                                        (reset! (h/global-cursor :theme) "dark"))}
                      "Dark"]))
          after  (ht/test-action result "set-theme")]
      (is (= "dark" (get-in after [:cursors :global :theme]))))))

(deftest test-action-modifies-session-cursors
  (testing "action can modify session state"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "login"}
                                                        (reset! (h/session-cursor :user) "alice"))}
                      "Login"]))
          after  (ht/test-action result "login")]
      (is (= "alice" (get-in after [:cursors :session :user]))))))

(deftest test-action-not-found
  (testing "throws with helpful message for unknown action"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "increment"}
                                                        (swap! (h/tab-cursor :count 0) inc))}
                      "Inc"]))]
      (is (thrown-with-msg? Exception #"Action not found: \"decrement\""
                            (ht/test-action result "decrement"))))))

;; ---------------------------------------------------------------------------
;; Full workflow: render → act → re-render
;; ---------------------------------------------------------------------------

(deftest test-full-workflow
  (testing "render → action → re-render shows state progression"
    (let [page-fn (fn [_req]
                    (let [count* (h/tab-cursor :count 0)]
                      [:div
                       [:h1 "Count: " @count*]
                       [:button {:data-on:click (h/action {:as "inc"}
                                                          (swap! (h/tab-cursor :count) inc))}
                        "+1"]
                       [:button {:data-on:click (h/action {:as "dec"}
                                                          (swap! (h/tab-cursor :count) dec))}
                        "-1"]]))

          ;; Initial render
          r1      (ht/test-page page-fn)]

      (is (str/includes? (:body-html r1) "Count: 0"))
      (is (= 0 (get-in r1 [:cursors :tab :count])))
      (is (contains? (:actions r1) "inc"))
      (is (contains? (:actions r1) "dec"))

      ;; Increment twice
      (ht/test-action r1 "inc")
      (let [after (ht/test-action r1 "inc")]
        (is (= 2 (get-in after [:cursors :tab :count]))))

      ;; Re-render with same app-state
      (let [r2 (ht/test-page page-fn {:app-state (:app-state r1)})]
        (is (str/includes? (:body-html r2) "Count: 2"))
        (is (= 2 (get-in r2 [:cursors :tab :count])))

        ;; Decrement once
        (ht/test-action r2 "dec")
        (let [r3 (ht/test-page page-fn {:app-state (:app-state r2)})]
          (is (str/includes? (:body-html r3) "Count: 1")))))))

;; ---------------------------------------------------------------------------
;; Router injection — h/navigate (render) and effects/navigate! (action)
;; ---------------------------------------------------------------------------

(def ^:private nav-routes
  [["/"          {:name :home :title "Home" :get (fn [_req] [:div "Home"])}]
   ["/about"     {:name :about :title "About" :get (fn [_req] [:div "About"])}]
   ["/users/:id" {:name :user-profile :get (fn [_req] [:div "User"])}]])

(deftest test-page-navigate-renders-link
  (testing "h/navigate inside test-page produces a usable link in the HTML"
    (let [result (ht/test-page
                   (fn [_req] [:a (h/navigate :about) "About"])
                   {:routes nav-routes})]
      (is (str/includes? (:body-html result) "href=\"/about\""))
      (is (str/includes? (:body-html result) "/hyper/actions"))
      (is (str/includes? (:body-html result) "pushState")))))

(deftest test-page-navigate-resolves-named-routes
  (testing "h/navigate resolves a bound router injected via :routes"
    (let [captured (atom nil)]
      (ht/test-page
        (fn [_req]
          (reset! captured (h/navigate :user-profile {:id "123"}))
          [:div])
        {:routes nav-routes})
      (is (= "/users/123" (:href @captured)))
      (is (str/includes? (str (:data-on:click__prevent @captured)) "pushState")))))

(deftest test-page-navigate-query-params
  (testing "h/navigate carries query params into the href"
    (let [captured (atom nil)]
      (ht/test-page
        (fn [_req]
          (reset! captured (h/navigate :home nil {:q "clojure"}))
          [:div])
        {:routes nav-routes})
      (is (= "/?q=clojure" (:href @captured))))))

(deftest test-page-navigate-unknown-route
  (testing "h/navigate returns nil for an unknown route name"
    (let [captured (atom :unset)]
      (ht/test-page
        (fn [_req]
          (reset! captured (h/navigate :nonexistent))
          [:div])
        {:routes nav-routes})
      (is (nil? @captured)))))

(deftest test-page-navigate-prebuilt-router
  (testing ":router opt accepts a pre-built reitit router"
    (let [router   (reitit/router nav-routes)
          captured (atom nil)]
      (ht/test-page
        (fn [_req]
          (reset! captured (h/navigate :about))
          [:div])
        {:router router})
      (is (= "/about" (:href @captured))))))

(deftest test-page-navigate-router-persists-across-renders
  (testing "router persists when threading :app-state without re-passing :routes"
    (let [r1       (ht/test-page (fn [_req] [:div]) {:routes nav-routes})
          captured (atom nil)]
      ;; Second render reuses app-state from r1 — note: no :routes here.
      (ht/test-page
        (fn [_req]
          (reset! captured (h/navigate :about))
          [:div])
        {:app-state (:app-state r1)})
      (is (= "/about" (:href @captured))))))

(deftest test-page-no-router-by-default
  (testing "without :routes/:router, the request carries no router (backward compatible)"
    (let [result (ht/test-page (fn [_req] [:div "Hi"]))]
      (is (nil? (get @(:app-state result) :router))))))

(def ^:private typed-routes
  [["/items/:id" {:name       :items
                  :parameters {:path  [:map [:id :int]]
                               :query [:map [:since inst?] [:tags [:vector :keyword]]]}
                  :get        (fn [_] [:p])}]])

(def ^:private typed-query
  {:since #inst "2026-10-07T00:00:00.000-00:00" :tags [:a :b]})

(defn- -production-url
  "Returns the URL production builds for :items with typed-query."
  [opts]
  (let [app-state* (atom (state/init-state))]
    (server/create-handler typed-routes app-state* opts)
    (let [router (:router @app-state*)]
      (routes/route-url router (routes/tab-route router :items {:id 1} typed-query)))))

(defn- -test-page-href
  "Returns the href of h/navigate to :items with typed-query inside test-page."
  [opts]
  (let [captured (atom nil)]
    (ht/test-page (fn [_req] (reset! captured (h/navigate :items {:id 1} typed-query)) [:div])
                  opts)
    (:href @captured)))

(deftest test-page-navigate-encodes-like-production
  (let [expected (-production-url {})]
    (is (= "/items/1?since=2026-10-07T00%3A00%3A00.000Z&tags=a&tags=b" expected))

    (testing "with :routes"
      (is (= expected (-test-page-href {:routes typed-routes}))))

    (testing "a pre-built router without coercion builds URLs from raw params"
      (is (= (state/build-url "/items/1" typed-query)
             (-test-page-href {:router (reitit/router typed-routes)}))))

    (testing "a pre-built Ring router encodes with its own coercion"
      (is (= expected (-test-page-href {:router (ring/router typed-routes
                                                             {:data {:coercion reitit.malli/coercion}})}))))

    (testing "with a custom :param-transformer"
      (let [csv  (mt/transformer
                   {:encoders {:vector {:leave (fn [v] (if (sequential? v) (str/join "," v) v))}}})
            opts {:param-transformer (mt/transformer csv h/default-malli-transformer)}]
        (is (= (-production-url opts)
               (-test-page-href (assoc opts :routes typed-routes))))
        (is (str/ends-with? (-production-url opts) "tags=a%2Cb"))))))

(deftest test-page-url-encodes-the-route
  (testing ":url encodes the route's typed params like the browser URL bar"
    (let [result (ht/test-page (fn [_req] [:div])
                               {:routes typed-routes
                                :route  {:name         :items
                                         :path         "/items/1"
                                         :path-params  {:id 1}
                                         :query-params typed-query}})]
      (is (= (-production-url {}) (:url result))))))

(deftest test-action-navigate-with-routes
  (testing "effects/navigate! in an action resolves routes injected via :routes"
    (let [home   (fn [_req]
                   [:button {:data-on:click
                             (h/action {:as "go-about"}
                                       (effects/navigate! :about))}
                    "Go About"])
          result (ht/test-page home {:routes nav-routes})
          after  (ht/test-action result "go-about")]
      ;; Route state updated server-side without needing a full create-handler
      (is (= :about (get-in after [:cursors :route :name])))
      (is (= "/about" (get-in after [:cursors :route :path])))
      ;; pushState script queued among effects
      (is (some #(str/includes? % "pushState")
                (get-in after [:effects :scripts]))))))
