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
root is refused, and the interpreter keeps importing its own stdlib while
confined (`confinement_test.clj`).

Unknowns: the process surface (`os.system`, `os.exec`, `subprocess`) is refused
by the shims today and could be refused here instead; the network is still
guarded in Python (`network_guard.py`) where `socket.connect` is an audit event.
Windows names paths differently and the canonicalizer is POSIX.

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
load a C extension. CPython can, so `numpy`, `pandas`, `tabulate`, `bs4` and
`toml` stop being ours to maintain — and an extension author, who is a user
writing their own code, gets the actual library instead of a subset. Nothing is
installed at runtime: a sandbox that can reach `pip` is a sandbox that can write
its own next payload, so the packages are BUILT INTO the platform artifact the
same way the interpreter is.

Data: wheels from PyPI, resolved at build time into
`resources/prebuilds/<platform>/python/lib/python3.14/site-packages`. Measured
cp314 wheel sizes: numpy 11.9 / 15.6 / 12.8 MB (darwin-arm64 / linux-x64 /
win-x64), pandas ~10, pillow ~5-7, matplotlib ~9-11; pure-Python wheels
(bs4 0.1, requests 0.1, python-pptx 0.5, openpyxl 0.3) are platform-independent.
The shim finder is APPENDED to `sys.meta_path`, so `PathFinder` already wins:
a package present on `sys.path` shadows its shim with no code change.

Acceptance criteria: an extension declares a dependency and imports it; the
sandbox refuses to install anything at runtime (audit hook, not convention); the
per-platform package artifact is separate from the base one, so an installation
that wants none of it pays nothing; and every redistributed license is recorded
the way `audit/README.md` records ours.

Unknowns: which packages are in the CURATED set versus resolved per extension;
whether a wheel with a C extension must be refused entry to `ctypes` (see the
process surface below) before this can ship; and the licence-record generator,
which today only knows in-house coordinates.

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
RUN of the tool. That unblocks Wave 3, whose shims are host-bridged.
