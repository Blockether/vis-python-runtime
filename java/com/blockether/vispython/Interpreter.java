package com.blockether.vispython;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The JVM half of the boundary: FFM downcalls into {@code native/vispython}.
 *
 * <p>A handful of entry points, mirroring the C source one to one, all of them
 * integers-and-bytes. A negative return from C is a failure whose reason CPython
 * already wrote into the out-buffer, so a call yields the verdict and the
 * message together and this class never has to ask the interpreter what went
 * wrong.
 *
 * <p>Traffic is not one way. {@link #bindHost} hands C an upcall stub, so a tool
 * the guest calls arrives back here, on the interpreter's own thread, while the
 * block waits. The stub's target is a STATIC method found by name: an upcall
 * whose target is a bound instance handle is the shape a native image cannot
 * keep, and the same reason this bridge is Java rather than interop - every
 * downcall below is an {@code invokeExact} against a signature the compiler
 * knows, not a reflective invocation the image would have to be told about.
 *
 * <p>EVERY call runs on ONE dedicated thread. {@code Py_InitializeEx} leaves the
 * GIL held by the thread that started the interpreter and never releases it, so
 * a call arriving on another thread walks into CPython without the lock and
 * crashes the process rather than throwing. Pinning is therefore part of the
 * contract, not an optimization; the thread is a daemon so it never holds the
 * JVM open.
 *
 * <p>Nothing is loaded until the first call, and a checkout with no build simply
 * throws from {@link Native#library()}.
 */
public final class Interpreter {

  /** The namespace a call runs in when the caller names none. */
  public static final String DEFAULT_SESSION = "__main__";

  /**
   * Ask {@link #initialize} to resolve a location itself. Absence and OFF are
   * different answers - null means "no python home", "no cache", "no packages" -
   * so absence needs a value of its own. No path contains a NUL.
   */
  public static final String DEFAULT = "\u0000vispython-default";
  /**
   * Bytes reserved for a result or an error message. Results that matter travel
   * as handles; this buffer only has to hold a repr or an exception line.
   */
  private static final int MESSAGE_CAPACITY = 8192;

  private static final FunctionDescriptor HOST_DESCRIPTOR =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

  /**
   * C symbol to its FFM descriptor. This map IS the registration surface: a
   * native image needs every one of these downcalls declared, so the list stays
   * small and explicit.
   */
  private static final Map<String, FunctionDescriptor> SIGNATURES = Map.of(
      "vispython_initialize", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_version", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_eval", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_exec", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_run", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_run_block", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_confine", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      "vispython_host", descriptor(ValueLayout.ADDRESS),
      "vispython_finalize", descriptor());

  private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "vis-python-runtime");
    thread.setDaemon(true);
    return thread;
  });

  /**
   * A reply that did not fit the buffer C offered, per thread. C grows its
   * buffer and calls straight back, on the same thread, for the same arguments -
   * and a tool that deleted a file must not delete it a second time because its
   * answer was long. So the oversized text waits here and the retry serves it
   * instead of running the host again.
   */
  private static final ThreadLocal<Object[]> PENDING = new ThreadLocal<>();

  private static Map<String, MethodHandle> handles;
  private static Native.Library library;
  private static MemorySegment hostStub;
  private static volatile HostFunction host;

  private Interpreter() {}

  /** What a start answers: the library it opened and the paths in force. */
  public record Startup(String library, List<String> sourcePaths, String pythonHome,
      String pycachePrefix, String packages) {}

  private static FunctionDescriptor descriptor(MemoryLayout... args) {
    return FunctionDescriptor.of(ValueLayout.JAVA_INT, args);
  }

  private static synchronized Map<String, MethodHandle> handles() {
    if (handles == null) {
      Native.Library resolved = Native.library();
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.libraryLookup(resolved.path(), Arena.global());
      Map<String, MethodHandle> linked = new HashMap<>();
      for (Map.Entry<String, FunctionDescriptor> entry : SIGNATURES.entrySet()) {
        String symbol = entry.getKey();
        MemorySegment address = lookup.find(symbol).orElseThrow(() -> new VisPythonException(
            "Runtime library exports no " + symbol,
            Map.of("symbol", symbol, "library", resolved.path())));
        linked.put(symbol, linker.downcallHandle(address, entry.getValue()));
      }
      library = resolved;
      handles = Map.copyOf(linked);
    }
    return handles;
  }

  /** The cdylib this interpreter is bound to, resolving it if needed. */
  public static Native.Library library() {
    handles();
    return library;
  }

  private static <T> T onRuntimeThread(Callable<T> task) {
    try {
      return THREAD.submit(task).get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new VisPythonException(String.valueOf(cause.getMessage()), Map.of(), cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VisPythonException("interrupted waiting for the interpreter", Map.of(), e);
    }
  }

  /**
   * Invoke {@code symbol} with an out-buffer appended, answering the buffer's
   * text on success and throwing with it on a negative status.
   */
  private static String call(String symbol, String... args) {
    MethodHandle handle = handles().get(symbol);
    return onRuntimeThread(() -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment out = arena.allocate(MESSAGE_CAPACITY);
        int status;
        try {
          status = switch (args.length) {
            case 0 -> (int) handle.invokeExact(out, MESSAGE_CAPACITY);
            case 1 -> (int) handle.invokeExact(arena.allocateFrom(args[0]), out, MESSAGE_CAPACITY);
            case 2 -> (int) handle.invokeExact(arena.allocateFrom(args[0]),
                arena.allocateFrom(args[1]), out, MESSAGE_CAPACITY);
            case 3 -> (int) handle.invokeExact(arena.allocateFrom(args[0]),
                arena.allocateFrom(args[1]), arena.allocateFrom(args[2]), out, MESSAGE_CAPACITY);
            default -> throw new IllegalArgumentException(symbol + " takes no " + args.length + " arguments");
          };
        } catch (RuntimeException | Error e) {
          throw e;
        } catch (Throwable t) {
          throw new VisPythonException("vis-python: " + symbol + " did not invoke: " + t,
              Map.of("symbol", symbol), t);
        }
        String text = out.getString(0);
        if (status < 0) {
          throw new VisPythonException("vis-python: " + (text.isEmpty() ? symbol : text),
              Map.of("symbol", symbol, "status", status, "message", text));
        }
        return text;
      }
    });
  }

  /** A Python string literal for a path or a name the host decides. */
  private static String literal(String text) {
    StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> quoted.append("\\\\");
        case '"' -> quoted.append("\\\"");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        default -> quoted.append(c);
      }
    }
    return quoted.append('"').toString();
  }

  /**
   * Start the embedded interpreter, once per process, and wire {@code sys.path}.
   *
   * <p>Each location takes one of three answers: {@link #DEFAULT} resolves what
   * this runtime decides, null turns it OFF - CPython's own standard-library
   * search, caching where CPython would put it, no package directory - and any
   * other string is used as given. Starting is process-wide; a SESSION is not.
   */
  public static Startup initialize(List<String> sourcePaths, String pythonHome,
      String pycachePrefix, String packages) {
    String home = DEFAULT.equals(pythonHome) ? Locations.pythonHome(library().path()) : pythonHome;
    String cache = DEFAULT.equals(pycachePrefix) ? Locations.pycachePrefix() : pycachePrefix;
    String target = DEFAULT.equals(packages) ? Locations.packagesDir() : packages;
    call("vispython_initialize", home == null ? "" : home, cache == null ? "" : cache);
    List<String> roots = Locations.sourceRoots(sourcePaths);
    if (!roots.isEmpty() || target != null) {
      // Starting is idempotent, so wiring sys.path has to be: a host that calls
      // this once per session would otherwise grow the path by a copy of every
      // root each time, and a path with a hundred duplicates is a hundred stat
      // calls on every import that misses.
      StringBuilder wiring = new StringBuilder("import sys\n");
      wiring.append("for _vis_root in [");
      for (int i = 0; i < roots.size(); i++) {
        wiring.append(i == 0 ? "" : ", ").append(literal(roots.get(i)));
      }
      wiring.append("]:\n");
      wiring.append("    if _vis_root not in sys.path:\n");
      wiring.append("        sys.path.insert(0, _vis_root)\n");
      if (target != null) {
        wiring.append("if ").append(literal(target)).append(" not in sys.path:\n");
        wiring.append("    sys.path.append(").append(literal(target)).append(")\n");
      }
      call("vispython_exec", DEFAULT_SESSION, wiring.toString());
    }
    return new Startup(library().path(), roots, home, cache, target);
  }

  /** The vendored CPython tree beside the resolved cdylib, or null for none. */
  public static String pythonHome() {
    return Locations.pythonHome(library().path());
  }

  /** The vendored interpreter's own executable, for the host to RUN. */
  public static String pythonExecutable() {
    return Locations.pythonExecutable(pythonHome());
  }

  /** The running interpreter's version string. Requires a start. */
  public static String version() {
    return call("vispython_version");
  }

  /**
   * Confine the interpreter to {@code readRoots} and {@code writeRoots},
   * answering the counts actually in force as {@code {read, write}}.
   *
   * <p>This is the sandbox's filesystem boundary and it is NOT Python: the
   * policy is C state behind an audit hook installed before the interpreter
   * started, so a block cannot read it, rewrite it or reach around it. The SAME
   * policy shuts the process surface and {@code ctypes}. {@code refusal} is the
   * sentence the guest reads; empty means the library's own. Confinement is the
   * PROCESS's: calling this REPLACES the policy for every session, and two empty
   * lists lift it.
   */
  public static int[] confine(List<String> readRoots, List<String> writeRoots, String refusal) {
    String answer = call("vispython_confine", String.join("\n", readRoots),
        String.join("\n", writeRoots), refusal == null ? "" : refusal);
    String[] counts = answer.trim().split("\\s+");
    return new int[] {Integer.parseInt(counts[0]), Integer.parseInt(counts[1])};
  }

  /** Evaluate {@code code} as an expression, answering {@code str(result)}. */
  public static String eval(String session, String code) {
    return call("vispython_eval", session, code);
  }

  /** Run {@code code} as a module body, for its side effects. */
  public static void exec(String session, String code) {
    call("vispython_exec", session, code);
  }

  /**
   * Run {@code code} the way the sandbox does - statements execute and a
   * trailing expression's value comes back - answering that value as EDN text,
   * because a dict is a map and a list a vector to the caller that parses it.
   */
  public static String run(String session, String code) {
    return call("vispython_run", session, code);
  }

  /**
   * Run {@code code} as a sandbox BLOCK, answering EDN text of what it printed
   * and what it raised. A block's ONE success channel is what it PRINTED. The
   * reapers run at the boundary, so a handle the block dropped is freed before
   * this returns.
   */
  public static String runBlock(String session, String code) {
    return call("vispython_run_block", session, code);
  }

  /**
   * Equip {@code session} with the sandbox runtime, answering how many names it
   * got. The runtime is IMPORTED, never interpolated into a string: CPython's
   * own import machinery compiles and caches it, so a traceback points at a file
   * and the second session pays nothing.
   */
  public static long installRuntime(String session) {
    exec(session, "import vis_runtime");
    return Long.parseLong(eval(session, "vis_runtime.install(globals())"));
  }

  /** Make the sandbox shim {@code name} importable, answering its source file. */
  public static String installShim(String session, String name) {
    exec(session, "import vis_runtime");
    return eval(session, "vis_runtime.install_shim(" + literal(name) + ")");
  }

  /**
   * Execute the sandbox module {@code name} INTO the session's own globals,
   * answering the source file that ran. This is how a CONFIGURED part of the
   * sandbox arrives: {@code network_guard} reads the policy the session was
   * handed as it executes, so it is executed into the namespace holding it
   * rather than imported.
   */
  public static String installModule(String session, String name) {
    exec(session, "import vis_runtime");
    return eval(session, "vis_runtime.install_module(globals(), " + literal(name) + ")");
  }

  /**
   * Bind the host tool {@code name} into {@code session}, answering the name
   * bound. Requires a host bound with {@link #bindHost}; without one the guest is
   * told so when it calls, not when the name is bound.
   */
  public static String installTool(String session, String name) {
    exec(session, "import vis_runtime");
    return eval(session, "vis_runtime.install_tool(globals(), " + literal(name) + ")");
  }

  /**
   * Drop {@code session}'s namespace, answering whether there was one. A host
   * that never closes a finished session holds everything every block ever
   * leaked, for the life of the process.
   */
  public static boolean closeSession(String session) {
    exec(DEFAULT_SESSION, "import vis_runtime");
    return "True".equals(eval(DEFAULT_SESSION, "vis_runtime.close_session(" + literal(session) + ")"));
  }

  private static String cString(MemorySegment segment) {
    return segment.reinterpret(Integer.MAX_VALUE).getString(0);
  }

  /**
   * Write {@code text} into C's buffer, answering the byte length it NEEDS.
   * Writes only the terminator when the answer does not fit: C grows the buffer
   * and asks again, which beats truncating mid-character.
   */
  private static int writeReply(String text, MemorySegment out, int capacity) {
    int needed = text.getBytes(StandardCharsets.UTF_8).length;
    MemorySegment room = out.reinterpret(capacity);
    if (needed < capacity) {
      room.setString(0, text);
    } else {
      room.set(ValueLayout.JAVA_BYTE, 0, (byte) 0);
    }
    return needed;
  }

  /**
   * The callback C invokes: read the two strings, run the bound host, write the
   * reply. Never throws across the boundary - an exception escaping an upcall
   * takes the process down, so a failure comes back as a negative status with
   * its reason in the buffer, exactly like a failure on the way in.
   */
  private static int hostUpcall(MemorySegment name, MemorySegment payload, MemorySegment out,
      int capacity) {
    try {
      String tool = cString(name);
      String body = cString(payload);
      Object[] pending = PENDING.get();
      String text;
      if (pending != null && pending[0].equals(tool) && pending[1].equals(body)) {
        text = (String) pending[2];
      } else {
        HostFunction bound = host;
        if (bound == null) {
          throw new IllegalStateException("no host is bound to this interpreter");
        }
        text = String.valueOf(bound.call(tool, body));
      }
      int needed = writeReply(text, out, capacity);
      if (needed >= capacity) {
        PENDING.set(new Object[] {tool, body, text});
      } else {
        PENDING.remove();
      }
      return needed;
    } catch (Throwable t) {
      PENDING.remove();
      String message = t.getMessage() == null || t.getMessage().isEmpty()
          ? t.getClass().getName() : t.getMessage();
      writeReply(message, out, capacity);
      return -1;
    }
  }

  private static synchronized MemorySegment hostStub() {
    if (hostStub == null) {
      try {
        MethodHandle target = MethodHandles.lookup().findStatic(Interpreter.class, "hostUpcall",
            MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                MemorySegment.class, int.class));
        hostStub = Linker.nativeLinker().upcallStub(target, HOST_DESCRIPTOR, Arena.global());
      } catch (ReflectiveOperationException e) {
        throw new VisPythonException("could not build the host upcall stub", Map.of(), e);
      }
    }
    return hostStub;
  }

  /**
   * Bind {@code function} as THE host this interpreter calls back into; null
   * unbinds. One stub behind one function: rebinding swaps the function, never
   * the pointer, so a process that rebinds a thousand times owns exactly one
   * stub.
   */
  public static void bindHost(HostFunction function) {
    host = function;
    MethodHandle handle = handles().get("vispython_host");
    MemorySegment stub = hostStub();
    onRuntimeThread(() -> {
      try {
        int ignored = (int) handle.invokeExact(stub);
        return ignored;
      } catch (Throwable t) {
        throw new VisPythonException("vis-python: could not bind the host", Map.of(), t);
      }
    });
  }

  /** Stop the interpreter. Idempotent. */
  public static void shutdown() {
    MethodHandle handle = handles().get("vispython_finalize");
    onRuntimeThread(() -> {
      int status;
      try {
        status = (int) handle.invokeExact();
      } catch (Throwable t) {
        throw new VisPythonException("vis-python: could not finalize", Map.of(), t);
      }
      if (status < 0) {
        throw new VisPythonException("vis-python: interpreter did not finalize cleanly",
            Map.of("symbol", "vispython_finalize", "status", status));
      }
      return status;
    });
  }
}
