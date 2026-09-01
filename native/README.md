# native/

`vis-python/vispython.c` is the whole native boundary: one flat `extern "C"`
surface (`vis_python_initialize`, `vis_python_version`, `vis_python_eval`,
`vis_python_exec`, `vis_python_finalize`) over an embedded CPython. Integers and
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

Consumers get the library as the per-platform artifacts
`clojure -T:build native-jar :platform <tag>` publishes.
