(ns com.blockether.vis-python-runtime.ffi
  "The JVM half of the boundary: FFM downcalls into `native/vis-python`.

   A handful of entry points, mirroring the C source one to one, all of them
   integers-and-bytes: `initialize!`, `version`, `eval-str`, `exec!`, `run`,
   `finalize!`. A negative return from C is a failure whose reason CPython
   already wrote into the out-buffer, so a call yields the verdict and the
   message together and this namespace never has to ask the interpreter what
   went wrong.

   Traffic is not one way. `bind-host!` hands C an upcall stub, so a tool the
   guest calls — `grep(...)` reads as Python and runs as Clojure — arrives back
   here, on the interpreter's own thread, while the block waits.

   EVERY call runs on ONE dedicated thread. `Py_InitializeEx` leaves the GIL
   held by the thread that started the interpreter and never releases it, so a
   call arriving on another thread walks into CPython without the lock and
   crashes the process rather than throwing. Pinning is therefore part of the
   contract, not an optimization; the thread is a daemon so it never holds the
   JVM open.

   Nothing is loaded until the first call: `resolve-library` decides where the
   cdylib is, and a checkout with no build simply throws from there."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle MethodHandles MethodType]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Callable ExecutionException Executors ExecutorService ThreadFactory]))

(def ^:private message-capacity
  "Bytes reserved for a result or an error message. Results that matter travel
   as handles later; this buffer only has to hold a repr or an exception line."
  8192)

(defn- descriptor ^FunctionDescriptor [^MemoryLayout return & args]
  (FunctionDescriptor/of return (into-array MemoryLayout args)))

(def ^:private signatures
  "C symbol -> its FFM descriptor. This map IS the registration surface: a
   native image needs every one of these downcalls declared, so the list stays
   small and explicit."
  {"vis_python_initialize" (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_version"    (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_eval"       (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_exec"       (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_run"        (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_run_block"  (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_confine"    (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_host"       (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS)
   "vis_python_finalize"   (descriptor ValueLayout/JAVA_INT)})

(defn- link-handles
  "Bind every symbol in `signatures` from the resolved cdylib, or throw naming
   the one that is missing — a partially linked bridge is worse than none."
  [{:keys [path] :as library}]
  (let [linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup ^String path (Arena/global))]
    (into {}
          (map (fn [[symbol-name ^FunctionDescriptor desc]]
                 (let [^MemorySegment address
                       (or (.orElse (.find lookup symbol-name) nil)
                           (throw (ex-info (str "Runtime library exports no " symbol-name)
                                           {:symbol symbol-name :library library})))]
                   ;; The empty option array is not decoration: without it the
                   ;; two-argument call resolves to `downcallHandle(descriptor,
                   ;; options...)` and the address lands where the descriptor
                   ;; belongs.
                   [symbol-name (.downcallHandle linker address desc
                                                 (into-array Linker$Option []))])))
          signatures)))

(defonce ^:private bridge
  (delay (let [library (runtime/resolve-library)]
           {:library library :handles (link-handles library)})))

(defonce ^:private runtime-thread
  (delay (Executors/newSingleThreadExecutor
          (reify ThreadFactory
            (newThread [_ runnable]
              (doto (Thread. ^Runnable runnable "vis-python-runtime")
                (.setDaemon true)))))))

(defn- on-runtime-thread
  "Run `f` on the interpreter's one thread, unwrapping the executor's wrapper so
   a caller sees the exception the bridge actually threw."
  [f]
  (try
    (.get (.submit ^ExecutorService @runtime-thread ^Callable f))
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn- handle ^MethodHandle [symbol-name]
  (get-in @bridge [:handles symbol-name]))

(defn- call
  "Invoke `symbol-name` with an out-buffer appended, returning the buffer's text
   on success and throwing `ex-info` carrying it on a negative status."
  [symbol-name & segments]
  (on-runtime-thread
   (fn []
     (with-open [arena (Arena/ofConfined)]
       (let [out  (.allocate arena (long message-capacity))
             args (concat (map (fn [^String s] (.allocateFrom arena s)) segments)
                          [out (int message-capacity)])
             status (int (.invokeWithArguments (handle symbol-name) ^java.util.List (vec args)))
             text (.getString out 0)]
         (when (neg? status)
           (throw (ex-info (str "vis-python: " (if (empty? text) symbol-name text))
                           {:symbol symbol-name :status status :message text})))
         text)))))

(def default-session
  "The namespace a call runs in when the caller names none."
  "__main__")

(defn- source-roots
  "Directories CPython may import from, in order: what the caller passed, then
   `VIS_PYTHON_SOURCE_PATH`, then this repository's own `python/` and
   `resources/vis-python/` in a dev checkout. A packaged build extracts its
   sources and passes them explicitly, the same way the cdylib is resolved."
  [extra]
  (let [env   (some-> (System/getenv "VIS_PYTHON_SOURCE_PATH")
                      (str/split (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))
        here  (System/getProperty "user.dir")
        repos (->> [(io/file here "python") (io/file here "resources" "vis-python")]
                   (filter #(.isDirectory ^java.io.File %))
                   (mapv #(.getAbsolutePath ^java.io.File %)))]
    (->> (concat extra env repos)
         (remove str/blank?)
         (distinct)
         (vec))))

(defn initialize!
  "Start the embedded interpreter, once per process, and put `:source-paths`
   (plus the defaults) on `sys.path`. Returns
   `{:library … :source-paths … :python-home … :pycache-prefix … :packages …}`.

   `:python-home` is the vendored CPython tree the interpreter is rooted at,
   defaulting to `runtime/resolve-python-home` and passing through to
   `Py_InitializeFromConfig`. An explicit nil starts the interpreter with
   CPython's own standard-library search, which is what a checkout built
   against a system Python wants.

   `:pycache-prefix` is where compiled bytecode lands, defaulting to
   `runtime/resolve-pycache-prefix`. The artifact ships none, so the first run
   pays the compile and writes it there; an explicit nil turns caching off and
   pays that compile on every run.

   `:packages` is the directory pip installs into, defaulting to
   `runtime/resolve-packages-dir` and APPENDED to `sys.path`: the artifact
   bundles no package, so this is where every real distribution the sandbox ever
   imports comes from. It is appended rather than inserted because a source root
   the host passed is the caller's own code and outranks an installed
   distribution of the same name.

   Starting is process-wide; a SESSION is not. Sessions are namespaces created
   on demand by `exec!`/`eval-str`, so many of them share one interpreter and
   one set of imported modules."
  ([] (initialize! {}))
  ([{:keys [source-paths python-home pycache-prefix packages]
     :or   {python-home ::vendored pycache-prefix ::default packages ::default}}]
   (let [home     (if (= ::vendored python-home)
                    (runtime/resolve-python-home (:library @bridge))
                    python-home)
         pycache  (if (= ::default pycache-prefix)
                    (runtime/resolve-pycache-prefix)
                    pycache-prefix)
         packages (if (= ::default packages)
                    (runtime/resolve-packages-dir)
                    packages)]
     (call "vis_python_initialize" (or home "") (or pycache ""))
     (let [roots (source-roots source-paths)]
       (when (or (seq roots) packages)
       ;; Starting is idempotent, so wiring `sys.path` has to be: a suite or a
       ;; host that calls this once per session would otherwise grow the path by
       ;; a copy of every root each time, and a path with a hundred duplicates is
       ;; a hundred stat calls on every import that misses.
         (call "vis_python_exec" default-session
               (str "import sys\n"
                    "for _vis_root in [" (str/join ", " (map pr-str roots)) "]:\n"
                    "    if _vis_root not in sys.path:\n"
                    "        sys.path.insert(0, _vis_root)\n"
                    (when packages
                      (str "if " (pr-str packages) " not in sys.path:\n"
                           "    sys.path.append(" (pr-str packages) ")\n")))))
       {:library (:library @bridge) :source-paths roots :python-home home
        :pycache-prefix pycache :packages packages}))))

(defn version
  "The running interpreter's version string. Requires `initialize!`."
  []
  (call "vis_python_version"))

(defn confine!
  "Confine the interpreter to `read-roots` and `write-roots`, answering the
   counts actually in force as `{:read n :write n}`.

   This is the sandbox's filesystem boundary and it is NOT Python: the policy is
   C state behind an audit hook installed before the interpreter started, so a
   block cannot read it, rewrite it or reach around it — the way GraalPy's own
   FileSystem could not be reached from the guest. A writable root is readable
   too, a path that will not resolve is refused, and a root that will not
   resolve is dropped, which is why the counts come back.

   The SAME policy shuts the process surface and `ctypes`. A confined
   interpreter spawns nothing: `subprocess`, `os.system`, `os.popen` and
   `os.exec` are events CPython raises itself, so nothing has to replace a
   module to refuse them and a block that imports its own way to one still
   arrives here. It opens no native library by name either, because `ctypes` is
   the one door from a block straight past this boundary into libc. An
   extension module the interpreter imports from its own tree is untouched — a
   real wheel is native code the host chose, and the import machinery raises
   none of those events.

   `refusal` is the sentence the guest reads when it reaches for a process, so a
   host that already words this its own way keeps wording it once; omitted, the
   library answers with its own.

   Confinement is the PROCESS's, like the interpreter: calling this REPLACES the
   policy for every session. Two empty lists lift it."
  ([read-roots write-roots] (confine! read-roots write-roots ""))
  ([read-roots write-roots refusal]
   (let [answer (call "vis_python_confine"
                      (str/join "\n" read-roots)
                      (str/join "\n" write-roots)
                      (str refusal))
         [read-count write-count] (map parse-long (str/split (str/trim answer) #"\s+"))]
     {:read read-count :write write-count})))

(defn eval-str
  "Evaluate `code` as a Python EXPRESSION, answering `str(result)`. Runs in
   `session` when given, otherwise in `__main__`. A Python exception arrives as
   `ex-info` holding its text."
  ([code] (eval-str default-session code))
  ([session code] (call "vis_python_eval" session code)))

(defn exec!
  "Run `code` as a Python module body for its side effects, in `session` when
   given, otherwise in `__main__`."
  ([code] (exec! default-session code))
  ([session code] (call "vis_python_exec" session code) nil))

(defn run
  "Run `code` the way the sandbox does — statements execute and a trailing
   expression's value comes back — answering that value as Clojure data.

   The value crosses the ABI as EDN (`vis_runtime/to_edn`), so a dict is a map
   and a list a vector; anything with no EDN shape arrives as its `str`. This
   is the call a caller wants; `eval-str` and `exec!` are the primitives
   underneath it."
  ([code] (run default-session code))
  ([session code] (edn/read-string (call "vis_python_run" session code))))

(defn run-block
  "Run `code` as a sandbox BLOCK and answer `{:stdout … :error …}`.

   A block's ONE success channel is what it PRINTED, so a caller that needs a
   value back ends the block with `print(...)` and reads `:stdout`; `:error` is
   nil unless the block raised. The reapers run at the boundary, so a handle the
   block dropped is freed before this returns."
  ([code] (run-block default-session code))
  ([session code]
   (let [answer (edn/read-string (call "vis_python_run_block" session code))]
     {:stdout (get answer "stdout") :error (get answer "error")})))

(defn install-runtime!
  "Equip `session` with the sandbox runtime and answer how many names it got.

   The runtime is IMPORTED, never interpolated into a string: `vis_runtime`
   lives on `sys.path` and CPython's own import machinery compiles and caches
   it, so a traceback points at a file and the second session pays nothing."
  ([] (install-runtime! default-session))
  ([session]
   (exec! session "import vis_runtime")
   (Long/parseLong (eval-str session "vis_runtime.install(globals())"))))

(defn install-shim!
  "Make the sandbox shim `name` importable in this interpreter, answering the
   source file it loaded. Shims are process-wide once loaded, the same as any
   other module: a second session imports, it does not reinstall."
  ([name] (install-shim! default-session name))
  ([session name]
   (exec! session "import vis_runtime")
   (eval-str session (str "vis_runtime.install_shim(" (pr-str name) ")"))))

(defn install-module!
  "Execute the sandbox module `name` INTO `session`'s own globals, answering the
   source file that ran.

   This is how a CONFIGURED part of the sandbox arrives: `network_guard` reads
   the policy the session was handed (`__vis_allowed_domains__`,
   `__vis_denied_domains__`) as it executes, so it is executed into the
   namespace holding them rather than imported."
  ([name] (install-module! default-session name))
  ([session name]
   (exec! session "import vis_runtime")
   (eval-str session (str "vis_runtime.install_module(globals(), " (pr-str name) ")"))))

;; The function `_vis_host.call` reaches, or nil when nothing is bound. One atom
;; behind one upcall stub: rebinding swaps the function, never the pointer, so a
;; process that rebinds a thousand times still owns exactly one stub.
(defonce ^:private host-callable (atom nil))

(def ^:private ^ThreadLocal pending-reply
  "A reply that did not fit the buffer C offered, per thread.

   C grows its buffer and calls straight back, on the same thread, for the same
   arguments — and a tool that deleted a file must not delete it a second time
   because its answer was long. So the oversized text waits here and the retry
   serves it instead of running the host again."
  (ThreadLocal.))

(defn- c-string
  "Read a NUL-terminated UTF-8 string out of a C pointer. The segment arrives
   with no size, so it is reinterpreted before it can be read."
  ^String [^MemorySegment segment]
  (.getString (.reinterpret segment (long Integer/MAX_VALUE)) 0))

(defn- write-reply
  "Write `text` into C's buffer, answering the byte length it NEEDS. Writes only
   the terminator when the answer does not fit: C grows the buffer and asks
   again, which beats truncating in the middle of a UTF-8 character."
  ^long [^String text ^MemorySegment out ^long cap]
  (let [needed (alength (.getBytes text StandardCharsets/UTF_8))
        room   (.reinterpret out cap)]
    (if (< needed cap)
      (.setString room 0 text)
      (.set room ValueLayout/JAVA_BYTE 0 (byte 0)))
    needed))

(defn- host-upcall
  "The callback C invokes: read the two strings, run the bound host, write the
   reply. Never throws across the boundary — an exception escaping an upcall
   takes the process down, so a failure comes back as a negative status with its
   reason in the buffer, exactly like a failure on the way in."
  [name payload out cap]
  (let [cap (long cap)]
    (try
      (let [nm      (c-string name)
            body    (c-string payload)
            pending (.get ^ThreadLocal pending-reply)
            text    (if (= (first pending) [nm body])
                      (second pending)
                      (let [f (or @host-callable
                                  (throw (ex-info "no host is bound to this interpreter" {})))]
                        (str (f nm body))))
            needed  (write-reply text out cap)]
        (if (>= needed cap)
          (.set ^ThreadLocal pending-reply [[nm body] text])
          (.remove ^ThreadLocal pending-reply))
        (int needed))
      (catch Throwable t
        (.remove ^ThreadLocal pending-reply)
        (write-reply (or (not-empty (str (.getMessage t))) (.getName (class t))) out cap)
        (int -1)))))

(defonce ^:private host-stub
  (delay
    (let [classes (fn ^"[Ljava.lang.Class;" [& cs] (into-array Class cs))
          invoked (MethodType/methodType Object ^"[Ljava.lang.Class;" (classes Object Object Object Object))
          native  (MethodType/methodType Integer/TYPE
                                         ^"[Ljava.lang.Class;"
                                         (classes MemorySegment MemorySegment MemorySegment Integer/TYPE))
          target  (-> (.findVirtual (MethodHandles/lookup) clojure.lang.IFn "invoke" invoked)
                      (.bindTo ^clojure.lang.IFn host-upcall)
                      (.asType native))]
      (.upcallStub (Linker/nativeLinker) target
                   (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS
                               ValueLayout/ADDRESS ValueLayout/JAVA_INT)
                   (Arena/global)
                   (into-array Linker$Option [])))))

(defn bind-host!
  "Bind `f` as THE host this interpreter calls back into; nil unbinds.

   `f` takes a callable's name and a text payload and answers text. Everything
   else about it is constrained by where it RUNS: inside the call the guest is
   blocked on, so it must not re-enter this namespace — `run`, `exec!` and their
   siblings would wait behind the call already in flight — and on any thread,
   because the GIL is released for its duration and a second guest thread can
   reach it while the first is still there.

   The dialect is the caller's: this carries text and reads none of it.
   `install-tool!` binds the JSON one the sandbox runtime speaks."
  [f]
  (reset! host-callable f)
  (on-runtime-thread
   (fn []
     (.invokeWithArguments (handle "vis_python_host") ^java.util.List (vector @host-stub))
     nil))
  nil)

(defn install-tool!
  "Bind the host tool `name` into `session`, answering the name bound.

   A tool is a name the guest CALLS and the host answers: the runtime wraps it
   so calling one hands back a thunk — what `await`, `gather` and top-level
   auto-settle are built on — and adds it to the names a block may not shadow.
   Requires a host bound with `bind-host!`; without one the guest is told so
   when it calls, not when the name is bound."
  ([name] (install-tool! default-session name))
  ([session name]
   (exec! session "import vis_runtime")
   (eval-str session (str "vis_runtime.install_tool(globals(), " (pr-str name) ")"))))
(defn close-session!
  "Drop `session`'s namespace, answering whether there was one.

   A session is a module the interpreter keeps until it is dropped, and the last
   reference to a file, a socket or a host handle a block left behind is usually
   that namespace: a host that never closes a finished session holds everything
   every block ever leaked, for the life of the process."
  [session]
  (exec! default-session "import vis_runtime")
  (= "True" (eval-str default-session (str "vis_runtime.close_session(" (pr-str session) ")"))))

(defn finalize!
  "Stop the interpreter. Idempotent."
  []
  (on-runtime-thread
   (fn []
     (let [no-args (java.util.Collections/emptyList)
           status (int (.invokeWithArguments (handle "vis_python_finalize") no-args))]
       (when (neg? status)
         (throw (ex-info "vis-python: interpreter did not finalize cleanly"
                         {:symbol "vis_python_finalize" :status status})))
       nil))))
