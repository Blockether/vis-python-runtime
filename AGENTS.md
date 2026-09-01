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
- **Python is IMPORTED, never interpolated into a string.** The host puts source
  roots on `sys.path` and imports `vis_runtime`; CPython owns compilation,
  `__pycache__` and tracebacks that name a file and a line. A multi-thousand-line
  runtime handed to `exec` as one string throws all three away.
- **One interpreter per process, many SESSIONS.** `initialize!` is process-wide
  and idempotent; a session is a module namespace created on demand, so sessions
  keep separate globals while sharing imported modules — the second session's
  runtime install costs nothing. Sub-interpreters are not the mechanism.
- **The sandbox Python lives here now** — `resources/vis-python/` and
  `resources/vis-shims/`, at the very resource paths Vis already reads, so the
  pin is the only change Vis makes. Until that pin exists Vis still carries its
  own copy and `sandbox-parity-test` compares every file byte for byte; edit a
  file on one side only and the suite fails. Delete that test with the last copy.
- **The consumer contract is the existing sandbox, unchanged.** Success is Vis'
  ~1.5 MB of shims in `resources/vis-shims/`, `resources/vis-python/async_runtime.py`
  and `packages/vis-agent/src/vis/__init__.py` running with no edit beyond the
  12 GraalPy-specific call sites already identified. If a shim has to change,
  the design is wrong — fix the bridge.
- **The filesystem boundary is C, never Python.** Confinement is an audit hook
  (PEP 578) installed before `Py_InitializeEx` over a policy the host sets
  through `vis_python_confine`; guest code cannot see it, remove it or reach
  around it. A Python-side guard — a rebound `open`, a wrapper in a shim — is
  advice, not a boundary, and never becomes one. `confinement_test.clj` is what
  proves the difference.

## Verifying

`clojure -M:test` (cognitect test-runner, `clojure.test`) is the whole suite.
FFM downcalls are a restricted operation: the `:test` alias already passes
`--enable-native-access=ALL-UNNAMED`, so keep new aliases consistent.

A green JVM suite is not a green native image. Once downcalls exist, their
registrations live in `resources/META-INF/native-image/com.blockether/vis-python-runtime/`
and travel inside the jar — never as a command-line flag in a consumer's build.
