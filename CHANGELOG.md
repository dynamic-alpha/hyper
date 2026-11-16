# Changelog

## Unreleased

### Features

- Testing - `hyper.test/test-action` takes the signal values the client sends
  with an action, as `:signals` next to the client params:
  `(ht/test-action result "send" {:signals {:draft "Which funds?"}})`. A
  signal read in the action gives its value from `:signals`, or its default
  value. Before, it gave its `"$name"` expression.

- Sessions - `create-handler` takes `:session {:cookie {...}}`, Ring cookie
  attributes merged over the session cookie's defaults, including `:name` to
  rename it: `(h/create-handler routes :session {:cookie {:name "sid"
  :same-site :strict}})`. Every page load now sets the cookie again, so it
  expires 7 days after the last visit rather than the first. Only pages set
  it: a route returning a Ring map no longer does. The cookie is now set with
  `SameSite=Lax` (which Chrome already assumed for a cookie without it), and
  with `Secure` when the request came over HTTPS (its scheme, or
  `X-Forwarded-Proto: https`). Pass `:same-site :none` for an app embedded in
  another site's iframe.

- Ring middleware - A redirect that `:middleware` returns for an action,
  upload, back/forward navigation or event stream request now sends the tab to
  its `Location` with a full page load, so middleware such as
  `wrap-require-sign-in` can refuse a request with
  `(ring.util.response/redirect "/sign-in")`. Before, an action only logged
  the response, and the browser followed the redirect out of sight. A redirect
  on a page load stays a redirect, and other responses pass through unchanged.

- Sessions - Actions can end or rotate their session. `effects/end-session!`
  reloads every open tab of the session into a fresh session, removes the
  session's data and deletes the session cookie, for signing out.
  `effects/rotate-session!` gives the session a new id, keeping its data, and
  sets the cookie to it, for signing in. Both take effect once the action has
  run, and `hyper.test/test-action` reports them as `:session` (`:end` or
  `:rotate`) in its `:effects`. A note that rotating reloads every other open
  tab of the session: in the same browser they come back with the session's
  data but lose their tab state, and a tab elsewhere holding the old id gets a
  fresh session. A note too that a page loaded with a session cookie the
  server does not know now gets a new session id rather than taking on the
  cookie's.

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
  parent re-renders on its own. Deps are compared regardless of order, and a
  region whose deps change while its tab is disconnected keeps its watches
  until the tab reconnects.

- Reactive regions - A region listing the same source twice in its deps now
  releases it when the region is removed. Before, the source was never
  disposed.

- clj-kondo hooks - `h/reactive` and `h/async` accept deps given as a symbol
  or an expression, such as `(h/reactive deps ...)`. Before, the hooks
  reported an error unless deps was a literal vector. A map, keyword, string,
  number, boolean or nil in place of deps is still reported.

- Ring responses - A GET route whose handler or render middleware returns a
  Ring map (an API route, a redirect) no longer leaves a tab behind, nor a
  session that request created. Before, every such request added a tab and,
  without a session cookie, a session, kept until the disconnect grace period
  ran out. A page load is unchanged, as is a tab that existed before the
  request.

- Reconnecting - A page whose tab the server no longer has now reloads itself
  when its event stream reconnects, into a new tab. This happens after a
  server restart, once the disconnect grace period has run out, or when the
  tab's session was replaced while it was away. Before, the page stayed at
  "reconnecting" until it was reloaded by hand. Pages of one browser loaded at
  the same time without a session the server holds each start their own
  session, while the browser keeps only the last session cookie; a page whose
  event stream has not yet connected now joins the session of the cookie its
  stream arrives with, rather than being refused.

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
