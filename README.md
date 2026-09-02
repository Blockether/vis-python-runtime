# vis-python-runtime

Embedded CPython for the Vis sandbox — vendored per platform, reached over the
JDK Foreign Function & Memory API, with no Truffle in the native image.

## Why

Vis executes sandbox Python (`packages/vis-agent` and every shim in
`resources/vis-shims/`) inside GraalPy. That is roughly **300 MB** of the
shipped binary: `python-language` alone is 95 MB of jar, plus `truffle-api`,
`icu4j-shadowed` and `regex`, and SVM expands what it compiles in. GraalPy also
does not refcount, so a dropped handle stays alive for the life of the JVM and
the sandbox has to keep an ownership registry by hand.

A vendored CPython behind a small C ABI is on the order of **20 MB** of shared
library plus its standard library, brings real refcounting, the full C-API and
CPython's own speed.

## Shape

    java/                   the bridge: FFM downcalls, the host upcall, pip
    src/                    the Clojure API over it, and nothing else
    native/vispython/       the C shim + the CPython vendoring build
    native/bubblewrap/      the static Linux process-enforcer build
    resources/vispython/    the guest half of the boundary (`vis_runtime.py`)
    resources/vis-python/   Vis' sandbox modules, mirrored byte for byte
    resources/prebuilds/    build output per platform (git-ignored)
    test/                   clojure -T:build javac && clojure -M:test

Nothing is published to Clojars. Consumers take the JVM API directly from a
release commit as a tools.deps git dependency; each tagged GitHub Release also
carries the convenience jar and the complete
`vis-python-runtime-<platform>-<version>.tar.gz` archives. Vis downloads the one
archive for its platform, unpacks it and names the selected cdylib with
`runtime/use-library!`. Every Linux archive includes its own static `bin/bwrap`;
`resolve-bubblewrap` finds it beside that cdylib and never searches `PATH`.

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
