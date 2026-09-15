# Get started with Java or Clojure

Use vis-python-runtime when your JVM application needs to run CPython and native
Python packages in-process. The library bundles CPython separately from its JVM
jar, so you do not need a system Python installation.

## Get a matching runtime

You need **JDK 22 or newer** and a platform archive matching your operating system
and CPU. Build and release checks use JDK 25. Download these assets from the same
[release](https://github.com/Blockether/vis-python-runtime/releases):

- `vis-python-runtime.jar` — Java classes, the Clojure API, and Python runtime sources.
- `vis-python-runtime-<platform>-<version>.tar.gz` — the native bridge and Python tree.
- The documentation ZIP — this guide, searchable Java and Clojure references,
  and a runnable Java example. Extract it and open `index.html`.

Released platform tags are `linux-x64`, `linux-arm64`, `darwin-x64`, and
`darwin-arm64`. Linux requires glibc 2.35 or newer. WSL2 uses the matching Linux
archive. Windows support is still in development; no Windows archive is published
by this release.

The Windows examples below are for experimental source builds on Windows 11 or
Windows Server 2022 x64. Such builds need `vispython.dll`, `visjail.dll`, the native
worker and the matching `python/` tree together. They are not a supported release.

The examples below embed trusted Python. To isolate a separate Windows program,
follow [Run isolated Windows programs](windows-jail.md), which explains its
private-copy model and limits. Unix `JailPolicy` requests are not translated on
Windows. Read [embedding and confinement](embedding.md) before running untrusted
code.

Start the JVM with `--enable-native-access=ALL-UNNAMED`. This is required for the
JDK Foreign Function & Memory calls, not an instruction to weaken Python policy.

## Java: run an expression

```java
import com.blockether.vispython.Interpreter;
import com.blockether.vispython.Native;
import java.util.List;

public class HelloPython {
    public static void main(String[] args) {
        Native.use(args[0]); // Unpacked platform archive directory
        Interpreter.initialize(List.of(), Interpreter.DEFAULT, Interpreter.DEFAULT, null);
        try {
            Interpreter.exec("example", "values = [1, 2, 3]");
            System.out.println(Interpreter.eval("example", "sum(values)"));
        } finally {
            Interpreter.closeSession("example");
        }
    }
}
```

Save this as `HelloPython.java`. On Linux or macOS:

```sh
javac -cp vis-python-runtime.jar HelloPython.java
java --enable-native-access=ALL-UNNAMED -cp .:vis-python-runtime.jar HelloPython /path/to/runtime
```

In Windows PowerShell, use a semicolon between classpath entries and quote paths
that contain spaces:

```powershell
javac -cp vis-python-runtime.jar HelloPython.java
java --enable-native-access=ALL-UNNAMED -cp ".;vis-python-runtime.jar" HelloPython "C:\runtime"
```

Both commands print `6`. This example runs trusted code without confinement. Its
last initialization argument is `null`, so it does not add the shared package
directory. Use `Interpreter.DEFAULT` there when you want the default package path.

## Clojure: use the same interpreter

Add the library as a tools.deps git dependency using the exact commit from your
chosen release. There is no Clojars runtime artifact:

```clojure
{:deps {com.blockether/vis-python-runtime
        {:git/url "https://github.com/Blockether/vis-python-runtime"
         :git/sha "<full release commit SHA>"}}
 :aliases {:python {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Run `clojure -X:deps prep` once to compile the Java bridge, then start
`clojure -M:python`:

```clojure
(require '[com.blockether.vis-python-runtime :as python])

(python/use-library! "/path/to/unpacked/runtime")
(python/initialize! {:packages nil})

(try
  (python/exec! "example" "values = [1, 2, 3]")
  (python/eval-str "example" "sum(values)") ; => "6"
  (finally
    (python/close-session! "example")))
```

On Windows, use a Clojure string such as `"C:/runtime"` or
`"C:\\runtime"`. `use-library!` and Java's `Native.use` accept either the
unpacked directory or the native library file. Choose it **before the first native
call**: selecting another path later does not unload an already loaded interpreter.

## Capture printed output

An expression result and a block's printed output are different APIs. Install the
runtime in your session, then run a block:

```clojure
(python/install-runtime! "example")
(python/run-block "example" "print('hello')")
;; => JSON text containing {"stdout":"hello\n","error":null}

(python/run-block "example" "1 + 2")
;; => JSON text containing {"stdout":"","error":null}

(python/close-session! "example")
```

Java's equivalents are `Interpreter.installRuntime` and `Interpreter.runBlock`.
A trailing expression is not output in a block: call `print(...)` when you want to
return text. Parse returned JSON with your application's JSON library.

For initialization details, callbacks, errors, and concurrency, continue with
[embedding and confinement](embedding.md).
