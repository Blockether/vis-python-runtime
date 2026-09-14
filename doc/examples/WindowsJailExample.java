import com.blockether.vispython.Native;
import com.blockether.vispython.WindowsJail;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Run on Windows: WindowsJailExample <unpacked-runtime> <existing-workspace-parent>. */
public final class WindowsJailExample {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected runtime directory and existing workspace parent");
    }
    Path runtime = Path.of(args[0]).toAbsolutePath();
    Native.use(runtime.toString());
    Path outputs;
    try (WindowsJail jail = WindowsJail.create(Path.of(args[1]))) {
      Path python = jail.stage(runtime.resolve("python"), "python").resolve("python.exe");
      Process process = jail.spawn(List.of(python.toString(), "-I", "-c",
          "from pathlib import Path; Path('result.txt').write_text('6'); print(6)"),
          Map.of("SystemRoot", System.getenv("SystemRoot")), null, false, true, 0, 0);
      try (var input = process.getOutputStream(); var output = process.getInputStream()) {
        input.close();
        // This command has tiny output; large-output programs need concurrent readers.
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException("Confined Python did not finish within 30 seconds");
        }
        String text = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IOException(text);
        System.out.print(text);
      }
      outputs = jail.workDirectory();
    }
    if (!"6".equals(Files.readString(outputs.resolve("result.txt")))) {
      throw new IOException("Expected a retained result file containing 6");
    }
    System.out.println("Retained outputs: " + outputs);
  }
}
