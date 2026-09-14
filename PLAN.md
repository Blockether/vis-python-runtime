# Linux runtime ABI compatibility

Build and verify the complete runtime against Ubuntu 22.04 / glibc 2.35.

## Context

An Ubuntu 22.04 user reported that libvispython could not load because the artifact
required newer glibc. Runtime CI/release and Vis native builds used Ubuntu 24.04;
Vis container exports used Debian bookworm (glibc 2.36). Static libpython would not
remove libc requirements from the bridge, workers or extension modules. Keep the
bundled shared-library ABI and build against the supported baseline instead.

## Phases

1. **Runtime build and archive checks**
   - Rationale: all delivered ELF objects must support the same libc baseline.
   - Data: `.github/workflows/{ci,release}.yml`, `scripts/check-linux-abi.sh`,
     `scripts/verify-platform-archive.sh` and release/source tests.
   - Acceptance criteria: both Linux architectures build/test on Ubuntu 22.04;
     every archive ELF, including bundled uv, passes the glibc 2.35 gate. Extracted
     uv creates a project environment offline and runs the extracted interpreter;
     extracted workers pass worker tests.
   - Unknowns: none; release-tag checks passed on all four platforms.
2. **Vis consumers**
   - Rationale: engine, TUI and downloaded Python sidecar must be compatible together.
   - Data: Vis native-release workflow, Dockerfile, `bin/verify-linux-abi`, release tests.
   - Acceptance criteria: baseline builders, ABI gates, native/SDK/PTY tests before
     publication. Preserve concurrent pipeline-order changes from another session.
   - Unknowns: Vis product publication remains a separate release. Its Linux
     native/SDK/PTY checks must pass before publishing the consuming binaries.
3. **Verify and deliver**
   - Rationale: source tests alone do not prove native Linux compatibility.
   - Data: affected tests, shellcheck, actionlint, formatting/lint and native artifacts.
   - Acceptance criteria: local checks pass; Linux builds and runtime tests pass;
     publish a new immutable runtime tag and pin its commit in Vis.
   - Unknowns: none about authorization; the user requested the runtime release.

## Plan state

Runtime v0.5.7 is published at commit `8cad3753d73488a507b4e7ed9cb1e5fb25a30284`.
Release run 34476856235 passed all four platform builds, tests, ABI/archive checks
and publication. Both Linux architectures used Ubuntu 22.04 and passed the
complete ELF glibc 2.35 gate, including uv. Extracted uv passed offline
sync/check/run without host tools; extracted workers passed their runtime tests.
Local verification passed 174 JVM tests and 5 extracted-worker tests.

Vis commit `aed0189bbd0a70ecd0f6fa6bed48a1e7d342b048` is pushed and pins that exact
release commit. Its 218 affected JVM tests, real editable sync/reload test and
71 release-bundle cases pass. A fresh macOS native build using the published
runtime, with no local dependency override, passed all 4 native uv tests and the
native/JVM package-worker compatibility test. The staged wrapper reports
uv 0.12.12 and the staged worker reports 0.5.7. The packager rejects sidecars
without executable uv or its licenses. Formatting, lint/reflection, shell and
workflow checks pass. No live gateway was restarted.

Runtime release and consuming pin are complete. Vis product publication is
tracked by its own release workflow.

# Python TLS strictness — Vis issue #185

Expose one explicit compatibility policy without disabling certificate verification.

## Context

Vis merges `python.tls_strict` from user and project YAML and supplies the effective
boolean to both sandbox and trusted extension workers. The runtime owns SSL behavior.
A legacy corporate CA has noncritical Basic Constraints and fails Python's strict
validation. Do not change CA bundles, disable verification, retry insecurely or add
a top-level alias. Existing configuration and default Python behavior stay intact.

## 1. Reproduce and implement
- Rationale: strictness is independent of which certificates are trusted.
- Data: runtime worker tests, Vis configuration and real-worker boundary tests.
- Acceptance criteria: default/true preserves behavior; false clears only STRICT;
  nested YAML validates and merges; both execution paths receive the same policy.
- Unknowns: client context construction differences, resolved with tests.

## 2. Cross-validate and document
- Rationale: flags alone do not establish TLS behavior.
- Data: synthetic TLS chains, stdlib/client checks, JVM and native worker tests.
- Acceptance criteria: legacy CA passes only on opt-in; correct CA passes; unknown CA,
  wrong hostname and expired certificates fail; docs describe scope and reload.
- Unknowns: native toolchain availability and external release checks.

## 3. Publish runtime and Vis
- Rationale: consumers must pin a published runtime artifact, not local source.
- Data: new immutable runtime tag, consumer pin, version sync and product CI.
- Acceptance criteria: checks pass, runtime assets publish, consuming Vis release
  finishes; preserve concurrent #186/#187 work and never restart a live gateway.
- Unknowns: coordinate Vis version files with the concurrent #186 release.

## Plan state

Phases 1–3 complete. Default/true/false pass through sandbox and trusted extension
workers. Synthetic handshakes accept a legacy CA only with false; valid CA passes;
unknown CA, wrong hostname, expiry and invalid signatures fail. Vis tests fail with
the old runtime and pass with the local implementation. Runtime full suite passed
179 tests/2919 assertions; after adding unknown-bit preservation, all 7 worker
tests/153 assertions passed against JVM and freshly built GraalVM CE native workers.
Formatting and lint/reflection pass. Runtime v0.5.9 is published at commit
`2242fa0e3ce3340dbb9712d5313b03bbba935a1b`; release run 34495872050 passed all four
platforms. Vis pins that published commit; 347 affected tests and the packaged
native TLS consumer test passed against it.

The full Vis v0.1.60 native suite exposed a cached guest-module directory removed
by an earlier test. Vis commit `02092aaba` now stages the cached source contents
in the current home before each worker launch. The regression failed before the
fix; 222 affected JVM tests, packaged native TLS and 72 release-bundle tests pass.
Vis v0.1.61 is tagged at `920fb468b349377941bceadb7fe4ab4cdba31611`; release run
34509051945 passed all source, JVM, SDK, native, desktop and mobile checks and
published the stable release with 15 assets. A macOS runner queue timeout passed
on retry without source changes or skipped checks. GitHub confirms v0.1.61 is
non-draft and latest. No release tags were moved; no live gateway was restarted.

# Windows runtime and generated API documentation

Ship a Windows x64 embedding runtime with the same Java and Clojure API.

## Context

The C bridge currently depends on POSIX threads and paths. `build.clj`, native
build scripts and release workflows package Linux and macOS only. Windows needs
its own MSVC build, bundled CPython DLL layout, native-image worker and actual
Windows execution tests. API documentation is currently source links and a short
Java example. Use standard Javadoc and Codox rather than a custom API renderer.
Keep the unrelated asynchronous runtime edits outside this change.

## Phases

1. **Port the embedding boundary**
   - Rationale: a platform name or successful DLL build is not runtime support.
   - Data: `native/vispython`, Java bridge, Windows-specific regression tests.
   - Acceptance criteria: load the bundled DLL safely; execute Python, callbacks
     and workers; preserve C filesystem confinement, thread limits and diagnostics.
     OS process jail remains explicitly unavailable on Windows, never downgraded.
   - Unknowns: Windows compiler, native-image and filesystem behavior until CI runs.
2. **Package and verify Windows releases**
   - Rationale: the DLL, CPython, uv and worker must travel and run together.
   - Data: Windows build/archive scripts, version pins, build tasks and workflows.
   - Acceptance criteria: hash-checked inputs, Windows x64 archive and extracted
     JVM/native-worker tests; keep all Linux and macOS release gates.
   - Unknowns: hosted Windows execution results and upstream archive layout.
3. **Generate and deliver API documentation**
   - Rationale: callers need browsable references and complete runnable examples.
   - Data: `doc/`, README, public API docstrings, development-only Codox alias.
   - Acceptance criteria: Javadoc and Clojure references build automatically,
     examples and links pass, documentation is included in release artifacts.
   - Unknowns: documentation warnings and generator compatibility until built.
4. **Verify and publish**
   - Rationale: Windows execution must be verified on Windows, not inferred on macOS.
   - Data: local regressions and lint, exact commit, CI and release artifacts.
   - Acceptance criteria: scoped commits pushed with unrelated work preserved;
     Windows/Linux/macOS checks pass; release workflow includes Windows and docs.
   - Unknowns: CI availability. No consumer installation or gateway restart.

## Plan state

CI run `34834014772` at `c6557e9` built the Windows DLL and native worker, then
stalled in the isolated native-boundary JVM until the 40-minute job limit. The
instrumented runner in `45621a2` names tests and fixture stages, captures Python
and JVM stacks, and exits 124 after two minutes without progress. Its five
regressions cover success, exceptions, stalled tests, fixtures and embedded Python.
The rebuilt macOS runtime passed 211 tests and 3085 assertions; formatting,
lint/reflection, PowerShell analysis and diff checks passed.

Instrumented CI `34843998016` isolated the hang to a denied DOS console path in
`windows-native-confinement-test`. CPython's WindowsConsoleIO opens console handles
without emitting the `open` audit event. The C fix in `b3188e0` guards its constructor
and existing initializer descriptor, including cached bound wrappers, while
preserving unconfined behavior. Initialization fails closed if site customization
already created subclasses with copied initializer slots. Regression tests cover
automatic dispatch, raw constructors, subclasses, cached initializers and startup
customization. Follow-up `e2d2807` passes helper JVM scripts through stdin on Windows
and tests a recorded `runtime/run` call instead of raw `eval-str`.

Windows execution is complete at `e2d2807`. CI `34847306246` passed all six jobs:
Windows, Linux x64/arm64, macOS x64/arm64 and generated API documentation. Windows
built the DLL and native worker, passed 11 native-boundary tests and 82 shared
suite tests, verified the archive and offline uv, then passed both suites again
against the extracted archive. Local affected checks passed 27 tests and 89
assertions, with clean Clojure formatting, lint/reflection and diff checks.

The repair is pushed without loosening confinement or extending the job timeout.
Pre-existing asynchronous-runtime changes and concurrent live-worker diagnostics
remain outside its commits. No release tag, consumer installation or live gateway
restart was performed; release publication remains a separate workflow.
