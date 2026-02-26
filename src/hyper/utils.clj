(ns hyper.utils
  "General-purpose utility functions."
  (:require [clojure.string]))

(defn escape-js-string
  "Escape a string for safe embedding in a single-quoted JavaScript string literal
   inside a <script> block. Handles backslashes, quotes, newlines, line/paragraph
   separators, and </script> injection."
  [s]
  (when s
    (-> s
        (clojure.string/replace "\\" "\\\\")
        (clojure.string/replace "'" "\\'")
        (clojure.string/replace "\"" "\\\"")
        (clojure.string/replace "\n" "\\n")
        (clojure.string/replace "\r" "\\r")
        (clojure.string/replace "\u2028" "\\u2028")
        (clojure.string/replace "\u2029" "\\u2029")
        ;; Prevent </script> from closing the script block in HTML parser
        (clojure.string/replace "</" "<\\/"))))
