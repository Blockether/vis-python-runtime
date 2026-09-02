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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * <p>Calls arrive on the CALLING thread. The C entry points take the GIL
 * themselves, so nothing serializes on a bridge thread and a session never
 * queues behind another session's host call. Two exceptions live on one pinned
 * daemon thread, because CPython binds them to whoever started it: starting the
 * interpreter and finalizing it.
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
   * Bytes reserved for an answer or an error message. Most answers are a repr,
   * a status word or an exception line and fit here with room to spare; a block
   * that printed a megabyte does not, so an answer too big for this buffer is
   * kept by the runtime and fetched whole - see {@code vispython_take_result}.
   */
  private static final int MESSAGE_CAPACITY = 8192;

  private static final FunctionDescriptor HOST_DESCRIPTOR =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

  /**
   * C symbol to its FFM descriptor. This map IS the registration surface: a
   * native image needs every one of these downcalls declared, so the list stays
   * small and explicit.
   */
  private static final Map<String, FunctionDescriptor> SIGNATURES = Map.ofEntries(
      Map.entry("vispython_initialize", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_version", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_eval", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_exec", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_run", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_run_block", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_confine", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_network", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_host", descriptor(ValueLayout.ADDRESS)),
      Map.entry("vispython_threads", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_logging", descriptor(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_drain_log", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_take_result", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
      Map.entry("vispython_interrupt", descriptor(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)));

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

  private static String invoke(String symbol, String... args) {
    MethodHandle handle = handles().get(symbol);
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
      // The status is the answer's WHOLE length: at or past the buffer it holds a
      // prefix, and the rest waits in the runtime. Calling again to make room
      // would run the block a second time, so the text is fetched, not remade.
      return status < MESSAGE_CAPACITY ? text : takeResult(symbol, status);
    }
  }

  /** Fetch the answer a call could not fit, whole, in one buffer of its size. */
  private static String takeResult(String symbol, int length) {
    MethodHandle handle = handles().get("vispython_take_result");
    int capacity = length + 1;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(capacity);
      int status;
      try {
        status = (int) handle.invokeExact(out, capacity);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new VisPythonException("vis-python: vispython_take_result did not invoke: " + t,
            Map.of("symbol", symbol), t);
      }
      if (status < 0) {
        throw new VisPythonException("vis-python: the answer of " + symbol + " was not kept",
            Map.of("symbol", symbol, "status", status, "length", length));
      }
      return out.getString(0);
    }
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
    onRuntimeThread(() -> invoke("vispython_initialize", home == null ? "" : home,
        cache == null ? "" : cache));
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
      invoke("vispython_exec", DEFAULT_SESSION, wiring.toString());
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
    return invoke("vispython_version");
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
   * lists lift it. The interpreter's own installation and its bytecode cache are
   * added to the roots here, so a host names only the session's directories.
   */
  public static int[] confine(List<String> readRoots, List<String> writeRoots, String refusal) {
    String answer = invoke("vispython_confine", String.join("\n", readRoots),
        String.join("\n", writeRoots), refusal == null ? "" : refusal);
    String[] counts = answer.trim().split("\\s+");
    return new int[] {Integer.parseInt(counts[0]), Integer.parseInt(counts[1])};
  }

  /**
   * Grant or refuse the guest the network as a whole, answering the flag in force.
   *
   * <p>This is a CAPABILITY and not part of confinement: with {@code allowed}
   * false the audit hook refuses every socket, name lookup and connection, so a
   * session whose host granted no egress cannot even learn an address. WHICH hosts
   * a session with egress may reach is the host proxy's decision, made where the
   * request is visible; nothing here can see a URL. {@code refusal} is the sentence
   * the guest reads; empty keeps the library's own. Like confinement this is
   * PROCESS state, so it REPLACES the flag for every session.
   */
  public static boolean network(boolean allowed, String refusal) {
    String answer = invoke("vispython_network", allowed ? "1" : "0", refusal == null ? "" : refusal);
    return !"0".equals(answer.trim());
  }

  /**
   * Set the process's thread policy, answering the {@code {cap, workers, quota}}
   * in force; a zero keeps what is already set.
   *
   * <p>Like confinement this is NOT Python. {@code cap} is checked from the audit
   * hook, so it counts a thread a block started for itself as well as the pool's
   * own, and every session shares it because every session shares the
   * interpreter; a {@code cap} of -1 lifts it entirely, the one shape for a
   * process that is not the sandbox's, where the code is the host's own and
   * confinement is off. {@code workers} sizes the pool the runtime's {@code gather}
   * dispatches on, once, when it first runs - the default is 32, the size for
   * BLOCKING work, since a worker waits on the host rather than competing for a
   * core. A gather that finds every worker busy runs its own thunks on the
   * calling thread rather than waiting for one. {@code quota} is how many of
   * those workers ONE
   * gather may hold, so a wide gather cannot take the pool from other sessions.
   */
  public static int[] threads(int cap, int workers, int quota) {
    String answer = invoke("vispython_threads", cap + " " + workers + " " + quota);
    String[] numbers = answer.trim().split("\\s+");
    return new int[] {Integer.parseInt(numbers[0]), Integer.parseInt(numbers[1]),
        Integer.parseInt(numbers[2])};
  }

  /**
   * Raise {@code KeyboardInterrupt} in the thread running guest code, answering
   * whether a thread state took it.
   *
   * <p>The one way out of a runaway block. A host future's cancel only reaches
   * the JAVA side and {@code Thread.interrupt} is invisible to guest code, so a
   * spinning {@code while True:} burns a core until the process dies. CPython
   * delivers the exception at a bytecode boundary: the block unwinds, its
   * {@code finally} blocks run and the session stays usable. A thread blocked in
   * a host call or inside C sees it only when it returns, which is what a
   * {@code false} - and a block that keeps running - means.
   *
   * <p>It takes the GIL the running block keeps dropping at its switch interval,
   * so it must never be called from the thread it interrupts.
   */
  public static boolean interrupt() {
    return "1".equals(invoke("vispython_interrupt").trim());
  }

  /**
   * Set what the runtime records, answering the policy in force as
   * {@code {level, mirror}}. Levels are {@code off} (the default: a library
   * records nothing until its host asks), {@code warn}, {@code info} and
   * {@code debug}. {@code mirror} writes each record to stderr as well, which
   * is for running this library with nothing draining it.
   */
  public static String[] logging(String level, boolean mirror) {
    return invoke("vispython_logging", level + " " + (mirror ? 1 : 0)).trim().split("\\s+");
  }

  /**
   * Bytes one drain copies out. A record is at most 256 bytes, so a pass carries
   * dozens of them and repeats until the ring is empty.
   */
  private static final int DRAIN_CAPACITY = 16384;

  private static ScheduledExecutorService drainer;

  /**
   * Take what has been recorded since the last call: NDJSON, one object per
   * line, oldest first.
   *
   * <p>The runtime RECORDS events and never writes a log, because the host it is
   * linked into already has a file, a rotation and a format for lines like
   * these. Draining is a PULL for the same reason the pool never calls out from
   * under its own lock. The answer is what fits one buffer and the rest waits,
   * so a host drains in a loop until the answer is empty; records lost to a
   * full ring arrive first, as a {@code log_dropped} event of their own.
   *
   * <p>It touches no PyObject and takes no GIL - only the log's own leaf mutex -
   * so it answers while a block still runs, which is exactly when these records
   * matter.
   */
  public static String drainLog() {
    MethodHandle handle = handles().get("vispython_drain_log");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(DRAIN_CAPACITY);
      int status;
      try {
        status = (int) handle.invokeExact(out, DRAIN_CAPACITY);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new VisPythonException("vis-python: vispython_drain_log did not invoke: " + t,
            Map.of("symbol", "vispython_drain_log"), t);
      }
      if (status < 0) {
        throw new VisPythonException("vis-python: the records could not be drained",
            Map.of("symbol", "vispython_drain_log", "status", status));
      }
      return out.getString(0);
    }
  }

  /**
   * Drain continuously into {@code sink}, which receives NDJSON text. This is
   * how a host is meant to read the runtime: the ring drops its OLDEST when
   * nobody takes them, so somebody has to keep taking. Each pass empties the
   * ring. {@code null} stops the drainer and a second call replaces the first.
   */
  public static synchronized void drainTo(Consumer<String> sink, long everyMillis) {
    if (drainer != null) {
      drainer.shutdownNow();
      drainer = null;
    }
    if (sink == null) {
      return;
    }
    drainer = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "vis-python-log");
      thread.setDaemon(true);
      return thread;
    });
    drainer.scheduleWithFixedDelay(() -> {
      try {
        for (String text = drainLog(); !text.isEmpty(); text = drainLog()) {
          sink.accept(text);
        }
      } catch (RuntimeException | Error ignored) {
        // Neither a sink that threw nor an interpreter that went away may kill
        // the drainer: the next pass takes what this one left, and a ring that
        // overflowed meanwhile says so itself.
      }
    }, everyMillis, everyMillis, TimeUnit.MILLISECONDS);
  }

  /** Evaluate {@code code} as an expression, answering {@code str(result)}. */
  public static String eval(String session, String code) {
    return invoke("vispython_eval", session, code);
  }

  /** Run {@code code} as a module body, for its side effects. */
  public static void exec(String session, String code) {
    invoke("vispython_exec", session, code);
  }

  /**
   * Run {@code code} the way the sandbox does - statements execute and a
   * trailing expression's value comes back - answering that value as JSON text,
   * because the caller reads it with the JSON reader it already has.
   */
  public static String run(String session, String code) {
    return invoke("vispython_run", session, code);
  }

  /**
   * Run {@code code} as a sandbox BLOCK, answering JSON text of what it printed
   * and what it raised. A block's ONE success channel is what it PRINTED. The
   * reapers run at the boundary, so a handle the block dropped is freed before
   * this returns.
   */
  public static String runBlock(String session, String code) {
    return invoke("vispython_run_block", session, code);
  }

  /**
   * Equip {@code session} with the sandbox runtime, answering how many names it
   * got. The runtime is IMPORTED, never interpolated into a string: CPython's
   * own import machinery compiles and caches it, so a traceback points at a file
   * and the second session pays nothing. The session names ITSELF here, so a
   * host call made from it carries that name and one host can serve many.
   */
  public static long installRuntime(String session) {
    exec(session, "import vis_runtime");
    return Long.parseLong(
        eval(session, "vis_runtime.install(globals(), " + literal(session) + ")"));
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
   * Bind the host tool {@code name} into {@code session} as an ORDINARY
   * function, answering the name bound. Same boundary as {@link #installTool},
   * without the deferral: trusted host-side Python calls a tool and expects its
   * answer, having no block runner to settle a thunk for it.
   */
  public static String installSyncTool(String session, String name) {
    exec(session, "import vis_runtime");
    return eval(session, "vis_runtime.install_sync_tool(globals(), " + literal(name) + ")");
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
  private static int hostUpcall(MemorySegment session, MemorySegment name, MemorySegment payload,
      MemorySegment out, int capacity) {
    try {
      String caller = cString(session);
      String tool = cString(name);
      String body = cString(payload);
      Object[] pending = PENDING.get();
      String text;
      if (pending != null && pending[0].equals(caller) && pending[1].equals(tool)
          && pending[2].equals(body)) {
        text = (String) pending[3];
      } else {
        HostFunction bound = host;
        if (bound == null) {
          throw new IllegalStateException("no host is bound to this interpreter");
        }
        text = String.valueOf(bound.call(caller, tool, body));
      }
      int needed = writeReply(text, out, capacity);
      if (needed >= capacity) {
        PENDING.set(new Object[] {caller, tool, body, text});
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
                MemorySegment.class, MemorySegment.class, int.class));
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
}
