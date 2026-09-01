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
host sets over the ABI (`vispython_confine`), so a block that rebinds `open`,
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
resource extracted once, the way the cdylib is extracted today. It carries no
bytecode: `__pycache__` is per-machine cache worth 11.8 MB against 18.4 MB of
stdlib source, invalid the moment the tree moves, and a jar that shipped it
would still be writing into a read-only installation. `vispython_initialize`
takes a `pycache_prefix` instead (`~/.vis/python/pycache` by default,
`VIS_PYTHON_PYCACHE_PREFIX` to move it), so the host compiles once on first run
and the shipped tree is never written to.

Acceptance criteria: Vis' binary builds and runs with GraalPy removed, and the
measured size drop is recorded here.

Unknowns: Windows vendoring (the upstream build is MSVC); whether one artifact
per platform is enough for glibc/musl.

## Phase 6 — Real packages, installed by pip

Rationale: the shims are pure-Python REIMPLEMENTATIONS because GraalPy could not
load a C extension. CPython can, so `numpy`, `pandas`, `pillow`, `bs4` and the
rest stop being ours to maintain, and a block gets the actual library instead of
a subset that resembles one. This is also what answers the bytes question the
host door raised: nothing has to marshal a raster or a frame across the boundary
once the library that owns it lives inside the interpreter.

Data: the artifact BUNDLES NOTHING — an interpreter, its standard library and
`pip`, 69 MB on darwin-arm64 against the ~300 MB a GraalPy context costs. Two
measurements killed the tier that preceded this. "Everything that has a shim is
a promise, so it ships" was projected at 65-80 MB per platform from wheel sizes;
the actual install is 315 MB, because a wheel on disk is not its download
(pandas 72 MB, numpy 35, matplotlib 33, lxml 20, fontTools 20, PIL 15, plus 59 MB
of vendored test suites and 85 MB of `__pycache__`). The tier that survived that
was 21 MB of pure-Python packages — and even those are somebody's decision baked
into an artifact everybody downloads, replaced on every release, and impossible
to correct without one. So the rule is now simpler than any list: what a user
needs, a user installs.

`vis-agent pip <args…>` is that door, and `pip.clj` is its whole implementation:
installs go to `~/.vis/python/packages` with the bytecode beside them in
`~/.vis/python/pycache`, and `initialize!` appends that directory to `sys.path`,
so an installed distribution shadows the shim of its name with no code change.
Three properties are not defaults but decisions. `--only-binary=:all:` refuses an
sdist, because an sdist runs its own `setup.py` at install time, on the host,
outside every boundary this project has. Pip runs in a PROCESS of its own, not in
the embedded interpreter, which is confined and would carry an installer's
imports into every session after it. And a block installs nothing, ever: a
sandbox that can reach an index is a sandbox that can write its own next payload,
the same reason `ctypes` reaches no symbol in Phase 4.

Certificates come from the JVM. Pip would otherwise verify TLS against the CA
bundle vendored inside it, so a machine whose operator added a corporate root to
the Java trust store — the only store this product's own HTTP client reads —
would have a runtime trusting two different sets of certificates and failing on
one of them. `pip/certificates-pem!` exports the default trust manager's
anchors to `~/.vis/python/cacert.pem` (measured: 109 certificates, 165 KB) and
pip is pointed at that file with `--cert`, `PIP_CERT` and `SSL_CERT_FILE`.

The mechanism needs no code. The shim finder is APPENDED to `sys.meta_path`, so
`PathFinder` already wins and a package present on `sys.path` shadows its shim —
the cutover is incremental, and a shim dies when its package arrives rather than
on a flag day.

Acceptance criteria: the shipped interpreter carries pip and no package
(`pip_test.clj`); `pip/install!` puts a real distribution in the packages
directory over TLS the JVM's own certificates verified, a block imports it from
there, and the install compiles into the cache prefix rather than into the
package directory; `vis-agent pip` reaches this from the CLI once Vis pins the
library; an extension declares a dependency and installs it; a block that tries
to install one is refused.

Unknowns: `vis-agent pip` itself, which cannot be written until Vis pins this
library — Vis' sandbox is still GraalPy, where `~/.vis/python/packages` means
nothing. Whether `--target` is the right shape or a real site directory with
`--user` is, since `pip uninstall` and upgrades read the first only through
`PYTHONPATH`. Hashes, which need a per-platform lock. glibc versus musl for the
linux wheels. And what a block should be TOLD when it imports a package nobody
installed, which is the one place the doc pages and the CLI have to agree.

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

Phase 1 done on the JVM side. `native/vispython/vispython.c` exports
initialize / version / eval / exec / finalize, `com.blockether.vispython.Interpreter`
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
rpath; `vispython_initialize` takes a `home` and starts the interpreter through
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

Phase 4 landed the boundary: `vispython_confine` over an audit hook installed
before `Py_InitializeEx`, two lists of canonical roots in C, and the guest with
no way to see or change them. Reads, writes, `..`, symlinks out of a root and
`os` mutations are covered by `confinement_test.clj`; what stays in Vis is the
JVM-side `sandbox-fs.clj`, which is Truffle's seam and dies with GraalPy.

Phase 2 closed with the door back OUT: `vispython_host` registers one function
pointer, `_vis_host.call(name, payload)` is the guest's only way through it, and
`vis_runtime.install_tool` binds a name the sandbox defers exactly like any
other tool. Only text crosses — the JSON envelope is the runtime's, the C
boundary reads none of it — the GIL is released for the call's duration so guest
threads keep running, and an oversized reply costs a retry rather than a second
other tool. Only text crosses — the JSON envelope is the runtime's, the C
boundary reads none of it — the GIL is released for the call's duration so guest
threads keep running, and an oversized reply costs a retry rather than a second
RUN of the tool. That unblocks Wave 3, whose shims are host-bridged.

The process surface moved into the boundary. `vispython_confine` now shuts it
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

Phase 6 bundles nothing. The tier that was here — `packages/base.txt` and
`packages/on-demand.txt`, installed into the vendored tree at build time — is
gone, and both files with it: 21 MB of somebody's decision baked into an artifact
everybody downloads and nobody can correct without a release. What replaced it is
`src/com/blockether/vis_python_runtime/pip.clj`: installs run in a process of
their own into `~/.vis/python/packages`, `initialize!` appends that directory to
`sys.path`, and an installed distribution shadows the shim of its name with no
code change. `--only-binary=:all:` refuses an sdist, which would run its own
`setup.py` on the host outside every boundary here. Trust is the JVM's: the
default trust manager's anchors are exported to `~/.vis/python/cacert.pem` (109
certificates, 165 KB measured) and pip verifies against that file, so a corporate
root added to `cacerts` covers the installer too and the machine has ONE trust
decision. The artifact is 69 MB on darwin-arm64, against the ~300 MB a GraalPy
context costs. `pip_test.clj` installs a real distribution from PyPI, imports it
in a block, and pins that the shipped interpreter carries pip and nothing else.

One measurement is kept because the estimate that preceded it was wrong:
bundling everything a shim promises is 315 MB installed, not the 65-80 MB
projected from wheel sizes. A second finding survives the tier that produced it —
a real package can shadow a shim that ANOTHER shim still needs (the real
`tabulate` broke the pandas shim, which renders through it), so the full suite is
the detector for that class of breakage, whoever runs the install.

The artifact ships no bytecode. `native/vispython/build.sh` strips every
`__pycache__` after vendoring, and `vispython_initialize` takes a
`pycache_prefix` that `runtime/resolve-pycache-prefix` puts under the user's own
directory — so what is shipped is source, what is per-machine is cache, and the
tree stays exactly as it was hashed. Measured on darwin-arm64: 90 MB to 77 MB
with the tier still bundled, 69 MB now that nothing is;
and a cold import of ten stdlib modules costs 148 ms against 17 ms cached, paid
once per machine rather than per run. Confinement adds the prefix to the
writable roots for as long as a policy is in force, because that directory is
the interpreter's cache and not the guest's data; a refusal there would not
break an import — the import machinery swallows the PermissionError — it would
quietly pay the compile on every run. `bytecode_test.clj` pins all three: the
shipped tree carries no `.pyc`, the interpreter reports the prefix the host
resolved, and a module a confined block imports is cached under the prefix with
no `__pycache__` beside its source. Suite: 116 tests / 583 assertions.

Unknown, recorded rather than fixed: the cache directory is writable by a
confined block, so a block could in principle leave a crafted `.pyc` there for a
later run to load. It is the same directory CPython would write itself, and a
block already runs arbitrary Python in the session that writes it, but the
persistence ACROSS sessions is new and wants either a host-owned warm-up pass or
`check_hash` invalidation before this ships.

The bridge is Java now, and the Clojure is an API. `java/com/blockether/vispython/`
holds five classes — `Interpreter` (the FFM downcalls, the pinned thread, the host
upcall, the session helpers), `Native` (platform tags and cdylib resolution),
`Locations` (every directory this runtime decides), `Pip`, `HostFunction` — and
`src/com/blockether/vis_python_runtime.clj` is one namespace of one- to
three-line functions over them: argument shapes, keyword maps, EDN. The reason is
Phase 5's verdict, not taste. In Clojure every downcall was
`MethodHandle.invokeWithArguments` — a reflective, boxing invocation — and the
host upcall's target was a `clojure.lang.IFn` bound and `asType`-adapted into
native shape; both are exactly what a GraalVM native image drops unless somebody
registers them, and a green JVM suite never notices. In Java the downcalls are
`invokeExact` against signatures the compiler knows and the upcall target is a
static method found by name. `com.blockether.vis-python-runtime.ffi` and
`…​.pip` are gone; `ffi_test.clj` is `bridge_test.clj`; a failure now arrives as
`VisPythonException` whose `.data` names the symbol, status, platform or path.
`clojure -T:build javac` compiles into `target/classes`, which is on `:paths`,
and `:deps/prep-lib` runs the same task for a consumer taking this as a git
dependency. Suite: 116 tests / 584 assertions.
