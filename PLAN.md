# Runtime 0.5.1 and Vis integration

*Publish the repaired async worker without claiming third-party binary compatibility.*

## Context

The coroutine trampoline lacked the real asyncio Task and selector loop required
by library awaitables. Vis also reused dead worker keys without restoring tools.
The local checkout was behind the release already pinned by Vis; fast-forwarding
to 0.5.0 supplies the existing `Trust` class instead of duplicating its API.

The optional `vtracer==0.6.15` wheel crashed on standalone bundled CPython 3.14.7
as well as in the worker. The diagnostic test added during investigation is not
part of this release's compatibility contract; the binary remains incompatible.
No ABI workaround, package downgrade, confinement relaxation or automatic replay
is included. Crash recovery is covered by terminating a test-owned worker in Vis;
real asyncio Futures, HTTPX/AnyIO and supported compiled wheels remain covered.

## Phases

1. **Reconcile and verify the runtime**
   - Rationale: release current APIs with the async correction, not an old checkout.
   - Data: `async_runtime.py`, asyncio/distribution/network tests and shared worker exercise.
   - Acceptance criteria: formatting/lint, full JVM suite and freshly built native
     worker pass; the platform archive passes the existing validation script.
   - Unknowns: other release platforms; local JVM/native tests and archive validation pass.
2. **Publish 0.5.1**
   - Rationale: consumers need immutable release assets matching the source pin.
   - Data: `VIS_PYTHON_VERSION`, GitHub release workflow and configured git identity.
   - Acceptance criteria: scoped commit on main, matching annotated tag, successful
     release workflow and JVM jar plus all four platform archives.
   - Unknowns: CI and release asset verification.
3. **Integrate into Vis**
   - Rationale: the runtime fix and host dead-worker recovery must work together.
   - Data: Vis `deps.edn`, `python/worker.clj`, `python/env.clj` and affected tests.
   - Acceptance criteria: release commit pinned; affected tests, lint and editing
     E2E pass; only scoped Vis changes committed and pushed.
   - Unknowns: published-pin and E2E verdicts; all 662 local consumer tests pass.
     No running gateway restart is authorized.

## Plan state

Reconciled with upstream 0.5.0 and selected 0.5.1. The full runtime suite passes
162 tests / 768 assertions, including a rebuilt GraalVM CE 25.3.4.1 worker and
network-off async callbacks. Formatting, lint and the local platform archive pass.
Consumer tests exposed the event loop's socket-based wakeup; a POSIX pipe fixes
that without changing C network policy. All 662 combined Vis consumer tests pass.
Editing E2E and cross-platform CI are pending before tag publication. The task
stash remains as a local backup; unrelated Vis changes are excluded. `vtracer`
remains a known limitation.
