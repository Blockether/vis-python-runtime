# vis-python-runtime guidance

Only what you would otherwise get wrong. This repository has one job: give Vis a
compact embedded CPython runtime. General engineering practice is assumed; a
contract that a docstring or a test can state lives there, not here.

## Hard rules

- **The boundary is our own C ABI, never the raw CPython C-API.** `native/vispython`
  exposes a small, stable, `extern "C"` surface (initialize, eval, call, handle
  release, error out-params) and the JVM side binds exactly that. Every function
  the JVM downcalls must be registered for `native-image`, so a bridge that
  reaches CPython's several-thousand-symbol API directly is unshippable.
  Same reason `clj-imaging` speaks to Rust through one flat C surface.
- **The bridge is JAVA (`src/java/com/blockether/vispython/`); Clojure is the API and
  nothing else.** Every downcall is an `invokeExact` against a signature the
  compiler knows, and the host upcall's target is a STATIC method found by name —
  the two shapes a GraalVM native image keeps. The same code as interop is a
  reflective invocation the image only keeps if somebody registered it, which a
  green JVM suite never catches: it fails in a user's terminal. So new bridge
  work, process pinning, the trust export and pip go in Java, and
  `src/clj/com/blockether/vis_python_runtime.clj` stays a skin: argument shapes,
  keyword maps. A Clojure function there that is longer than three lines of
  CODE is a sign the logic belongs on the other side, and
  `vis-python-runtime-test/skin-test` fails when the ratio slips or a body grows.
  Java is compiled by
  `clojure -T:build javac` into `target/classes`, which is on `:paths`.
- **No runtime dependencies in `deps.edn`, and no Clojars deployment.** This
  library is linked into someone else's GraalVM native image; every dependency
  declared here becomes reachable code in their binary. Consumers pin a release
  commit as a tools.deps git dependency; tags publish the JVM jar and all native
  archives only as GitHub Release assets.
- **Nothing links at build time.** The cdylib is resolved when first needed
  (`VIS_PYTHON_NATIVE_PATH`, then the per-platform classpath resource). Never
  `System/loadLibrary` against a fixed path or a bundled absolute location.
- **`resources/prebuilds/` is build output, git-ignored.** It reaches consumers
  as the GitHub release asset `vis-python-runtime-<platform>-<version>.tar.gz`
  built by `clojure -T:build platform-archive :platform <tag>`, NEVER as a maven
  artifact: the tree is tens of megabytes, past what Clojars accepts, and a jar
  carries neither symlinks nor execute bits an interpreter needs. Every tree also
  carries `libvisjail` beside `libvispython`: upstream bubblewrap is compiled into
  the Linux cdylib and the macOS cdylib enters the system Seatbelt policy. Consumers
  never search `PATH` or require a separately installed enforcer.
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
- **The sandbox Python lives here now** — `resources/vis-python/`, at the very
  resource paths Vis already reads, so the pin is the only change Vis makes.
  Shims do NOT: the sandbox runs a real CPython with pip, so `numpy` is numpy
  and the only Python Vis still ships is its own host-call doors, which call
  Vis' tools and could not live anywhere else.
- **ONE Python resource root: `resources/vis-python/`.** The sandbox's ambient
  runtime and this library's own guest half (`vis_runtime.py`) live there
  together, because two roots differing only by a hyphen bought nothing but the
  question of which was which. `resources/vis-python-runtime/` is NOT a root: it
  is the metadata namespace holding `SOURCES` (and the `VERSION` the build
  writes), named after the artifact so neither collides on a shared classpath.
  `native/vispython/` is build input a compiler reads, never a shipped source
  root, so guest Python never lives there.
- **Python is addressed from the MANIFEST, never by directory name.** There is no
  name lookup and no `user.dir` fallback: the host embedding this library carries
  a `resources/vis-python/` of its own, earlier on the classpath, and a lookup by
  name answers with the HOST's copies (measured). `Sources` resolves every entry
  relative to the `SOURCES` resource's own URL, which only this library ships, so
  an artifact carrying no manifest contributes no root at all.
- **ONE wire dialect: JSON, in both directions.** `host_call` carries a JSON
  envelope in, `vis_runtime.to_json` renders the value out, and `run` /
  `run-block` answer JSON TEXT the caller reads with the reader it already has.
  The bridge reads none of it; never reintroduce a second encoding.
- **The consumer contract is the existing sandbox.** Vis'
  `resources/vis-python/async_runtime.py`, its host-call doors and
  `packages/vis-agent/src/vis/__init__.py` define that boundary. If a door has to
  change, fix the bridge.
- **The filesystem boundary is C, never Python.** Confinement is an audit hook
  (PEP 578) installed before `Py_InitializeEx` over a policy the host sets
  through `vispython_confine`; guest code cannot see it, remove it or reach
  around it. A Python-side guard — a rebound `open`, a wrapper in a shim — is
  advice, not a boundary, and never becomes one. A host names only the SESSION's
  directories: the interpreter's own installation and its bytecode cache are the
  library's duty, added by `vispython_confine` itself, because a policy without
  them refuses the next cold import — a broken interpreter, not a sandbox.
  `confinement_test.clj` is what proves the difference.
- **Every policy here is the SANDBOX's; extension Python is trusted and does not
  belong under it.** Confinement, the thread cap and the pool are PROCESS state —
  `vispython_confine` replaces the policy for every session, and a session is a
  module namespace sharing the one interpreter — so a single process cannot hold
  a confined block and unconfined extension code at once. Extension Python gets
  its OWN process: its own interpreter, confinement lifted with two empty lists,
  no cap at all (`-1`), and its own diagnostics ring drained by whoever owns
  that process. So everything recorded here is about the sandbox — the pool, the
  cap, a refusal, a block's timing — and a host that files records from both
  tags them at the drain, because C cannot know which process it is.
- **Threads are C's too, and there is ONE pool.** `par` is `_vis_host.par`: the
  workers, the per-call quota and the hard cap on live threads are C state the
  host sets through `vispython_threads`, and the cap is checked from the SAME
  audit hook as confinement, so a thread a block starts for itself spends from
  the same budget. Never a pool in `vis_runtime` — a module global a block can
  resize or walk past — and never a pool per SESSION: sessions share the
  interpreter, so they share its GIL, and a pool each would multiply threads by
  sessions for no overlap at all. A `par` raises the first failure AT ONCE,
  while the siblings run on, because `gather` cancels children it has been told
  about. `threads_test.clj` is what proves all of it.
- **Diagnostics are RECORDED here and PULLED by the host — never pushed, never a
  guest value.** Events go into a bounded ring in C and the host takes them with
  `vispython_drain_log` as NDJSON, because the host it is linked into already
  owns a file, a rotation and a format for them, and because pushing would mean
  calling out of a pool worker — through one pinned JVM thread, possibly under
  the pool lock — which is the inversion the pool exists to avoid. The ring holds
  1024 records and overwrites its oldest, reporting the gap as `log_dropped`, so
  the Java half keeps taking: `Interpreter.drainTo` drains on its own thread —
  the ONE downcall that does not take the interpreter's, because it touches no
  PyObject and a block may hold that thread for minutes. Recording is `:off`
  until a host asks. An event carries counts, durations and names the HOST chose:
  a payload, a thunk's argument, a path a block asked for and the text of an
  exception are the block's, and this log ends up in files people paste into bug
  reports. `log_test.clj` is what proves it.

## Verifying

`clojure -T:build javac && clojure -M:test` is the whole suite (cognitect
test-runner, `clojure.test`). The javac is not optional: `target/classes` is on
`:paths`, so a stale or missing build is a `ClassNotFoundException`, and a
consumer taking this as a git dependency gets the same compile from
`:deps/prep-lib`. FFM downcalls are a restricted operation: the `:test` alias
already passes `--enable-native-access=ALL-UNNAMED`, so keep new aliases
consistent.

A green JVM suite is not a green native image. Once downcalls exist, their
registrations live in `resources/META-INF/native-image/com.blockether/vis-python-runtime/`
and travel inside the jar — never as a command-line flag in a consumer's build.

**The Python this library ships is addressed from its own manifest, never
looked up by name.** The TRACKED `resources/vis-python-runtime/SOURCES` lists
every shipped file, so a source git dependency is already self-describing before
a jar exists and a native image never has to discover a classpath directory.
`Sources` resolves each entry RELATIVE to that resource's URL. A lookup by name
(`getResource("vis-python/async_runtime.py")`) answers whichever classpath entry
comes first, and the host embedding this library carries a directory of the same
name — measured: from a Vis checkout the runtime imported VIS' copies and its own
never won. `target/classes` (which `:deps/prep-lib` owns) still carries no Python:
the source tree and the jar both take the manifest and files from `resources/`.
