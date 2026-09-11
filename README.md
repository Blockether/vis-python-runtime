# vis-python-runtime

Embedded CPython for the Vis sandbox, vendored per platform and reached through
the JDK Foreign Function & Memory API.

## Why

A vendored CPython behind a small C ABI gives Vis real reference counting, the
C extension support and CPython's own speed without exposing the raw
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

`install-runtime!` binds `VIS_PYTHON_RUNTIME_VERSION` in each Python session from the
embedded library's version resource. This is the runtime library version, not CPython's
version or a pip package version. Vis supplies its own build and SDK metadata separately.

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
the process-level enforcer required to contain native extension modules.

Confinement refuses native calls through `ctypes`. Trusted extensions that need
those calls, including SciPy's callback initialization, must run in a separate
unconfined worker. They must not share interpreter memory or host-call authority
with model code. Object results cross as public data, not interpreter references.
Test representative operations through the worker boundary, not just installation.

## TLS policy

The host can set `VIS_PYTHON_TLS_STRICT=false` before interpreter initialization
for compatibility with trusted legacy CAs. The default is `true` (unmodified
Python behavior); other values are rejected. Initialization imports `tls_policy`
before guest code or package hooks run. Repeated initialization is idempotent.

Compatibility mode removes only `ssl.VERIFY_X509_STRICT` from assignments to
`ssl.SSLContext.verify_flags`, including stdlib and library context factories.
All other flags, certificate trust, signatures, expiry and hostname checking
remain unchanged. It neither installs CAs nor retries failed handshakes. It is
not an enforcement boundary against guest code configuring its own contexts.
It does not affect other TLS implementations or subprocesses. A new worker is
required to change policy; the host must configure sandbox and trusted workers.

## License

MIT.
