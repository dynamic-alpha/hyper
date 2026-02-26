# Hyper

A reactive server-rendered web framework for Clojure built on
[Datastar](https://data-star.dev/) and
[Reitit](https://github.com/metosin/reitit).

Hyper renders your pages as hiccup on the server using
[Chassis](https://github.com/onionpancakes/chassis), then keeps them alive
over SSE — when state changes, the server re-renders and patches the DOM
automatically. No client-side framework, no JSON APIs, no JavaScript to write.

```clojure
(require '[hyper.core :as h])

(defn home-page [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:h1 "Count: " @count*]
     [:button {:data-on:click (h/action (swap! (h/tab-cursor :count) inc))}
      "Increment"]]))

(def routes
  [["/" {:name :home
         :title "Home"
         :get #'home-page}]])

(def handler (h/create-handler #'routes))
(def app (h/start! handler {:port 3000}))
```

## Cursors

Cursors are the primary way to read and write state in Hyper. They behave just
like atoms — use `deref`, `reset!`, `swap!`, and `add-watch` as you would with a
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
handler on the server, and returns a Datastar expression string that can be
bound to any event attribute.

```clojure
(defn counter [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:p "Count: " @count*]
     [:button {:data-on:click (h/action (swap! (h/tab-cursor :count) inc))} "+1"]
     [:button {:data-on:click (h/action (swap! (h/tab-cursor :count) dec))} "-1"]]))
```

When the button is clicked, Datastar POSTs to the server, Hyper executes the
action body, the cursor mutation triggers the watcher, and the tab re-renders
over SSE — all in one round trip with no page reload.

Actions have full access to the request context, so you can use any cursor type
inside them:

```clojure
;; Toggle a global theme that affects all tabs and sessions
[:button {:data-on:click (h/action
                           (let [theme* (h/global-cursor :theme "light")]
                             (swap! theme* #(if (= % "light") "dark" "light"))))}
 "Toggle theme"]

;; Update session state shared across tabs
[:button {:data-on:click (h/action
                           (reset! (h/session-cursor :user) {:name "Alice"}))}
 "Log in"]
```

Actions are scoped to the tab that rendered them and are cleaned up automatically
when the tab disconnects. The body can contain arbitrary Clojure — call
functions, hit databases, update multiple cursors — whatever happens, the
resulting state changes trigger re-renders for the appropriate tabs.

### Client params

Actions can capture client-side DOM values and transmit them to the server using special `$` symbols:

| Symbol | Captures | Use case |
|---|---|---|
| `$value` | `evt.target.value` | Input/select/textarea value |
| `$checked` | `evt.target.checked` | Checkbox/radio boolean state |
| `$key` | `evt.key` | Keyboard event key name |
| `$form-data` | All named form fields | Form submission as a map |

Example usage:

```clojure
;; Capture input value on change
[:input {:data-on:change (h/action (reset! (h/tab-cursor :query) $value))}]

;; React to specific keys
[:input {:data-on:keydown
         (h/action (when (= $key "Enter")
                     (search! $value)))}]

;; Checkbox toggle
[:input {:type "checkbox"
         :data-on:change (h/action (reset! (h/tab-cursor :dark?) $checked))}]

;; Full form submission
[:form {:data-on:submit__prevent (h/action (save-user! $form-data))}
 [:input {:name "email"}]
 [:input {:name "password" :type "password"}]
 [:button "Save"]]
```

When `$` symbols appear in the action body, the macro automatically generates a `fetch()` call instead of `@post()`, sending the extracted values as a JSON body. On the server, the action function receives these values bound to the corresponding `$` symbols.

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
full HTML as a [Datastar](https://data-star.dev/) fragment, and Datastar
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

### Global `:watches`

For sources that should trigger a re-render on **every** page, pass `:watches`
to `create-handler`. These are added to all page routes automatically — useful
for things like a top-level config atom or feature-flags that affect every view:

```clojure
(def feature-flags* (atom {:new-ui? false}))

(def handler
  (h/create-handler
    #'routes
    :watches [feature-flags*]))
```

Global watches are combined with any per-route `:watches` — global sources come
first, then route-specific ones.

## Assets and `<head>` injection

Hyper doesn’t ship with an asset pipeline (Tailwind, Vite, etc.), but it *does*
provide a couple small hooks so apps can easily:

- serve precompiled static assets (CSS/JS/images)
- inject tags into the HTML `<head>` (stylesheets, scripts, meta tags)

### Static assets

Enable static serving when you create your handler:

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-resources "public"))
```

Put files under `resources/public/` and they’ll be available by URL:

- `resources/public/app.css` → `GET /app.css`
- `resources/public/favicon.ico` → `GET /favicon.ico`

For filesystem-based serving (useful in dev):

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-dir "public"))
```

You can also pass multiple directories (first match wins):

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-dir ["public" "target/public"]))
```

### Injecting into `<head>`

Pass `:head` as either hiccup, or a function `(fn [req] ...)` that returns hiccup.
When `:head` is a function, it is re-evaluated on every SSE render cycle and the
full `<head>` is pushed to the client. This means dynamic stylesheets, meta tags,
and the `<title>` are all kept in sync reactively.

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-resources "public"
    :head [[:link {:rel "stylesheet" :href "/app.css"}]
           [:script {:defer true :src "/app.js"}] ]))
```

This is typically how you’d include your compiled Tailwind stylesheet.

## Testing

Tests are run with [Kaocha](https://github.com/lambdaisland/kaocha) via the
`:test` alias. There are two test suites: `:unit` for fast in-process tests and
`:e2e` for browser-based end-to-end tests.

```bash
# Run unit tests only
clojure -M:test --focus :unit

# Run E2E browser tests only
clojure -M:test --focus :e2e

# Run all tests
clojure -M:test
```

### Unit tests

Unit tests live in `test/hyper/` and cover cursors, actions, navigation, routing,
rendering, state management, and brotli compression. They run in-process with no
server or browser — just bind `*request*` and exercise the API directly.

### E2E tests

End-to-end tests use [Playwright](https://playwright.dev/) via the
[wally](https://github.com/pfeodrippe/wally) library to drive a real headless
Chromium browser against a running Hyper server. They're tagged with `^:e2e`
metadata so Kaocha can filter them.

The E2E suite covers:

- **Cursor isolation** — multiple browser contexts (separate sessions) and
  multiple tabs within a session verify that global, session, tab, and URL
  cursors propagate to exactly the right scope
- **Title live reload** — redefining the routes Var updates `document.title`
  via SSE without a page refresh
- **Content live reload** — redefining the routes Var with new inline handler
  functions hot-swaps the page content via SSE

