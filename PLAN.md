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
