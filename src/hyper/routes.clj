(ns ^:no-doc hyper.routes
  "Route-querying helpers shared between server and render.

   Pure functions for looking up route metadata by name, resolving
   titles/head content, reading the current routes (supporting
   live-reloading via Var indirection), and building route URLs."
  (:require [hyper.utils :as utils]
            [malli.transform :as mt]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as reitit.malli]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [taoensso.telemere :as t]))

(defn index-routes
  "Build a {route-name → route-data} map from a routes vector for O(1) lookups.
   Handles both flat and arbitrarily-nested reitit-style route trees by compiling
   a temporary router and reading back the flattened routes."
  [routes]
  (if (seq routes)
    (into {}
          (keep (fn [[_path data]]
                  (when-let [n (:name data)]
                    [n data])))
          (-> routes reitit/router reitit/routes))
    {}))

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
   - If :head is a Var, dereferences and calls the resulting function with req.
   - Otherwise, :head is treated as hiccup and used as-is.

   Intended for injecting stylesheets/scripts (e.g. Tailwind output CSS) without
   Hyper needing to know anything about build tooling."
  [head req]
  (cond
    (fn? head)   (head req)
    (var? head)  (resolve-head @head req)
    (some? head) head
    :else        nil))

(def default-malli-transformer
  "Malli transformer that decodes route params from URL strings and encodes
   them back. Extends malli's string transformer so a single value decodes
   into a one-element vector, sequence or set."
  (mt/transformer
    (let [single->coll {:enter (fn [v] (if (string? v) [v] v))}]
      {:decoders {:vector     single->coll
                  :sequential single->coll
                  :set        single->coll}})
    (mt/string-transformer)))

(defn- -param-provider
  "Returns a reitit transformation provider that applies transformer between
   reitit's extra-key stripping and default-value transformers."
  [transformer]
  ;; Mirrors reitit.coercion.malli's private -provider.
  (reify reitit.malli/TransformationProvider
    (-transformer [_ {:keys [strip-extra-keys default-values]}]
      (mt/transformer
        (when strip-extra-keys (mt/strip-extra-keys-transformer))
        transformer
        (when default-values
          (mt/default-value-transformer (if (map? default-values) default-values {})))))))

(defn- -param-coercion
  "Returns a reitit malli coercion that decodes path and query params with
   transformer."
  [transformer]
  (reitit.malli/create {:transformers (assoc-in (:transformers reitit.malli/default-options)
                                                [:string :default]
                                                (-param-provider transformer))}))

(defn- -param-coercers
  "Returns {:encoders {param-type f} :coercers coercers} for endpoint data
   with a :coercion and :path or :query :parameters, or nil. Encoders turn
   param values into URL strings; coercers decode them as a request would."
  [{:keys [coercion parameters]}]
  (let [params (not-empty (select-keys parameters [:path :query]))]
    (when (and coercion params)
      {:encoders (update-vals params
                              (fn [schema]
                                (let [encode (coercion/-query-string-coercer coercion schema)]
                                  (fn [value] (encode value nil)))))
       :coercers (coercion/request-coercers coercion params nil)})))

(defn- -compile-result
  "Compiles a route like reitit.ring/compile-result, attaching the GET
   endpoint's param coercers to its data under ::params."
  [route opts]
  (let [result (ring/compile-result route opts)]
    (if-let [coercers (some-> result :get :data -param-coercers)]
      (assoc-in result [:get :data ::params] coercers)
      result)))

(defn build-router
  "Compiles routes into a Ring router whose path and query params are coerced
   with :param-transformer (default `default-malli-transformer`), and that
   tab-route and route-url use to encode params into URLs.

   opts:
   - :param-transformer  Malli transformer for route params.
   - :middleware         Ring middleware applied to every route."
  [routes {:keys [param-transformer middleware]}]
  (ring/router routes
               {:conflicts nil
                :compile   -compile-result
                :data      {:coercion   (-param-coercion (or param-transformer default-malli-transformer))
                            :middleware (vec middleware)}}))

(defn- -route-param-coercers
  "Returns the param coercers of the named route's GET endpoint in router, or
   nil when it declares no :path or :query :parameters. Routers not built by
   build-router have them derived from the endpoint's coercion."
  [router route-name]
  (when-let [data (and router route-name
                       (some-> (reitit/match-by-name router route-name) :result :get :data))]
    (or (::params data) (-param-coercers data))))

(defn- -encode [encoders param-type params]
  (if-let [encode (get encoders param-type)]
    (encode params)
    params))

(defn- -decode
  "Decodes URL-encoded params with coercers, or logs a warning and returns nil
   when they don't match the route's :parameters."
  [route-name coercers path-params query-params]
  (try
    (coercion/coerce-request coercers {:path-params path-params :query-params query-params})
    (catch clojure.lang.ExceptionInfo e
      (if (= ::coercion/request-coercion (:type (ex-data e)))
        (t/log! {:level :warn
                 :id    :hyper.warn/invalid-route-params
                 :data  {:route-name   route-name
                         :in           (:in (ex-data e))
                         :path-params  path-params
                         :query-params query-params}
                 :msg   (str "Navigating to " route-name " with params that don't match its"
                             " :parameters schema. The URL will fail coercion when requested.")})
        (throw e)))))

(defn tab-route
  "Returns the tab route {:name :path :path-params :query-params} for the named
   route, with params as a request for its URL would decode them. Params that
   don't match the route's :parameters are kept as given, with a warning.
   Returns nil when router has no route with that name and params."
  [router route-name path-params query-params]
  (let [path-params                 (or path-params {})
        query-params                (or query-params {})
        {:keys [encoders coercers]} (-route-param-coercers router route-name)
        url-path-params             (-encode encoders :path path-params)]
    (when-let [path (:path (reitit/match-by-name router route-name url-path-params))]
      (let [decoded (when coercers
                      (-decode route-name coercers url-path-params
                               (-encode encoders :query query-params)))]
        {:name         route-name
         :path         path
         :path-params  (get decoded :path path-params)
         :query-params (get decoded :query query-params)}))))

(defn route-url
  "Returns the URL of a tab route, with its query params encoded per the
   route's :parameters in router."
  [router {route-name :name :keys [path query-params]}]
  (let [{:keys [encoders]} (-route-param-coercers router route-name)]
    (utils/build-url path (-encode encoders :query (or query-params {})))))
