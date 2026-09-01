(ns com.blockether.vis-python-runtime.harness
  "What a ported shim test needs and nothing else.

   Vis' shim tests read the same way whatever they cover: run a Python snippet
   in a sandbox context, expect `True`. Here the context is a session in the
   embedded interpreter, so `truthy` is the whole harness — one interpreter for
   the suite, one session per shim, and a skip when no cdylib has been built."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute]
           [com.blockether.vispython VisPythonException]))

(def built?
  "False in a checkout where `native/vispython/build.sh` has not run."
  (try (boolean (runtime/resolve-library))
       (catch VisPythonException _ false)))

(def ^:private opened
  "Every session this harness has handed out and not yet closed."
  (atom []))

(defn- track!
  "Remember `session` so `close-sessions!` can drop it."
  [session]
  (swap! opened conj session)
  session)

(defn close-sessions!
  "Close every session the harness opened.

   A session is a module the interpreter keeps until it is dropped, and the last
   reference to whatever a block left behind is usually its globals — so a suite
   that never closes one measures descriptor discipline against a process still
   holding everything it ever opened."
  []
  (doseq [s @opened] (runtime/close-session! s))
  (reset! opened []))

(defn session
  "A session of its own with the sandbox runtime and `shim` installed.

   One interpreter serves the whole suite, so a session is a fresh namespace
   AND a fresh module table: every shim loaded so far is dropped first, and a
   test that monkeypatches one hands nothing to the next test."
  [shim]
  (runtime/initialize!)
  (let [s (str "shim-" shim "-" (System/nanoTime))]
    (runtime/install-runtime! s)
    (runtime/exec! s "import vis_runtime")
    (runtime/eval-str s "vis_runtime.forget_shims()")
    (runtime/install-shim! s shim)
    (track! s)))

(defmacro defbuilt-test
  "A test that needs the interpreter: skips LOUDLY, naming the command that
   fixes it, in a checkout where no cdylib has been built."
  [test-name & body]
  `(clojure.test/deftest ~test-name
     (if-not built?
       (println "SKIP" ~(str test-name) "- no cdylib, run native/vispython/build.sh")
       (do ~@body))))

(defmacro defshim-test
  "A ported shim test: binds `session` to a session with `shim` installed.
   Keeps the moved bodies unchanged."
  [test-name shim & body]
  `(defbuilt-test ~test-name
     (let [~'session (session ~shim)]
       ~@body)))

(defn ev
  "Run `code` in `session` and answer its value as Clojure data — the moved
   tests' `ev`, which read a sandbox result as data and compare against it.
   The value crosses as JSON, so the reading happens here."
  [session code]
  (json/read-str (runtime/run session code)))

(defn truthy
  "Whether `code` answered Python `True`."
  [session code]
  (true? (ev session code)))

(defn block-session
  "A session that runs BLOCKS: the runtime in its own globals and an empty
   handle registry, which is process-wide and would otherwise carry whatever an
   earlier block pinned."
  []
  (runtime/initialize!)
  (let [s (str "block-" (System/nanoTime))]
    (runtime/install-runtime! s)
    (runtime/exec! s "import vis_runtime")
    (runtime/eval-str s "vis_runtime.reset_handles()")
    (track! s)))

(defn block
  "Run `code` as a sandbox block in `session`: `{:stdout … :error …}`, read
   from the JSON the boundary answers."
  [session code]
  (let [answer (json/read-str (runtime/run-block session code))]
    {:stdout (get answer "stdout") :error (get answer "error")}))

(defn printed
  "The JSON value a block PRINTED. A block has ONE success channel — what it
   printed — so a test that needs a value back ends with `print(json.dumps(…))`
   and reads it here."
  [answer]
  (json/read-str (str/trim (str (:stdout answer)))))

(defn ev-guarded
  "Like `ev`, with `sys.modules` restored afterwards. One interpreter means one
   module table, so a snippet that breaks an import on purpose — the way the
   moved load-independence tests do — must put the table back."
  [session code]
  (runtime/run session "import sys\n_vis_saved_modules = dict(sys.modules)\nNone")
  (try (ev session code)
       (finally
         (runtime/run session
                      "import sys\nsys.modules.clear()\nsys.modules.update(_vis_saved_modules)\nNone"))))

(defn fresh
  "A session with the runtime but NO shim installed: the moved tests that ran
   in a context of their own are the ones asserting on lazy import, so the
   shim must arrive through `import`, not before it."
  [shim]
  (runtime/initialize!)
  (let [s (str "fresh-" shim "-" (System/nanoTime))]
    (runtime/install-runtime! s)
    (runtime/exec! s "import vis_runtime")
    (runtime/eval-str s "vis_runtime.forget_shims()")
    (track! s)))

(defn guarded-session
  "A session confined to `allowed`/`denied` the way the engine confines one: the
   two policy names in the session's globals, then `network_guard` executed into
   it.

   One interpreter has ONE `socket`, so the policy in force is the one of the
   session configured LAST — configuring a session here is ENTERING it, and that
   is why these tests run in sequence rather than side by side."
  [allowed denied]
  (runtime/initialize!)
  (let [s (str "guard-" (System/nanoTime))]
    (runtime/install-runtime! s)
    (runtime/exec! s
                   (str "__vis_allowed_domains__ = " (json/write-str allowed) "\n"
                        "__vis_denied_domains__ = " (json/write-str denied)))
    (runtime/install-module! s "network_guard")
    (track! s)))

(defn tool!
  "Publish `nm` in `session` as a DEFERRED tool over the Python `body`.

   A tool is not an ordinary function: calling one hands back a thunk, and that
   deferral is the seam `await`, `gather` and top-level auto-settle are built
   on. The engine binds its own host callables through `__vis_deferred__`, so a
   test that needs a tool declares one the same way, in Python. `body` is the
   function body, already indented, for a function taking `params`.

   The name joins `__vis_protected_names__` too, because that is what a bound
   tool IS to the sandbox: a name a block may not shadow with an import or a
   top-level def."
  [session nm params body]
  (runtime/exec! session
                 (str "def __vis_impl_" nm "__(" params "):\n" body "\n"
                      nm " = __vis_deferred__(__vis_impl_" nm "__, " (pr-str nm) ")\n"
                      "__vis_protected_names__ = sorted(set(__vis_protected_names__)"
                      " | {" (pr-str nm) "})")))

(defn bind-tools!
  "Bind `tools` — tool name to a function of its ARGUMENT VECTOR — as the host this
   interpreter calls back into, and answer it.

   The library carries TEXT: the JSON envelope is the runtime's, so decoding it
   is what every host does and what a test should not spell out twice. A tool
   that throws comes back as the failure envelope, which is how the guest gets a
   catchable exception rather than a dead block."
  [tools]
  (runtime/bind-host!
   (fn [nm payload]
     (let [args (get (json/read-str payload) "args")
           tool (get tools nm)]
       (json/write-str
        (if (nil? tool)
          {"error" (str "no tool named " nm)}
          (try {"value" (tool args)}
               (catch Throwable t
                 {"error" (str (.getMessage t))})))))))
  tools)

(defn tool-session
  "A block session with every tool in `tools` bound and installed, so a block
   can call them by name."
  [tools]
  (bind-tools! tools)
  (let [s (block-session)]
    (doseq [nm (keys tools)] (runtime/install-tool! s nm))
    s))

(defn temp-dir
  "A real directory, resolved through every symlink, so an assertion compares
   canonical paths with canonical paths (`/tmp` is a symlink on macOS)."
  ^String [prefix]
  (str (.toRealPath (Files/createTempDirectory prefix (make-array FileAttribute 0))
                    (make-array LinkOption 0))))

(defn interpreter-roots
  "What the interpreter must keep READING to stay alive under confinement: its
   own installation and the source roots it was started with. A policy without
   them refuses the next stdlib import, which is not a sandbox - it is a broken
   interpreter."
  []
  (runtime/initialize!)
  (->> (json/read-str (runtime/run "import sys\n[sys.prefix, sys.base_prefix, sys.exec_prefix] + list(sys.path)"))
       (map str)
       (remove str/blank?)
       (distinct)
       (vec)))

(defn confined-session
  "A block session running under `read-roots` / `write-roots`, answering a reach
   for a process with `refusal` when the caller supplies one."
  ([read-roots write-roots] (confined-session read-roots write-roots ""))
  ([read-roots write-roots refusal]
   (let [session (block-session)]
     (runtime/confine! (into (interpreter-roots) read-roots) write-roots refusal)
     session)))
