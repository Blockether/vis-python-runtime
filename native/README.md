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

Linux builds also run `bubblewrap/build.sh`. It pins bubblewrap and libcap,
links both into one static musl executable, and puts it at `bin/bwrap` in the
same platform tree. The artifact therefore borrows neither a system `bwrap`
nor its libc. Building it requires `musl-tools`, Meson, Ninja, pkg-config and
Linux UAPI headers; the script verifies both source hashes and executes a real
namespace before accepting the binary.

Consumers get the whole tree from the per-platform archive built with
`clojure -T:build platform-archive :platform <tag>`.
