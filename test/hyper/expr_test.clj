(ns hyper.expr-test
  "Unit tests for hyper.expr — the Clojure → Datastar expression transpiler."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.datastar :as datastar]
            [hyper.expr :as expr :refer [->expr]]
            [hyper.signal :as signal]
            [hyper.state :as state]))

(defn- local-sig [nm default]
  (signal/->LocalSignal (str "_" nm) (str "_" nm) default))

(defn- sig [js-name]
  (signal/->Signal js-name js-name [(keyword js-name)] (atom {}) "t1" nil))

(defmacro ^:private rendering
  "Evaluate body inside a minimal render context so `h/action` can register
   and produce its expression."
  [app-state* & body]
  `(binding [context/*request*    {:hyper/session-id "s"         :hyper/tab-id "t"
                                   :hyper/app-state  ~app-state* :hyper/router nil}
             context/*action-idx* (atom 0)]
     ~@body))

(defn- new-tab []
  (let [app-state* (atom (state/init-state))]
    (state/get-or-create-tab! app-state* "s" "t")
    app-state*))

;; ---------------------------------------------------------------------------
;; Raw Datastar style (no locals)
;; ---------------------------------------------------------------------------

(deftest test-raw-datastar-style
  (testing "set! on $signals"
    (is (= "($count = 0)" (->expr (set! $count 0)))))

  (testing "kebab-case signal names are preserved"
    (is (= "($record-id = 5)" (->expr (set! $record-id 5)))))

  (testing "namespaced signals"
    (is (= "($person.name = \"alice\")" (->expr (set! $person.name "alice")))))

  (testing "actions"
    (is (= "@post(\"/update\")" (->expr (@post "/update"))))
    (is (= "@get(\"/poke\")" (->expr (@get "/poke")))))

  (testing "multiple statements join with ;"
    (is (= "($a = 1); @get(\"/x\")" (->expr (set! $a 1) (@get "/x")))))

  (testing "a form with a ; returns its value through __hyper_r"
    (let [out (->expr (when-let [x (or $a $b)] x))]
      (is (str/starts-with? out "var __hyper_r = ((() => {"))
      (is (str/ends-with? out "; return __hyper_r;"))))

  (testing "earlier statements precede the __hyper_r variable"
    (let [out (->expr (set! $z 1) (when-let [x (or $a $b)] x))]
      (is (str/starts-with? out "($z = 1); var __hyper_r = ("))
      (is (str/ends-with? out "; return __hyper_r;"))))

  (testing "evt and el pass through as client-side symbols"
    (is (= "(evt.key === \"Enter\")" (->expr (= evt.key "Enter"))))
    (is (str/includes? (->expr (.focus el)) "el.focus()"))))

;; ---------------------------------------------------------------------------
;; Core references use the hyper_sc alias
;; ---------------------------------------------------------------------------

(deftest test-sandbox-safe-output
  (testing "= with a literal compiles to ==="
    (let [out (->expr (and (= $a 1) (or $b evt.shiftKey)))]
      (is (str/includes? out "($a === 1)"))
      (is (str/includes? out "&&"))
      (is (str/includes? out "evt.shiftKey"))))

  (testing "not= compiles to hyper_sc._EQ_"
    (is (= "!hyper_sc._EQ_($name, \"\")" (->expr (not= $name "")))))

  (testing "str compiles to a template literal"
    (is (= "`${\"n=\"}${$n??''}`" (->expr (str "n=" $n)))))

  (testing "when compiles to a ternary"
    (let [out (->expr (when $open (@post "/x")))]
      (is (str/includes? out "?"))
      (is (str/includes? out "@post"))))

  (testing "no squint_core references in any output"
    (doseq [out [(->expr (and $a $b $c))
                 (->expr (or $a $b))
                 (->expr (not $a))
                 (->expr (= $a $b $c))
                 (->expr (not= $a $b))
                 (->expr (str $a "x" $b))
                 (->expr (when $a (set! $b 1) (set! $c 2)))
                 (->expr (if $a 1 2))
                 (->expr (println "dbg" $a))
                 (->expr (+ $a 1))
                 (->expr (< $a 5))]]
      (is (not (str/includes? out "squint_core")) out))))

;; ---------------------------------------------------------------------------
;; Boundary inference — signals as atoms
;; ---------------------------------------------------------------------------

(deftest test-signal-atom-vocabulary
  (testing "swap! with not — the toggle idiom"
    (let [open?* (local-sig "open" false)]
      (is (= "($_open = hyper_sc.not($_open))" (->expr (swap! open?* not))))))

  (testing "swap! with inc and with extra args"
    (let [n* (local-sig "n" 0)]
      (is (= "($_n = ($_n + 1))" (->expr (swap! n* inc))))
      (is (= "($_n = ($_n + 5))" (->expr (swap! n* + 5))))))

  (testing "reset! from a client-side value"
    (let [query* (sig "searchQuery")]
      (is (= "($searchQuery = evt.target.value)"
             (->expr (reset! query* evt.target.value))))))

  (testing "deref reads as a signal reference"
    (let [name* (sig "userName")]
      (is (= "!hyper_sc._EQ_($userName, \"\")" (->expr (not= @name* ""))))
      (is (= "$userName" (->expr @name*)))))

  (testing "the full guard + assign + action composition"
    (let [query* (sig "q")]
      (is (= (str "var __hyper_r = ((((evt.key === \"Enter\")) ? "
                  "((() => { $q = evt.target.value; return @post(\"/search\") })()) : (null))); "
                  "return __hyper_r;")
             (->expr (when (= evt.key "Enter")
                       (reset! query* evt.target.value)
                       (@post "/search")))))))

  (testing "reset!/swap! on a non-signal throws at runtime"
    (let [x 5]
      (is (thrown-with-msg? Exception #"must be a signal"
                            (->expr (reset! x 1)))))))

;; ---------------------------------------------------------------------------
;; Boundary inference — locals and Clojure data
;; ---------------------------------------------------------------------------

(deftest test-value-splicing
  (testing "locals splice as JS literals"
    (let [factor 2
          label  "a'b"]
      (is (= "($scaled = ($raw * 2))" (->expr (set! $scaled (* $raw factor)))))
      (is (= "($label = 'a\\'b')" (->expr (set! $label label)))
          "strings are escaped JS literals")))

  (testing "keyword-call forms evaluate as Clojure"
    (let [person {:id 1234 :name "alice"}]
      (is (= "($person-id = 1234)" (->expr (set! $person-id (:id person)))))
      (is (= "($person-name = 'alice')" (->expr (set! $person-name (:name person)))))))

  (testing "explicit unquote still works as the escape hatch"
    (let [v 42]
      (is (= "($x = 43)" (->expr (set! $x ~(inc v)))))))

  (testing "collections splice as JS literals"
    (let [xs [1 2 3]]
      (is (= "($items = [1, 2, 3])" (->expr (set! $items xs)))))))

;; ---------------------------------------------------------------------------
;; Compile-once semantics
;; ---------------------------------------------------------------------------

(deftest test-macro-time-compilation
  (testing "no splices: expansion is a constant string"
    (is (string? (macroexpand-1 '(hyper.expr/->expr (set! $count 0))))))

  (testing "with splices: expansion is a substitute call over a compiled template"
    ;; macroexpand-1 has no &env locals, so use the explicit unquote form
    ;; to exercise the splice path.
    (let [expansion (macroexpand-1 '(hyper.expr/->expr (set! $x ~(compute))))]
      (is (seq? expansion))
      (is (= 'hyper.expr/substitute (first expansion)))
      (is (string? (second expansion)) "template compiled at expansion, not per call")))

  (testing "evaluating an expansion records its squint core names in core-vars*"
    (let [expansion (macroexpand-1 '(hyper.expr/->expr (or $a $b)))]
      (with-redefs [expr/core-vars* (atom (sorted-set))]
        (eval expansion)
        (is (contains? @expr/core-vars* "truth_"))))))

;; ---------------------------------------------------------------------------
;; Deref of static signal vars (e.g. the connection signals)
;; ---------------------------------------------------------------------------

(deftest test-deref-signal-var
  (testing "deref of a top-level var holding a signal splices to a $ref"
    (is (= "hyper_sc.not($_hyperConnected)"
           (->expr (not @signal/connected?*))))
    (is (= "($_hyperConnection === \"reconnecting\")"
           (->expr (= @signal/connection* :reconnecting)))))

  (testing "keyword and string tokens compile identically"
    (is (= (->expr (= @signal/connection* :open))
           (->expr (= @signal/connection* "open")))))

  (testing "deref of a non-signal var is left to the client (no splice)"
    (is (str/includes? (->expr (not @some-undefined-thing))
                       "hyper_sc.deref"))))

;; ---------------------------------------------------------------------------
;; DatastarExpr splicing — signals and actions ride one dispatch
;; ---------------------------------------------------------------------------

(deftest test-splice-datastar-expr
  (testing "signals splice to their $ref via the protocol"
    (is (= "$userName" (expr/splice (sig "userName") :value)))
    (is (= "$_open" (expr/splice (local-sig "open" false) :value))))

  (testing "a RawExpr splices as raw JS, not a quoted literal"
    (is (= "@post('/x')" (expr/splice (datastar/raw-expr "@post('/x')") :value))))

  (testing "ordinary values fall back to JS literals"
    (is (= "'hi'" (expr/splice "hi" :value)))
    (is (= "[1, 2, 3]" (expr/splice [1 2 3] :value))))

  (testing ":signal mode stays strict — a RawExpr is not an assignment target"
    (is (thrown-with-msg? Exception #"must be a signal"
                          (expr/splice (datastar/raw-expr "@post('/x')") :signal)))))

;; ---------------------------------------------------------------------------
;; Actions embedded inside expr
;; ---------------------------------------------------------------------------

(deftest test-action-in-expr
  (testing "a bare action splices its raw @post(...) into the expression"
    (let [app-state* (new-tab)]
      (rendering app-state*
                 (is (re-matches #"@post\('/hyper/actions\?action-id=a_t_[0-9a-f]{8}'\)"
                                 (h/expr (h/action (reset! (h/tab-cursor :q) 1))))))))

  (testing "an action guarded by a client-side condition compiles to a ternary"
    (let [app-state* (new-tab)]
      (rendering app-state*
                 (let [out (h/expr (when (= evt.key "Enter")
                                     (h/action (reset! (h/tab-cursor :q) 2))))]
                   (is (str/includes? out "evt.key === \"Enter\""))
                   (is (re-find #"@post\('/hyper/actions\?action-id=a_t_[0-9a-f]{8}'\)" out))
                   (is (str/includes? out "?"))
                   (is (not (str/includes? out "action(")))))))

  (testing "the embedded action is registered in tab state at render time"
    (let [app-state* (new-tab)]
      (let [out (rendering app-state*
                           (h/expr (h/action (reset! (h/tab-cursor :q) 3))))
            id  (second (re-find #"action-id=([^']+)" out))]
        (is (contains? (:actions @app-state*) id))))))

;; ---------------------------------------------------------------------------
;; Client-param vocabulary in expr ($value, $key, …)
;; ---------------------------------------------------------------------------

(deftest test-client-params-in-expr
  (testing "client-param symbols expand to their client-side JS"
    (is (= "(evt.key === \"Enter\")" (->expr (= $key "Enter"))))
    (is (= "evt.target.checked" (->expr $checked)))
    (is (= "evt.detail" (->expr $detail)))
    (is (= "Object.fromEntries(new FormData(evt.target.closest('form')))"
           (->expr $form-data))))

  (testing "$value assigns a signal the same as evt.target.value"
    (let [q* (sig "q")]
      (is (= (->expr (reset! q* evt.target.value))
             (->expr (reset! q* $value))))))

  (testing "non-param $signals still pass through untouched"
    (is (= "($count = 0)" (->expr (set! $count 0))))
    (let [name* (sig "userName")]
      (is (= "$userName" (->expr @name*)))))

  (testing "a client-param guard composes with an embedded action's own params"
    (let [app-state* (new-tab)]
      (rendering app-state*
                 (let [out (h/expr (when (= $key "Enter")
                                     (h/action (reset! (h/tab-cursor :q) $value))))]
                   (is (str/includes? out "evt.key === \"Enter\""))
                   (is (str/includes? out "hyper.encodeClientParams({value:evt.target.value})")))))))
