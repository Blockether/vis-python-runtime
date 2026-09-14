# Embed Python safely and predictably

Use the Java `Interpreter` API or the Clojure
`com.blockether.vis-python-runtime` namespace. They expose the same native runtime;
the Clojure functions translate arguments and return values, not execution behavior.

## Initialize once, close sessions when finished

1. Choose the unpacked native archive with `Native.use(path)` or `use-library!`.
   Otherwise resolution uses `VIS_PYTHON_NATIVE_PATH`, then a prebuild resource in
   a source checkout. Nothing downloads a runtime during library loading.
2. Initialize the interpreter once per process. It stays loaded for the process
   lifetime. Session names select separate globals, not separate interpreters.
3. Apply process policy before installing runtime modules or executing guest code.
4. Call `installRuntime(session)` / `install-runtime!` for block execution and
   installed host tools. This also processes installed `.pth` files, which may run
   code; only install packages you trust.
5. Call `closeSession(session)` / `close-session!` to release a session's globals
   and owned handles. This does not unload the library or clear shared imports.

Initialization accepts source paths, Python home, bytecode cache, and package
location. In Java, `Interpreter.DEFAULT` requests the resolved default and `null`
turns a location off. In Clojure, omit the map key for its default or supply `nil`
to turn it off. Disabling Python home asks CPython to resolve its own standard
library; it does not produce a self-contained deployment.

## Choose an execution API

| Java | Clojure | Input | Result |
| --- | --- | --- | --- |
| `Interpreter.eval` | `eval-str` | One Python expression | Python `str(result)` as JVM text |
| `Interpreter.exec` | `exec!` | Python statements | No return value; changes session globals |
| `Interpreter.run` | `run` | Statements and optional trailing expression | Result encoded as JSON text |
| `Interpreter.runBlock` | `run-block` | A runtime block, including supported `await` calls | JSON with `stdout` and `error` |

`eval` does not deserialize Python objects into Java objects. Use `run` and your
JSON reader when you need numbers, lists, or maps. In `runBlock`, only printed text
is successful output; a trailing expression is discarded. `error` is `null` on
success, otherwise a Python error string. Output printed before an error remains
in `stdout`.

Expression and statement failures throw `VisPythonException`. Block-level Python
failures are represented by the block JSON, while loading or bridge failures can
still throw. Use `exception.data()` in Java or `(.data exception)` in Clojure for
available diagnostic fields such as `symbol`, `status`, `platform`, or `path`.
Fields depend on the failure: do not assume every exception has all of them.

For module-style code that must own an `asyncio` event loop, do not call
`asyncio.run` inside an already asynchronous block. The synchronous `exec` path
runs ordinary Python statements; block execution already has its own async runner.

## Expose a host callback

One callback is bound for the whole interpreter. Authorize each request against the
callback's **caller session**, not a `session` field supplied in the JSON payload.
An empty caller means the runtime could not associate the call with a session;
reject it rather than guessing.

```java
Interpreter.bindHost((caller, name, payload) -> {
    if (!"example".equals(caller) || !"greeting".equals(name)) {
        return "{\"error\":\"Host call denied\"}";
    }
    return "{\"value\":\"Hello from Java\"}";
});
Interpreter.installSyncTool("example", "greeting");
System.out.println(Interpreter.eval("example", "greeting()"));
// Hello from Java
```

The Clojure equivalent is:

```clojure
(python/bind-host!
  (fn [caller name payload]
    (if (and (= caller "example") (= name "greeting"))
      "{\"value\":\"Hello from Clojure\"}"
      "{\"error\":\"Host call denied\"}")))
(python/install-sync-tool! "example" "greeting")
(python/eval-str "example" "greeting()") ; => "Hello from Clojure"
```

These examples follow the initialization and runtime installation steps above.
The tool protocol carries `{"args": [...], "kwargs": {...}}` in requests and
`{"value": ...}` or `{"error": "..."}` in replies. Use a JSON library to parse
arguments and encode real results rather than interpolating untrusted text.
`installSyncTool` creates an ordinary Python function; `installTool` creates a
runtime tool that can be awaited from a block.

Callbacks may run concurrently on different threads while Python releases the
GIL. Keep callback state thread-safe and **do not re-enter the interpreter** from
a callback. At shutdown, unbind your callback with `bindHost(null)` or
`(bind-host! nil)` after guest calls finish.

## Concurrency is shared, not isolated

There is one CPython interpreter and one GIL per process. Sessions keep separate
globals but share imported modules and process policy. CPU-bound Python does not
become parallel just because callers use different session names. The bridge does
allow other work to proceed while a host callback releases the GIL.

Filesystem roots, network access, stdin, thread limits, and diagnostics are
process-wide. Configure them centrally; changing a policy for one session changes
it for all sessions. Use separate worker processes when workloads need distinct
trust or policy boundaries. A trusted extension must not become an unconfined
session sharing the sandbox's interpreter.

`interrupt` / `interrupt!` requests `KeyboardInterrupt` from another thread.
Delivery happens at a Python execution boundary, not necessarily while Python is
blocked in native code or a host callback. It is not a hard timeout. A host that
requires a hard deadline needs control of the worker process as well.

## Confinement has two layers

**Embedded-runtime policy:** `confine` / `confine!` applies filesystem roots in
native runtime state and blocks restricted process/FFI operations through the
audit boundary. The interpreter installation and bytecode cache are added to the
allowed roots. Two empty root lists remove filesystem confinement. `network` /
`network!` grants or denies network access for the whole interpreter; host/domain
selection belongs to the host's network proxy. `threads` / `threads!` controls
thread and runtime-pool limits.

**OS child-process policy:** `Jail.spawn` / `spawn-process!` uses Seatbelt on macOS
and the bundled Linux namespace enforcer on Linux. Check `Jail.unsupportedReason`
or `jail-unsupported-reason` before relying on that path-based policy.

On Windows, use [`WindowsJail` / `windows-jail`](windows-jail.md). This separate
contract uses a low-privilege AppContainer, copied read-only application files
and private writable directories, with network capabilities disabled. It does
not translate Unix path grants or deny lists into Windows ACL edits. Unsupported
path-policy requests still fail rather than launch without enforcement. An
embedded Windows session alone is not an OS-confined worker.

The runtime is not a virtual machine, and a session name alone is not a security
boundary. Review the native packages and host capabilities you expose. Do not
configure a trusted filesystem capability as a way to bypass guest policy.

## Keep diagnostics bounded

Logging is off by default. Enable `logging` / `logging!` explicitly and drain the
native ring with `drainLog` / `drain-log!`, or attach a periodic sink using `drainTo`
/ `logs!`. The records are NDJSON, not guest output. They carry host-chosen names,
counts, and durations, not guest source or payloads. A full ring can drop records;
a subsequent drain reports the loss.

## Generate the reference locally

From a source checkout with a full JDK 25:

```sh
clojure -T:build javac
clojure -X:docs
```

Open `target/docs/index.html`. The build uses standard Javadoc for Java and Codox
for Clojure, compiles the Java example, and fails on malformed Javadoc. Generated
files stay under `target/`; documentation dependencies exist only in the `:docs`
alias and are not shipped runtime dependencies.
