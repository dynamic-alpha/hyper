(ns hyper.component-test
  "Unit tests for hyper.component and its sub-namespaces — squint
   compilation, the component registry, attribute serialization,
   macro-time hiccup compilation, and bundle assembly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.component :as hc]
            [hyper.component.bundle :as hcb]
            [hyper.component.hiccup :as hch]
            [hyper.signal :as signal]
            [squint.compiler.source-map :as sm]))

(defn- with-fresh-registry*
  "Run f with an isolated component registry and bundle cache."
  [f]
  (with-redefs-fn {#'hc/registry*                         (atom {})
                   #'hyper.component.bundle/bundle-cache* (atom {})}
    f))

(defmacro with-fresh-registry [& body]
  `(with-fresh-registry* (fn [] ~@body)))

;; ---------------------------------------------------------------------------
;; Squint compilation
;; ---------------------------------------------------------------------------

(deftest test-compile-squint
  (testing "compiles CLJS-dialect source to JS with $sc core alias"
    (let [js (hc/compile-squint "(defn f [xs] (map inc xs))")]
      (is (str/includes? js "$sc.map"))
      (is (not (str/includes? js "import")) "imports are elided")))

  (testing "hiccup literals compile to plain JS arrays with string tags"
    ;; Note: a bare (fn ...) in statement context is elided as a dead
    ;; expression — render fns are always wrapped in a ($define ...) call
    ;; by registration-source, so bind it here to observe the output.
    (let [js (hc/compile-squint "(def f (fn [x] [:div.box \"v: \" x]))")]
      (is (str/includes? js "[\"div.box\", \"v: \", x]"))))

  (testing "compilation failure throws with source attached"
    (let [e (try (hc/compile-squint "(fn [x] (")
                 (catch Exception e e))]
      (is (some? e))
      (is (= "(fn [x] (" (:source (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest test-register-component!
  (with-fresh-registry
    (testing "registers compiled component under its tag name"
      (hc/register-component! "my-widget"
                              {:attrs  [:value :label]
                               :render "(fn [{:keys [value label]} _ctx] [:div label \": \" value])"})
      (let [{:keys [js attrs]} (get @hc/registry* "my-widget")]
        (is (str/includes? js "$define(\"my-widget\""))
        (is (str/includes? js "\"attrs\": [\"value\", \"label\"]"))
        (is (= [:value :label] attrs))))

    (testing "re-registering replaces the spec"
      (hc/register-component! "my-widget"
                              {:attrs  [:value]
                               :render "(fn [{:keys [value]} _ctx] [:span value])"})
      (is (str/includes? (get-in @hc/registry* ["my-widget" :js]) "[\"span\""))))

  (testing "tag name must contain a hyphen (custom elements spec)"
    (is (thrown? AssertionError
                 (hc/register-component! "widget" {:attrs [] :render "(fn [_ _] [:div])"})))))

;; ---------------------------------------------------------------------------
;; Attribute serialization
;; ---------------------------------------------------------------------------

(deftest test-attr-value
  (is (= "plain" (hc/attr-value "plain")) "strings pass through raw")
  (is (= "42" (hc/attr-value 42)))
  (is (= "1.5" (hc/attr-value 1.5)))
  (is (= "true" (hc/attr-value true)))
  (is (= "red" (hc/attr-value :red)) "keywords serialize as name")
  (is (nil? (hc/attr-value nil)))
  (is (= "[1,2,3]" (hc/attr-value [1 2 3])) "collections get JSON"))

(deftest test-stable-json-determinism
  (testing "map key order does not affect output"
    (let [a (hash-map :b 2 :a 1 :c {:z 26 :y 25 :x 24})
          b (hash-map :c {:x 24 :y 25 :z 26} :a 1 :b 2)]
      (is (= (hc/stable-json a) (hc/stable-json b)))
      (is (= "{\"a\":1,\"b\":2,\"c\":{\"x\":24,\"y\":25,\"z\":26}}"
             (hc/stable-json a))))))

(deftest test-attrs
  (let [out (hc/attrs {:value                  42
                       :label                  "CPU"
                       :points                 [{:x 1} {:x 2}]
                       :id                     "g1"
                       :class                  "wide"
                       :style                  "color:red"
                       :data-on:gauge-selected "@post('/x')"})]
    (testing "component data attrs are serialized"
      (is (= "42" (:value out)))
      (is (= "CPU" (:label out)))
      (is (= "[{\"x\":1},{\"x\":2}]" (:points out))))
    (testing "host-element contract keys pass through untouched"
      (is (= "g1" (:id out)))
      (is (= "wide" (:class out)))
      (is (= "color:red" (:style out)))
      (is (= "@post('/x')" (:data-on:gauge-selected out))))))

(deftest test-attrs-signal-link
  (testing "signal object as attr value generates the live-link triple"
    (let [sig (signal/->Signal "hovSym" "hov-sym" [:hov-sym] (atom {}) "tab1" "init")
          out (hc/attrs {:linked sig :label "x"})]
      (testing "attr seeded from signal default, JSON-encoded for first paint"
        (is (= "\"init\"" (:linked out))))
      (testing "Datastar keeps the attribute synced to the signal"
        (is (= "JSON.stringify($hovSym)" (:data-attr:linked out))))
      (testing "an event named after the attr writes the signal"
        (is (= "$hovSym = evt.detail" (:data-on:linked out))))
      (testing "other attrs unaffected"
        (is (= "x" (:label out))))))

  (testing "current tab-state value wins over the default"
    (let [app-state (atom {:tabs {"t1" {:signals {:hov-sym "live"}}}})
          sig       (signal/->Signal "hovSym" "hov-sym" [:hov-sym] app-state "t1" "init")]
      (is (= "\"live\"" (:linked (hc/attrs {:linked sig}))))))

  (testing "collection values JSON-encode deterministically"
    (let [sig (signal/->Signal "sel" "sel" [:sel] (atom {}) "t1" {:b 2 :a 1})]
      (is (= "{\"a\":1,\"b\":2}" (:sel (hc/attrs {:sel sig})))))))

;; ---------------------------------------------------------------------------
;; Bundle assembly
;; ---------------------------------------------------------------------------

(deftest test-bundle
  (with-fresh-registry
    (testing "nil when no components are registered"
      (is (nil? (hcb/bundle)))
      (is (nil? (hcb/head-script-tag ""))))

    (hc/register-component! "a-widget" {:attrs [:v] :render "(fn [{:keys [v]} _] [:em v])"})

    (testing "bundle is a single ES module: prelude, runtime, components"
      (let [{:keys [js hash]} (hcb/bundle)]
        (is (str/starts-with? js "const $sc = window.hyper_sc;"))
        (is (str/includes? js "function $define(") "runtime shim included")
        (is (str/includes? js "$define(\"a-widget\"") "component included")
        (is (= 16 (count hash)))))

    (testing "bundle is cached against the registry snapshot"
      (is (identical? (hcb/bundle) (hcb/bundle))))

    (testing "registering another component changes the hash and sorts by name"
      (let [h1 (:hash (hcb/bundle))]
        (hc/register-component! "b-widget" {:attrs [] :render "(fn [_ _] [:i])"})
        (let [{:keys [js hash]} (hcb/bundle)]
          (is (not= h1 hash))
          (is (< (str/index-of js "$define(\"a-widget\"")
                 (str/index-of js "$define(\"b-widget\""))))))

    (testing "core-vars returns the $sc names of registered components"
      (hc/register-component! "c-widget" {:attrs [:v] :render "(fn [{:keys [v]} _] [:em (count v)])"})
      (is (contains? (hcb/core-vars) "count")))))

;; ---------------------------------------------------------------------------
;; Macro-time hiccup compilation
;; ---------------------------------------------------------------------------

(deftest test-parse-tag
  (is (= ["div" nil nil]       (hch/parse-tag :div)))
  (is (= ["div" "m" nil]       (hch/parse-tag :div#m)))
  (is (= ["div" nil "a b"]     (hch/parse-tag :div.a.b)))
  (is (= ["svg" "c" "chart"]   (hch/parse-tag :svg.chart#c)))
  (is (= ["div" nil "box"]     (hch/parse-tag :.box)))
  (is (= ["div" "only" nil]    (hch/parse-tag :#only)))
  (is (= ["my-widget" nil nil] (hch/parse-tag :my-widget))))

(deftest test-compile-hiccup
  (testing "basic elements compile to $h descriptor calls"
    (is (= '($h "div" nil nil nil ["x"])
           (hch/compile-hiccup '[:div "x"])))
    (is (= '($h "div" "m" "box" {:title "t"} ["x"])
           (hch/compile-hiccup '[:div.box#m {:title "t"} "x"]))))

  (testing "children compile recursively; expressions stay in child position"
    (is (= '($h "div" nil nil nil [($h "span" nil nil nil [y]) (str a b)])
           (hch/compile-hiccup '[:div [:span y] (str a b)]))))

  (testing "a non-literal second position is a child, not attrs"
    (is (= '($h "div" nil nil nil [props "x"])
           (hch/compile-hiccup '[:div props "x"]))))

  (testing "control forms recurse into value positions only"
    (is (= '(if c ($h "i" nil nil nil ["y"]) ($h "em" nil nil nil ["n"]))
           (hch/compile-hiccup '(if c [:i "y"] [:em "n"]))))
    (is (= '(let [pct 5] ($h "div" nil nil nil [pct]))
           (hch/compile-hiccup '(let [pct 5] [:div pct]))))
    (is (= '(for [v vs] ($h "li" nil nil nil [v]))
           (hch/compile-hiccup '(for [v vs] [:li v]))))
    (is (= '(when c (log! [:not :hiccup]) ($h "b" nil nil nil []))
           (hch/compile-hiccup '(when c (log! [:not :hiccup]) [:b])))
        "earlier (side-effect) body forms are untouched")
    (is (= '(cond a ($h "i" nil nil nil []) :else ($h "em" nil nil nil []))
           (hch/compile-hiccup '(cond a [:i] :else [:em])))
        "cond compiles results, never tests"))

  (testing "keyword vectors inside unknown expressions are never corrupted"
    (is (= '(get m [:a :b])
           (hch/compile-hiccup '(get m [:a :b]))))
    (is (= '(my-helper [:div "raw"])
           (hch/compile-hiccup '(my-helper [:div "raw"])))
        "unknown calls pass through whole — runtime fallback handles results"))

  (testing "non-keyword-head vectors are untouched"
    (is (= '[a b c] (hch/compile-hiccup '[a b c])))))

(deftest test-defc-compiles-render-hiccup
  (with-fresh-registry
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc compiled-widget
      [{:keys [items]}]
      (render
        [:ul.list
         (for [i items] [:li i])]))
    (let [js (get-in @hc/registry* ["compiled-widget" :js])]
      (is (str/includes? js "$h(\"ul\", null, \"list\", null,")
          "literal hiccup compiles to descriptor calls")
      (is (str/includes? js "$h(\"li\", null, null, null,")
          "hiccup inside (for ...) compiles too"))))

;; ---------------------------------------------------------------------------
;; defc macro
;; ---------------------------------------------------------------------------

(deftest test-defc-tag-name
  (testing "sym->tag converts naming conventions to kebab tag strings"
    (is (= "temp-gauge"   (hc/sym->tag 'temp-gauge)))
    (is (= "temp-gauge"   (hc/sym->tag 'temp_gauge)))
    (is (= "temp-gauge"   (hc/sym->tag 'TempGauge)))))

(deftest test-extract-attrs
  (testing "extracts :keys as keyword vector"
    (is (= [:value :max :label]
           (hc/extract-attrs '[{:keys [value max label]}]))))
  (testing "returns nil for unsupported forms"
    (is (nil? (hc/extract-attrs '[value max])))))

(deftest test-defc-macro
  (with-fresh-registry
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc my-widget
      "A test widget."
      [{:keys [count label]}]
      (event ::clicked [_e]
        (emit "widget-clicked" {:count count}))
      (render
        [:div {:on {:click ::clicked}}
         [:span label " = " count]]))

    (testing "registers component in the global registry"
      (is (contains? @hc/registry* "my-widget")))

    (testing "generated JS calls $define with correct attrs"
      (let [js (get-in @hc/registry* ["my-widget" :js])]
        (is (str/includes? js "$define(\"my-widget\""))
        (is (str/includes? js "\"attrs\": [\"count\", \"label\"]"))))

    (testing "generated JS wires event handlers through $handlers"
      (let [js (get-in @hc/registry* ["my-widget" :js])]
        (is (str/includes? js "ctx.emit") "uses property access, not function call")
        (is (str/includes? js "on_clicked"))
        (is (str/includes? js "$handlers"))))

    (testing "generated JS rewrites ::clicked to $handlers lookup"
      (let [js (get-in @hc/registry* ["my-widget" :js])]
        ;; The ::clicked keyword in {:on {:click ::clicked}} should become a
        ;; $handlers lookup rather than appearing as a literal keyword.
        (is (not (str/includes? js ":clicked"))
            "literal ::clicked should be replaced with handler lookup")
        (is (str/includes? js "\"click\""))))

    (testing "server-side fn returns hiccup host element with serialized attrs"
      (let [result (my-widget {:count 5 :label "Score"})]
        (is (= :my-widget (first result)))
        (is (= "5"       (get (second result) :count)))
        (is (= "Score"   (get (second result) :label)))))

    (testing "data-* attrs pass through untouched"
      (let [result (my-widget {:count                  1             :label "x"
                               :data-on:widget-clicked "@post('/x')"})]
        (is (= "@post('/x')" (get (second result) :data-on:widget-clicked)))))

    (testing "compilation error is thrown at macro expansion time, not runtime"
      ;; We test this by calling register-component! directly with bad squint
      (let [e (try (hc/compile-squint "(fn [x] (") (catch Exception e e))]
        (is (some? e))
        (is (= "(fn [x] (" (:source (ex-data e))))))

    (testing "missing render segment throws at expansion"
      ;; eval wraps macroexpansion errors in CompilerException
      (is (thrown? Exception
                   (eval '(hyper.component/defc no-render-widget
                            [{:keys [x]}])))))))

(deftest test-defc-source-map
  (with-fresh-registry
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc sm-widget
      [{:keys [label]}]
      (render
        [:span (str label "!")]))

    (let [{:keys [line file]}       (meta #'sm-widget)
          {:keys [segments source]} (get-in @hc/registry* ["sm-widget" :source-map])]
      (testing "defc maps the str call to its line in the source file"
        (is (= file source))
        (is (= #{(+ line 2)} (set (map #(nth % 2) segments)))))

      (testing "the bundle links a source map with the file name and content"
        (let [{:keys [js source-map]} (hcb/bundle true)]
          (is (str/ends-with? js "//# sourceMappingURL=components.js.map\n"))
          (is (str/includes? source-map (str "\"sources\":[\"" source "\"]")))
          (is (str/includes? source-map "\"sourcesContent\":[\"(ns hyper.component-test"))))

      (testing "source-map? false omits the map and the sourceMappingURL"
        (let [{:keys [js source-map]} (hcb/bundle false)]
          (is (nil? source-map))
          (is (not (str/includes? js "sourceMappingURL")))))

      (testing "bundle caches source-map? true and false separately"
        (let [with-map (hcb/bundle true)]
          (hcb/bundle false)
          (is (identical? with-map (hcb/bundle true))))))))

(deftest test-bundle-source-map-lines
  (with-fresh-registry
    (hc/register-component! "sm-0" {:attrs [] :render "(fn [_ _] [:i])"})
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc sm-a
      [{:keys [label]}]
      (render
        [:span (str label "!")]))
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc sm-b
      [{:keys [label]}]
      (render
        [:span (str label "?")]))

    (let [segments* (atom nil)
          encode    sm/encode
          js        (with-redefs [sm/encode (fn [segments opts]
                                              (reset! segments* segments)
                                              (encode segments opts))]
                      (:js (hcb/bundle true)))
          js-lines  (str/split-lines js)
          marker    {(+ 2 (:line (meta #'sm-a))) "\"!\""
                     (+ 2 (:line (meta #'sm-b))) "\"?\""}]
      (testing "each segment points at the bundle line with its component's str call"
        (is (= (set (keys marker)) (set (map #(nth % 2) @segments*))))
        (doseq [[gl _ sl _ src] @segments*]
          (is (= 0 src))
          (is (str/includes? (nth js-lines gl) (marker sl))))))))

(deftest test-defc-no-source-path
  (with-fresh-registry
    (testing "defc with *file* NO_SOURCE_PATH stores no source map"
      (binding [*file* "NO_SOURCE_PATH"]
        (eval '(hyper.component/defc repl-widget
                 [{:keys [x]}]
                 (render [:i x]))))
      (is (contains? @hc/registry* "repl-widget"))
      (is (nil? (get-in @hc/registry* ["repl-widget" :source-map]))))))

(deftest test-defc-requires
  (with-fresh-registry
    (testing ":require compiles alias-qualified symbols and lands in the bundle"
      #_{:clj-kondo/ignore [:inline-def]}
      (hc/defc lib-chart
        {:require [["https://esm.sh/d3@7" :as d3]
                   ["https://esm.sh/chart" :as chart-lib]]}
        [{:keys [points]}]
        (render
          [:svg (d3/line points) (chart-lib/init points)]))
      (let [{:keys [js requires]} (get @hc/registry* "lib-chart")]
        (is (= [{:url "https://esm.sh/d3@7" :alias 'd3}
                {:url "https://esm.sh/chart" :alias 'chart-lib}]
               requires))
        (is (str/includes? js "d3.line"))
        (is (str/includes? js "chart_lib.init") "alias munged like squint output"))
      (let [bundle-js (:js (hcb/bundle))]
        (is (str/includes? bundle-js "import * as d3 from 'https://esm.sh/d3@7';"))
        (is (str/includes? bundle-js "import * as chart_lib from 'https://esm.sh/chart';"))))

    (testing "same URL+alias across components imports once"
      #_{:clj-kondo/ignore [:inline-def]}
      (hc/defc other-chart
        {:require [["https://esm.sh/d3@7" :as d3]]}
        [{:keys [data]}]
        (render [:svg (d3/area data)]))
      (let [bundle-js (:js (hcb/bundle))]
        (is (= 1 (count (re-seq #"import \* as d3 " bundle-js))))))

    (testing "alias conflict (same alias, different URL) throws at registration"
      (is (thrown-with-msg?
            Exception #"Aliases must map to a single URL"
            (eval '(hyper.component/defc conflicting-chart
                     {:require [["https://esm.sh/d3@6" :as d3]]}
                     [{:keys [x]}]
                     (render [:svg x]))))))

    (testing "malformed :require entry throws at expansion"
      ;; eval wraps macroexpansion errors in CompilerException; our ex-info
      ;; (with the malformed entry attached) is the cause.
      (let [e (try (eval '(hyper.component/defc bad-require-chart
                            {:require ["https://esm.sh/d3@7"]}
                            [{:keys [x]}]
                            (render [:svg x])))
                   nil
                   (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message (ex-cause e)) "Malformed :require entry"))
        (is (= "https://esm.sh/d3@7" (:entry (ex-data (ex-cause e)))))))))

(deftest test-defc-lifecycle
  (with-fresh-registry
    (testing "full seamless component generates all lifecycle spec entries"
      #_{:clj-kondo/ignore [:inline-def]}
      (hc/defc seamless-chart
        [{:keys [data]}]
        (render [:div.scaffold])
        (mount [root]
          (set! (.-chart ctx) root)
          (emit "ready" {:n (count data)}))
        (update [_root old]
          (js/console.log old data))
        (unmount [_root]
          (set! (.-chart ctx) nil)))
      (let [js (get-in @hc/registry* ["seamless-chart" :js])]
        (testing "spec carries every lifecycle fn"
          (is (str/includes? js "\"render\": (function"))
          (is (str/includes? js "\"mount\": (function"))
          (is (str/includes? js "\"update\": (function"))
          (is (str/includes? js "\"unmount\": (function")))
        (testing "lifecycle fns follow the runtime calling convention (props, ctx, root[, old])"
          (is (re-find #"\"mount\": \(function \(p__\d+, ctx, root\)" js))
          (is (re-find #"\"update\": \(function \(p__\d+, ctx, _root, old\)" js)))
        (testing "ctx is the instance state slot and emit is in scope"
          (is (str/includes? js "ctx.chart = "))
          (is (str/includes? js "ctx.emit"))))))

  (with-fresh-registry
    (testing "mount-only component (no render) is valid"
      #_{:clj-kondo/ignore [:inline-def]}
      (hc/defc bare-mount
        [{:keys [data]}]
        (mount [root] (js/console.log root data)))
      (let [js (get-in @hc/registry* ["bare-mount" :js])]
        (is (str/includes? js "\"mount\": (function"))
        (is (not (str/includes? js "\"render\""))))))

  (testing "neither render nor mount throws at expansion"
    (is (thrown? Exception
                 (eval '(hyper.component/defc no-body-chart
                          [{:keys [x]}]
                          (unmount [_root] x))))))

  (testing "bad lifecycle arity throws at expansion"
    (is (thrown? Exception
                 (eval '(hyper.component/defc bad-arity-chart
                          [{:keys [x]}]
                          (render [:div x])
                          (mount [root extra] (js/console.log root extra)))))))

  (testing "duplicate lifecycle segment throws at expansion"
    (is (thrown? Exception
                 (eval '(hyper.component/defc dup-mount-chart
                          [{:keys [x]}]
                          (render [:div x])
                          (mount [root] root)
                          (mount [root] root)))))))

(deftest test-defc-no-events
  (with-fresh-registry
    (testing "component without events compiles cleanly"
      #_{:clj-kondo/ignore [:inline-def]}
      (hc/defc plain-badge
        [{:keys [label color]}]
        (render
          [:span {:style (str "background:" color)} label]))
      (let [js (get-in @hc/registry* ["plain-badge" :js])]
        (is (str/includes? js "$define(\"plain-badge\""))
        (is (not (str/includes? js "$handlers")) "no handler table when no events"))
      (is (= [:plain-badge {:label "OK" :color "green"}]
             (plain-badge {:label "OK" :color "green"}))))))

(deftest test-defc-children
  (with-fresh-registry
    #_{:clj-kondo/ignore [:inline-def]}
    (hc/defc slot-card
      [{:keys [title]}]
      (render
        [:div [:h2 title] [:slot]]))

    (testing "no children behaves exactly as before (host element only)"
      (is (= [:slot-card {:title "Hi"}]
             (slot-card {:title "Hi"}))))

    (testing "trailing children are appended to the host element's light DOM"
      (is (= [:slot-card {:title "Hi"} [:span "a"] [:span "b"]]
             (slot-card {:title "Hi"} [:span "a"] [:span "b"]))))

    (testing "serialized attrs and children coexist"
      (let [result (slot-card {:title "Hi"} [:p "body"])]
        (is (= :slot-card (first result)))
        (is (= "Hi" (get (second result) :title)))
        (is (= [:p "body"] (nth result 2)))))

    (testing "a single seq of children splices through (hiccup flattens it)"
      (is (= [:slot-card {:title "Hi"} '([:li 1] [:li 2])]
             (slot-card {:title "Hi"} (for [i [1 2]] [:li i])))))))

(deftest test-head-script-tag
  (with-fresh-registry
    (hc/register-component! "a-widget" {:attrs [] :render "(fn [_ _] [:i])"})
    (testing "script tag carries base-path and content hash"
      (let [[tag attrs] (hcb/head-script-tag "/my-app")]
        (is (= :script tag))
        (is (= "module" (:type attrs)))
        (is (str/starts-with? (:src attrs) "/my-app/hyper/components.js?v="))
        (is (= (str "/my-app/hyper/components.js?v=" (:hash (hcb/bundle)))
               (:src attrs)))))))
