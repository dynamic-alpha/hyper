(ns ^:no-doc hyper.datastar
  "The Datastar-expression boundary type.

   `RawExpr` wraps a finished Datastar/JS expression string (e.g. the
   `@hyperAction(...)` an `h/action` produces).  It exists so the same value can be
   used in two contexts without the caller choosing between them:

   - as a hiccup attribute value — it renders like the string it wraps,
     escaped for the HTML attribute context (via Chassis);
   - spliced inside `h/expr` — it contributes its raw JS, not a quoted JS
     string literal (via the `DatastarExpr` protocol).

   Signals implement `DatastarExpr` too (yielding their `$ref`), so both ride
   the same splice path; anything that does not implement it falls back to a
   JavaScript literal."
  (:require [dev.onionpancakes.chassis.core :as c]))

(defprotocol DatastarExpr
  (-datastar-js [x]
    "The raw Datastar/JS expression source for x, embeddable verbatim in a
     compiled expression."))

(defn datastar-expr?
  "True when x renders as a raw Datastar expression (implements DatastarExpr)."
  [x]
  (satisfies? DatastarExpr x))

(defrecord RawExpr [js]
  DatastarExpr
  (-datastar-js [_] js)
  Object
  (toString [_] js))

(defn raw-expr
  "Wrap a finished Datastar expression string as a RawExpr.  Idempotent."
  [js]
  (if (instance? RawExpr js) js (->RawExpr js)))

;; Render a RawExpr in a hiccup attribute exactly as its wrapped string would
;; render — delegate to Chassis's String implementation so the JS is escaped
;; for the attribute context (quotes, ampersands, …), unlike the raw
;; AttributeValueFragment path that signal names use.
(extend-protocol c/AttributeValue
  RawExpr
  (append-attribute-fragment-to [this sb attr-name]
    (c/append-attribute-fragment-to (:js this) sb attr-name)))
