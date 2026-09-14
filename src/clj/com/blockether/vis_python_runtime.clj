(ns com.blockether.vis-python-runtime
  "Embed CPython in a JVM application, with session globals and host callbacks.

   Start with [[use-library!]] and [[initialize!]]. Use [[eval-str]] for an
   expression, [[exec!]] for statements, or [[install-runtime!]] followed by
   [[run-block]] to capture printed output and Python errors as JSON. Release a
   session's globals with [[close-session!]] when you finish.

   One interpreter and GIL serve the whole process. Sessions have separate
   globals but share imports, filesystem/network policy, thread limits and host
   callbacks. A session is not a process or a security boundary. Apply policy
   before installing runtime modules or executing untrusted code.

   This namespace wraps the Java API in `com.blockether.vispython`; it does not
   implement a second interpreter. Library resolution happens on first use:
   [[use-library!]], then `VIS_PYTHON_NATIVE_PATH`, then a platform prebuild on
   the classpath. Keep the unpacked platform archive's native library and
   `python/` directory together. Start the JVM with
   `--enable-native-access=ALL-UNNAMED`.

   Bridge failures throw `com.blockether.vispython.VisPythonException`; its
   `.data` map contains available diagnostics such as symbol, status or path.
   Python errors from [[run-block]] instead appear in its JSON `error` field."
  (:import [com.blockether.vispython HostFunction Interpreter Jail JailPolicy JailPolicy$Egress
            Locations Native Pip Trust WindowsJail]
           [java.nio.file Path]
           [java.util.function Consumer]))

(def native-path-env
  "Name of the environment variable that overrides library resolution."
  Native/NATIVE_PATH_ENV)

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
  "Resolve the native library as `{:source :configured|:env|:resource :path …}`.
   A path selected by [[use-library!]] takes precedence over the environment;
   the returned `:path` is the file the bridge opens."
  ([] (resolve-library (platform)))
  ([platform-tag]
   (let [found (Native/library platform-tag)]
     ;; The source is a keyword because a caller branches on it; the path is the
     ;; one thing FFM opens.
     {:source (keyword (.source found)) :path (.path found)})))

(defn use-library!
  "Select the native library file or unpacked archive directory before first use.
   `nil` restores environment/classpath resolution. The interpreter loads once
   per process: this does not replace an already loaded library."
  [path]
  (Native/use (some-> path
                      str)))

(defn resolve-python-home
  "The vendored CPython tree to root the interpreter at, or nil to let CPython
   search for itself. `VIS_PYTHON_HOME` wins; otherwise it is the `python/`
   directory beside the resolved cdylib."
  ([] (Interpreter/pythonHome))
  ([{:keys [path]}] (Locations/pythonHome path)))

(defn uv-executable
  "The pinned uv bundled with the selected Python tree, or nil. Never searches PATH."
  []
  (Locations/uvExecutable (Interpreter/pythonHome)))

(defn resolve-jail
  "The `libvisjail` cdylib beside the selected CPython cdylib, or nil."
  ([] (resolve-jail (resolve-library)))
  ([{:keys [path]}] (Locations/jail path)))

(defn- egress
  [network]
  (cond (map? network) (JailPolicy$Egress/proxy (int (:proxy network)))
        (= :open network) JailPolicy$Egress/OPEN
        :else JailPolicy$Egress/OFF))

(defn jail-policy
  "The confinement value `spawn-process!` compiles, from a map:
   `:read-write`/`:read-only`/`:deny-read`/`:deny-write`/`:deny-exec` are path
   lists (`~` allowed; a deny always wins; temp and the platform's own code are
   the compiler's to add), `:network` is `:off` (default), `:open` or
   `{:proxy <port>}` — the one loopback port sockets may reach — `:unix-connect`
   lists exact local control sockets, `:inbound` lists ports additionally exposed
   on every interface (loopback listeners are always allowed), and `:keychain?`
   opens the OS credential store. This path-policy contract is for macOS/Linux;
   Windows uses [[windows-jail]] and private copies instead of host-path grants."
  [{:keys [read-write read-only deny-read deny-write deny-exec unix-connect network inbound
           keychain?]}]
  (JailPolicy. (vec read-write)
               (vec read-only)
               (vec deny-read)
               (vec deny-write)
               (vec deny-exec)
               (vec unix-connect)
               (egress network)
               (mapv int inbound)
               (boolean keychain?)))

(defn jail-unsupported-reason
  "Why this host cannot confine a child — no Seatbelt or namespaces, WSL1, no
   `libvisjail` beside the runtime — or nil when it can. Windows has a separate
   [[windows-jail-unsupported-reason]] and workspace contract."
  []
  (Jail/unsupportedReason))

(defn jailed?
  "True inside a child this jail already confined: `spawn-process!` then
   passes the inherited kernel policy on instead of applying a second one."
  []
  (Jail/inherited))

(defn spawn-process!
  "Spawn `command` as a detached process through `libvisjail`, returning a
   `java.lang.Process`. `:policy` (a [[jail-policy]] map) confines it — Seatbelt
   on macOS, embedded bubblewrap on Linux, compiled by the runtime — and a spawn
   the host cannot enforce throws instead of running the child unconfined; no
   policy keeps only the process group and stream handling. `:environment` is
   the COMPLETE child environment; a confined child also carries `Jail/MARKER`.
   On Windows use [[spawn-windows-process!]]; this function always refuses it."
  ([command] (spawn-process! command {}))
  ([command {:keys [environment directory policy pty? merge-stderr? rows columns]}]
   (Jail/spawn (vec command)
               (or environment {})
               directory
               (some-> policy
                       jail-policy)
               (boolean pty?)
               (boolean merge-stderr?)
               (int (or rows 0))
               (int (or columns 0)))))

(defn windows-jail-unsupported-reason
  "Why the Windows workspace backend is unavailable, or nil when its platform
   archive is present. Creation still validates OS security prerequisites."
  []
  (WindowsJail/unsupportedReason))

(defn windows-jail
  "Create a new Windows LPAC workspace under an existing local parent directory.
   Use `with-open` to terminate its jobs and release native resources. The app,
   work and tmp directories remain afterward so you can inspect the outputs.
   This grants no host-path access and enables no network capabilities."
  ^WindowsJail [parent]
  (when (nil? parent) (throw (IllegalArgumentException. "Windows jail parent is required")))
  (WindowsJail/create (Path/of (str parent) (make-array String 0))))

(defn windows-jail-directories
  "Root directories belonging to an open or closed Windows jail. Prepare writable inputs
   in `:work`; [[stage-windows-jail!]] copies read-only inputs into `:application`.
   `:temporary` contains the child's per-profile TEMP/TMP and application-data directories."
  [^WindowsJail jail]
  {:directory (str (.directory jail))
   :application (str (.applicationDirectory jail))
   :work (str (.workDirectory jail))
   :temporary (str (.temporaryDirectory jail))})

(defn stage-windows-jail!
  "Copy a local file/tree into a new relative destination under the jail's app
   directory; return its absolute path. Refuse links, reparse points and unsafe
   destinations. Source ACLs stay unchanged. Call before the first spawn."
  [^WindowsJail jail source relative-destination]
  (when (nil? source) (throw (IllegalArgumentException. "Windows jail source is required")))
  (str (.stage jail (Path/of (str source) (make-array String 0)) relative-destination)))

(defn spawn-windows-process!
  "Run a command in a [[windows-jail]], returning `java.lang.Process`.
   The executable must be absolute, inside its staged app or Windows System32.
   `:directory` is work-relative (nil means work); `:environment` replaces the host environment.
   TEMP/TMP/LOCALAPPDATA stay private; SystemRoot is OS-derived. Pipes are the default.
   `:pty?` needs positive `:rows`/`:columns`; unknown, network, host-path or credential options throw.
   Both destroy methods terminate the process job and its descendants."
  ([jail command] (spawn-windows-process! jail command {}))
  ([^WindowsJail jail command
    {:keys [environment directory pty? merge-stderr? rows columns] :as options}]
   (when-let [unknown (seq (remove #{:environment :directory :pty? :merge-stderr? :rows :columns}
                             (keys options)))]
     (throw (IllegalArgumentException. (str "Unsupported Windows jail options: "
                                            (pr-str unknown)))))
   (.spawn jail
           (vec command)
           (or environment {})
           directory
           (boolean pty?)
           (boolean merge-stderr?)
           (int (or rows 0))
           (int (or columns 0)))))

(defn initialize!
  "Start the embedded interpreter once per process and add `:source-paths` to
   its import path. Returns
   `{:library … :source-paths … :python-home … :pycache-prefix … :packages …}`.

   Omit `:python-home`, `:pycache-prefix` or `:packages` to use its resolved
   default. An explicit `nil` disables that location: CPython resolves its own
   standard library, bytecode caching is off, or no package directory is added.
   Initialization is process-wide and idempotent; it does not create an isolated
   interpreter per session. Apply guest policy before [[install-runtime!]]."
  ([] (initialize! {}))
  ([{:keys [source-paths python-home pycache-prefix packages]
     :or {python-home Interpreter/DEFAULT
          pycache-prefix Interpreter/DEFAULT
          packages Interpreter/DEFAULT}}]
   (let [startup (Interpreter/initialize (vec source-paths) python-home pycache-prefix packages)]
     {:library (.library startup)
      :source-paths (vec (.sourcePaths startup))
      :python-home (.pythonHome startup)
      :pycache-prefix (.pycachePrefix startup)
      :packages (.packages startup)})))

(defn python-version
  "The running interpreter's version string. Requires `initialize!`."
  []
  (Interpreter/version))

(defn trust!
  "Mark `session` as running code the HOST trusts, answering how many sessions
   are trusted now. `trusted?` false takes it back.

   A trusted session reaches the filesystem through the runtime's own `_vis_fs`,
   in C, past the confinement that is there for the model's code — the same shape
   as a shell: a capability a session was GIVEN, not a policy the process
   inherits. Trust is keyed on the session the RUNTIME was asked to run, so no
   block can move itself into one: a name, a frame and an envelope are all
   forgeable from inside Python, and what the runtime is executing is not."
  ([session] (trust! session true))
  ([session trusted?] (Interpreter/trust (str session) (boolean trusted?))))

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
  ([allowed? refusal] (Interpreter/network (boolean allowed?) (str refusal))))

(defn stdin!
  "Say what the guest's `sys.stdin` reads, answering true.

   PROCESS state like confinement, and for the same reason: descriptor 0
   belongs to the host, so a guest `input()` blocks on a terminal nobody is
   typing into and — every session's Python running on the one runtime thread
   — takes the process with it. `text` is what the guest reads before EOF; `\"\"`
   is an empty stream, which is the sandbox's answer. `nil` restores the
   process's own stdin, for the caller that owns it: the human at the CLI."
  [text]
  (Interpreter/stdin text)
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
   (Interpreter/drainTo (when sink
                          (reify
                            Consumer
                              (accept [_ text] (sink text))))
                        (long every-ms))))

(defn eval-str
  "Evaluate one Python expression and return Python `str(result)` as JVM text.
   Requires [[initialize!]]. Uses persistent `session` globals; it does not
   convert Python objects to Clojure values. Python failures throw
   `VisPythonException`. See [[run]] for JSON-encoded values."
  ([code] (eval-str default-session code))
  ([session code] (Interpreter/eval session code)))

(defn exec!
  "Execute Python statements in persistent `session` globals; returns nil.
   Requires [[initialize!]]. Output is not captured by this API and Python
   failures throw `VisPythonException`. Use [[run-block]] for output capture."
  ([code] (exec! default-session code))
  ([session code] (Interpreter/exec session code)))

(defn run
  "Execute statements and return the trailing expression's value as JSON text.
   Requires [[initialize!]]. Read the result with your application's JSON
   library; unlike [[eval-str]], Python values retain their JSON shape.
   Unlike [[run-block]], this API returns a value rather than printed output."
  ([code] (run default-session code))
  ([session code] (Interpreter/run session code)))

(defn run-block
  "Execute a runtime block and return JSON text with `stdout` and `error`.
   Install the session with [[install-runtime!]] first. Printed output is the
   only success channel; a trailing expression is discarded. `error` is null
   on success or a Python error string; prior stdout is preserved on failure.
   Loading and bridge errors can still throw `VisPythonException`."
  ([code] (run-block default-session code))
  ([session code] (Interpreter/runBlock session code)))

(defn install-runtime!
  "Equip `session` with the sandbox runtime and activate installed .pth files.
   The host must apply its process policy first; editable source paths do not grant
   additional filesystem access. Returns the number of installed runtime names."
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
  "Release a session's globals and owned handles; return whether it existed.
   This does not unload the process-wide interpreter or clear shared imports."
  [session]
  (Interpreter/closeSession session))

(defn bind-host!
  "Bind `f` as THE host this interpreter calls back into; nil unbinds.

   `f` takes the CALLING SESSION, a callable's name and a text payload, and
   answers text. The session is the interpreter's answer, not the guest's: the
   nearest calling frame whose globals is a namespace this library created. A
   host that binds tools per session authorizes against THIS, never against a
   session named in the payload — that field is written by the guest, and a
   block that named a neighbour's session used to reach the neighbour's tools.
   An empty string means the call came from no session at all.

   Everything else about `f` is constrained by where it RUNS: inside the call
   the guest is blocked on, so it must not re-enter this namespace, and on any
   thread, because the GIL is released for its duration."
  [f]
  (Interpreter/bindHost (when f
                          (reify
                            HostFunction
                              (call [_ session name payload] (str (f session name payload))))))
  nil)

(defn packages-dir
  "Directory every sandbox interpreter imports host-installed wheels from."
  []
  (Locations/packagesDir))

(defn resolve-worker
  "The `vis-python-worker` executable beside the selected CPython cdylib, or nil
   for a checkout or a jar, which run `com.blockether.vispython.Worker` on a JVM."
  ([] (resolve-worker (resolve-library)))
  ([{:keys [path]}] (Locations/worker path)))

(defn certificates-pem!
  "Export effective host trust (JVM roots plus the installed host PEM) for pip."
  ([] (Trust/certificatesPem (Locations/certificatesFile)))
  ([path] (Trust/certificatesPem path)))

(defn pip-command
  "The argv `pip-install!` would run for `specs`."
  [{:keys [python target cert upgrade?]} specs]
  (vec (Pip/installCommand python target cert (boolean upgrade?) (vec specs))))

(defn pip-install!
  "Install `specs` on the HOST, answering `{:exit … :out … :command …}`; failure is data.
   Defaults: vendored Python, shared packages, bytecode cache and host trust.
   Explicit :cert wins; otherwise preserve PIP_CERT or export effective host trust.
   Preserves pip.conf/PIP_CONFIG_FILE, PIP_INDEX_URL/PIP_EXTRA_INDEX_URL,
   PIP_PROXY, HTTP_PROXY/HTTPS_PROXY and NO_PROXY. Prefer one Artifactory virtual
   index. Set credentials/proxy on the gateway before startup, never in session code."
  ([specs] (pip-install! {} specs))
  ([{:keys [python target cert pycache-prefix upgrade? timeout-ms]} specs]
   (let [result (Pip/install python
                             target
                             cert
                             pycache-prefix
                             (boolean upgrade?)
                             (long (or timeout-ms 0))
                             (vec specs))]
     {:exit (.exit result) :out (.out result) :command (vec (.command result))})))
