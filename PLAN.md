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
     every archive ELF passes the ABI gate; extracted workers pass worker tests.
   - Unknowns: Linux native execution is unavailable on this macOS host because
     its container VM is stopped. Workflow execution remains required.
2. **Vis consumers**
   - Rationale: engine, TUI and downloaded Python sidecar must be compatible together.
   - Data: Vis native-release workflow, Dockerfile, `bin/verify-linux-abi`, release tests.
   - Acceptance criteria: baseline builders, ABI gates, native/SDK/PTY tests before
     publication. Preserve concurrent pipeline-order changes from another session.
   - Unknowns: current released runtime assets are unchanged; a verified new runtime
     release and subsequent Vis dependency pin are needed to deliver the correction.
3. **Verify and deliver**
   - Rationale: source tests alone do not prove native Linux compatibility.
   - Data: affected tests, shellcheck, actionlint, formatting/lint and native artifacts.
   - Acceptance criteria: local checks pass; Linux builds and runtime tests pass;
     release/pin only with explicit authorization, without moving existing tags.
   - Unknowns: no runtime release or external workflow dispatch was requested.

## Plan state

Source changes implemented in both checkouts. Regression tests first failed on the
old runner baseline and now pass. Vis release tests pass (68 cases); runtime source,
ABI and worker tests pass (16 tests, 173 assertions) on macOS. The existing native
macOS TUI passes its real PTY resize/highlighting test. Formatting, Clojure lint
(including reflection), shellcheck, actionlint and diff checks pass.

Linux native verification, new runtime artifacts and the consuming Vis pin remain
pending. The local container engine cannot connect to its stopped VM; no Linux build
or new native artifact is claimed. No runtime release, commit or push performed.
