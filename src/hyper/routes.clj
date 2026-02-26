(ns hyper.routes
  "Route-querying helpers shared between server and render.

   Pure functions for looking up route metadata by name, resolving
   titles/head content, and reading the current routes (supporting
   live-reloading via Var indirection).")

(defn index-routes
  "Build a {route-name → route-data} map from a routes vector for O(1) lookups."
  [routes]
  (into {}
        (keep (fn [[_path data]]
                (when-let [n (:name data)]
                  [n data])))
        routes))

(defn find-render-fn
  "Find the :get handler for a named route."
  [route-index route-name]
  (:get (route-index route-name)))

(defn find-route-title
  "Find the :title metadata for a named route.
   Returns the title value (string or fn) or nil."
  [route-index route-name]
  (:title (route-index route-name)))

(defn find-route-watches
  "Collect all Watchable sources for a named route.
   Returns a vector of sources built from:
   - global-watches supplied to create-handler (applied to every route)
   - The route's :watches vector (explicit per-route external sources)
   - The route's :get value, if it's a Var (auto-watch for live reloading)
   Returns nil if there are no watches."
  [route-index global-watches route-name]
  (when-let [route-data (route-index route-name)]
    (let [global      (vec global-watches)
          explicit    (vec (or (:watches route-data) []))
          get-handler (:get route-data)
          watches     (cond-> (into global explicit)
                        (var? get-handler) (conj get-handler))]
      (when (seq watches)
        watches))))

(defn live-routes
  "Get the current routes vector, resolving through :routes-source if it's a Var."
  [app-state*]
  (let [source (get @app-state* :routes-source)]
    (if (var? source)
      @source
      (get @app-state* :routes))))

(defn live-route-index
  "Get an indexed map of the current routes for O(1) lookups by name."
  [app-state*]
  (index-routes (live-routes app-state*)))

(defn resolve-title
  "Resolve a title value. If it's a fn, call it with the request.
   Returns the resolved string, or nil."
  [title req]
  (cond
    (nil? title)    nil
    (fn? title)     (title req)
    (string? title) title
    (instance? clojure.lang.IDeref title) (str @title)
    :else           (str title)))

(defn resolve-head
  "Resolve extra <head> content.

   - If :head is a function, it is called with the Ring request (already enriched
     with Hyper context) and should return hiccup nodes.
   - Otherwise, :head is treated as hiccup and used as-is.

   Intended for injecting stylesheets/scripts (e.g. Tailwind output CSS) without
   Hyper needing to know anything about build tooling."
  [head req]
  (cond
    (fn? head)   (head req)
    (some? head) head
    :else        nil))
