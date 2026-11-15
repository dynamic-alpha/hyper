# Changelog

## Unreleased

### Bug Fixes

- Session leaks - Fixes an issue where sessions were never cleaned up. A session
  is now removed once its last tab has been gone for the disconnect grace period
  (`:disconnect-grace-ms`, 3 minutes by default). A note that this means
  `h/session-cursor` content will be lost when a person closes their last tab
  and comes back later. This was always intended but did not behave this way.

- Page loads - A page whose event stream never connects (bots, link previews,
  `curl`, JavaScript turned off) is now removed after the disconnect grace
  period, with its session when it was the last tab. Before, it was kept
  forever.

- Sessions - A tab belongs to the session it was loaded in. Its actions,
  uploads, back/forward navigations and event stream are refused with a 404
  once the `hyper-session` cookie is cleared or replaced, so signing a person
  out stops their open tabs.

- Render middleware - A layout middleware that wraps the handler's result
  now works with form-2 and form-3 pages. Before, the page's render function
  or view ended up inside the hiccup and the render failed. Middleware now
  always receives rendered hiccup (or a Ring response) from the handler,
  including on a page's first render.

- Reactive regions - A region drawn again with different deps now watches its
  new deps and releases the old ones. Before, it kept watching the deps it was
  first drawn with, so a nested `h/reactive` whose deps depend on its parent
  missed changes. A region's re-render now also removes the nested regions it
  no longer draws, and a nested region without a `:key` keeps its id when its
  parent re-renders on its own.

## 0.3.0 — 2026-11-14

### Breaking Changes

#### `:datastar-script` is replaced by `:datastar-url`

*BREAKING*: `create-handler` no longer takes hiccup for the Datastar script
tag. Pass the URL of Datastar's ES module bundle instead. Hyper adds the tag
itself and imports Datastar from the same URL to register its action.

```clojure
;; before
(h/create-handler routes
                  :datastar-script [:script {:type "module" :src "/js/datastar.js"}])

;; after
(h/create-handler routes
                  :datastar-url "/js/datastar.js")
```

- Passing `:datastar-script` now throws when the handler is created.
- The tag can no longer be suppressed with `nil`.
- `hyper.server/default-datastar-script` is replaced by
  `hyper.server/default-datastar-url`.

If you did not set `:datastar-script`, nothing changes.

#### Actions are sent through Hyper's own queue

*BREAKING*: actions no longer use one Datastar `@post` each. They go through
`@hyperAction`, which posts a tab's actions to `/hyper/actions?tab-id=…`. The
server runs them one at a time, in the order they were made, each against the
render it was made on. See [How actions run](Readme.md#how-actions-run).

- A single action can no longer be posted to `/hyper/actions?action-id=…`.
- `window.hyper.encodeClientParams` and `hyper.actions/execute-action!` are
  removed.
- A slow action now holds up the tab's later actions. Move long-running work to `h/spawn!`.

### Features

- Ordered actions - Actions now run one at a time, in the order they were
  made, making them more consistent with user expectations and the state they
  operate against.
- Expanded Squint - Better support for Clojure expressions in `h/expr`, with a
  bundle served by Hyper. Use `:tree-shake?` for smaller bundles that only ship
  the code your components and expressions depend on.
- Custom Datastar URL support - Point Hyper at an arbitrary endpoint via
  `:datastar-url`.
- Easier component debugging - `:source-map?` option for `create-handler` to
  generate source maps.
- `navigating?*` signal to know when a route is changing.
- Support for multiple elements being returned from `h/reactive` and
  `h/async`.
- `:param-transformer` - Option for `create-handler` that controls how route
  parameters are read from URLs and written back into links. Defaults to
  Malli's string transformer.

### Bug Fixes

- Route params - Parameters now stay the same across links, page loads and
  back/forward navigation.
- Signals - A value set by the server is no longer overwritten by an action
  made just before it.
- `h/route` - Works inside actions, and reflects route changes made earlier in
  the same action.
- Local signals - Signals declared during a partial re-render now work without
  waiting for a full render.
- `h/spawn!` - Calling it from an action now starts a new worker every time.
- Client params - Params written with multi-statement `h/expr` forms no longer
  cause a syntax error.
- `h/expr` - `=`, `str` and boolean operators now compile correctly.
- Live reload - Now picks up form-2 and form-3 render functions.
- `server/start!` - The `:stop-timeout-ms` option is now respected.
- `connection*` - Now set correctly on page load.
- Parameter validation - Invalid route parameters now return a readable error.

## 0.2.2 — 2026-07-14

### Bug Fixes
- Preserve explicit nil in cursor default init

### Build
- Gate Clojars release on CI

## 0.2.1 — 2026-07-10

### Bug Fixes

- Fix missing request context bindings in teardown and partial renders

## 0.2.0 — 2026-07-10

### Bug Fixes
- Don't drop cursor writes made during flush

### Refactoring

#### Consolidating into hyper.core

*DEPRECATION*: You should no longer require `hyper.protocols` or `hyper.client-params` directly.

Maps the following into `hyper.core` directly:

- `action-name` — wraps `context/*action-name*`
- `route` — wraps `(:hyper/route *request*)`
- `Watchable` — re-export of `hyper.protocols/Watchable`
- `client-param` — re-export of `hyper.client-params/client-param` multimethod

## 0.1.1 — 2026-07-09

### Build
- Add bb release task and drop git-cliff header

### Documentation
- Note pre-1.0 breaking-change policy

### Deps
- Pin squint via maven

## 0.1.0 — 2026-07-09

Initial public release.
