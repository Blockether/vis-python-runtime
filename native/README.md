# native/

`vispython/vispython.c` is the whole native boundary: one flat `extern "C"`
surface (`vispython_initialize`, `vispython_version`, `vispython_eval`,
`vispython_exec`, `vispython_finalize`) over an embedded CPython. Integers and
NUL-terminated UTF-8 cross it; no PyObject ever does, so reference counting
stays where CPython already does it.

`./build.sh` VENDORS the CPython pinned in `.cpython-version` into
`resources/prebuilds/<platform>/python/` and links the shim against it, so the
prebuild directory is the shipping unit: the cdylib and the interpreter tree it
was built for, side by side. `resolve-python-home` finds the tree by that
adjacency and hands it to `Py_InitializeFromConfig`, which is why an
installation never resolves a standard library from the machine.

The upstream build is astral-sh/python-build-standalone. We do not build CPython
ourselves; a build of our own would be one more thing to patch for every CVE on
five platforms. `VIS_PYTHON_SYSTEM=1 ./build.sh` links whatever
`python3-config` reports instead — a fast dev loop, and not shippable, because
the artifact then depends on that installation.

Note for macOS: the upstream `-full` archives carry LLVM BITCODE objects (they
are built with LTO by a newer LLVM than Apple's linker reads), so a static
`libpython3.14.a` does NOT link with the system toolchain. The vendored shared
library is the supported path, and it is self-contained all the same.

Linux archives target Ubuntu 22.04 (glibc 2.35) on x64 and arm64. Both native
libraries and the worker are built and tested on that baseline. Archive verification
checks every ELF file, including vendored CPython and its extension modules, and
rejects newer glibc requirements. Building on a newer distribution can introduce
new symbol versions even when the C source uses no new APIs.

CPython remains a bundled shared library. Statically linking libpython would not
remove glibc requirements from the bridge, worker or third-party extension modules;
statically linking glibc is not a replacement for testing this dynamic-loading ABI.
`visjail/build.sh` produces the second cdylib in each macOS and Linux platform tree. On Linux
it hash-pins bubblewrap and libcap, compiles the upstream bubblewrap sources into
`libvisjail.so`, and statically links libcap; libc is the only host ABI and there
is no `bwrap` executable or package lookup. On macOS the same ABI forks a child,
enters the supplied system Seatbelt profile with `sandbox_init`, and execs the
command. In both cases the JVM calls one spawn/read/write/wait/kill surface and
never assembles an enforcer command.

Consumers get the whole tree from the per-platform archive built with
`clojure -T:build platform-archive :platform <tag>`.

## Collect worker hang evidence

If a worker stops answering Python operations, you can request a private stack
sample before terminating it. Configure it before applying interpreter confinement:
`stack-diagnostics` takes an absolute, non-existing destination in its `code` field
and answers a value with `status: "ready"`. The parent must create the destination's
private directory first. Setup creates an empty `0600` file; it does not grant
Python permission to read or write that path.

Request `dump-stacks` only when you need evidence. It replaces the file and answers
`written`, `unavailable`, `busy` or `failed`, with a fixed reason when relevant.
An empty `stack-diagnostics` path closes the retained descriptor. Normal worker
connection shutdown also attempts this cleanup. A closed or replaced descriptor
is not reused for a later dump.

Stack collection does not acquire the GIL or install or deliver signals. It copies
memory through the operating system before inspecting it, so concurrent thread or
frame removal produces incomplete samples instead of dereferencing freed memory.
The implementation currently supports release, GIL-enabled CPython **3.14.7** on
macOS and Linux. Other builds, including Windows, answer `unsupported-build`;
an operating-system policy that denies memory sampling answers `memory-read-failed`.
A CPython update requires reviewing the pinned private layouts before enabling it.

Each sample is limited to 64 threads, 64 frames per thread and 100 code points per
file or function name. It records definition lines and bytecode offsets, **not
current source lines**. Names and paths may contain private information: keep the
artifact local and review it before sharing. Source text and local values are not
included. This file is separate from the payload-free diagnostic ring.

Always bound the complete control exchange in the parent, including socket writes.
Disk IO can still stall; unavailable or partial evidence must not prevent worker
retirement. Sampling is best effort, not a consistent debugger snapshot.

## Windows x64

Use PowerShell 7.3 or newer in a Visual Studio x64 Native Tools environment:

```powershell
./native/vispython/build.ps1
clojure -T:build javac
clojure -T:build worker-image
clojure -T:build windows-jail-probe
./scripts/test-windows.ps1
clojure -T:build platform-archive :platform windows-x64
./scripts/verify-platform-archive.ps1
```

The archive contains `vispython.dll`, `visjail.dll`, `vis-python-worker.exe`, a complete
`python/` tree and `python/Scripts/uv.exe`. CPython, uv and GraalVM CE downloads
use checked-in SHA-256 pins. The build uses MSVC, not MinGW or a POSIX emulation
layer. CI builds and executes the runtime on Windows Server 2022 x64, including
the extracted archive; Windows 11 x64 is the desktop target.

`visjail/build.ps1` builds the Windows process backend alongside the interpreter.
`WindowsJail` uses LPAC, private copied application files and Job Objects, without
network capabilities or permissions on the original source trees. It is not a
translation of Unix `JailPolicy`; see the [Windows jail guide](../doc/windows-jail.md)
and `visjail/visjail.h` for the Java/Clojure and C contracts. The Windows test script
executes JVM and native-image jail launchers before and after archive extraction;
the test probe executables are not shipped.
Windows ARM64 is not a release target. This runtime support does not imply that
all Vis gateway, terminal or workspace features run natively on Windows.
