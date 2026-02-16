# Hyper

A reactive server-rendered web framework for Clojure built on
[Datastar](https://data-star.dev/) and
[Reitit](https://github.com/metosin/reitit).

Hyper renders your pages as plain hiccup on the server, then keeps them alive
over SSE — when state changes, the server re-renders and patches the DOM
automatically. No client-side framework, no JSON APIs, no JavaScript to write.

```clojure
(require '[hyper.core :as h])

(defn home-page [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:h1 "Count: " @count*]
     [:button (h/action (swap! (h/tab-cursor :count) inc))
      "Increment"]]))

(def routes
  [["/" {:name :home
         :title "Home"
         :get #'home-page}]])

(def handler (h/create-handler #'routes))
(def server (h/start! handler {:port 3000}))
```

## Cursors

Cursors are the primary way to read and write state in Hyper. They implement
`IAtom` — use `deref`, `reset!`, `swap!`, and `add-watch` as you would with a
normal atom.

Each cursor type scopes state differently:

```clojure
(h/global-cursor :theme "light")       ;; shared across everything
(h/session-cursor :user)               ;; scoped to browser session
(h/tab-cursor :count 0)                ;; scoped to a single tab
(h/path-cursor :page 1)               ;; backed by URL query params
```

`path` can be a keyword or a vector for nested access: `(h/tab-cursor [:form :email])`.
The optional second argument sets a default value when the path is nil.

| Cursor | Shared across tabs? | Shared across sessions? | Survives page reload? |
|---|---|---|---|
| `global-cursor` | ✅ | ✅ | ✅ (global, in-memory) |
| `session-cursor` | ✅ | No | ✅  (session length) |
| `tab-cursor` | No | No | No (in-memory) |
| `path-cursor` | No | No | ✅ (URL query params) |

Mutating any cursor triggers a re-render for every tab that depends on that
scope — global changes re-render all tabs, session changes re-render tabs in
that session, and so on.

## Actions

Actions are server-side functions triggered by user interactions. The `action`
macro captures the current session and tab context at render time, registers a
handler on the server, and returns Datastar attributes that POST to it on click.

```clojure
(defn counter [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:p "Count: " @count*]
     [:button (h/action (swap! (h/tab-cursor :count) inc)) "+1"]
     [:button (h/action (swap! (h/tab-cursor :count) dec)) "-1"]]))
```

When the button is clicked, Datastar POSTs to the server, Hyper executes the
action body, the cursor mutation triggers the watcher, and the tab re-renders
over SSE — all in one round trip with no page reload.

Actions have full access to the request context, so you can use any cursor type
inside them:

```clojure
;; Toggle a global theme that affects all tabs and sessions
[:button (h/action
           (let [theme* (h/global-cursor :theme "light")]
             (swap! theme* #(if (= % "light") "dark" "light"))))
 "Toggle theme"]

;; Update session state shared across tabs
[:button (h/action
           (reset! (h/session-cursor :user) {:name "Alice"}))
 "Log in"]
```

Actions are scoped to the tab that rendered them and are cleaned up automatically
when the tab disconnects. The body can contain arbitrary Clojure — call
functions, hit databases, update multiple cursors — whatever happens, the
resulting state changes trigger re-renders for the appropriate tabs.

## Navigation

Hyper uses [Reitit](https://github.com/metosin/reitit) for routing. Routes are
plain vectors with `:name`, `:get`, and optional metadata like `:title`:

```clojure
(def routes
  [["/"           {:name :home
                   :title "Home"
                   :get #'home-page}]
   ["/about"      {:name :about
                   :title "About"
                   :get #'about-page}]
   ["/user/:id"   {:name :user
                   :title (fn [req] (str "User " (get-in req [:hyper/route-match :path-params :id])))
                   :get #'user-page}]])
```

Use `navigate` to create SPA links. It returns attributes for an `<a>` tag —
click navigates via Datastar + pushState, right-click / cmd-click opens in a new
tab via the `:href`:

```clojure
[:a (h/navigate :home) "Home"]
[:a (h/navigate :user {:id "42"}) "View User"]
[:a (h/navigate :search {} {:q "clojure"}) "Search"]
```

The `:title` metadata is included in the browser history entry so that
back/forward navigation shows meaningful titles. Titles can be static strings,
functions of the request, or deref-able values like cursors.

Pass routes as a Var (`#'routes`) to `create-handler` for live-reloading during
development — route changes are picked up on the next request without restarting
the server and any connected tabs will automatically re-render.

## Watches

Under the hood, Hyper maintains a persistent SSE connection per tab. When state
changes, the server re-renders your page function, diffs nothing — it sends the
full hiccup as a [Datastar](https://data-star.dev/) fragment, and Datastar
morphs the DOM. Cursors changing state trigger this automatically, but for
external sources you need to tell Hyper what to watch.

### `watch!`

Call `watch!` from your render function to observe any external source. When it
changes, Hyper re-renders and pushes an update to the client:

```clojure
(def db-results* (atom []))

(defn dashboard [req]
  (h/watch! db-results*)
  [:div
   [:h1 "Results"]
   [:ul (for [r @db-results*]
          [:li (:name r)])]])
```

`watch!` is idempotent — safe to call on every render. Watches are automatically
cleaned up when the tab disconnects.

### The `Watchable` protocol

By default, `watch!` works with anything that implements `clojure.lang.IRef`
(atoms, refs, agents, vars). For custom external sources, extend
`hyper.protocols/Watchable`:

```clojure
(require '[hyper.protocols :as proto])

(extend-protocol proto/Watchable
  my.db/QueryResult
  (-add-watch [this key callback]
    ;; callback is (fn [old-val new-val])
    ;; Set up your change listener, call callback when data changes
    )
  (-remove-watch [this key]
    ;; Tear down the listener
    ))
```

### Route-level `:watches`

For sources that are tied to a specific page, declare them directly on the route
with `:watches`. Hyper sets them up when a tab navigates to that route and tears
them down when it navigates away:

```clojure
(def live-orders* (atom []))

(def routes
  [["/" {:name    :dashboard
         :title   "Dashboard"
         :get     #'dashboard-page
         :watches [live-orders*]}]])
```

When the `:get` handler is a Var (e.g. `#'dashboard-page`), it's automatically
added to the route's watches. This means redefining the function at the REPL
triggers an instant live reload for all connected tabs — no page refresh needed.

