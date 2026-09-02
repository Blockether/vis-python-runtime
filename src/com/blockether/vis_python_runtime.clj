(ns com.blockether.vis-python-runtime
  "Embedded CPython for the Vis sandbox: the whole Clojure API.

   Vis runs sandbox Python — `packages/vis-agent` plus every shim in
   `resources/vis-shims/` — inside GraalPy today, which costs roughly 300 MB in
   the native image. This library replaces that engine with a VENDORED CPython
   reached through the JDK Foreign Function & Memory API over a first-party C
   ABI (`native/vispython`), so the image carries a cdylib and an interpreter
   tree beside it instead of a Truffle language inside it.

   The bridge itself is JAVA — `java/com/blockether/vispython/` — and this
   namespace is a thin skin over it: Clojure argument shapes and keyword maps,
   and nothing else. The reason is the native image the result is linked
   into. Every downcall there is an `invokeExact` against a signature the
   compiler knows and the host upcall's target is a static method found by name,
   while the same code as interop is a reflective invocation an image only keeps
   if somebody remembered to register it — the failure that does not show up in
   a green JVM suite, only in a user's terminal. Java also owns the process
   pinning, the upcall stub, the trust export and pip, because none of that is
   made clearer by being written in Clojure.

   Nothing links at build time. The library is resolved when it is first needed:
   a path the host named through `use-library!` wins, then
   `VIS_PYTHON_NATIVE_PATH`, then the classpath resource
   `prebuilds/<platform>/<file>` that `com.blockether/vis-python-runtime-native-<platform>`
   carries. A failure anywhere below is a `VisPythonException` whose `.data`
   names the symbol, status, platform or path it is about."
  (:import [com.blockether.vispython HostFunction Interpreter Locations Native Pip]
           [java.util.function Consumer]))

(def native-path-env
  "Name of the environment variable that overrides library resolution."
  Native/NATIVE_PATH_ENV)

(def python-home-env
  "Name of the environment variable that overrides the vendored interpreter."
  Locations/PYTHON_HOME_ENV)

(def packages-dir-env
  "Name of the environment variable that overrides the package directory."
  Locations/PACKAGES_ENV)

(def pycache-prefix-env
  "Name of the environment variable that overrides the bytecode cache location."
  Locations/PYCACHE_PREFIX_ENV)

(def version
  "This library's version, from the `vis-python-runtime/VERSION` resource the
   build writes, else \"dev\" in a source checkout."
  (Native/version))

(def default-session
  "The namespace a call runs in when the caller names none."
  Interpreter/DEFAULT_SESSION)

(defn platform
  "The platform tag prebuilt artifacts are named by, `<os>-<arch>`."
  ([] (Native/platform))
  ([os-name os-arch] (Native/platform os-name os-arch)))

(defn library-name
  "The cdylib file name for a platform tag."
  ([] (Native/libraryName))
  ([platform-tag] (Native/libraryName platform-tag)))

(defn resolve-library
  "Where the runtime cdylib is, as `{:source \"env\"|\"resource\" :path \"…\"}`."
  ([] (resolve-library (platform)))
  ([platform-tag]
   (let [found (Native/library platform-tag)]
     ;; The source is a keyword because a caller branches on it; the path is the
     ;; one thing FFM opens.
     {:source (keyword (.source found)) :path (.path found)})))

(defn use-library!
  "Resolve to THIS cdylib (or the directory holding it) from now on — for a host
   that fetched the platform artifact itself, because a JVM cannot set its own
   environment. `nil` restores ordinary resolution."
  [path]
  (Native/use (some-> path str)))

(defn materialize-library!
  "Unpack `prebuilds/<platform>/` out of a platform JAR into
   `~/.vis/python/runtime/<version>/<platform>` and answer it like
   `resolve-library`. The whole directory travels: the interpreter is found
   beside its library, so the cdylib alone is a runtime that cannot import."
  ([jar] (materialize-library! jar (platform)))
  ([jar platform-tag]
   (let [found (Native/materialize (.toPath (java.io.File. (str jar))) platform-tag)]
     {:source (keyword (.source found)) :path (.path found)})))

(defn resolve-python-home
  "The vendored CPython tree to root the interpreter at, or nil to let CPython
   search for itself. `VIS_PYTHON_HOME` wins; otherwise it is the `python/`
   directory beside the resolved cdylib."
  ([] (Interpreter/pythonHome))
  ([{:keys [path]}] (Locations/pythonHome path)))

(defn resolve-packages-dir
  "Where pip installs for the sandbox, `~/.vis/python/packages` by default. The
   artifact bundles nothing, so this is where every real distribution comes
   from — a host confining the interpreter makes it readable, never writable."
  []
  (Locations/packagesDir))

(defn resolve-pycache-prefix
  "Where the interpreter writes the bytecode it compiles,
   `~/.vis/python/pycache` by default. The artifact ships none."
  []
  (Locations/pycachePrefix))

(defn resolve-python-executable
  "The vendored interpreter's own executable, for the host to RUN."
  ([] (Interpreter/pythonExecutable))
  ([python-home] (Locations/pythonExecutable python-home)))

(defn initialize!
  "Start the embedded interpreter, once per process, and put `:source-paths`
   (plus the defaults) on `sys.path`. Answers
   `{:library … :source-paths … :python-home … :pycache-prefix … :packages …}`.

   `:python-home`, `:pycache-prefix` and `:packages` default to what the runtime
   resolves; an explicit nil turns each one off — CPython's own standard-library
   search, no bytecode cache, no package directory. Starting is process-wide and
   idempotent; a SESSION is not."
  ([] (initialize! {}))
  ([{:keys [source-paths python-home pycache-prefix packages]
     :or   {python-home     Interpreter/DEFAULT
            pycache-prefix  Interpreter/DEFAULT
            packages        Interpreter/DEFAULT}}]
   (let [startup (Interpreter/initialize (vec source-paths) python-home pycache-prefix packages)]
     {:library        (.library startup)
      :source-paths   (vec (.sourcePaths startup))
      :python-home    (.pythonHome startup)
      :pycache-prefix (.pycachePrefix startup)
      :packages       (.packages startup)})))

(defn python-version
  "The running interpreter's version string. Requires `initialize!`."
  []
  (Interpreter/version))

(defn confine!
  "Confine the interpreter to `read-roots` and `write-roots`, answering the
   counts actually in force as `{:read n :write n}`.

   This is the sandbox's filesystem boundary and it is NOT Python: the policy is
   C state behind an audit hook installed before the interpreter started. The
   same policy shuts the process surface and `ctypes`. `refusal` is the sentence
   the guest reads. Confinement is the PROCESS's: this REPLACES the policy for
   every session, and two empty lists lift it. The interpreter's own installation
   and its bytecode cache are added to the roots here, so a host names only the
   session's directories."
  ([read-roots write-roots] (confine! read-roots write-roots ""))
  ([read-roots write-roots refusal]
   (let [[read write] (Interpreter/confine (vec read-roots) (vec write-roots) refusal)]
     {:read read :write write})))

(defn network!
  "Grant or refuse the guest the network as a whole, answering the flag in force.

   A CAPABILITY, not part of confinement: refused, the audit hook stops every
   socket, name lookup and connection, so a session whose host granted no egress
   cannot even learn an address. WHICH hosts a session with egress may reach is
   the host proxy's decision, made where the request is visible. Like confinement
   this is PROCESS state and REPLACES the flag for every session; `refusal` is the
   sentence the guest reads."
  ([allowed?] (network! allowed? ""))
  ([allowed? refusal]
   (Interpreter/network (boolean allowed?) (str refusal))))
(defn stdin!
  "Say what the guest's `sys.stdin` reads, answering true.

   PROCESS state like confinement, and for the same reason: descriptor 0
   belongs to the host, so a guest `input()` blocks on a terminal nobody is
   typing into and — every session's Python running on the one runtime thread
   — takes the process with it. `text` is what the guest reads before EOF; `\"\"`
   is an empty stream, which is the sandbox's answer. `nil` restores the
   process's own stdin, for the caller that owns it: the human at the CLI."
  [text]
  (Interpreter/exec
    default-session
    (if (nil? text)
      "import vis_runtime\nvis_runtime.set_stdin(None)"
      (str "import base64, vis_runtime\nvis_runtime.set_stdin(base64.b64decode('"
           (.encodeToString (java.util.Base64/getEncoder)
                            (.getBytes ^String text "UTF-8"))
           "').decode('utf-8'))")))
  true)

(defn threads!
  "Set the process's thread policy, answering `{:cap n :workers n :quota n}` in
   force. A zero keeps what is already set.

   Like confinement this is C state, not Python: `:cap` is checked from the audit
   hook, so it counts a thread a block started for itself as well as the pool's
   own, and every session shares it because every session shares the interpreter.
   A `:cap` of -1 lifts it entirely — the one shape for a process that is not the
   sandbox's, where the code is the host's own and confinement is off.
   `:workers` sizes the pool `gather` dispatches on, `:quota` is how many of them
   one gather may hold."
  [cap workers quota]
  (let [[c w q] (Interpreter/threads cap workers quota)]
    {:cap c :workers w :quota q}))

(defn interrupt!
  "Raise `KeyboardInterrupt` in the thread running guest code, answering whether
   a thread state took it.

   The one way out of a runaway block: a host future's cancel reaches only the
   JVM side, so a spinning `while True:` burns a core until the process dies.
   CPython delivers the exception at a bytecode boundary — the block unwinds, its
   `finally` blocks run, the session stays usable — while a thread blocked in a
   host call or inside C sees it only when it returns, which is what `false`
   means. Call it from ANY thread except the one running the block."
  []
  (Interpreter/interrupt))

(defn logging!
  "Set what the runtime records, answering `{:level … :mirror? …}` in force.
   Levels are `:off` — the default, because a library records nothing until its
   host asks — `:warn`, `:info` and `:debug`. `mirror?` writes each record to
   stderr as well, for running this library with nothing draining it."
  ([level] (logging! level false))
  ([level mirror?]
   (let [[in-force flag] (Interpreter/logging (name level) (boolean mirror?))]
     {:level (keyword in-force) :mirror? (= "1" flag)})))

(defn drain-log!
  "Take what the runtime has recorded since the last call: NDJSON text, one
   event per line, oldest first.

   The runtime records and never writes a log — the host it is linked into
   already has the file and the format for these lines, and pushing them out of
   a pool worker would call the host from under a lock. The answer is what fits
   one buffer, so drain until it comes back blank; records lost to a full ring
   arrive first as a `log_dropped` event."
  []
  (Interpreter/drainLog))

(defn logs!
  "Drain the runtime's records continuously into `sink`, a function of one NDJSON
   string, every `every-ms` (250 by default). `nil` stops it and a second call
   replaces the first.

   This is how a host reads the runtime rather than `drain-log!` by hand: the
   ring drops its OLDEST record when nobody takes it, so somebody has to keep
   taking. Draining does not use the interpreter's thread, so a block that runs
   for minutes does not hold its own records back."
  ([sink] (logs! sink 250))
  ([sink every-ms]
   (Interpreter/drainTo (when sink (reify Consumer (accept [_ text] (sink text))))
                        (long every-ms))))

(defn eval-str
  "Evaluate `code` as a Python EXPRESSION, answering `str(result)`."
  ([code] (eval-str default-session code))
  ([session code] (Interpreter/eval session code)))

(defn exec!
  "Run `code` as a Python module body, for its side effects."
  ([code] (exec! default-session code))
  ([session code] (Interpreter/exec session code)))

(defn run
  "Run `code` the way the sandbox does — statements execute and a trailing
   expression's value comes back — answering that value as JSON text: one
   dialect crosses this boundary in both directions."
  ([code] (run default-session code))
  ([session code] (Interpreter/run session code)))

(defn run-block
  "Run `code` as a sandbox BLOCK, answering JSON text of what it printed and
   what it raised. A block's ONE success channel is what it PRINTED."
  ([code] (run-block default-session code))
  ([session code] (Interpreter/runBlock session code)))

(defn install-runtime!
  "Equip `session` with the sandbox runtime, answering how many names it got."
  ([] (install-runtime! default-session))
  ([session] (Interpreter/installRuntime session)))

(defn install-module!
  "Execute the sandbox module `name` INTO `session`'s own globals, answering the
   source file that ran — how a CONFIGURED part of the sandbox arrives."
  ([name] (install-module! default-session name))
  ([session name] (Interpreter/installModule session name)))

(defn install-tool!
  "Bind the host tool `name` into `session`, answering the name bound."
  ([name] (install-tool! default-session name))
  ([session name] (Interpreter/installTool session name)))

(defn install-sync-tool!
  "Bind the host tool `name` into `session` as an ordinary function, answering
   the name bound.

   The same boundary as [[install-tool!]] without the deferral, for Python the
   HOST runs: a thunk needs a block runner to settle it, and trusted code has
   none - it calls a tool and reads the answer."
  ([name] (install-sync-tool! default-session name))
  ([session name] (Interpreter/installSyncTool session name)))

(defn close-session!
  "Drop `session`'s namespace, answering whether there was one."
  [session]
  (Interpreter/closeSession session))

(defn bind-host!
  "Bind `f` as THE host this interpreter calls back into; nil unbinds.

   `f` takes a callable's name and a text payload and answers text. Everything
   else about it is constrained by where it RUNS: inside the call the guest is
   blocked on, so it must not re-enter this namespace, and on any thread,
   because the GIL is released for its duration."
  [f]
  (Interpreter/bindHost
   (when f
     (reify HostFunction
       (call [_ name payload] (str (f name payload))))))
  nil)
(defn finalize!
  "Stop the interpreter. Idempotent."
  []
  (Interpreter/shutdown))

(defn certificates-pem!
  "Export the JVM's trust anchors to a PEM file for pip and answer its path,
   `~/.vis/python/cacert.pem` by default. Pip would otherwise verify against the
   CA bundle vendored inside it, so a corporate root added to the Java trust
   store has to be exported or the machine trusts two different sets."
  ([] (Pip/certificatesPem))
  ([path] (Pip/certificatesPem path)))

(defn pip-command
  "The argv `pip-install!` would run for `specs`."
  [{:keys [python target cert upgrade?]} specs]
  (vec (Pip/installCommand python target cert (boolean upgrade?) (vec specs))))

(defn pip-install!
  "Install `specs` for the sandbox, answering `{:exit … :out … :command …}`.

   pip runs as a HOST process — the embedded interpreter is confined, and a
   block never installs anything. Omitted keys take the runtime's own answers:
   the vendored interpreter, `resolve-packages-dir`, the bytecode cache prefix
   and the exported certificates. A non-zero `:exit` is data, not a throw,
   because the caller is a CLI that has to print pip's own words."
  ([specs] (pip-install! {} specs))
  ([{:keys [python target cert pycache-prefix upgrade? timeout-ms]} specs]
   (let [result (Pip/install python target cert pycache-prefix (boolean upgrade?)
                             (long (or timeout-ms 0)) (vec specs))]
     {:exit (.exit result) :out (.out result) :command (vec (.command result))})))
