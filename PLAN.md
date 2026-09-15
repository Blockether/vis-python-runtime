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
remain outside its commits. No consumer installation or live gateway restart was
performed.

[Release v0.5.16](https://github.com/Blockether/vis-python-runtime/releases/tag/v0.5.16)
is published at `44c3831e336e2e6138cbaea070c3da59820ca154`. Exact-commit CI
`34849010340` passed all six jobs; the macOS x64 job required one retry after a
Maven Central HTTP 502. Release workflow `34850817074` passed all seven jobs,
including the real Windows JVM, native-worker and extracted-archive suites.

All eight release assets are available: five platform archives, the JVM jar, the
combined Javadoc/Codox ZIP and a standard Javadoc jar. Downloaded Windows, macOS
ARM64, JVM and documentation assets match their published SHA-256 digests. The
Windows archive contains the DLL, native worker, CPython and uv; the documentation
ZIP contains both API references and 36 HTML pages. Java and Clojure quickstarts
run against the downloaded JVM jar and macOS archive, including the Java host
callback. The downloaded native worker passes seven tests and 183 assertions.
Windows OS-level process confinement remains unavailable and fails closed in
v0.5.16.

# Native Windows process isolation

A small, documented launcher with an explicit Windows security contract.

## Context

`Jail` currently compiles path-based `JailPolicy` values to Seatbelt or bubblewrap.
Windows does not provide equivalent unprivileged mount namespaces. Granting ACLs
on arbitrary host trees would not enforce path denies across renames, hard links
or newly created children. A VM, driver or global firewall configuration would
change the installation and privilege requirements.

Add a separate `WindowsJail` capability-workspace API. Each context owns a fresh
private directory, a unique low-privilege AppContainer identity and Job Objects.
Copy selected programs and inputs into a read-only application directory; provide
separate writable work and temporary directories. Do not modify source ACLs or
reinterpret unsupported `JailPolicy` fields. Network capabilities stay disabled.
Outputs remain available to the host after closing the context.

## Phases

1. Native enforcement and lifecycle
   - Rationale: arbitrary child programs need a kernel boundary, not Python hooks.
   - Data: `native/visjail/visjail_windows.c`, `visjail.h` and `build.ps1`.
   - Acceptance criteria: safe copied inputs, LPAC tokens, suspended launch into
     jobs, explicit handle inheritance, pipes and ConPTY, fail-closed setup and
     descendant cleanup. Native handles use logical IDs, never narrowed pointers.
   - Unknowns: actual Windows ACL/token behavior and ConPTY teardown are runtime
     test requirements, not assumptions proved by compilation.

2. Public API and documentation
   - Rationale: callers must understand what Windows can actually isolate.
   - Data: `WindowsJail`, shared `Jail`/`JailedProcess`, the Clojure API and guides.
   - Acceptance criteria: create, stage, spawn and close; complete environment
     with private temporary paths; documented private-copy semantics and limits;
     no environment marker bypass, implicit network permission or host ACL edits.
   - Unknowns: compatibility of staged stock Python and the native worker.

3. Adversarial and packaging verification
   - Rationale: a Windows build and a native worker running as a guest do not
     prove native-image launcher downcalls or confinement.
   - Data: Windows guest and Java launcher probes, JVM tests, CI/release scripts,
     shipped reachability metadata and extracted platform archives.
   - Acceptance criteria: test filesystem/token/network boundaries, sibling and
     descendant isolation, streams/PTY/cleanup, and copied application behavior
     on Windows through both JVM and native-image launchers. Recheck Unix jail
     behavior and keep unrelated changes outside the scoped commits.
   - Unknowns: Windows CI findings. No consumer install or gateway restart.

## Plan state

The native backend, Java/Clojure interface, guide and Windows CI probes are
implemented. MSVC builds the Windows DLL and test guest, and both the native
worker and native-image launcher compile. CI passes all four Unix platforms and
the documentation checks. Local formatting, lint and PowerShell analysis pass;
read-only review findings on source pinning and process/ConPTY lifetimes have
been addressed.

Windows CI run 34878855420 verifies minimal process launch, the effective LPAC
access mask (`2`), validation and staging stress. The private profile subtree
exists before launch; the corrected profile-path checks, temporary-file
create/write/delete, staged read-only inputs and host/sibling/profile/temporary
file denials pass. Ordinary descendant creation still returns Windows error 5.
Runs 34880164527 and 34881927919 ruled out self-process access, child mitigation
policy, console/handle variants and executable read/execute/mapping prerequisites.
Run 34883140674 successfully configured and read back explicit user/package/System
default object grants, but descendant creation failed identically. That ineffective
token adjustment is removed rather than retained as an unproven permissions fix.

Run 34884642944 also fails after changing the inherited working directory,
explicit same-container startup attributes and the guest application-data base.
Self-thread access, token query/duplicate/assign access and directory traversal
succeed. All four Unix lanes and API documentation checks pass.

Run 34887357608 reports `STATUS_ACCESS_DENIED` (`C0000022`) for ordinary
creation, a null application name, the current primary token and a System32
command. The effective job has only kill-on-close enabled and no active-process
limit. No descendant runs in these comparisons.

Run 34889232808 observes `NtCreateUserProcess` returning `STATUS_SUCCESS` with
`PsCreateSuccess` for all three comparisons, including the empty desktop. Win32
creation fails afterward with error 5; the child never runs. All native builds,
four Unix lanes and API documentation checks pass, but Windows E2E does not.

Run 34891790772 installs all five KernelBase observers without error, but only
native creation traverses them. No CSR, process/thread update or resume call is
observed through those slots; this does not exclude calls from other modules.

Run 34893563254 observes native creation and two successful process queries
(command line and extended basic information), but no failing call through the
expanded imports. All four Unix lanes and API documentation checks pass. Error 5
still blocks Windows E2E. Earlier last-NT-status readings were not reset before
launch and may include a previous error; they do not identify the failing call.

Run 34896088686 resets both last-error fields and observes KernelBase converting
`STATUS_ACCESS_DENIED` to error 5 before terminating the suspended child. All
native builds, four Unix lanes and API documentation checks pass; Windows E2E
still stops at descendant creation.

Run 34898378805 observes the actual failing `NtOpenKey(KEY_READ)` call in
Kernel32. Matching Microsoft symbol-server binaries map its caller to the
SideBySide registry lookup during Win32 child initialization. Native process
creation succeeds first; access denied from this lookup causes the later error 5
and cleanup. All four Unix lanes and API documentation checks pass.

The production candidate adds exactly the supported `registryRead` capability to
each launch. Its SID is derived once per context and released on failure or
successful close. Profile creation retains its separate private identity without
adding shared capability grants. No network capability or existing host ACL is
changed. This is not a SideBySide-only grant or an intrinsic read-only filter;
the documentation describes the broader Windows ACL-based boundary.

Run 34901704961 passes the first JVM launcher's validation, staging stress,
registry, filesystem and streams stages on Windows Server 2022. This includes
exact capability readback, native/Win32 SxS reads and private-key denials,
unchanged host registry fixtures, real descendant creation and breakaway denial.
Profile removal and private application-data writes also pass. All Windows
builds, including the native-image launcher, succeed; its execution is not yet
reached.

Run 34902956037 also passes the first JVM network and lifetime stages. The
network probe accepts only explicit `WSAEACCES` at socket creation or
connect/send; host TCP/UDP controls and zero-connection/packet checks pass for
both IP families. All four Unix jobs and documentation checks pass.

Run 34904408638 verifies the ConPTY correction from Microsoft terminal issue
#11276: both launch paths use `STARTF_USESTDHANDLES`, with null standard handles
for ConPTY. The redirected-parent regression passes all three console handles,
the exact LPAC token, dimensions, input roundtrip and output capture. Active
ConPTY backpressure cleanup and parent-crash cleanup also pass.

Run 34905273552 also passes inherited-handle isolation. The host positive control
reads the synthetic secret; the confined guest cannot. Its handle-type probe
accepts only `STATUS_INVALID_HANDLE` at that one query, not arbitrary crashes.
All four Unix jobs and documentation checks pass again.

Run 34906429094 confirms that requesting `TOKEN_ADJUST_DEFAULT` fixes lowering
of the restricted test-host token. Run 34907694497 then reproduces its ordinary
pipe failure directly in C, before Java or any jail call. The token owner is
already the user. Its copied default DACL grants full access to Administrators
and System plus read/execute to another SID, but has no user allow entry.
Administrator membership is disabled and no privilege except traversal is
enabled. `CreatePipe` with default security returns access denied.

Run 34908364994 passes the complete first JVM launcher: 266 checks, including
the standard-host Win32/Java pipe controls, disabled administrator membership,
privilege/integrity checks, user-owned two-entry default DACL, actual LPAC jail
creation and private registry isolation. Stock CPython and the native worker's
AF_UNIX protocol both pass; the worker is not reported as unsupported.

The native-image launcher then fails before jail creation because its runtime
archive was not selected. The JVM found the checkout DLL via its file classpath;
the image has no such file-classpath fallback. The harness now passes the resolved
external runtime in each probe process's environment, inherited by its test
children, without changing the calling shell's environment. Each top-level probe
also checks that the selected DLL is the requested runtime before the jail tests.

Run 34909384557 passes the complete JVM launcher with 267 checks. The native-image
launcher now verifies its selected runtime and passes validation, staging,
registry, filesystem, streams, network, lifetime and terminal round trips. Its
active-ConPTY-close subprocess reaches the 20-second watchdog. The outer harness
discarded child output on timeout, so the failing inner phase was not visible.
The probe now retains bounded post-kill diagnostics, labels its readiness/close/
exit/writer/cleanup phases and prints thread stacks on an inner failure. Cleanup
preserves the primary exception. The 20-second success deadline is unchanged;
no confinement or production teardown behavior has changed for this diagnosis.

Run 34910492970 initially passes all six jobs. Windows completes both JVM and
native-image launchers (267 checks each), stock Python, the native worker,
Clojure suites and the runnable documentation example, then repeats those checks
against the extracted archive. An identical Windows-job rerun fails in extracted
native-image **output readiness**, before context close. The consumer is waiting
for output, the PTY pump is polling, and the writer is in its native write;
cleanup succeeds. This is not evidence of a native teardown deadlock.

The backpressure fixture now keeps producing until killed instead of assuming a
finite console write produces the same number of terminal-stream bytes. It also
keeps the paused reader thread alive: `PipedInputStream` otherwise treats the
terminated reader as a broken pipe and can release the writer before context
close. Readiness still requires 128KiB, input still writes 16MiB, and every child
retains its 20-second watchdog. Counted-read diagnostics and four independent
close attempts per launcher check this previously intermittent scenario. No
production policy or teardown path changed.

Run 34922719764 passes all four JVM close attempts and the complete JVM launcher
(273 checks). Native-image readiness still times out after receiving 63,612
bytes while input is flooding, with the consumer waiting and the pump polling.
Continuous output alone did not resolve readiness. The fixture now establishes
output readiness and a full 64KiB consumer pipe **before** starting the oversized
input write. It then requires that write to remain incomplete for 100ms before
closing the context. This checks simultaneous backpressure without relying on
forward output progress after deliberately saturating console input. All original
byte thresholds, close checks and the 20-second child deadline remain. Windows
verification and publication are still pending.

Run 34923285018 reaches real duplex backpressure and passes the first three JVM
close attempts in about 0.55 seconds each. The fourth times out in context close:
128KiB has been received, the reader remains alive, the writer has been released,
and the Java reaper has exited. The PTY pump is waiting on the full Java pipe.
Native teardown now emits temporary phase labels for its table/job/stream/console/
closer/profile steps, without changing their order or deadlines. Remove those
labels after locating and correcting the native wait; do not publish them as a
normal runtime logging feature.

Run 34923942703 passes the complete JVM launcher (273 checks), then hangs in the
second native-image close child. Native labels locate the wait in the direct
`ClosePseudoConsole` call, after the job and logical stream teardown. The active
writer can retain the duplex stream past logical close, so temporary diagnostics
now also record its reference count and actual read/write `CloseHandle` results.
This distinguishes an output-handle ordering race from a console wait after both
handles were already closed. Remove all temporary native prints before release;
no teardown ordering or deadline has changed yet.

Run 34924594273 confirms the ordering race: the duplex stream has two references
at logical close, and both successful native handle closes occur after entering
`ClosePseudoConsole`, which remains blocked. Context teardown now counts live
native streams and waits on a condition variable until canceled IO releases every
handle, including independently closed streams, before closing the console. A
reaper cannot start a new console closer while this context barrier is active.
All temporary native prints are removed. The suite retains four active-close
children and adds four kill/reap-before-close children per launcher to check an
already-started asynchronous closer too. Native Windows verification remains
pending; byte thresholds, watchdogs and confinement are unchanged.

Run 34925493396 still times out in the first direct JVM close child after the
barrier change. The writer is released and the consumer remains paused. This
means the observed ordering race alone is not a verified explanation or fix.
Temporary native handle-result, barrier-count and teardown-phase diagnostics are
restored to locate the remaining wait; remove them after verification and before
publication. The additional pre-reaped case has not run yet.
