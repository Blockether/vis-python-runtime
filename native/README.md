# native/

`vis-python/vispython.c` is the whole native boundary: one flat `extern "C"`
surface (`vis_python_initialize`, `vis_python_version`, `vis_python_eval`,
`vis_python_exec`, `vis_python_finalize`) over an embedded CPython. Integers and
NUL-terminated UTF-8 cross it; no PyObject ever does, so reference counting
stays where CPython already does it.

`./build.sh` compiles it for this machine into `resources/prebuilds/<platform>/`
(git-ignored build output). It links whatever `python3-config` reports, which
makes the artifact depend on that install: fine for development, replaced by a
static libpython before anything ships — the C source and the JVM binding do not
change when that happens.

Consumers get the library as the per-platform artifacts
`clojure -T:build native-jar :platform <tag>` publishes.
