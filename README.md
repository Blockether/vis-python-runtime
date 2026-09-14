# vis-python-runtime

Run CPython from Java or Clojure, with support for native Python packages.
Used by [Vis](https://github.com/Blockether/vis) to run Python in its sandbox.
Supports Linux and macOS.

## Java example

You need JDK 22 or newer. Download the JVM jar and the matching platform archive
from [Releases](https://github.com/Blockether/vis-python-runtime/releases).
Add the jar to your classpath, unpack the archive, and start Java with
`--enable-native-access=ALL-UNNAMED`.

```java
import com.blockether.vispython.Interpreter;
import com.blockether.vispython.Native;
import java.util.List;

public class Example {
    public static void main(String[] args) {
        Native.use("/path/to/unpacked/runtime"); // Directory containing libvispython
        Interpreter.initialize(List.of(), Interpreter.DEFAULT, Interpreter.DEFAULT, null);
        System.out.println(Interpreter.eval("example", "sum([1, 2, 3])")); // 6
    }
}
```

This example runs trusted code without confinement. Sessions share one interpreter
and GIL. See the [Java API](src/java/com/blockether/vispython/Interpreter.java)
for sandbox controls, or the [Clojure API](src/clj/com/blockether/vis_python_runtime.clj)
for Clojure usage.

## License

[MIT](LICENSE).
