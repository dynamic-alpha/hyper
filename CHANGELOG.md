# Changelog

## 0.3.0 — 2026-11-13

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
- Datastar's fetch lifecycle events and `data-indicator` do not fire for
  Hyper's actions.
- A slow action now holds up the tab's later actions, including `h/navigate`
  links. Move long-running work to `h/spawn!`.

### Bug Fixes
- Keep server signal writes over an action's older copy
- Embed client params written with h/expr as expressions
- H/route reads the tab's live route
- Bind :hyper/route in actions and test-action
- Local signals introduced during partial re-renders
- Start a new worker for each spawn! called from an action
- Connection* initialization on load
- Live-reload of form 2 or 3 var render fns
- Server/start! :stop-timeout-ms option obeyed
- Keep parameters in sync from links to page loads
- H/expr tree-shaking for AOT-compiled code
- Compress the served squint core once
- Compile && and || in h/expr as JS operators
- Compile = and str in h/expr with squint core
- Keep h/expr intact through Datastar's statement splitter
- Return sendable parameter validation errors

### Build
- Rename the :expr-bundle alias to :tree-shake
- Use org.babashka/esbuild for tree shaking
- Add --no-tag to release

### CI
- Run the tree-shaking tests

### Documentation
- Describe how hyper sends and runs actions
- Remove confusing sentence
- Improve tree-shaking docs
- Give the served squint core size as sent
- Note where h/expr core names will come from

### Features
- Run actions in order against the render they were made on
- Take the Datastar module URL as :datastar-url
- Navigating?* signal for in-flight navigation
- Serve source maps for defc components behind :source-map?
- Support sequential reactive /async elements
- Take h/expr and defc core names from squint's compiler
- Share the page's squint core with defc components
- Version the served squint core
- Rename :tree-shake to :tree-shake? and document it
- Tree-shake the served squint core with esbuild
- Compile h/expr against a served squint core

### Refactoring
- Rename data-hyper-core to data-hyper-squint-version
- Pass every hyper_sc name to esbuild unfiltered
- Drop the println rewrite from h/expr
- Remove unreachable h/expr compiler macros

### Style
- Align deps.edn aliases

### Tests
- Wait for the spawned worker to start before disconnecting

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
