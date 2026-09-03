# vis-python-runtime

Embedded CPython for the Vis sandbox, vendored per platform and reached through
the JDK Foreign Function & Memory API.

## Why

A vendored CPython behind a small C ABI gives Vis real reference counting, the
full C extension ecosystem and CPython's own speed without exposing the raw
CPython API to the JVM.

## Shape

    src/java/               FFM bridges: CPython, process jail, pip
    src/clj/                the Clojure API over them, and nothing else
    native/vispython/       the C shim + the CPython vendoring build
    native/visjail/         the process ABI: bubblewrap / Seatbelt
    resources/vis-python/   ALL the Python this library ships: the guest half of
                            the boundary (`vis_runtime.py`) and the sandbox runtime
    resources/vis-python-runtime/  SOURCES: the manifest naming every file above
    resources/prebuilds/    build output per platform (git-ignored)
    test/                   clojure -T:build javac && clojure -M:test

Nothing is published to Clojars. Consumers take the JVM API directly from a
release commit as a tools.deps git dependency; each tagged GitHub Release also
carries the convenience jar and the complete
`vis-python-runtime-<platform>-<version>.tar.gz` archives. Vis downloads the one
archive for its platform, unpacks it and names the installation with
`runtime/use-library!`. Every archive carries adjacent `libvispython` and
`libvisjail` cdylibs. The latter compiles upstream bubblewrap into the library on
Linux and enters the operating system's Seatbelt policy on macOS; neither backend
uses `PATH` or requires a separately installed enforcer.

```clojure
{:deps
 {com.blockether/vis-python-runtime
  {:git/url "https://github.com/Blockether/vis-python-runtime.git"
   :git/sha "<release-commit>"}}}
```

## Status

The embedded bridge, vendored interpreter, confinement, host calls, pip-backed
packages and per-platform packaging are implemented. Linux packaging also ships
the process-level enforcer required to contain native extension modules. The Vis
consumer migration and native-image verdict remain tracked in `PLAN.md`.

## License

MIT.
