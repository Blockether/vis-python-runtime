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
   - Unknowns: none; the local verdict and all four CI platforms pass.
2. **Publish 0.5.1**
   - Rationale: consumers need immutable release assets matching the source pin.
   - Data: `VIS_PYTHON_VERSION`, GitHub release workflow and configured git identity.
   - Acceptance criteria: scoped commit on main, matching annotated tag, successful
     release workflow and JVM jar plus all four platform archives.
   - Unknowns: none; the release is public with five assets, and the downloaded
     macOS arm64 worker passes its 66 boundary assertions.
3. **Integrate into Vis**
   - Rationale: the runtime fix and host dead-worker recovery must work together.
   - Data: Vis `deps.edn`, `python/worker.clj`, `python/env.clj` and affected tests.
   - Acceptance criteria: release commit pinned; affected tests, lint and editing
     E2E pass; only scoped Vis changes committed and pushed.
   - Unknowns: repository-wide Vis CI is separate from the completed affected tests.
     No running gateway was restarted.

## Plan state

Completed. Runtime [0.5.1](https://github.com/Blockether/vis-python-runtime/releases/tag/v0.5.1)
is published from `23d20035e334e499fd3ea7273d74f3e6bc1fac0c`, with the JVM jar
and all four platform archives. Runtime CI run `34151000350` and release run
`34151526822` passed every job. The full local suite passed 162 tests / 768
assertions; formatting, lint and native archive validation also passed.

The published macOS arm64 worker reports 0.5.1 and passes 3 tests / 66 assertions.
Vis pins the release and includes dead-worker recovery in
`aef9b60f7acd28f7f858d59d4ab6f6be0685b829`, pushed to main. All 662 affected tests
and both editing E2E scenarios pass against the published pin, not a local/root
override. The asyncio wakeup uses a POSIX pipe without changing C network policy.

Unrelated Vis work was excluded. The local task stash is retained as a backup.
No product Vis release or running gateway restart was performed. `vtracer` remains
a known third-party limitation, explicitly documented in the release notes.
