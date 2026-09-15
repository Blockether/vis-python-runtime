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

Run 34926154800 passes all four direct and four pre-reaped JVM children (281
launcher checks), then still hangs in the first native-image direct close. Both
`CloseHandle` calls succeed **before** the barrier returns and the direct console
call starts. This rules out the disposal barrier as the remaining wait. Terminal
master disposal now explicitly disconnects both named-pipe clients before closing
its server handles. This is restricted to duplex terminal masters; ordinary pipe
EOF/data delivery is unchanged. It tests a stronger channel-break hypothesis,
not a documented requirement that was previously established. Native diagnostics
remain until Windows verification resolves it; publication is still pending.

Run 34926734487 passes the JVM's 281 checks, then again hangs in the first
native-image direct close. Both explicit disconnects and both handle closes
succeed before `ClosePseudoConsole` starts. Disconnecting the clients is not a
verified fix. The next run adds failure-only native wait-chain diagnostics for
the failing host and its direct console-host children, with numeric object
states and CPU times but no object names, memory or environments. The helper enables an
already-held debug privilege only in its own test process; unavailable access is
reported, never treated as verification. Its three-second watchdog and forced
cleanup do not change the test's success deadlines or outer watchdog. Production
teardown, isolation and release status are unchanged while this wait is diagnosed.

Run 34928490534 again passes JVM 281 and fails the first native-image active
close. Its bounded wait-chain helper succeeds: the closing thread waits for
`conhost.exe`, whose blocked threads depend on another blocked critical-section
owner. The final owner wait is unsupported by WCT; this does not identify a
particular IO call. The next implementation uses Microsoft's alternative teardown
contract: keep draining terminal output until the console closer returns.

After logical close and the last native IO reference, abandoned terminal output
and its event move to the non-recycled process record. Context close kills the
job, quiesces all streams, starts or reuses the console closer, and drains with a
fixed 8KiB buffer independently of Java. It also watches the closer so it does
not wait for an additional EOF after close completes. Normal open-stream output
is never discard-drained. Record reclamation excludes owned output and active
cleanup; errors preserve a poisoned, retryable context and its native handles.
The failed disconnect experiment and all production debug prints are removed.
The suite retains the existing active/reaped cases and adds four prior-logical-
close and four simultaneous-context children per launcher. A reader-thread join
proves prior logical close; shared check counts are now atomic. Confinement,
backpressure thresholds and success watchdogs are unchanged. Concurrent children
also launch eight finite-output terminals in a third context, retaining their
final `PASS` marker while exercising record reclamation during the other closes.

Run `34929767932` at `1002ba2` passes all five platform lanes and the generated
documentation checks. The Windows probes pass 297 checks on each JVM and
native-image launcher, both from the build directory and the extracted archive.
All four active, reaped, consumer-closed and concurrent backpressure children run
in every launcher. Stock Python, the jailed native-worker protocol, standard-user
launch and the documented Java example pass too. Local documentation generation
checks 38 HTML pages and passes its four assertions.

CI run `34930599126` at `bd7239f` again passes every lane, including all four
Windows probe executions. Release run `34930646583`, however, fails before the
guest network checks: binding the host UDP receiver to the port chosen for TCP
returns `Address already in use`. A free TCP port does not reserve that UDP port.
Publication was blocked; the `v0.5.17` tag is preserved unchanged.

The test now lets the OS reserve each TCP/UDP and IPv4/IPv6 listener independently
and passes all four actual ports to the guest. Positive host controls, all eight
LPAC access-denied checks and empty-listener assertions remain mandatory. There
is no retry, skipped check, deadline increase or production isolation change.

CI run `34931609756` and release run `34932355483` pass on all five platforms.
The Windows release lane again passes 297 checks per JVM/native-image launcher,
both before and after archive extraction, including the corrected network
fixture and every backpressured terminal-close case.

Runtime `v0.5.18` is published from `eeae415a40e505b3fb4df8990940ad53ebd6a8eb`
and was the latest stable release then. All eight downloaded assets
match their published sizes and SHA-256 digests. The JVM jar reports `0.5.18` and
contains `WindowsJail`; all five platform archives contain their native enforcers.
The published API ZIP passes local file/fragment link checks across 38 HTML pages,
and the Javadoc jar across 32 pages. Both Java and Clojure Windows APIs are present.

The v0.5.18 release phases are complete. No consumer installation, dependency pin
or running gateway was changed by that release.

## Windows service execution and consumer follow-up

1. Repair noninteractive Windows launch.
   - Rationale: the existing release test fails before the jailed guest reaches main
     when its host runs through SSH in Session 0.
   - Data: JVM and native-image reproduce STATUS_DLL_INIT_FAILED (0xc0000142);
     ordinary guest startup and the separate embedded-runtime suites pass.
     The service window station and desktop have no package access grants.
   - Acceptance criteria: retain a deterministic suite regression; pass the complete
     JVM/native-image suites from source and extracted archives without changing
     existing host ACLs, weakening LPAC, or reducing lifetime/backpressure checks.
   - Data: a new host-only desktop fixture reproduces the failure. Null startup
     desktop metadata inherits an explicitly named parent desktop; token-only SSH
     startup was not sufficient verification. An explicit empty `lpDesktop`, with
     exactly the least-rights inherited station and SID-private desktop handles,
     passes the standard-user private-station regression, including pipes, ConPTY,
     actual UI identity, forbidden rights, sibling/host access and cleanup.
     Production changes no existing host UI ACL, process station or thread desktop.
     The aggregate timeout is separate: the JDK's persisted CryptoAPI seed context
     fails under this passwordless SSH logon and falls back to roughly eight seconds
     of threaded entropy per fresh JVM. Windows-PRNG uses an ephemeral OS context;
     its secure provider works here without changing machine authentication or
     security policy. Directory-name generation now reuses that provider and atomic
     create, retaining the original 64 random bits and bounded IPC path length.
     A longer candidate name exceeded Windows AF_UNIX's path bound and was rejected.
     The complete JVM probe passes 323 checks, including real native-worker AF_UNIX
     exchange. Direct before/after opens verify desktop lifetime without assuming
     that an SSH host can enumerate its window station. Native-image exposed a
     test-helper race writing empty input after a fast ConPTY guest had exited;
     the helper no longer attempts a write when there are no input bytes.
     The PowerShell runner now keeps the Clojure CLI alias literal: ClojureTools
     otherwise interprets a splatted alias token as a filename. Its regression fails
     before the change and passes through the actual Windows module after it.
   - Verification: fresh Windows DLLs, worker and probe images pass the source and
     extracted-archive runs. Every JVM/native-image probe passes 323 checks, including
     the real worker AF_UNIX exchange, private/standard/service UI isolation and all
     16 backpressured ConPTY cases. Both runs pass the Windows API (4 tests / 34
     assertions), native boundary (11 / 68) and documented Java example. Shared suites
     pass 84 / 485 from source and 84 / 482 from the archive; no failures or errors.
     Local Java compilation, 11 / 90 affected tests, Javadoc/local links across 38 HTML
     pages, Clojure formatting/lint/reflection, PowerShell analysis and diff checks pass.
   - CI follow-up: run `34958566674` passes the four non-Windows platforms and docs,
     but exposes an invalid assumption that every host Default desktop denies LPAC.
     A new disposable shared-Default fixture reproduces the failure through SSH:
     `OpenDesktop` succeeds for its explicit `0x81` grant; the old error code was stale.
     The denial fixture now uses a distinct, protected, host-only desktop in the same
     station, with a successful host-open control and verified handle cleanup.
     The private-service Default denial remains tested; the shared Default must allow
     its exact grant and reject stronger rights. Existing SID, sibling, station-right,
     descriptor-preservation, ConPTY and lifetime assertions remain in place.
     This corrects the test oracle, not production confinement. Pre-existing UI access
     follows Windows ACLs, which the API documentation now states explicitly.
     The corrected JVM and rebuilt native-image probes pass 331 checks each, from
     source and the extracted archive. Both runs pass the Windows API (4 / 34),
     native boundary (11 / 68) and Java example; shared suites pass 84 / 485 and
     84 / 482, respectively, with no failures or errors. All eight uploaded source
     hashes match the reviewed checkout. Strict C and Java compilation, 5 release
     tests / 73 assertions and Javadoc/local links across 38 HTML pages also pass.
     A subsequent CI run at `a76f6a3` passes the corrected top-level control but
     fails the private-service stage in an interactive logon: the guest selects
     `WinSta0` while its SID-private desktop cannot be reopened there (error 2).
     Supplying the exact creator-station/private-desktop path is not a valid fix:
     the rebuilt DLL causes `STATUS_DLL_INIT_FAILED` in the first SSH-session JVM
     validation. That candidate has been reverted without changing any permissions.
     The regression now reports its host snapshot and, on an own-desktop failure,
     a bounded snapshot of the guest's UI handles to distinguish handle inheritance
     from station selection. All positive controls and denials remain required.
     Strict C and Java compilation pass. The diagnostic revision passes all four
     331-check JVM/native-image runs, Windows API (4 / 34), native boundary (11 / 68),
     and source/extracted shared suites (84 / 485 and 84 / 482), with zero failures
     or errors. All eight remote source hashes match; 5 local release-contract tests
     / 73 assertions also pass. CI at `25b91d7` confirms that the guest inherits exactly
     one station handle with `WINSTA_READATTRIBUTES` and its SID-private desktop
     with the required rights, but selects a separate `WinSta0` handle. This is a
     station-selection failure, not a missing handle or an ACL-test mismatch.
     Supplying only the private desktop name passes strict C compilation and all
     four SSH source/extracted JVM/native-image probes (331 checks each), Windows
     API (4 / 34), native boundary (11 / 68), and shared suites (84 / 485 and
     84 / 482), without failures or errors. All eight source hashes match the VM.
     However, CI `34967991111` at `877f9cd` rejects that candidate: the interactive
     private-service guest exits with `STATUS_DLL_INIT_FAILED` before any output.
     Desktop-only and fully qualified names are both falsified; inherited desktop
     selection is restored without changing any permissions or assertions. The
     rebuilt restored DLL passes both JVM/native-image probes (331 checks each)
     and 15 Windows API/native tests with 102 assertions and no failures or errors.
     All eight restored source hashes match the VM.
      A separate standard-token fixture cannot create a new window station
      (`ERROR_ACCESS_DENIED`), so the new helper uses the station selected by
      Windows for its own process. It creates the protected SID-private desktop
      there and transfers only the required handles, without changing the host's
      station, desktop or security descriptors. Strict MSVC `/W4 /WX`, Java,
      Clojure formatting/reflection, PowerShell lint and release contracts pass.
      Source and extracted JVM/native-image probes each pass 366 checks. Both
      layouts pass Windows API (4 tests / 34 assertions) and native boundary
      (11 / 68) suites; shared suites pass 84 / 492 and 84 / 489 respectively.
      A regression also verifies that the helper survives runtime staging.
    - Unknowns: interactive CI and the next release's rebuilt assets remain to pass.
2. Resolve generic Windows policy and consumer integration.
   - Rationale: WindowsJail is a copy-in private-workspace API, not the generic
     JailPolicy backend used by Vis shell and REPL processes.
   - Data: live host-path grants, path denies, proxy/open egress, inbound ports and
     local-socket/credential-service grants are not implemented by WindowsJail.
     Low-integrity write checks precede DACL grants on ordinary host files.
      A test fixture evaluates per-silo BindFlt mappings of original paths to a
      WinFsp live view backed by pinned host handles outside the silo. The signed
      WinFsp 2.1 driver loads and its dispatcher starts with Secure Boot enabled,
      but the first mapping returns `0x80070001`; separate NTFS controls are being
      tested before attributing this failure to the filesystem substrate.
      Package-scoped WFP soft and hard permits still return Winsock 10013 for
      loopback. Own-workspace AF_UNIX connects succeed while sibling/outside
      paths fail; abstract sockets fail even in the trusted control. Credential
      tests distinguish an SSH logon without a credential set from a synthetic
      service-logon control; neither establishes a usable LPAC credential grant.
      These fixtures are not a production backend or a policy-support claim.
    - Acceptance criteria: enforce live read/write grants, read-only grants and
      deny-read/write/execute precedence; OFF/PROXY/OPEN egress; every inbound port;
      exact local-socket and credential-service grants through actual native
      descendants. Preserve host ACLs and integrity labels, reject alias/race
      escapes, and retain fail-closed behavior throughout implementation.
    - Unknowns: filesystem composition, race-safe backing resolution, execution
      denial, network/socket/credential mediation, lifecycle, setup and licensing.
      Copying, environment-only proxies and host-wide permission changes do not
      satisfy the policy contract.
3. Verify distribution and the Vis beta.
   - Rationale: runtime-only success does not prove consumer execution.
   - Data: v0.5.18 is immutable; the consumer has not been integrated by this work.
   - Acceptance criteria: publish a new verified runtime, integrate the consumer,
     exercise real SDK workflows and complete the authorized beta pipelines.
   - Unknowns: integration is gated on the actual policy implementation, not just
     the presence of a Windows archive.

Plan state: the bounded UI helper passes source and extracted Windows security
and lifecycle gates; interactive CI and release assets remain to verify. The
Node 20 MSVC action is replaced by a local DevShell setup script; actual x64
exports, workflow lint and PowerShell lint pass. GraalVM CE 25.3.4.1 preloads a
warning count of two even when the driver emits no warnings, as confirmed by
`--dry-run` and upstream issue oracle/graal#14253. A fixed stable CE release is
not yet available; no warning counter or diagnostic is suppressed. The optional
WinFsp test SDK also emits an ABI-alignment warning; production builds do not
include that dependency.

No new release tag is published. Generic Windows policy parity, consumer
integration and the Vis beta remain open. A passing UI helper or filesystem
feasibility probe alone is not full Windows support. Unrelated runtime
diagnostics and Vis edits are excluded.
