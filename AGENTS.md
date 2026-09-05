# vis-python-runtime guidance

This repository owns embedded CPython and sandbox runtime machinery; Vis owns host tools and UI.
Keep repository-specific constraints here and implementation details with their code/tests.
Read only the owners relevant to the task. General engineering practice is assumed.

## Scope and completion

Continue in-scope local implementation and verification without repeated approval. Fix failures
caused by the change and report any remaining blockers. Documentation changes need content/link/diff
checks, not a native rebuild. Runtime changes need affected tests; JVM and native-image results are
separate verdicts. Do not report a build as verified runtime behavior.

Commit, push, publish or mutate external systems only when requested. Confirm destructive actions
and history rewrites; do not restart a consumer's running gateway as an incidental test step.
Use the configured human git identity, never `root`. Commit subjects use `type(scope): summary`
under 72 characters and a `Vis-Session: <bare-uuid>` trailer. Never bypass hooks.
No profanity, private deployment details or credentials in tracked content or commit messages.

## Ownership and boundaries

| Area | Contract |
|---|---|
| `native/vispython/` | Owns the small `extern "C"` ABI, interpreter, confinement, thread pool and diagnostics. The JVM binds our ABI, never the raw CPython C API. This directory is build input, not shipped guest source. |
| `src/java/com/blockether/vispython/` | Owns bridge logic, process pinning, trust export and pip. Downcalls use typed `invokeExact`; host upcalls target static methods. Reflective interop can pass on the JVM and fail in native images. |
| `src/clj/com/blockether/vis_python_runtime.clj` | Thin public API for argument shapes and keyword maps, not a second bridge. `skin-test` enforces that boundary. |
| `resources/vis-python/` | The sole Python runtime source root, including `vis_runtime.py`. Import modules; do not embed guest code as interpolated strings. Vis host-call shims do not belong here. |
| `resources/vis-python-runtime/` | Metadata namespace: tracked `SOURCES` plus build-generated `VERSION`, not another Python source root. `Sources` resolves entries relative to the manifest URL, never by classpath directory name or `user.dir`; a host can have a same-named directory. |
| `resources/prebuilds/` | Ignored build output, distributed as platform archives. Includes `libvisjail` alongside `libvispython`; consumers do not search PATH for an enforcer. |

One interpreter per process; sessions have separate module globals but share imports and the GIL.
Do not introduce sub-interpreters or a pool per session. Sandbox policy is process-wide: trusted
extension Python needs a separate process, not an unconfined session sharing a sandbox interpreter.

The filesystem boundary and thread limits belong in C, not replaceable Python wrappers. The host
supplies session directories; the library adds interpreter and bytecode-cache access. Preserve
confinement and thread accounting together; see `confinement_test.clj` and `threads_test.clj`.

The wire dialect is JSON in both directions. The bridge transports it without a second encoding.
Preserve the existing host-call and bootstrap contract with Vis' `packages/vis-agent/src/vis/__init__.py`;
changes across that boundary need consumer-side verification, not duplicated runtime files in Vis.

Diagnostics are recorded in a bounded C ring and pulled by the host, never pushed from workers.
They are off until requested and contain host-chosen names/counts/durations, not guest payloads,
paths or exception text. Draining must remain possible while a block holds the interpreter thread.
`log_test.clj` owns the regression contract.

## Build and verification traps

- Run `clojure -T:build javac` before `clojure -M:test`. `target/classes` is on `:paths`, so stale Java
  can give misleading results. Consumers get compilation through `:deps/prep-lib`.
- This repo uses cognitect test-runner and **`clojure.test`**, unlike Vis' Lazytest suite.
- The test alias enables `--enable-native-access=ALL-UNNAMED`; new FFM aliases need it too.
- Every JVM downcall needs native-image registration inside
  `resources/META-INF/native-image/com.blockether/vis-python-runtime/`, not consumer CLI flags.
- The native library loads on first use from `VIS_PYTHON_NATIVE_PATH` or the platform classpath
  resource. No build-time linking or fixed-path `System/loadLibrary`.
- Clojure uses `.zprint.edn`, one blank line between top-level forms and one final newline.
  Preserve comments attached to forms. Use the existing test and lint setup rather than introducing
  another framework. This repository has no local skills; do not copy Vis' UI skill here.

## Distribution (only when requested)

No runtime dependencies in `deps.edn` and no Clojars deployment: consumers link this library into
native images and pin release commits as tools.deps git dependencies. GitHub Release assets carry
the JVM jar and native platform archives. `clojure -T:build platform-archive :platform <tag>` produces
the latter; a jar cannot preserve the interpreter's symlinks and executable bits.

`VIS_PYTHON_VERSION` is the single version source, without environment overrides or snapshot suffixes.
The build packages it as `vis-python-runtime/VERSION`; a bare `VERSION` collides on shared classpaths.
