# vis-python-runtime

Run CPython from Java or Clojure, including native Python packages. The runtime
bundles its own interpreter and is used by [Vis](https://github.com/Blockether/vis).

**Platforms:** Linux x64/arm64, macOS x64/arm64, and Windows x64.
You need JDK 22 or newer; builds and release tests use JDK 25. Linux requires
glibc 2.35 or newer. On Windows, use the explicit
[private-workspace jail](doc/windows-jail.md) for OS-level process isolation;
Unix path-based `JailPolicy` requests still fail closed.

## Start here

Download the JVM jar and matching platform archive from
[Releases](https://github.com/Blockether/vis-python-runtime/releases), keeping both
at the same version. Unpack the platform archive without separating the native
library from its `python/` tree. No system Python installation is required.

- **[Java and Clojure quickstarts](doc/getting-started.md)** — installation, working examples, and expected output.
- **[Embedding guide](doc/embedding.md)** — execution APIs, callbacks, session lifecycle, errors, and confinement.
- **[Run isolated Windows programs](doc/windows-jail.md)** — copied inputs, private workspaces, process cleanup, and security limits.
- **API reference:** download the documentation ZIP from the release, extract it,
  and open `index.html` for searchable **Javadoc** and **Codox** references.
  The release also includes a standard Javadoc jar for IDEs.

## Java example

```java
import com.blockether.vispython.Interpreter;
import com.blockether.vispython.Native;
import java.util.List;

public class Example {
    public static void main(String[] args) {
        Native.use(args[0]); // Unpacked platform archive directory
        Interpreter.initialize(List.of(), Interpreter.DEFAULT, Interpreter.DEFAULT, null);
        try {
            System.out.println(Interpreter.eval("example", "sum([1, 2, 3])")); // 6
        } finally {
            Interpreter.closeSession("example");
        }
    }
}
```

Start Java with `--enable-native-access=ALL-UNNAMED`. See the quickstart for
classpath commands on Linux, macOS, and Windows, or use the
[runnable Java example](doc/examples/Example.java), which also demonstrates block
output and a host callback.

## Clojure example

After adding the release commit as a tools.deps git dependency and preparing the
Java bridge:

```clojure
(require '[com.blockether.vis-python-runtime :as python])

(python/use-library! "/path/to/unpacked/runtime")
(python/initialize! {:packages nil})
(try
  (python/eval-str "example" "sum([1, 2, 3])") ; => "6"
  (finally
    (python/close-session! "example")))
```

These examples run trusted code without confinement. Sessions have separate
globals but share one interpreter, imported modules, and the GIL. A session is
not a security boundary; read the embedding guide before running untrusted code.

## Build the API documentation

```sh
clojure -T:build javac
clojure -X:docs
```

Open `target/docs/index.html`. Java reference comes from Javadoc comments; Clojure
reference comes from namespace and function docstrings through Codox. The build
also compiles the Java example. Generated files stay out of source control and
documentation tools add no runtime dependencies.

## License

[MIT](LICENSE).
