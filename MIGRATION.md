# Migration ledger: Vis' Python moving into this repository

What has moved, what is next, and what is never moving. A line is checked when
the work lands HERE; the copy in Vis is deleted in the commit that lands the
pin, never before — Vis has no other source for these files until then.

Measured from Vis at the time of writing: 179 test namespaces, 51 of them
touching sandbox Python, 41 090 lines. Of those, 13917 lines are
candidates to move and 17 803 lines are engine tests that stay.

## Done

- [x] `resources/vis-python/` — 13 files, imported by the runtime from here
- [x] `resources/vis-shims/` — 23 files, 1.54 MB
- [x] `sandbox-parity-test` — hashes every one of the 36 files against the
      sibling Vis checkout, so the two copies cannot drift while both exist
- [x] the loading model: source roots on `sys.path`, `import vis_runtime`,
      `install` per session

## Wave 1 — shim tests whose shim calls no host callable (2907 lines) — LANDED

The cheap ones: the Python under test needs nothing from the JVM, so a test is
`harness/session` plus `ev` in this repository and pays for no GraalPy context
at all. All eight run here, 68 tests and 310 assertions, against the embedded
CPython.

- [x] `bs4_compat_shim_test.clj` — 917 lines — `bs4.py` (6208 lines) calls no host callable
- [x] `numpy_compat_shim_test.clj` — 395 lines — `numpy.py` is pure Python
- [x] `pandas_compat_shim_test.clj` — 211 lines — `pandas.py` is pure Python
- [x] `tabulate_compat_shim_test.clj` — 193 lines — `tabulate.py` is pure Python
- [x] `toml_compat_shim_test.clj` — 59 lines — `toml.py` is pure Python
- [x] `urllib3_compat_shim_test.clj` — 569 lines — `urllib3.py` is pure Python
- [x] `httpx_compat_shim_test.clj` — 420 lines — `httpx.py` is pure Python over the socket layer
- [x] `fonttools_compat_shim_test.clj` — 143 lines — `fonttools.py` keeps its error state in Python

What the wave demanded of the runtime, each of it now pinned by `ffi_test`:

- results cross as JSON (`vis_runtime/to_json`, `ffi/run`), and the moved
  assertions read that JSON as data. GraalPy coerced an integral
  float to an integer; the three numpy expectations that baked that in now
  carry the float CPython actually has.
- `import <shim>` resolves lazily through `vis_runtime.ShimFinder`, appended to
  `sys.meta_path` so the stdlib always wins, and a shim that fails to load is
  blamed BY NAME together with its cause (`ShimLoader`).
- one interpreter means one module table, so `harness/session` drops every
  loaded shim per test (`vis_runtime.forget_shims`) — a test that monkeypatches
  a shim hands nothing to the next one — and `harness/ev-guarded` restores
  `sys.modules` around the tests that break an import on purpose.

Two shim bugs the move exposed, fixed in BOTH copies (parity holds):

- `bs4.py` used a bare `re` in three places while importing `re as _re`; it only
  worked because Vis' sandbox namespace happens to carry `re`.
- `bs4.py` overrode `HTMLParser.set_cdata_mode(self, elem)`, whose signature
  grew a keyword-only `escapable` after 3.11 — invisible on GraalPy, fatal on
  CPython 3.14.

## Wave 2 — sandbox runtime behaviour (6026 lines)

Blocked on the bridge growing the ops these exercise (handles, descriptors,
process surface). Each one moves the day its op exists here.

The first one is in, and it is the one the project exists for: the handle
registry reclaims on CPython exactly as it was written to on GraalPy.

- [x] `env_python_fd_test.clj` — 512 lines — descriptor discipline for the sandbox `open`
      (the sqlite3 case moves with its shim, the socket cases with the shims that
      open connections)
- [x] `env_python_handles_test.clj` — 180 lines — DELETED with the `__vis_own__` registry itself
- [ ] `env_python_grep_paging_test.clj` — 118 lines — a capped search pages itself
- [x] `network_guard_test.clj` — 144 lines — `network_guard.py` (policy only; the
      capability and the proxy/CA environment are the host's and stay in Vis)
- [x] `sandbox_fs_test.clj` — 1246 lines — the BOUNDARY moved, the mechanism did
      not: Vis' `sandbox-fs.clj` is a Truffle `FileSystem` and dies with GraalPy,
      so what lands here is `confinement_test.clj` over the audit hook in
      `native/vispython/vispython.c`. The outbox tap, the `:fs/access` gate and
      the atomic-move / mutation-notice cases are the engine's and stay in Vis
- [x] `env_python_form_eval_test.clj` — 1879 lines — per-form evaluation, the
      ambient stdlib surface, deferred tools and the whole `asyncio` shim
      (`form_eval_test.clj` + `asyncio_test.clj` here). What stayed in Vis is the
      HOST's half: prose-leading SyntaxError classification, the enrichment that
      turns a NameError into an apropos hint, the op-error shapes
      (`:python/host`, `:vis/tool-failure`), the polyglot-proxy cases, and
      `import socket` refusing — GraalPy's sandbox, not a rule CPython has
- [ ] `env_python_test.clj` — 1711 lines — context construction end to end
- [ ] `env_python_engine_test.clj` — 236 lines — engine selection and options

The handle registry is GONE, in both repositories. It existed because GraalPy does
not refcount: a shim handed the block a wrapper around a host id, dropping the wrapper
ran no `__del__`, and the host kept the raster or the connection for the life of the
JVM. CPython refcounts, so the last reference dying IS the release, and there is no
shim left to hand out such a handle — Vis keeps only host-call doors. Deleted with it:
`__vis_handle_kind__` / `__vis_own__` / `__vis_forget__` / `__vis_disown__` /
`__vis_reclaim_handles__` / `__vis_handle_census__`, `__vis_run_reapers__` and its call
in `run_block`, `vis_runtime.reset_handles`, and both suites that pinned them.

What the block seam still demands of the runtime:

- a BLOCK is not an expression: `vis_runtime.run_block` redirects stdout and awaits
  `__vis_run_async__`. `ffi/run-block` answers `{:stdout … :error …}`.
- `install` EXECUTES the runtime's source into the session namespace instead of
  copying names out of an imported module, because the runtime's own globals and the
  block's globals have to be the same dictionary.
- `auto_imports` is imported at install, so a block writes `json.dumps(...)`
  without an import, as it does in Vis.

What the network guard demanded, and it is the sharpest difference the move has
produced so far: GraalPy gave every session its own interpreter, CPython gives
them ONE, and two places quietly relied on the isolation.

- `network_guard.py` wrapped `socket` on every install, so a second session ran
  under its own policy AND every earlier one — a host it allowed stayed blocked.
  It wraps once now and reads the policy from a holder, so an install REPLACES
  the policy. One interpreter therefore enforces ONE policy at a time: entering
  a session is installing its policy.
- `async_runtime.py` re-assigned the socket doors on every install, which put
  the pristine `connect` back UNDER the guard and disabled it silently. The
  doors go on once (`__vis_socket_doors_on__`); every table they touch was
  already a survivor.

Both changes are in Vis' copies too, and Vis' `network-guard-test`,
`env-python-fd-test` and `env-python-handles-test` stay green on GraalPy (37
tests), because per-context state makes the once-per-interpreter guards no-ops
there.

What the form-eval and asyncio tests demanded, and it is where the host's job
starts moving here:

- `gather` dispatches on `__vis_par__`, which Vis' loop supplies and nothing in
  this library did — so a block that imported asyncio ran on the REAL asyncio
  and died with "no running event loop". `vis_runtime.par` is that pool now: one
  bounded `ThreadPoolExecutor` for the process, seeded by `install` only when
  the host bound none. A `gather` inside a gather child runs sequentially,
  because submitting to a pool the callers already hold is how one deadlocks.
- `run_block` REWRITES the block's imports (`vis_runtime.rewrite_imports` over
  the runtime's own `__vis_strip_protected_imports__`). Vis does this in Clojure
  before it hands the source over (`env_python.clj/strip-protected-imports`);
  running a block is what needs it, so it lands here and Vis' copy goes with the
  pin.
- `install` seeds `__vis_protected_names__` with the runtime's public surface,
  which is what makes `from asyncio import gather` keep the sandbox's `gather`
  and a top-level `def cat(...)` a refusal. The host adds its tool names to it,
  the way it already does.

Nothing about a tool result changed: it arrives as `__VisDict__`, the sandbox's
own dict subclass, exactly as it did before. The subclass is not a GraalPy
artifact - it is what makes a missing key raise a KeyError naming the keys the
tool did answer - and the JSON a case asserts on is byte for byte the same.

What the filesystem boundary demanded, and it is the one thing GraalPy gave for
free: confinement. Truffle took a `FileSystem` implementation; CPython opens
files with the process's own credentials, so the guard is an audit hook (PEP
578) added before `Py_InitializeEx` over a policy in C
(`vispython_confine`, `ffi/confine!`). Guest code cannot see it, remove it or
reach around it — a rebound `open`, `os.remove`, a `..` escape and a symlink
pointing out of a root all arrive at the same hook. A writable root is readable
too, the interpreter's own installation is passed as a read root so imports keep
working, and an unresolvable path is refused rather than trusted.

## Wave 3 — shims with a host bridge (4607 lines)

The Python half can move; the assertions that prove the JVM capability stay in
Vis. Split them, never copy them.

The bridge itself now EXISTS here, which is what blocked this wave: `bind-host!`
registers one upcall stub, `install-tool!` binds a name the sandbox defers like
any other tool, and `_vis_host.call(name, payload)` is the guest's only way
through (`host_test.clj`). What a shim like `pil.py` still needs is the JVM
capability behind each `__vis_*` name — those are Vis' own, so a shim moves only
with a host that answers them, and the assertions proving the capability stay in
Vis either way.

- [ ] `pil_compat_shim_test.clj` — 1619 lines — `__vis_pil_*`: every image op
      delegates to com.blockether/imaging on the JVM and the pixels never enter
      Python, so the suite belongs to this wave and not to Wave 1 where it was
      first listed. The JVM-side assertions (`foundation.shim-pil/images`, live
      raster counts) stay in Vis when it splits
- [ ] `anydoc_compat_shim_test.clj` — 837 lines — `__vis_anydoc_detect__`, `__vis_anydoc_markdown__`
- [ ] `matplotlib_compat_shim_test.clj` — 755 lines — `__vis_mpl_render__`, `__vis_mpl_render_file__`
- [ ] `nippy_compat_shim_test.clj` — 93 lines — `__vis_nippy_encode__`, `__vis_nippy_decode__`
- [ ] `sqlite3_compat_shim_test.clj` — 151 lines — `__vis_blob__`, `__vis_forget__`
- [ ] `tzdata_compat_shim_test.clj` — 144 lines — `__vis_tz_fromutc__`, `__vis_tz_available__`
- [ ] `xlsxwriter_compat_shim_test.clj` — 67 lines — `__vis_xlsx_build__`
- [ ] `pptx_compat_shim_test.clj` — 241 lines — `__vis_pptx_build__`, `__vis_pptx_read__`
- [ ] `yaml_compat_shim_test.clj` — 76 lines — `__vis_yaml_dump__` (YAMLStar on the JVM)
- [ ] `posix_refusal_shim_test.clj` — 150 lines — `__vis_process_surface__`, the shell handle
- [ ] `shim_regression_test.clj` — 474 lines — mixed: split by shim when the waves above land

## Moves with the docs harvest

- [ ] `apropos_resource_test.clj` — 377 lines. The shim prose lives in
      the Python docstrings that now live here, so the generator and its drift
      gate follow the sources; Vis keeps only "every manifest `:apropos`
      resolves".

## Never moving

Engine tests, 17803 lines: `loop_test`, `shell_test`, `python_extensions_test`,
`python_process_handler_test`, `python_cli_test`, `python_package_test`,
`doctor_test`, `native_reachability_test`, `sandbox_resources_test`,
`sandbox_shim_contract_test`, `shim_identity_test`, and the `foundation/shim_*_test`
namespaces that prove the HOST half of a bridge. Their subject is Vis' engine,
not the interpreter.

## Deletion protocol

1. The work lands here and is checked above.
2. Vis pins this library; in that same commit the mirrored sources and the moved
   test namespaces are deleted from Vis and `sandbox-parity-test` goes with them.
3. Nothing is deleted from Vis before the pin exists. A half-moved test is a
   test nobody runs.

What the descriptor tests demanded, all of it the same lesson as the guard:

- `__vis_pyify__` rebuilt anything that was not a primitive, because off GraalPy
  it had no `polyglot` module to ask. Settle wraps the value of every top-level
  assignment, so `h = open(p, "rb")` came back as a LIST OF THE FILE'S LINES.
  Without polyglot there are no proxies at all — a tool result arrives decoded —
  so `__vis_is_foreign__` is now False there, and GraalPy is untouched.
- the descriptor ceiling was a per-namespace number, and one interpreter has one
  set of doors: `__vis_fd_limits__` is [ceiling, sweep-at] in a survivor, and
  Vis' own test sets it the same way.
- a session had to become CLOSEABLE (`vis_runtime.close_session`,
  `ffi/close-session!`). A session is a module in `sys.modules`; the last
  reference to whatever a block left open is its globals, so a host that never
  drops a finished session holds every descriptor it ever leaked. Clearing is
  not enough — a namespace is a reference cycle through every function defined
  in it, so the collector has to run.
- `run_block` REINSTALLS the runtime into a session that lost it, which is what
  Vis' `ensure-async-runtime!` did: `globals().clear()` is legal Python and a
  block is allowed to run it.

One case changed shape on purpose: Vis proved the registry tracks the raw layer
by reading through `raw` AFTER dropping the wrapper. CPython closes the whole
stack when the wrapper dies, so the port asserts the identity — which is the
contract — and reads while the stack is alive.

## The Vis side, after the pin

The ledger above is what MOVED here. This is what is left on the other side, in
order, so the work survives an interrupted session. It lives here because vis'
own `PLAN.md` holds an unfinished plan already, and one plan is in flight there
at a time.

Branch `cpython-runtime` in vis, off `origin/main`.

1. **One door back into vis — DONE** (`9d7add1e`). `internal/python_host.clj`
   registers `{session {name fn}}`, `dispatch` reads the `{"session","args"}`
   envelope and answers `{"value"}`/`{"error"}`, `bind!` hands it to
   `bind-host!`, `install-tools!` calls `install-tool!` per name.
   `python_host_test` runs `print(await greet('world'))` through the real
   interpreter. The `:local/root` pin has to become a released coordinate before
   the PR.
2. **`env_python.clj` over this runtime.** 3,686 lines collapse into a driver:
   a session is a NAMESPACE STRING, host to guest is one `exec!` of a JSON
   literal, guest to host is that door. Deleted there: `->py`/`->clj`,
   `new-engine!` and the engine cache, the GC options, the GraalVM version
   check, `retire-context!`, every Python-source string (this repository ships
   each one as a file) and all shim machinery. The one gap: `run_block` answers
   `{"stdout","error"}` with a one-line error, while vis renders a position and
   a traceback — read back from the `__vis_err_pos__`/`__vis_err_obj__` this
   runtime stashes for exactly that lookup.
3. **Confinement, threads, network.** `confine!` from the session's roots,
   `threads!` for cap and pool, `network_guard` + `proxy_env` as modules so real
   `requests`/`httpx` route through vis' egress proxy and trust its CA. Two
   things `sandbox_fs.clj` did have no equivalent yet: the per-path access gate
   and the syntax gate on guest writes, with mutation reporting. Both want the
   same seam — a write gate the audit hook asks the host about. The open
   decision is that confinement is PROCESS state while sessions have different
   roots: per-thread policy in C defaulting to DENY, or a process per session.
4. **Delete the shims from vis** — `resources/vis-shims/`,
   `resources/vis-python/` and the parity test here, per the deletion protocol —
   and let `pip-install!` serve what a block imports.
5. **Remove GraalPy from vis' tree**: `deps.edn` (root and extensions),
   `build.clj`, the reachability metadata, workflows, the audit record and the
   docs. About 150 files carry the token today.
6. **The binary and the deployment**: a linux-x64 prebuild of this runtime,
   `clojure -T:build native`, `-M:test-native`, then the gateway rollout the
   private operations repository describes.
