package com.blockether.vispython;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Run Windows programs in a private, network-disabled capability workspace.
 *
 * <p>This is not a Windows translation of {@link JailPolicy}. A unique
 * low-privilege AppContainer identity can read and execute staged copies in
 * {@link #applicationDirectory()}, and write in {@link #workDirectory()} and
 * {@link #temporaryDirectory()}. Windows supplies its LPAC-accessible system
 * resources. No access is granted to the original input objects, and no host
 * tree is mounted or recursively granted permissions. The kernel enforces
 * access checks for child programs and their descendants.
 *
 * <p>Create a context, {@link #stage stage} programs and read-only inputs, then
 * {@link #spawn spawn} processes. The first spawn seals the application tree;
 * later staging is rejected. Processes in one context share its identity and
 * writable directories. The context also has private Windows-managed profile
 * folders and registry storage, separate from the retained workspace. Use
 * separate contexts for mutually untrusted tasks.
 * Network access, arbitrary host-path grants, proxy ports, credential-store
 * access and Unix sockets are not options in this API.
 *
 * <p>Requires the matching Windows x64 archive and Windows 11 or Windows Server
 * 2022. It installs no service or driver and needs no administrator setup. Keep
 * this host outside the sandbox. Close the context to terminate all remaining
 * descendants, release native resources and remove its Windows profile; the workspace
 * and outputs remain for you. A host crash kills the jobs but may leave the
 * private directory and AppContainer profile behind.
 */
public final class WindowsJail implements AutoCloseable {
  private static final int ERROR_CAPACITY = 4096;
  private static final Map<String, FunctionDescriptor> SIGNATURES = Map.of(
      "visjail_windows_create", FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "visjail_windows_stage", FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "visjail_windows_seal", FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "visjail_windows_destroy", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
      "visjail_windows_pid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static Map<String, MethodHandle> handles;

  private final int context;
  private final Path directory;
  private boolean sealed;
  private boolean failed;
  private boolean closed;

  private WindowsJail(int context, Path directory) {
    this.context = context;
    this.directory = directory;
  }

  private static synchronized Map<String, MethodHandle> handles() {
    if (handles == null) {
      SymbolLookup lookup = Jail.libraryLookup();
      Linker linker = Linker.nativeLinker();
      Map<String, MethodHandle> linked = new HashMap<>();
      for (Map.Entry<String, FunctionDescriptor> entry : SIGNATURES.entrySet()) {
        MemorySegment address = lookup.find(entry.getKey()).orElseThrow(() ->
            new VisPythonException("Windows jail library exports no " + entry.getKey(),
                Map.of("symbol", entry.getKey())));
        linked.put(entry.getKey(), linker.downcallHandle(address, entry.getValue()));
      }
      handles = Map.copyOf(linked);
    }
    return handles;
  }

  /** Why the Windows workspace backend is unavailable, or null when its archive is present. */
  public static String unsupportedReason() {
    try {
      if (!"windows-x64".equals(Native.platform())) {
        return "WindowsJail requires Windows x64; use Jail with JailPolicy on macOS or Linux";
      }
      if (Locations.jail(Native.library().path()) == null) {
        return "the selected Windows runtime has no adjacent visjail.dll";
      }
      return null;
    } catch (VisPythonException exception) {
      return "WindowsJail requires a matching Windows x64 runtime archive";
    }
  }

  /** Checks the platform and archive; native creation still validates OS security prerequisites. */
  public static boolean supported() {
    return unsupportedReason() == null;
  }

  /**
   * Create a new private directory below an existing local {@code parent}.
   * Nothing in an existing project is granted permissions or replaced. The
   * native backend rejects reparse points, network volumes and unsafe paths.
   * Failed creation attempts profile cleanup; a cleanup error names the profile
   * that may need removal.
   *
   * @param parent existing directory in which you can create the workspace
   * @return an open context; use try-with-resources
   * @throws VisPythonException when the OS cannot enforce the boundary
   * @throws UncheckedIOException when the private directory cannot be created
   */
  public static WindowsJail create(Path parent) {
    Objects.requireNonNull(parent, "parent");
    String reason = unsupportedReason();
    if (reason != null) throw new VisPythonException("Windows jail unavailable: " + reason, Map.of());
    Path directory;
    try {
      directory = Files.createTempDirectory(parent.toAbsolutePath(), "visjail-");
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not create a Windows jail workspace", exception);
    }
    try (Arena arena = Arena.ofConfined()) {
      MethodHandle create = handles().get("visjail_windows_create");
      MemorySegment error = arena.allocate(ERROR_CAPACITY);
      int context = (int) create.invokeExact(arena.allocateFrom(directory.toString()), error,
          ERROR_CAPACITY);
      if (context <= 0) throw nativeFailure("create", context, error);
      return new WindowsJail(context, directory);
    } catch (Throwable exception) {
      try {
        Files.deleteIfExists(directory);
      } catch (IOException cleanup) {
        exception.addSuppressed(cleanup);
      }
      throw invocationFailure("create", exception);
    }
  }

  /** The retained private directory containing app, work and tmp. */
  public Path directory() { return directory; }

  /** Staged application copies. Use {@link #stage} rather than modifying this tree directly. */
  public Path applicationDirectory() { return directory.resolve("app"); }

  /** The writable task directory. The host may prepare inputs here before launching the guest. */
  public Path workDirectory() { return directory.resolve("work"); }

  /** The private writable directory used for every child's TEMP and TMP. */
  public Path temporaryDirectory() { return directory.resolve("tmp"); }

  /**
   * Copy one local file or directory into a new relative location under app.
   * Source security descriptors are not copied or modified. Native handle-based
   * traversal refuses reparse points, symbolic links and multiply-linked files.
   * Destinations must be new and cannot contain dot components or Windows device
   * names. Sources must not be modified concurrently. A partial copy failure
   * makes the context unusable; close it and create another.
   *
   * @param source existing local source file or directory
   * @param relativeDestination new relative destination, for example {@code python}
   * @return the absolute staged path to use in the command
   */
  public synchronized Path stage(Path source, String relativeDestination) {
    requireOpen();
    if (sealed) throw new IllegalStateException("Application copies are sealed after the first spawn");
    Objects.requireNonNull(source, "source");
    Path destination = relativePath(applicationDirectory(), relativeDestination, false);
    MethodHandle stage = handles().get("visjail_windows_stage");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment error = arena.allocate(ERROR_CAPACITY);
      int status = (int) stage.invokeExact(context,
          arena.allocateFrom(source.toAbsolutePath().toString()),
          arena.allocateFrom(applicationDirectory().relativize(destination).toString()),
          error, ERROR_CAPACITY);
      if (status != 0) throw nativeFailure("stage", status, error);
      return destination;
    } catch (Throwable exception) {
      failed = true;
      throw invocationFailure("stage", exception);
    }
  }

  /**
   * Launch a confined process. The executable must be an absolute path inside
   * app or Windows System32; no PATH search is performed. The working directory
   * is relative to work (null or empty means its root) and must already exist.
   * Environment entries replace, rather than extend, the host environment;
   * TEMP and TMP are always set to this context's private temporary directory.
   * Pass SystemRoot explicitly if the selected program requires it.
   *
   * <p>Pipes preserve separate stdout/stderr unless {@code mergeError} is true.
   * ConPTY combines them and uses positive {@code rows}/{@code columns}. Consume
   * output while writing large inputs. Windows has no POSIX signal delivery:
   * destroy and destroyForcibly both terminate the process's job and descendants.
   * Closing the context terminates every launch. A process's main exit also
   * terminates its remaining descendants; it cannot leave background jobs behind.
   *
   * @return a Process with a real Windows PID and managed native stream handles
   */
  public synchronized JailedProcess spawn(List<String> command, Map<String, String> environment,
      String relativeWorkingDirectory, boolean pty, boolean mergeError, int rows, int columns) {
    requireOpen();
    if (command == null || command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
    for (String argument : command) requireString(argument, "command argument");
    if (!Path.of(command.getFirst()).isAbsolute()) {
      throw new IllegalArgumentException("Windows jail executable must be an absolute path");
    }
    if (pty && (rows <= 0 || columns <= 0 || rows > Short.MAX_VALUE || columns > Short.MAX_VALUE)) {
      throw new IllegalArgumentException("ConPTY rows and columns must be between 1 and 32767");
    }
    Path cwd = relativePath(workDirectory(), relativeWorkingDirectory, true);
    Map<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (environment != null) {
      for (Map.Entry<String, String> entry : environment.entrySet()) {
        String key = entry.getKey();
        requireString(key, "environment name");
        requireString(entry.getValue(), "environment value");
        if (key.isEmpty() || key.indexOf('=') >= 0 || env.putIfAbsent(key, entry.getValue()) != null) {
          throw new IllegalArgumentException("Environment names must be nonempty, unique ignoring case and contain no =");
        }
      }
    }
    env.put("TEMP", temporaryDirectory().toString());
    env.put("TMP", temporaryDirectory().toString());
    seal();
    return Jail.spawnWindows(List.copyOf(command), env, cwd.toString(), context,
        pty, mergeError, rows, columns);
  }

  private void seal() {
    if (sealed) return;
    MethodHandle seal = handles().get("visjail_windows_seal");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment error = arena.allocate(ERROR_CAPACITY);
      int status = (int) seal.invokeExact(context, error, ERROR_CAPACITY);
      if (status != 0) throw nativeFailure("seal", status, error);
      sealed = true;
    } catch (Throwable exception) {
      failed = true;
      throw invocationFailure("seal", exception);
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Windows jail is closed");
    if (failed) throw new IllegalStateException("Windows jail preparation failed; close this context");
  }

  private static void requireString(String value, String kind) {
    if (value == null || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(kind + " must be non-null and contain no NUL");
    }
  }

  private static Path relativePath(Path base, String value, boolean allowEmpty) {
    if (allowEmpty && (value == null || value.isEmpty())) return base;
    requireString(value, "relative path");
    Path result = base;
    for (String part : value.replace('\\', '/').split("/", -1)) {
      String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
      if (part.isEmpty() || part.equals(".") || part.equals("..")
          || part.endsWith(".") || part.endsWith(" ") || part.chars().anyMatch(c -> c < 32)
          || part.chars().anyMatch(c -> ":*?\"<>|".indexOf(c) >= 0)
          || stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) {
        throw new IllegalArgumentException("Expected an unambiguous relative Windows path");
      }
      result = result.resolve(part);
    }
    return result;
  }

  static long processId(int process) {
    MethodHandle pid = handles().get("visjail_windows_pid");
    try {
      int value = (int) pid.invokeExact(process);
      if (value == 0) throw new VisPythonException("Windows jail returned no process ID", Map.of());
      return Integer.toUnsignedLong(value);
    } catch (Throwable exception) {
      throw invocationFailure("pid", exception);
    }
  }

  private static VisPythonException nativeFailure(String operation, int status, MemorySegment error) {
    return new VisPythonException("Windows jail " + operation + " failed: " + error.getString(0),
        Map.of("operation", operation, "status", status));
  }

  private static RuntimeException invocationFailure(String operation, Throwable exception) {
    if (exception instanceof Error error) throw error;
    if (exception instanceof RuntimeException runtime) return runtime;
    return new VisPythonException("Could not invoke Windows jail " + operation,
        Map.of("operation", operation), exception);
  }

  /**
   * Kill and wait for all descendants, remove the Windows profile, and retain the workspace.
   * Idempotent after success. If cleanup throws, close can be called again to retry it.
   */
  @Override public synchronized void close() {
    if (closed) return;
    MethodHandle destroy = handles().get("visjail_windows_destroy");
    try {
      int status = (int) destroy.invokeExact(context);
      if (status != 0) {
        throw new VisPythonException("Could not close Windows jail", Map.of("status", status));
      }
      closed = true;
    } catch (Throwable exception) {
      throw invocationFailure("destroy", exception);
    }
  }
}
