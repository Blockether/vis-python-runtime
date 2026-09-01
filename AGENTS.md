# vis-python-runtime guidance

Only what you would otherwise get wrong. This repository has one job: give Vis a
Python runtime that costs tens of megabytes instead of the ~300 MB GraalPy adds
to the native image. General engineering practice is assumed; a contract that a
docstring or a test can state lives there, not here.

## Hard rules

- **The boundary is our own C ABI, never the raw CPython C-API.** `native/vis-python`
  exposes a small, stable, `extern "C"` surface (initialize, eval, call, handle
  release, error out-params) and the JVM side binds exactly that. Every function
  the JVM downcalls must be registered for `native-image`, so a bridge that
  reaches CPython's several-thousand-symbol API directly is unshippable.
  Same reason `clj-imaging` speaks to Rust through one flat C surface.
- **No runtime dependencies in `deps.edn`.** This library is linked into someone
  else's GraalVM native image; every dependency declared here becomes reachable
  code in their binary. Keep `:deps {}`.
- **Nothing links at build time.** The cdylib is resolved when first needed
  (`VIS_PYTHON_NATIVE_PATH`, then the per-platform classpath resource). Never
  `System/loadLibrary` against a fixed path or a bundled absolute location.
- **`resources/prebuilds/` is build output, git-ignored.** It reaches consumers
  only as `com.blockether/vis-python-runtime-native-<platform>` jars built by
  `clojure -T:build native-jar :platform <tag>`.
- **The repo-root `VIS_PYTHON_VERSION` file is the single version source**, the
  same convention as vis' `VIS_VERSION`: verbatim, no env override, no snapshot
  suffix. The build writes it into the jar as the NAMESPACED resource
  `vis-python-runtime/VERSION`, because a bare `VERSION` resource collides with
  other libraries on a shared classpath.
- **The consumer contract is the existing sandbox, unchanged.** Success is Vis'
  ~1.5 MB of shims in `resources/vis-shims/`, `resources/vis-python/async_runtime.py`
  and `packages/vis-agent/src/vis/__init__.py` running with no edit beyond the
  12 GraalPy-specific call sites already identified. If a shim has to change,
  the design is wrong — fix the bridge.

## Verifying

`clojure -M:test` (cognitect test-runner, `clojure.test`) is the whole suite.
FFM downcalls are a restricted operation: the `:test` alias already passes
`--enable-native-access=ALL-UNNAMED`, so keep new aliases consistent.

A green JVM suite is not a green native image. Once downcalls exist, their
registrations live in `resources/META-INF/native-image/com.blockether/vis-python-runtime/`
and travel inside the jar — never as a command-line flag in a consumer's build.
