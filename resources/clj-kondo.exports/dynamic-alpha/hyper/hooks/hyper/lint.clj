(ns hooks.hyper.lint
  "Shared lint helpers for the hyper render-region hooks.

   `check-render-effects!` scans a render body (the body of `reactive` /
   `async`) for effects that break render purity — framework effects
   (`watch!`, `spawn!`) and state mutations (`swap!`/`reset!` and the `-vals!`
   variants) — and registers a warning for each.

   It descends through ordinary render forms (hiccup vectors, `let`/`when`/`for`,
   …) but PRUNES sub-forms that are not render flow:

   - `action` / `expr` — event and client-side contexts, where mutating a
     cursor or signal is exactly the point;
   - nested `reactive` / `async` / `defc` — they carry their own analysis.

   Matching is by symbol name, so both `h/watch!` and a referred `watch!` are
   caught, and `compare-and-set!` (the cursor/signal default-init, declarative)
   is intentionally absent from the mutation set.

   `invalid-deps?` tells whether a region's deps argument can never be deps."
  (:require [clj-kondo.hooks-api :as api]))

;; Matched against the *name* (string) of a list node's head symbol, so both
;; `h/watch!` and a referred `watch!` are caught.
(def ^:private mutation-names         #{"swap!" "reset!" "swap-vals!" "reset-vals!"})
(def ^:private framework-effect-names #{"watch!" "spawn!"})
(def ^:private prune-names            #{"action" "expr" "defc" "reactive" "async"})

(defn- head-name
  "The unqualified name of a list node's head symbol (e.g. \"watch!\" for both
   `h/watch!` and `watch!`), or nil."
  [node]
  (when (api/list-node? node)
    (let [h (first (:children node))]
      (when (api/token-node? h)
        (let [s (api/sexpr h)]
          (when (symbol? s) (name s)))))))

(defn- finding! [node message]
  (api/reg-finding!
   (assoc (meta node)
          :level   :warning
          :message message
          :type    :hyper.purity/effect-in-render)))

(defn- scan!
  [node]
  (let [hn (head-name node)]
    (when-not (contains? prune-names hn)
      (cond
        (contains? framework-effect-names hn)
        (finding! node
                  (str "`" hn "` is an effect — a render body must be pure. "
                       "Move it to a form-2 setup closure or a form-3 :mount."))

        (contains? mutation-names hn)
        (finding! node
                  (str "`" hn "` mutates state in a render body — a render must be "
                       "pure. Put it in an action, a form-2 setup closure, or a "
                       "worker (h/spawn!).")))
      (doseq [c (:children node)] (scan! c)))))

(defn check-render-effects!
  "Scan render-body nodes for render-purity violations, registering a warning
   for each effect found in render flow."
  [body-nodes]
  (doseq [n body-nodes] (scan! n)))

(defn invalid-deps?
  "True when a region's deps argument is missing, or is a literal that cannot
   be deps: a map, keyword, string, number, boolean or nil."
  [deps]
  (or (nil? deps)
      (api/map-node? deps)
      (api/keyword-node? deps)
      (api/string-node? deps)
      (and (api/token-node? deps)
           (let [v (api/sexpr deps)]
             (or (nil? v) (boolean? v) (number? v))))))
