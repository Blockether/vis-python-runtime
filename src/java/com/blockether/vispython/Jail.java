package com.blockether.vispython;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** FFM boundary for the process-local {@code libvisjail} launcher. */
public final class Jail {
  private static final int ERROR_CAPACITY = 4096;
  private static final int RESULT_COUNT = 4;
  private static final int PTY = 1;
  private static final int MERGE_STDERR = 2;
  private static final int CONFINED = 4;
  /** The complete FFM registration surface for libvisjail. */
  private static final Map<String, FunctionDescriptor> SIGNATURES = Map.of(
      "visjail_spawn", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT),
      "visjail_read", descriptor(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT),
      "visjail_write", descriptor(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT),
      "visjail_close", descriptor(ValueLayout.JAVA_INT),
      "visjail_poll", descriptor(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
      "visjail_wait", descriptor(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS),
      "visjail_kill", descriptor(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

  private static Map<String, MethodHandle> handles;

  private Jail() {}

  private static FunctionDescriptor descriptor(MemoryLayout... arguments) {
    return FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments);
  }

  private static synchronized Map<String, MethodHandle> handles() {
    if (handles == null) {
      String path = Locations.jail(Native.library().path());
      if (path == null) {
        throw new VisPythonException("Runtime has no process-jail library",
            Map.of("library", Native.library().path(), "expected", Native.platform()));
      }
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.libraryLookup(path, Arena.global());
      Map<String, MethodHandle> linked = new HashMap<>();
      for (Map.Entry<String, FunctionDescriptor> entry : SIGNATURES.entrySet()) {
        MemorySegment address = lookup.find(entry.getKey()).orElseThrow(() ->
            new VisPythonException("Process-jail library exports no " + entry.getKey(),
                Map.of("symbol", entry.getKey(), "library", path)));
        linked.put(entry.getKey(), linker.downcallHandle(address, entry.getValue()));
      }
      handles = Map.copyOf(linked);
    }
    return handles;
  }

  private static byte[] blob(List<String> values) {
    int size = values.isEmpty() ? 1 : 0;
    List<byte[]> encoded = new ArrayList<>(values.size());
    for (String value : values) {
      if (value == null || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("process strings must be non-null and contain no NUL");
      }
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      encoded.add(bytes);
      size += bytes.length + 1;
    }
    byte[] result = new byte[size];
    int at = 0;
    for (byte[] bytes : encoded) {
      System.arraycopy(bytes, 0, result, at, bytes.length);
      at += bytes.length + 1;
    }
    return result;
  }

  /** Set in a confined child's environment; Seatbelt is inherited across exec and refuses a second profile. */
  public static final String MARKER = "VIS_SEATBELT_ACTIVE";

  /** True inside a child this jail already confined: the policy is inherited, never applied twice. */
  public static boolean inherited() {
    return "1".equals(System.getenv(MARKER));
  }

  private static boolean wsl1() {
    try {
      String release = Files.readString(Path.of("/proc/sys/kernel/osrelease")).toLowerCase(Locale.ROOT);
      return release.contains("microsoft") && !release.contains("wsl2");
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Why this host cannot confine a child, or null when it can. */
  public static String unsupportedReason() {
    String platform;
    try {
      platform = Native.platform();
    } catch (VisPythonException e) {
      return "the OS process jail is not available on this operating system";
    }
    if (!platform.startsWith("darwin-") && !platform.startsWith("linux-")) {
      return "the OS process jail is not available on this operating system";
    }
    if (platform.startsWith("linux-") && wsl1()) {
      return "WSL1 has no real Linux kernel namespaces; the jail needs WSL2";
    }
    try {
      if (Locations.jail(Native.library().path()) == null) {
        return "the selected Python runtime has no matching libvisjail";
      }
    } catch (VisPythonException e) {
      return "the selected Python runtime has no matching libvisjail";
    }
    return null;
  }

  public static boolean supported() {
    return unsupportedReason() == null;
  }

  /**
   * Spawn a detached process, confined under {@code policy} when one is given: a
   * Seatbelt profile on macOS, embedded bubblewrap on Linux, compiled here from
   * the one policy value. A null policy applies no jail but keeps the process
   * group, PTY and stream handling. Inside a confined child the policy is
   * already the kernel's, so the child is spawned as it is, still marked. The
   * environment is complete, not host additions.
   */
  public static JailedProcess spawn(List<String> command, Map<String, String> environment,
      String directory, JailPolicy policy, boolean pty, boolean mergeError, int rows, int columns) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    boolean confined = policy != null && !inherited();
    if (confined) {
      String reason = unsupportedReason();
      if (reason != null) {
        throw new VisPythonException("Process denied: " + reason,
            Map.of("command", command.get(0), "reason", reason));
      }
    }
    Map<String, String> env = new HashMap<>(environment == null ? Map.of() : environment);
    if (policy != null) {
      env.put(MARKER, "1");
    }
    boolean linux = confined && Native.platform().startsWith("linux-");
    String profile = null;
    List<String> actual = new ArrayList<>();
    int proxyPort = 0;
    int inboundPort = 0;
    if (confined && linux) {
      actual.add("visjail");
      actual.addAll(Bubblewrap.compile(policy));
      proxyPort = Bubblewrap.proxyPort(policy);
      inboundPort = Bubblewrap.inboundPort(policy);
      String bus = policy.keychain() ? Bubblewrap.sessionBus() : null;
      if (bus != null) {
        env.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + bus);
      }
    } else if (confined) {
      profile = Seatbelt.compile(policy);
    }
    actual.addAll(command);
    List<String> pairs = env.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    byte[] argvBytes = blob(actual);
    byte[] envBytes = blob(pairs);
    int flags = (pty ? PTY : 0) | (mergeError ? MERGE_STDERR : 0) | (confined ? CONFINED : 0);
    MethodHandle handle = handles().get("visjail_spawn");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment argv = arena.allocate(argvBytes.length);
      MemorySegment envp = arena.allocate(envBytes.length);
      MemorySegment result = arena.allocate((long) RESULT_COUNT * Integer.BYTES);
      MemorySegment error = arena.allocate(ERROR_CAPACITY);
      MemorySegment cwd = directory == null ? MemorySegment.NULL : arena.allocateFrom(directory);
      MemorySegment sbpl = profile == null ? MemorySegment.NULL : arena.allocateFrom(profile);
      MemorySegment.copy(argvBytes, 0, argv, ValueLayout.JAVA_BYTE, 0, argvBytes.length);
      MemorySegment.copy(envBytes, 0, envp, ValueLayout.JAVA_BYTE, 0, envBytes.length);
      int status = (int) handle.invokeExact(argv, argvBytes.length, envp, envBytes.length,
          cwd, sbpl, flags, rows, columns, proxyPort, inboundPort, result, error, ERROR_CAPACITY);
      if (status != 0) {
        throw new VisPythonException("Could not spawn confined process: " + error.getString(0),
            Map.of("status", status, "command", command.get(0)));
      }
      return new JailedProcess(
          result.getAtIndex(ValueLayout.JAVA_INT, 0),
          result.getAtIndex(ValueLayout.JAVA_INT, 1),
          result.getAtIndex(ValueLayout.JAVA_INT, 2),
          result.getAtIndex(ValueLayout.JAVA_INT, 3), pty);
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new VisPythonException("Could not invoke libvisjail: " + throwable,
          Map.of("command", command.get(0)), throwable);
    }
  }

  static int read(int fd, byte[] destination) {
    MethodHandle handle = handles().get("visjail_read");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(destination.length);
      int count = (int) handle.invokeExact(fd, buffer, destination.length);
      if (count > 0) {
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, destination, 0, count);
      }
      return count;
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new VisPythonException("libvisjail read failed: " + throwable, Map.of("fd", fd), throwable);
    }
  }

  static int write(int fd, byte[] source, int offset, int length) {
    MethodHandle handle = handles().get("visjail_write");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(length);
      MemorySegment.copy(source, offset, buffer, ValueLayout.JAVA_BYTE, 0, length);
      return (int) handle.invokeExact(fd, buffer, length);
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new VisPythonException("libvisjail write failed: " + throwable, Map.of("fd", fd), throwable);
    }
  }

  static int close(int fd) {
    return invokeInts("visjail_close", fd, 0, null);
  }

  static int poll(int fd, int timeoutMillis) {
    return invokeInts("visjail_poll", fd, timeoutMillis, null);
  }

  static int waitFor(int pid, boolean nohang, MemorySegment answer) {
    return invokeInts("visjail_wait", pid, nohang ? 1 : 0, answer);
  }

  static int kill(int pid, int signal) {
    return invokeInts("visjail_kill", pid, signal, null);
  }

  private static int invokeInts(String symbol, int first, int second, MemorySegment address) {
    MethodHandle handle = handles().get(symbol);
    try {
      return switch (symbol) {
        case "visjail_close" -> (int) handle.invokeExact(first);
        case "visjail_poll", "visjail_kill" -> (int) handle.invokeExact(first, second);
        case "visjail_wait" -> (int) handle.invokeExact(first, second, address);
        default -> throw new IllegalArgumentException(symbol);
      };
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new VisPythonException("Could not invoke " + symbol + ": " + throwable,
          Map.of("symbol", symbol), throwable);
    }
  }
}
