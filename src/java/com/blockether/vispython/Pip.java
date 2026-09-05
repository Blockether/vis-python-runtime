package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Installing packages: the only way the sandbox ever gets one.
 *
 * <p>The artifact BUNDLES NOTHING. It is an interpreter, its standard library
 * and pip; every real distribution arrives here, into one directory beside the
 * bytecode cache, and every session imports from it. So a shipped tree is the
 * same bytes on every machine, a package the user chose survives the next
 * release, and there is no requirements file anybody has to re-decide.
 *
 * <p>Two things make this the HOST's job and never a block's. pip runs in a
 * process of its own - the embedded interpreter is confined, and being one
 * interpreter for the whole process it would carry an installer's imports and
 * monkeypatches into every session after it. And installing reaches an index,
 * which is precisely what a sandbox is for refusing: a block that could install
 * could write its own next payload.
 *
 * <p>Trust is owned by {@link Trust}. Index and HTTP proxy configuration belongs to pip:
 * inherited PIP_INDEX_URL, PIP_EXTRA_INDEX_URL, PIP_PROXY, HTTP(S)_PROXY, NO_PROXY and
 * pip.conf are preserved. No public index or proxy is hardcoded. An explicit cert wins
 * over PIP_CERT; otherwise the shared host trust is exported as the default PIP_CERT.
 */
public final class Pip {

  private Pip() {}

  /** What an install did: pip's own verdict, its output, and the argv that ran. */
  public record Result(int exit, String out, List<String> command) {}

  /** How long an install may take before it is killed, when the caller says 0. */
  public static final long DEFAULT_TIMEOUT_MS = 600_000L;

  /**
   * The argv that installs {@code specs} into {@code target}.
   *
   * <p>{@code --only-binary=:all:} is not a preference: an sdist runs its own
   * {@code setup.py} at install time, on the host, outside every boundary this
   * project has. A wheel is data that gets unpacked.
   */
  public static List<String> installCommand(String python, String target, String cert,
      boolean upgrade, List<String> specs) {
    List<String> command = new ArrayList<>(List.of(python, "-m", "pip", "install",
        "--target", target, "--only-binary=:all:", "--disable-pip-version-check", "--no-input"));
    if (cert != null) {
      command.add("--cert");
      command.add(cert);
    }
    if (upgrade) {
      command.add("--upgrade");
    }
    command.addAll(specs);
    return List.copyOf(command);
  }

  /**
   * Install {@code specs} for the sandbox. A non-zero exit comes back as data
   * with pip's own output, because the caller is a CLI that has to print it.
   *
   * <p>Nulls take the runtime's own answers: the vendored interpreter, the
   * packages directory, the bytecode cache prefix, and the JVM's certificates
   * exported to a file. A {@code timeoutMs} of 0 takes {@link #DEFAULT_TIMEOUT_MS}.
   */
  public static Result install(String python, String target, String cert, String pycachePrefix,
      boolean upgrade, long timeoutMs, List<String> specs) {
    String interpreter = python != null ? python : Interpreter.pythonExecutable();
    String directory = target != null ? target : Locations.packagesDir();
    String certificates = cert;
    String cache = pycachePrefix != null ? pycachePrefix : Locations.pycachePrefix();
    if (interpreter == null) {
      throw new VisPythonException("no interpreter to run pip with",
          Map.of("target", String.valueOf(directory)));
    }
    if (directory == null) {
      throw new VisPythonException("no directory to install into",
          Map.of("python", interpreter));
    }
    List<String> command = installCommand(interpreter, directory, certificates, upgrade, specs);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    // pip reads the target as an ordinary path, so it needs the same directory
    // on sys.path to see what is already installed - and nothing of the
    // machine's own Python may leak into it.
    environment.put("PYTHONPATH", directory);
    environment.put("PYTHONNOUSERSITE", "1");
    if (certificates != null) {
      environment.put("PIP_CERT", certificates);
    } else if (!environment.containsKey("PIP_CERT")) {
      environment.put("PIP_CERT", Trust.certificatesPem(Locations.certificatesFile()));
    }
    // Do not replace proxy/index settings, requests overrides or pip's configuration files.
    if (cache != null) {
      environment.put("PYTHONPYCACHEPREFIX", cache);
    }
    try {
      Process process = builder.start();
      String out;
      try (InputStream in = process.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      boolean done = process.waitFor(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS,
          TimeUnit.MILLISECONDS);
      if (!done) {
        process.destroyForcibly();
        return new Result(-1, out, command);
      }
      return new Result(process.exitValue(), out, command);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VisPythonException("interrupted waiting for pip", Map.of(), e);
    }
  }
}
