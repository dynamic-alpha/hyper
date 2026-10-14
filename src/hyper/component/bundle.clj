(ns ^:no-doc hyper.component.bundle
  "Assembly and serving support for the client-components JS bundle.

   Components registered in hyper.component/registry* are assembled into a
   single ES module with a binding to the page's squint core, the hyper
   component runtime (resources/hyper/component-runtime.js) and each compiled
   component.  The module is served at /hyper/components.js (see
   hyper.server) and injected into the page head with a content-hashed URL
   (see hyper.render), which makes the response immutable-cacheable and lets
   REPL redefinition hot-swap the bundle over SSE."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyper.component :as component]
            [squint.compiler.source-map :as sm]))

(defn- runtime-js []
  (slurp (io/resource "hyper/component-runtime.js")))

(defn- sha256-hex
  [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) (take 8 d)))))

(defn- import-lines
  "Build the deduplicated ES module import statements for all component
   :requires across the registry.  Aliases are munged the same way Squint
   munges them in compiled output (e.g. chart-lib -> chart_lib), so the
   import identifier matches the compiled references.

   Throws when an alias maps to more than one URL — the bundle is a single
   module scope (this is also checked per-component at registration; this
   guard catches stale-registry edge cases)."
  [registry]
  (let [pairs (->> (vals registry)
                   (mapcat :requires)
                   (map (juxt :alias :url))
                   distinct
                   (sort-by (comp str first)))]
    (doseq [[alias urls] (group-by first pairs)
            :when        (> (count urls) 1)]
      (throw (ex-info (str "Alias " alias " maps to multiple URLs across components: "
                           (pr-str (mapv second urls)))
                      {:alias alias :urls (mapv second urls)})))
    (apply str
           (map (fn [[alias url]]
                  (str "import * as " (munge (name alias)) " from '" url "';\n"))
                pairs))))

(defn- source-text [path]
  (when path
    (or (some-> (io/resource path) slurp)
        (let [f (io/file path)] (when (.exists f) (slurp f))))))

(defn- bundle-source-map
  "Returns the source map JSON for components placed after prefix, joined with
   newlines, or nil if no component carries segments."
  [prefix components]
  (let [sources  (vec (distinct (keep (comp :source :source-map) components)))
        index    (zipmap sources (range))
        segments (loop [[c & more] components, line (sm/count-newlines prefix), acc []]
                   (if-not c
                     acc
                     (let [{:keys [segments source]} (:source-map c)
                           acc                       (into acc (map (fn [[gl gc sl sc]] [(+ gl line) gc sl sc (index source)]))
                                                           (when source segments))]
                       (recur more (+ line 1 (sm/count-newlines (:js c))) acc))))]
    (when (seq segments)
      (sm/encode segments {:file            "components.js"
                           :sources         sources
                           :sources-content (mapv source-text sources)}))))

(defn- assemble-bundle
  [registry source-map?]
  (let [prefix     (str "const $sc = window.hyper_sc;\n"
                        (import-lines registry)
                        (runtime-js)
                        "\n")
        components (map val (sort-by key registry))
        source-map (when source-map? (bundle-source-map prefix components))
        js         (cond-> (str prefix (str/join "\n" (map :js components)))
                     source-map (str "\n//# sourceMappingURL=components.js.map\n"))]
    {:js js :hash (sha256-hex js) :source-map source-map}))

(defonce ^:private bundle-cache* (atom {}))

(defn bundle
  "Assemble (or return cached) the components JS bundle.

   Returns {:js \"...\" :hash \"...\" :source-map \"...\"} or nil when no
   components are registered.  The bundle is a single ES module: a binding to the page's
   squint core (window.hyper_sc), the hyper component runtime, then each
   registered component sorted by name.  Cached against the registry snapshot.
   :source-map is nil if source-map? is false."
  ([] (bundle false))
  ([source-map?]
   (let [registry @component/registry*]
     (when (seq registry)
       (let [cached (get @bundle-cache* source-map?)]
         (if (= registry (:key cached))
           (:bundle cached)
           (let [b (assemble-bundle registry source-map?)]
             (swap! bundle-cache* assoc source-map? {:key registry :bundle b})
             b)))))))

(defn core-vars
  "Returns the munged squint core names that the registered components use."
  []
  (into #{} (mapcat :core-vars) (vals @component/registry*)))

(defn head-script-tag
  "Hiccup script tag for the components bundle, or nil when no components
   are registered.  The content hash is carried in the query string so the
   endpoint can serve immutable cache headers and a registry change rotates
   the URL (which, combined with head-element fingerprint diffing, makes
   REPL redefinition hot-swap the bundle over SSE)."
  ([base-path] (head-script-tag base-path false))
  ([base-path source-map?]
   (when-let [{:keys [hash]} (bundle source-map?)]
     [:script {:type "module"
               :src  (str base-path "/hyper/components.js?v=" hash)}])))
