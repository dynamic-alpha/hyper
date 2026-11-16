# Changelog

## 0.4.0 — 2026-11-16

### Upgrading

Nothing in the documented API was removed, but sessions now behave as
intended, which an app may notice:

- Sessions end. A session is removed once its last tab has been gone for the
  disconnect grace period (`:disconnect-grace-ms`, 3 minutes by default), so
  `h/session-cursor` data no longer outlives a person's tabs. Restore what
  must last longer, such as sign-in, from your own cookie (see
  `wrap-rehydrate-session` in the README), or raise `:disconnect-grace-ms`.
- Tabs belong to their session. A tab's actions, uploads, navigations and
  event stream are refused once its session cookie is cleared or replaced,
  and a session cookie the server does not hold gets a new session id. Sign
  people in with `rotate-session!` and out with `end-session!` (see Features).
- The session cookie is now `SameSite=Lax`, and `Secure` over HTTPS, and only
  page responses set it. A cross-site POST, such as an OAuth or SAML
  `form_post` callback, arrives without it; an app embedded in another site's
  iframe needs `:session {:cookie {:same-site :none}}`.
- Hyper's own endpoints refuse requests whose `Sec-Fetch-Site` header is
  `same-site` or `cross-site`.
- Render middleware always receives rendered hiccup (or a Ring response) from
  the handler. It no longer sees a page's render function or view on the
  page's first render.
- A write through `h/session-cursor` to a session that no longer exists is
  dropped, with a `:hyper.warn/session-cursor-write-dropped` warning.
- `hyper.server/wrap-hyper-context` is replaced by
  `hyper.ring.middleware/wrap-hyper`, which takes the handler first.

### Features

- Signing in and out - Actions can rotate or end their session with
  `effects/rotate-session!` and `effects/end-session!`, and routes (an OAuth
  or SSO callback, a magic link, a form post) with `hyper.ring`:
  `(-> (redirect "/") (h.ring/rotate-session! req))`. Rotating gives the
  session a new id and keeps its data; its open pages reconnect a second or
  two later with the new cookie and keep their tab state, while a page
  elsewhere holding the old id gets a fresh session. Ending removes the
  session and deletes its cookie, and its open pages reload into a fresh
  session. Both take effect once the action or response is done, so a
  sign-in form posted or a link clicked from a Hyper page lands on its
  redirect target.
  `hyper.test/test-action` reports them as `:session` (`:end` or `:rotate`)
  in its `:effects`.

- `hyper.ring` - `h.ring/session-id` gives the request's session id when the
  server holds that session, and `h.ring/endpoint` tells Hyper's own requests
  (`:action`, `:upload`, `:navigate`, `:events`) apart from page and app
  routes. A route outside `create-handler` uses `hyper.ring` by wrapping
  itself in `(-> route (hyper.ring.middleware/wrap-hyper app*))`, with the
  atom passed to `create-handler` as `:app-state`.

- Session cookie options - `create-handler` takes `:session {:cookie {...}}`,
  Ring cookie attributes merged over the session cookie's defaults,
  including `:name`: `(h/create-handler routes :session {:cookie {:name "sid"
  :same-site :strict}})`. Every page load sets the cookie again, so it
  expires 7 days after the last visit rather than the first.

- `h/run-in-tabs!` - Runs a function in every open tab, or in those where a
  predicate is true, with each tab's context, from an action or a background
  thread: `(h/run-in-tabs! app* #(= user-id (:id @(h/session-cursor :user)))
  effects/end-session!)`. Its effects act on each tab, including cookies.
  `effects/rotate-session!` throws in it: rotate in the person's own action
  or route, so only their browser gets the new id.

- Middleware redirects - A redirect that `:middleware` returns for an action,
  upload, back/forward navigation or event stream request sends the tab to
  its `Location` with a full page load, so middleware can refuse a request
  with `(ring.util.response/redirect "/sign-in")`. A redirect on a page load
  stays a redirect.

- Testing - `hyper.test/test-action` takes the signal values the client sends
  with an action: `(ht/test-action result "send" {:signals {:draft "Which
  funds?"}})`. A signal not given reads as its default value. Before, it gave
  its `"$name"` expression.

### Bug Fixes

- Sessions - A tab belongs to the session it was loaded in: its actions,
  uploads, navigations and event stream are refused once the session cookie
  is cleared or replaced, so signing a person out stops their open tabs.
  Before, any request naming the tab ran.

- Session and tab leaks - Sessions were never removed, and every request
  could leave a tab behind. Now a session is removed a grace period after
  its last tab, or after it was left with no tabs; a page whose event stream
  never connects (bots, link previews, `curl`) is removed after the grace
  period; a route returning a Ring map (an API route, a redirect) leaves no
  tab or new session behind; and a late session-cursor write or render no
  longer brings a removed session back.

- Reconnecting - A page whose tab the server no longer has (after a server
  restart, a grace period that ran out, or an ended session) now reloads
  itself when its event stream reconnects. Before, it stayed at
  "reconnecting" until reloaded by hand. Pages of one browser loaded at the
  same time without a session now end up in one session.

- Reaping - A tab whose event stream connects just as its grace period runs
  out is either kept, with its state, or reaped with its stream ended and the
  page reloaded. Before, the reaper could tear the tab down while its stream
  was attaching.

- Cross-site requests - Hyper's actions, uploads, navigations, event streams
  and cookie requests answer a request whose `Sec-Fetch-Site` is `same-site`
  or `cross-site` with a 403, before the app's `:middleware` runs. This needs
  no configuration behind a proxy. Page routes are not affected.

- Render middleware - A layout middleware that wraps the handler's result now
  works with form-2 and form-3 pages. Before, the page's render function or
  view ended up inside the hiccup and the render failed. A page's setup or
  `:mount` runs once, even when middleware calls the handler twice.

- Reactive regions - A region drawn again with different deps now watches its
  new deps and releases the old ones, in any order; before, it kept watching
  the deps it was first drawn with. A region's re-render removes the nested
  regions it no longer draws, a nested region without a `:key` keeps its id,
  a region listing the same source twice releases it, and a region whose deps
  change while its tab is disconnected keeps its watches until it
  reconnects.

- clj-kondo hooks - `h/reactive` and `h/async` accept deps given as a symbol
  or an expression, such as `(h/reactive deps ...)`. A map, keyword, string,
  number, boolean or nil in place of deps is still reported.

- `execute-script!` - A script for a tab whose connection dropped is sent
  when it reconnects. Before, it was dropped.

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
