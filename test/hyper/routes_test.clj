(ns hyper.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.routes :as routes]
            [hyper.state :as state]))

(def sample-routes
  [["/" {:name  :home
         :get   (fn [_] [:div "Home"])
         :title "Home"}]
   ["/about" {:name  :about
              :get   (fn [_] [:div "About"])
              :title (fn [_req] "About Us")}]
   ["/users/:id" {:name :user-profile
                  :get  (fn [_] [:div "User"])}]])

(deftest index-routes-test
  (testing "builds name->data map"
    (let [idx (routes/index-routes sample-routes)]
      (is (= 3 (count idx)))
      (is (contains? idx :home))
      (is (contains? idx :about))
      (is (contains? idx :user-profile))))

  (testing "skips routes without :name"
    (let [routes [["/" {:get (fn [_] [:div])}]
                  ["/named" {:name :named :get (fn [_] [:div])}]]
          idx    (routes/index-routes routes)]
      (is (= 1 (count idx)))
      (is (contains? idx :named))))

  (testing "returns empty map for empty routes"
    (is (= {} (routes/index-routes [])))
    (is (= {} (routes/index-routes nil)))))

(deftest find-render-fn-test
  (let [idx (routes/index-routes sample-routes)]
    (testing "finds :get handler by name"
      (is (fn? (routes/find-render-fn idx :home)))
      (is (fn? (routes/find-render-fn idx :about))))

    (testing "returns nil for unknown route"
      (is (nil? (routes/find-render-fn idx :nonexistent))))))

(deftest find-route-title-test
  (let [idx (routes/index-routes sample-routes)]
    (testing "finds string title"
      (is (= "Home" (routes/find-route-title idx :home))))

    (testing "finds fn title"
      (is (fn? (routes/find-route-title idx :about))))

    (testing "returns nil when no title"
      (is (nil? (routes/find-route-title idx :user-profile))))

    (testing "returns nil for unknown route"
      (is (nil? (routes/find-route-title idx :nonexistent))))))

(deftest find-route-watches-test
  (testing "returns nil when no watches"
    (let [routes [["/" {:name :home :get (fn [_] [:div])}]]
          idx    (routes/index-routes routes)]
      (is (nil? (routes/find-route-watches idx [] :home)))))

  (testing "returns global watches for every route"
    (let [global-src (atom 0)
          routes     [["/" {:name :home :get (fn [_] [:div])}]
                      ["/about" {:name :about :get (fn [_] [:div])}]]
          idx        (routes/index-routes routes)]
      (is (= [global-src] (routes/find-route-watches idx [global-src] :home)))
      (is (= [global-src] (routes/find-route-watches idx [global-src] :about)))))

  (testing "combines global and per-route watches"
    (let [global-src (atom :global)
          route-src  (atom :route)
          routes     [["/" {:name :home :get (fn [_] [:div]) :watches [route-src]}]]
          idx        (routes/index-routes routes)]
      (is (= [global-src route-src] (routes/find-route-watches idx [global-src] :home)))))

  (testing "auto-watches Var-based :get handlers"
    (let [handler-var (intern *ns* (gensym "handler-") (fn [_] [:div]))
          routes      [["/" {:name :home :get handler-var}]]
          idx         (routes/index-routes routes)]
      (is (= [handler-var] (routes/find-route-watches idx [] :home)))))

  (testing "returns nil for unknown route"
    (let [idx (routes/index-routes sample-routes)]
      (is (nil? (routes/find-route-watches idx [] :nonexistent))))))

(deftest live-routes-test
  (testing "returns routes from atom when source is not a Var"
    (let [routes     [["/" {:name :home}]]
          app-state* (atom (assoc (state/init-state) :routes routes))]
      (is (= routes (routes/live-routes app-state*)))))

  (testing "dereferences Var source"
    (let [routes     [["/" {:name :home}]]
          routes-var (intern *ns* (gensym "routes-") routes)
          app-state* (atom (assoc (state/init-state) :routes-source routes-var))]
      (is (= routes (routes/live-routes app-state*))))))

(deftest live-route-index-test
  (testing "returns indexed map from live routes"
    (let [routes     [["/" {:name :home :get identity}]]
          app-state* (atom (assoc (state/init-state) :routes routes))
          idx        (routes/live-route-index app-state*)]
      (is (contains? idx :home))
      (is (= identity (:get (idx :home)))))))

(deftest resolve-title-test
  (testing "nil returns nil"
    (is (nil? (routes/resolve-title nil {}))))

  (testing "string returns as-is"
    (is (= "Hello" (routes/resolve-title "Hello" {}))))

  (testing "fn is called with request"
    (is (= "dynamic" (routes/resolve-title (fn [_] "dynamic") {}))))

  (testing "deref-able is derefed and stringified"
    (is (= "42" (routes/resolve-title (atom 42) {}))))

  (testing "other values are stringified"
    (is (= "123" (routes/resolve-title 123 {})))))

(deftest resolve-head-test
  (testing "nil returns nil"
    (is (nil? (routes/resolve-head nil {}))))

  (testing "fn is called with request"
    (let [head-fn (fn [req] [:link {:href (:css req)}])]
      (is (= [:link {:href "/app.css"}]
             (routes/resolve-head head-fn {:css "/app.css"})))))

  (testing "static value returned as-is"
    (is (= [:style "body{}"]
           (routes/resolve-head [:style "body{}"] {})))))
