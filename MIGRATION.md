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

- results cross as EDN (`vis_runtime/to_edn`, `ffi/run`), because the moved
  assertions compare Clojure data, not a repr. GraalPy coerced an integral
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

- [ ] `env_python_fd_test.clj` — 512 lines — descriptor discipline for the sandbox `open`
- [ ] `env_python_handles_test.clj` — 180 lines — the `__vis_own__` registry — the reason for the whole project
- [ ] `env_python_grep_paging_test.clj` — 118 lines — a capped search pages itself
- [ ] `network_guard_test.clj` — 144 lines — `network_guard.py`
- [ ] `sandbox_fs_test.clj` — 1246 lines — sandbox filesystem surface
- [ ] `env_python_form_eval_test.clj` — 1879 lines — expression/statement evaluation, printing, tracebacks
- [ ] `env_python_test.clj` — 1711 lines — context construction end to end
- [ ] `env_python_engine_test.clj` — 236 lines — engine selection and options

## Wave 3 — shims with a host bridge (4607 lines)

The Python half can move; the assertions that prove the JVM capability stay in
Vis. Split them, never copy them.

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
