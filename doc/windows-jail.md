# Run isolated Windows programs

Use `WindowsJail` when you need to run a Windows program outside the embedded
interpreter, with an OS boundary around it and its descendants. You select the
program and inputs, copy them into a private workspace, then launch the process
and collect its output.

This is a native Windows launcher, not a VM or a Unix mount-namespace emulator.
It uses a unique low-privilege AppContainer (LPAC) and Job Objects. It installs no
service or driver and requires no administrator setup. Network capabilities are
disabled. The Java API returns a `Process`; Clojure provides thin wrappers for
the same implementation.

## Before you start

Use Windows 11 or Windows Server 2022 on x64, a supported JDK, and matching JVM
and Windows platform archives. The unpacked archive must contain both
`vispython.dll` and `visjail.dll`. Start the JVM with
`--enable-native-access=ALL-UNNAMED`, as in the [quickstart](getting-started.md).
`WindowsJail.unsupportedReason()` reports a missing platform or archive; creating
the context checks the native security prerequisites and fails closed.

Choose an existing local directory where the host can create a new workspace.
Keep the host outside the jail. Do not run the launcher itself inside an
untrusted task or use it to grant arbitrary project trees permissions.

## Create, stage, run, close

Each context creates three directories:

| Directory | Guest access | What you put there |
|---|---|---|
| `app` | Read and execute after sealing | Copies of programs and read-only inputs |
| `work` | Read and write | Task inputs that may change, plus results |
| `tmp` | Read and write | Private temporary files; used for `TEMP` and `TMP` |

`stage` copies contents, not security descriptors. It leaves the source files and
ACLs unchanged, rejects links and reparse points, and never overwrites a staged
destination. Stage complete program directories when an executable needs nearby
DLLs or data. Copying a large runtime has a cost; reuse one context for related
launches rather than staging it again for every command.

The first spawn seals `app`; later staging is rejected. Use a new context for
another mutually untrusted task. Processes within one context share its identity
and writable directories.

### Java

This example uses the stock Python from the unpacked archive, not an embedded
Python session:

```java
Native.use("C:/runtime");
try (WindowsJail jail = WindowsJail.create(Path.of("C:/tasks"))) {
  Path python = jail.stage(Path.of("C:/runtime/python"), "python")
      .resolve("python.exe");
  Process process = jail.spawn(
      List.of(python.toString(), "-I", "-c", "print(6)"),
      Map.of(),
      null, false, true, 0, 0);
  process.getOutputStream().close();
  String output = new String(process.getInputStream().readAllBytes(),
      StandardCharsets.UTF_8);
  if (process.waitFor() != 0) throw new IOException(output);
  System.out.print(output);
}
```

Expected output is `6`. The executable must be an absolute staged path or an
absolute Windows System32 program. The launcher never searches `PATH` for it.
A complete runnable example is included as `examples/WindowsJailExample.java` in
the documentation archive.

### Clojure

```clojure
(require '[com.blockether.vis-python-runtime :as runtime])

(runtime/use-library! "C:/runtime")
(with-open [jail (runtime/windows-jail "C:/tasks")]
  (let [python (str (runtime/stage-windows-jail! jail "C:/runtime/python" "python")
                    "/python.exe")
        process (runtime/spawn-windows-process!
                  jail [python "-I" "-c" "print(6)"]
                  {:merge-stderr? true})]
    (.close (.getOutputStream process))
    (print (slurp (.getInputStream process)))
    (assert (zero? (.waitFor process)))))
```

Use `windows-jail-directories` to get the directory paths. The host can prepare
writable inputs in `:work` before launching a process. Do not modify the staged
application while guests are running.

## Environment, streams and lifetime

The environment is **complete**, not additions to the host environment. Pass
only the values the program needs; an empty map is valid. The launcher supplies
three reserved values: `TEMP` and `TMP` point to the private temporary directory,
and `SystemRoot` comes from Windows itself so the OS can initialize the process.
Caller values cannot override these, even with different capitalization. No
other host environment values are copied, and an environment marker cannot
bypass Windows confinement.

The working directory is relative to `work`; null/nil or an empty string means
its root. It must exist. Dot components, device names and reparse-point paths
are rejected rather than normalized into a different location.

Pipes keep stdout and stderr separate unless you request merging. For ConPTY,
set `pty`/`:pty?` and positive rows and columns; terminal output combines both
streams. Drain output while writing large inputs to avoid ordinary pipe
backpressure. Close process streams when finished.

Both `destroy()` and `destroyForcibly()` terminate the Windows job and its
descendants; there is no POSIX signal delivery or graceful-termination promise.
`supportsNormalTermination()` returns false. When the main process exits, its
remaining descendants are terminated. Closing a context terminates every
remaining launch, releases native resources and deletes its Windows profile.
If cleanup fails, `close()` throws; you can call it again to retry.

Closing **retains** the private directory and outputs for the host. Inspect them
as untrusted task data; do not follow guest-created links into other directories
when exporting or deleting results. A host crash also closes the kill-on-close
jobs, but may leave the private directory and AppContainer profile behind.
Failed creation also attempts profile cleanup; if that fails, the error names
the profile that may need removal.

## Security contract and limits

The boundary is the Windows token, ACL checks and jobs, not a Python wrapper.
The launcher creates the process suspended, supplies only its intended standard
handles, assigns its jobs, and resumes it only after setup succeeds. Each context
has a separate AppContainer identity with private Windows-managed profile folders
and registry storage, in addition to the retained workspace. Files in another
context and private host files without LPAC access are not granted to it.

Windows' existing LPAC-accessible system and public resources remain subject to
their OS ACLs. This API does not provide a filtered drive namespace or replace
Windows' global object-security model. Do not describe it as a path-based
allowlist over the entire machine. It also does not protect against a compromised
kernel, an administrator or the trusted host, and supplies no CPU, memory or disk
quota.

These Unix `JailPolicy` features are **not supported** by this API:

- In-place read/write mounts of existing host trees and nested path deny lists.
- Network access, exact proxy-port exceptions and additional inbound ports.
- Credential-store grants and Unix control-socket exceptions.
- Reusing an environment marker as proof of inherited confinement.

`Jail.spawn` / `spawn-process!` still rejects Windows, rather than quietly
changing that policy's meaning. `spawn-windows-process!` rejects unknown option
keys. If a copy or seal operation fails partway through, close that context and
create another; never launch a partially prepared application.

## Reference and verification

The generated documentation includes Javadoc for `WindowsJail` and
`JailedProcess`, and Codox for the Clojure wrappers. Native embedders use the
Windows context functions declared in `native/visjail/visjail.h`, followed by the
shared spawn and stream ABI. Context, process and stream IDs are opaque integers,
not Windows HANDLE values; the PID query is descriptive, not a kill target.

The Windows CI lane runs filesystem, token, network and process-lifecycle probes
through both JVM and native-image **launchers**, then repeats them against the
extracted platform archive. A compiled DLL or a native worker running as a guest
alone is not treated as proof that this boundary works.
