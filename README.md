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

    src/                    the JVM half: resolution, the FFM bridge
    native/vis-python/      the C shim + the vendoring build
    resources/prebuilds/    build output per platform (git-ignored)
    test/                   clojure -M:test

The jar carries no library. It resolves one at runtime from
`VIS_PYTHON_NATIVE_PATH`, else from the classpath resource
`prebuilds/<platform>/<file>` shipped by
`com.blockether/vis-python-runtime-native-<platform>`.

```clojure
(require '[com.blockether.vis-python-runtime :as runtime])
(runtime/platform)         ;=> "darwin-arm64"
(runtime/resolve-library)  ;=> {:source :env :path "/…/libvispython.dylib"}
```

## Status

Phase 0: repository shape, resolution and versioning. The FFM bridge and the
native build are the next phases — see `PLAN.md`.

## License

MIT.
