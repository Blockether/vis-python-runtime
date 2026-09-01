# Replace GraalPy with an embedded CPython

*Three hundred megabytes of Truffle for a language we only ever exec.*

## Context

Vis ships one native binary. Sandbox Python runs in GraalPy, and that engine is
the single largest thing in the image: measured in `~/.m2`, `python-language`
25.1.3 is 95.1 MB of jar, `icu4j-shadowed` 18.6 MB, `truffle-api` 16.8 MB,
`regex` 3.9 MB — roughly 135 MB of input that SVM expands 2–2.5x, which is the
~300 MB the binary carries. The stdlib is already outside the image
(`resources/vis-docs/graalpython.md` documents `-H:-IncludeLanguageResources
-H:+CopyLanguageResources`), so that optimisation is spent.

Size is not the only cost. GraalPy does not refcount: dropping a wrapper runs no
`__del__` and the host handle leaks for the life of the JVM, which is why
`resources/vis-python/async_runtime.py` has to carry a hand-maintained
`__vis_handle_kind__` / `__vis_own__` registry.

The engine's price is measured in `bench/pre-cpython.md`, not estimated: the
binary is 612 MiB, a Python session costs ~270-320 MB RSS and 0.6-1.1 s of boot
before user code runs, while the whole engine without Python answers in 0.01 s
at 27 MB. Shims are not the cost — `pass` and `import numpy, pandas` differ by
~0.3 s and ~27 MB.

Coupling to the engine is small but wider than a first scan suggested: `import
java`, `__graalpython__`, `polyglot`, `GraalPy` across `resources/vis-shims/`
and `resources/vis-python/` is 35 hits in 11 files, and most of them are
WORKAROUNDS that CPython deletes rather than ports — `hard_link.py` (GraalPy
loses the destination), `process_redirect.py` (a file redirect degrades to
INHERIT), `__vis_check_compile_traps__` (ordinary SyntaxErrors that arrive as
uncatchable host faults), and the flush/ownership machinery that exists only
because nothing refcounts. The ~1.5 MB of shims is portable as written.

Alternatives considered:

- **Tune the GraalPy image** (`-Os`, interpreter-only Truffle runtime). Buys
  20–30%, not 80%, and interpreter-only slows data-processing shims.
- **RustPython.** ~25–35 MB, but no C-API, no JVM interop, no JIT, and stdlib
  gaps at the edges — every host binding would be rewritten anyway.
- **Fork CPython.** Nothing we need requires touching CPython's sources, and a
  fork means owning every CVE and every rebase forever.
- **Sidecar binary downloaded on demand.** Solves download size only; the
  runtime cost and the handle registry stay.

## Phase 1 — Go/no-go: FFM downcalls into libpython under native-image

Rationale: this is the only assumption that can kill the plan, and it must fail
cheaply. Reflection in v0.1.24 passed the suite and the build and died in a
user's terminal; Panama downcalls are the same class of failure.

Data: GraalVM CE 25.1.3 (pinned in Vis' `.graalvm-version`), a vendored CPython,
a C shim exposing `initialize`, `eval_string`, `finalize`.

Acceptance criteria: a `native-image`-built executable runs `import ast` and
returns a value, with the downcall registrations living inside the jar under
`resources/META-INF/native-image/com.blockether/vis-python-runtime/`.

Unknowns: whether SVM's FFM support on 25.1.3 needs a stub or upcalls for
CPython's callbacks.

## Phase 2 — The C ABI

Rationale: the JVM must never see CPython's raw API; one flat surface keeps the
reachability metadata small and the bridge auditable.

Data: the calls Vis actually makes — exec a module, call a callable, convert
scalars and bytes, hold and release a handle, read an error.

Acceptance criteria: every exported symbol documented beside its declaration,
GIL ownership stated per call, and a test proving a released handle is freed
(the exact leak GraalPy could not express).

Unknowns: none left. The ownership model for host callables is settled — one
upcall stub for the process behind an atom, text in and text out, and a reply
too big for the buffer waits on the JVM side so the retry never runs the tool
twice (`bind-host!`, `install-tool!`, `host_test.clj`).

## Phase 3 — Run the existing sandbox unchanged

Rationale: this is what makes the swap a runtime replacement instead of a
rewrite of Vis' Python surface.

Data: `packages/vis-agent/src/vis/__init__.py`, `resources/vis-python/`, all of
`resources/vis-shims/`, and Vis' own `run_tests({"language": "python"})`.

Acceptance criteria: that suite passes with no shim edited, the GraalPy-specific
call sites resolve through the already-present non-GraalPy branches, and
`bench/run.py` against the new engine beats every target row in
`bench/pre-cpython.md`.

Unknowns: how much of `async_runtime.py`'s ownership registry, the deterministic
flush pass, `hard_link.py` and `process_redirect.py` can be deleted outright
once refcounting and a real POSIX layer are underneath.

## Phase 4 — Confinement without Truffle

Rationale: GraalPy confined the guest by handing Truffle a `FileSystem` the
guest could not reach (Vis' `sandbox-fs.clj`). CPython opens files with the
process's own credentials, so removing GraalPy without a replacement would not
shrink the binary — it would delete the sandbox.

Data: PEP 578 audit hooks. `PySys_AddAuditHook` runs before the interpreter
starts, cannot be removed, and is invisible to Python; the policy is C state the
host sets over the ABI (`vis_python_confine`), so a block that rebinds `open`,
reaches through `os` or imports its way to a descriptor still arrives at it.

Acceptance criteria: a block reads inside a root and is refused outside it, a
`..` escape and a symlink out of a root are refused, a write into a read-only
root is refused, the interpreter keeps importing its own stdlib while confined,
and a confined block spawns no process and reaches no native symbol while
`import ctypes` and an extension module still work (`confinement_test.clj`).

Unknowns: the network is still guarded in Python (`network_guard.py`), where
`socket.connect` is an audit event and could move here too. `ctypes.string_at`
reads process memory from an address and is left alone, because an address is
what a confined block has no way to obtain. Windows names paths differently and
the canonicalizer is POSIX.

## Phase 5 — Ship it

Rationale: per-platform prebuilds and one pin, exactly like `clj-imaging`.

Data: `build.clj` native-jar targets, Vis' root `deps.edn` pin,
`audit/README.md` regenerated by `bb scripts/gen-audit.bb`. The platform
artifact has to carry the VENDORED interpreter tree, not just the cdylib, and a
jar holds neither symlinks nor permission bits — so the tree ships as an archive
resource extracted once, the way the cdylib is extracted today.

Acceptance criteria: Vis' binary builds and runs with GraalPy removed, and the
measured size drop is recorded here.

Unknowns: Windows vendoring (the upstream build is MSVC); whether one artifact
per platform is enough for glibc/musl.

## Phase 6 — Real packages, redistributed

Rationale: the shims are pure-Python REIMPLEMENTATIONS because GraalPy could not
load a C extension. CPython can, so `numpy`, `pandas`, `pillow`, `bs4` and the
rest stop being ours to maintain, and a block gets the actual library instead of
a subset that resembles one. This is also what answers the bytes question the
host door raised: nothing has to marshal a raster or a frame across the boundary
once the library that owns it lives inside the interpreter.

Data: two tiers, and the line between them is MEASURED. The first estimate here
was wrong and is kept as the reason the rule changed: "everything that has a shim
is a promise, so it ships" was projected at 65-80 MB per platform from wheel
sizes, and the actual install is 315 MB on darwin-arm64 — the whole GraalPy cost
back again, because a wheel on disk is not its download (pandas 72 MB, numpy 35,
matplotlib 33, lxml 20, fontTools 20, PIL 15, and 59 MB of vendored test suites
and 85 MB of `__pycache__` on top).

So the split is by cost, with one exception by KIND. `packages/base.txt` ships:
bs4 + soupsieve, PyYAML, python-dateutil, pytz, tzdata, XlsxWriter — a few MB
together, and the artifact measures 90 MB against the ~300 MB a GraalPy context
costs. `packages/on-demand.txt` is what the host installs on first import:
numpy, pandas, matplotlib, pillow, fontTools, python-pptx, pytest, 161 MB with
their test suites stripped, and `tabulate`, which is there only because the
pandas SHIM renders through it. The exception by kind is `requests`, `urllib3`
and `httpx`: their shims are host BRIDGES to the JVM HTTP client, not
reimplementations, so shipping the real ones moves network policy onto
`network_guard.py` — a wave of its own, not a line in a requirements file. A
block installs nothing either way: a sandbox that can reach an index is a sandbox
that can write its own next payload, which is the same reason `ctypes` reaches no
symbol in Phase 4.

The mechanism needs no code. The shim finder is APPENDED to `sys.meta_path`, so
`PathFinder` already wins and a package present on `sys.path` shadows its shim —
the cutover is incremental, and a shim dies when its package arrives rather than
on a flag day.

Acceptance criteria: the base tier is installed at build time into
`resources/prebuilds/<platform>/python/lib/python3.14/site-packages` from pinned
requirements, transitive dependencies included; a block gets the REAL
distribution at its real file for every name the artifact ships
(`packages_test.clj`); the whole suite still passes, which is what proves a
shipped package did not shadow a shim something else still needs; an extension
declares a dependency and imports it; a block that tries to install one is
refused; and every redistributed license is recorded the way `audit/README.md`
records ours.

Unknowns: hashes — the pins are exact but not `--require-hashes`, which needs a
per-platform lock generated on each build machine. Whether the on-demand tier
installs into the vendored tree or a per-installation site directory. glibc
versus musl for the linux wheels. And the licence-record generator, which today
only knows in-house coordinates.

## Phase 7 — Delete the shims

Rationale: a shim a real package shadows is dead code that still has to be read,
tested and explained, and it is why the sandbox prompt has to warn that every
module is "a Vis REIMPLEMENTATION, not the upstream package". That sentence stops
being true here, and 1.54 MB of `resources/vis-shims/` plus roughly 5000 lines of
host halves stop existing.

Data: `resources/vis-shims/` is 23 files; the host halves are Vis'
`src/com/blockether/vis/internal/foundation/shim_*.clj`. The waves, in the order
their evidence arrives: the stdlib ones first (`sqlite3`, `tzdata` -> `zoneinfo`,
`toml` -> `tomllib`), then the pure-Python packages already shipping in
`packages/base.txt` (bs4, yaml, dateutil, pytz, xlsxwriter), then the binary ones
with the tier that carries them (numpy, pandas + tabulate, pillow, matplotlib,
fontTools, pptx, pytest). `requests`, `urllib3` and `httpx` are last and are the
only wave that is not a deletion: their shims are bridges, so the wave replaces a
host bridge with a real client under `network_guard.py` and has to argue that on
its own evidence. `posix.py` goes with them, its work already done in C. Nothing
shared can be deleted until Vis pins this library, because `sandbox-parity-test`
hashes both copies.

Acceptance criteria: each wave deletes the shim, its host half and its bridge in
one commit; the shim's own tests keep passing against the real package; the
per-pack `resources/META-INF/vis/apropos/shim-<pack>.edn` pages and the sandbox
prompt describe the real library; and no `__vis_*__` bridge survives with no
caller.

Unknowns: `anydoc`, `attach`, `nippy` and `ruff` are not reimplementations of
anything — they are host capabilities wearing a module's shape, and they stay.

## State

Phase 0 done: repository, resolution, version, build and test scaffolding, plus
the measured GraalPy baseline in `bench/pre-cpython.md` and the harness that
produced it.

Phase 1 done on the JVM side. `native/vis-python/vispython.c` exports
initialize / version / eval / exec / finalize, `com.blockether.vis-python-runtime.ffi`
binds all five through FFM on one pinned thread, and the loading model is
settled: source roots on `sys.path`, `import vis_runtime`, `install` per
session. Sessions are module namespaces over one interpreter.

Measured, against the 1.1 s and ~330 MB a GraalPy context costs in
`bench/pre-cpython.md`: 37 ms to a started interpreter with import roots, 6 ms
to install Vis' whole 4.4k-line `async_runtime.py` (204 names) into the first
session, 0 ms into the second.

The interpreter is now VENDORED, which closes the half of Phase 1 that was about
where CPython comes from. `.cpython-version` pins astral-sh/python-build-standalone
(3.14.7, release 20260825); `build.sh` unpacks that tree into the platform's
prebuild directory and links the shim against it with an `@loader_path`/`$ORIGIN`
rpath; `vis_python_initialize` takes a `home` and starts the interpreter through
`Py_InitializeFromConfig`, so `sys.prefix` is inside the vendored tree and no
system Python is consulted. Measured on darwin-arm64: 26 MB downloaded, 68 MB on
disk (18.8 MB `libpython3.14.dylib`, 22 MB standard library), our own cdylib
54 KB. The whole suite passes against it with no `PYTHONHOME` in the environment.

A STATIC libpython was tried first and refused for a real reason, recorded so
nobody repeats it: upstream publishes only `debug` and `pgo+lto` full archives
for macOS, and the `pgo+lto` `libpython3.14.a` holds LLVM bitcode from a newer
LLVM than Apple's linker can read (`Unknown attribute kind (105)`), while
Homebrew's `libpython3.14.a` is a symlink to the framework dylib. Vendoring the
shared library is self-contained all the same, so static buys nothing here.

Still open in Phase 1, and the actual gate: the same downcalls under
`native-image` on CE 25.1.3, with registrations inside the jar at
`resources/META-INF/native-image/com.blockether/vis-python-runtime/`. The host
door adds one item to that gate: an FFM UPCALL stub has to survive
`native-image`, not just the downcalls.

Moved: all 36 sandbox Python sources (1.77 MB) — `resources/vis-python/` (13
files) and `resources/vis-shims/` (23 files) — now live here at the resource
paths Vis already reads, and the runtime imports `async_runtime` from this
repository. Vis keeps its copy until it can pin this library, which needs a
remote; `sandbox-parity-test` hashes both sides so the two cannot drift while
the copies coexist.

Phase 4 landed the boundary: `vis_python_confine` over an audit hook installed
before `Py_InitializeEx`, two lists of canonical roots in C, and the guest with
no way to see or change them. Reads, writes, `..`, symlinks out of a root and
`os` mutations are covered by `confinement_test.clj`; what stays in Vis is the
JVM-side `sandbox-fs.clj`, which is Truffle's seam and dies with GraalPy.

Phase 2 closed with the door back OUT: `vis_python_host` registers one function
pointer, `_vis_host.call(name, payload)` is the guest's only way through it, and
`vis_runtime.install_tool` binds a name the sandbox defers exactly like any
other tool. Only text crosses — the JSON envelope is the runtime's, the C
boundary reads none of it — the GIL is released for the call's duration so guest
threads keep running, and an oversized reply costs a retry rather than a second
other tool. Only text crosses — the JSON envelope is the runtime's, the C
boundary reads none of it — the GIL is released for the call's duration so guest
threads keep running, and an oversized reply costs a retry rather than a second
RUN of the tool. That unblocks Wave 3, whose shims are host-bridged.

The process surface moved into the boundary. `vis_python_confine` now shuts it
too: `os.system`, `os.exec`, `os.fork`, `os.posix_spawn`, `subprocess.Popen` and
`pty.spawn` are events CPython raises itself, so the refusal no longer needs
`resources/vis-shims/posix.py` to put a module of fakes in
`sys.modules["subprocess"]` — a guard written in the language it guards, covering
only the doors it knew to name. `ctypes` is shut the same way, at the SYMBOL and
not at the library: `import ctypes` opens one itself and a package that merely
imports it has to keep running, while `ctypes.dlsym` and `ctypes.call_function`
are the steps that turn a handle into a call into libc. An extension module the
interpreter imports raises none of those events, which is exactly what lets Phase
6 ship real wheels. The host supplies the sentence a guest reads, so Vis keeps
wording this once. `posix.py` itself stays until Vis pins this library, because
the parity test hashes both copies.

Phase 6 has its first tier. `packages/base.txt` and `packages/on-demand.txt` are
the two lists, `native/vis-python/build.sh` installs the first into the vendored
tree and strips the test suites vendored inside those packages, and
`packages_test.clj` pins the thing that matters: a block gets the REAL
distribution, at its real file under `site-packages`, with nothing changed to
make it so. Measured on darwin-arm64 the whole artifact is 90 MB, against the
~300 MB a GraalPy context costs.

Two decisions came out of the measurement rather than out of taste, and both are
recorded because the estimate that preceded them was wrong. Bundling everything a
shim promises is 315 MB installed, not the 65-80 MB projected from wheel sizes.
And a shipped package can shadow a shim that ANOTHER shim still needs: the real
`tabulate` broke the pandas shim, which renders through it, so tabulate now
travels with pandas in the on-demand list. The suite is the detector for that
class of breakage and it is green at 111 tests / 595 assertions.
