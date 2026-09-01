(ns com.blockether.vis-python-runtime.ffi
  "The JVM half of the boundary: FFM downcalls into `native/vis-python`.

   Five entry points, mirroring the C source one to one, all of them
   integers-and-bytes: `initialize!`, `version`, `eval-str`, `exec!`,
   `finalize!`. A negative return from C is a failure whose reason CPython
   already wrote into the out-buffer, so a call yields the verdict and the
   message together and this namespace never has to ask the interpreter what
   went wrong.

   EVERY call runs on ONE dedicated thread. `Py_InitializeEx` leaves the GIL
   held by the thread that started the interpreter and never releases it, so a
   call arriving on another thread walks into CPython without the lock and
   crashes the process rather than throwing. Pinning is therefore part of the
   contract, not an optimization; the thread is a daemon so it never holds the
   JVM open.

   Nothing is loaded until the first call: `resolve-library` decides where the
   cdylib is, and a checkout with no build simply throws from there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
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
  {"vis_python_initialize" (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_version"    (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_eval"       (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
   "vis_python_exec"       (descriptor ValueLayout/JAVA_INT ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT)
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
   `VIS_PYTHON_SOURCE_PATH`, then this repository's own `python/` in a dev
   checkout. A packaged build extracts its sources and passes them explicitly,
   the same way the cdylib is resolved."
  [extra]
  (let [env  (some-> (System/getenv "VIS_PYTHON_SOURCE_PATH")
                     (str/split (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))))
        repo (io/file (System/getProperty "user.dir") "python")]
    (->> (concat extra env (when (.isDirectory repo) [(.getAbsolutePath repo)]))
         (remove str/blank?)
         (distinct)
         (vec))))

(defn initialize!
  "Start the embedded interpreter, once per process, and put `:source-paths`
   (plus the defaults) on `sys.path`. Returns `{:library … :source-paths …}`.

   Starting is process-wide; a SESSION is not. Sessions are namespaces created
   on demand by `exec!`/`eval-str`, so many of them share one interpreter and
   one set of imported modules."
  ([] (initialize! {}))
  ([{:keys [source-paths]}]
   (call "vis_python_initialize")
   (let [roots (source-roots source-paths)]
     (when (seq roots)
       (call "vis_python_exec" default-session
             (str "import sys\n"
                  (str/join "\n" (map #(str "sys.path.insert(0, " (pr-str %) ")") roots)))))
     {:library (:library @bridge) :source-paths roots})))

(defn version
  "The running interpreter's version string. Requires `initialize!`."
  []
  (call "vis_python_version"))

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

(defn install-runtime!
  "Equip `session` with the sandbox runtime and answer how many names it got.

   The runtime is IMPORTED, never interpolated into a string: `vis_runtime`
   lives on `sys.path` and CPython's own import machinery compiles and caches
   it, so a traceback points at a file and the second session pays nothing."
  ([] (install-runtime! default-session))
  ([session]
   (exec! session "import vis_runtime")
   (Long/parseLong (eval-str session "vis_runtime.install(globals())"))))

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
