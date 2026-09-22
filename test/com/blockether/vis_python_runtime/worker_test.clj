(ns com.blockether.vis-python-runtime.worker-test
  "`vis-python-worker`: the interpreter as a program of its own.

   A host runs many sessions and gives each one a worker process, so what a
   worker must get right is the WIRE — one JSON line per message, requests from
   the parent, `host` requests back — and its life: connect first, serve until
   the parent hangs up, then leave. The cases drive a real worker over a real
   unix socket, the way vis does. The same drive runs against the JVM class and,
   when `worker-image` has built one beside the cdylib, the native image both
   with and without the OS jail."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness])
  (:import [com.blockether.vispython Json Native Worker]
           [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(deftest json-round-trip-test
  (testing "every JSON value the wire carries survives write and parse"
    (let [value
          {"op" "run"
           "id" 7
           "code" "print('zażółć \"q\" \\ \n')"
           "nested" [1 2.5 true false nil {"k" []}]}

          text
          (Json/write value)]

      (is (= value (Json/parse text)))
      (is (= value (json/read-str text)) "and clojure.data.json reads the same value")
      (is (= value (Json/parse (json/write-str value))) "and reads what clojure.data.json wrote")))
  (testing "an incomplete or trailing text is refused, never half-read"
    (is (thrown? Exception (Json/parse "{\"a\": 1")))
    (is (thrown? Exception (Json/parse "1 2")))
    (is (thrown? Exception (Json/object "[1]")))))

(defn- jvm-argv
  "Run the worker CLASS on this JVM, from this suite's own classpath."
  [socket home]
  [(str (System/getProperty "java.home")
        File/separator
        "bin"
        File/separator
        (if (str/starts-with? (Native/platform) "windows-") "java.exe" "java"))
   (str "-Duser.home=" home) (str "-XX:ErrorFile=" home "/hs_err_pid%p.log")
   "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
   "com.blockether.vispython.Worker" socket])

(defn- image-argv
  "Run the native image `worker-image` built, when there is one."
  []
  (when-let [executable (runtime/resolve-worker)]
    (fn [socket home]
      [executable (str "-Duser.home=" home) socket])))

(defn- start!
  "Listen on a fresh unix socket, start the worker with `argv` and answer the
   accepted connection together with the process and its log.

   Unix socket paths are bounded: use /tmp on macOS and the platform temp
   directory on Windows, never an invented POSIX path on that platform."
  [argv & [policy environment]]
  (let [home
        (harness/temp-dir "vis-worker-home")

        path
        (str (io/file (if (str/starts-with? (Native/platform) "windows-")
                        (System/getProperty "java.io.tmpdir")
                        "/tmp")
                      (str "vw-" (System/nanoTime) ".sock")))

        server
        (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
          (.bind (UnixDomainSocketAddress/of path)))

        log
        (File/createTempFile "vis-worker" ".log")

        builder
        (doto (ProcessBuilder. ^java.util.List (argv path home))
          (.redirectErrorStream true)
          (.redirectOutput log))

        _
        (do (.put (.environment builder) Native/NATIVE_PATH_ENV (:path (runtime/resolve-library)))
            (.putAll (.environment builder) (or environment {})))

        process
        (if policy
          (let [library
                (io/file (:path (runtime/resolve-library)))

                child
                (runtime/spawn-process!
                  (argv path home)
                  {:directory home
                   :environment (merge
                                  {Native/NATIVE_PATH_ENV (str library) "HOME" home "TMPDIR" home}
                                  environment)
                   :policy (-> policy
                               (update :read-write conj home)
                               (update :read-only conj (str (.getParentFile library)))
                               (assoc :unix-connect [path]))
                   :merge-stderr? true})]

            (future (with-open [input
                                (.getInputStream child)

                                output
                                (io/output-stream log)]

                      (io/copy input output)))
            child)
          (.start builder))

        accept
        (future (.accept server))

        channel
        (deref accept 60000 ::timeout)]

    (when (= ::timeout channel)
      (.destroyForcibly process)
      (throw (ex-info "the worker never connected" {:argv (argv path home) :log (slurp log)})))
    (.close server)
    (when-not (str/starts-with? (Native/platform) "windows-")
      (Files/deleteIfExists (Path/of path (make-array String 0))))
    {:process process
     :log log
     :home home
     :socket-path path
     :channel channel
     :reader (BufferedReader. (InputStreamReader. (Channels/newInputStream ^SocketChannel channel)
                                                  StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ^SocketChannel channel)
                                                   StandardCharsets/UTF_8))
     :tools (atom {})
     :sequence (atom 0)}))

(defn- send!
  [{:keys [^BufferedWriter writer]} message]
  (.write writer ^String (json/write-str message))
  (.write writer "\n")
  (.flush writer))

(defn- request!
  "Ask the worker one thing and answer its reply, serving every `host` request
   it makes on the way — a block calling a tool is waiting on this same socket."
  [{:keys [^BufferedReader reader tools sequence] :as worker} op & {:as fields}]
  (let [id (swap! sequence inc)]
    (send! worker
           (assoc fields
             "op" op
             "id" id))
    (loop []

      (let [line (.readLine reader)]
        (when (nil? line)
          (throw (ex-info "the worker hung up" {:op op :log (slurp (:log worker))})))
        (let [message (json/read-str line)]
          (cond (= "host" (get message "op"))
                (let [tool (get @tools (get message "tool"))
                      args (get (json/read-str (get message "payload")) "args")]

                  (send! worker
                         (if tool
                           {"id" (get message "id") "value" (json/write-str {"value" (tool args)})}
                           {"id" (get message "id")
                            "error" (str "no tool named " (get message "tool"))}))
                  (recur))
                (= id (get message "id")) message
                :else (recur)))))))

(defn- value!
  [worker op & {:as fields}]
  (let [reply (apply request! worker op (mapcat identity fields))]
    (is (nil? (get reply "error")) (str op " failed: " (get reply "error")))
    (get reply "value")))

(defn- exercise!
  "The whole drive, against whatever `argv` starts."
  [argv & [jailed?]]
  (let [source-dir
        (harness/temp-dir "vis-worker-source")

        _
        (spit (str source-dir "/worker_host_fixture.py") "value = 73
")

        root
        (harness/temp-dir "vis-worker-root")

        worker
        (start! (fn [socket home]
                  (conj (vec (argv socket home)) source-dir))
                (when jailed? {:read-write [root] :read-only [source-dir]}))

        session
        "worker-test"]

    (try
      (testing "the interpreter answers over the wire"
        (value! worker "install-runtime" "session" session)
        (is (= runtime/version
               (value! worker "eval" "session" session "code" "VIS_PYTHON_RUNTIME_VERSION")))
        (is (= "2" (value! worker "run" "session" session "code" "1 + 1"))))
      ;; Vis #199: native and JVM workers must expose CPython, not their host launcher.
      (testing "executable identity names the bundled interpreter"
        (let [executable
              (value! worker "eval" "session" session "code" "__import__('sys').executable")]
          (is (= (com.blockether.vispython.Locations/pythonExecutable
                   (com.blockether.vispython.Locations/pythonHome (:path
                                                                    (runtime/resolve-library))))
                 executable))
          (is (= executable
                 (value! worker
                         "eval"
                         "session" session
                         "code" "__import__('sys')._base_executable")))))
      (when-not jailed?
        (testing "a trusted worker re-executes CPython with -c and -m"
          (value! worker "trust" "session" session "code" "1")
          (try (doseq [args ["['-c', 'print(123)']" "['-m', 'json.tool']"]]
                 (is (= [0 "123\n" ""]
                        (json/read-str
                          (value! worker
                                  "run"
                                  "session" session
                                  "code"
                                  (str "import os, subprocess, sys\n"
                                       "child = subprocess.run([sys.executable] + "
                                       args
                                       ", input='123', capture_output=True, text=True, timeout=15, "
                                       "env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))\n"
                                       "[child.returncode, child.stdout, child.stderr]"))))))
               (finally (value! worker "trust" "session" session "code" "0")))))
      (testing "local async and host calls need no network capability"
        (value! worker "network" "session" session "code" "{\"enabled\":false}"))
      (testing "host source directories are installed before serving interpreter requests"
        (value! worker "exec" "session" session "code" "import worker_host_fixture")
        (is (= "73"
               (value! worker "eval" "session" session "code" "str(worker_host_fixture.value)"))))
      (testing "a block reaches a host tool through the parent, and its answer comes back"
        (swap! (:tools worker) assoc
          "echo"
          (fn [args]
            (str "<" (first args) ">")))
        (value! worker "install-tool" "session" session "code" "echo")
        (let [answer
              (json/read-str
                (value! worker "run-block" "session" session "code" "print(await echo('hi'))"))]
          (is (nil? (get answer "error")) (str (get answer "error")))
          (is (= "<hi>" (str/trim (str (get answer "stdout")))))))
      (testing "directory exhaustion is recoverable across the worker wire (Vis #279)"
        (value! worker
                "exec"
                "session" session
                "code" (str "import os, pathlib\n"
                            "scan_root = pathlib.Path(" (pr-str (str root))
                            ") / 'scan-budget'\n"
                            "scan_root.mkdir()\n(scan_root / 'entry').touch()\n"))
        (let [answer
              (json/read-str (value! worker
                                     "run-block"
                                     "session" session
                                     "code" (str "scan_kept = 279\n"
                                                 "for _ in range(10001):\n"
                                                 "    list(scan_root.rglob('*.missing'))")))

              recovered
              (json/read-str (value! worker
                                     "run-block"
                                     "session" session
                                     "code" "print(scan_kept, len(os.listdir(scan_root)))"))]

          (is (str/includes? (str (get answer "error")) "10000"))
          (is (str/includes? (str (get answer "error")) "grep/ls"))
          (is (nil? (get recovered "error")) (pr-str recovered))
          (is (= "279 1\n" (get recovered "stdout")))))
      (testing "a library Future runs on a real asyncio Task beside a host call"
        (value!
          worker
          "exec"
          "session" session
          "code"
          "import asyncio as real_asyncio
async def library_call():
    loop = real_asyncio.get_running_loop()
    assert real_asyncio.current_task() is not None
    future = loop.create_future()
    loop.call_later(0.01, future.set_result, 'library')
    return await future")
        (let [answer (json/read-str (value! worker
                                            "run-block"
                                            "session" session
                                            "code"
                                            "print(await gather(library_call(), echo('host')))"))]
          (is (nil? (get answer "error")) (str (get answer "error")))
          (is (= "['library', '<host>']" (str/trim (str (get answer "stdout")))))))
      (testing "a tool the parent refuses is a catchable failure in the block"
        (let [answer (json/read-str (value! worker
                                            "run-block"
                                            "session" session
                                            "code" (str "try:\n" "    await missing()\n"
                                                        "except Exception as e:\n"
                                                        "    print('caught:', e)")))]
          (is (str/includes? (str (get answer "error") (get answer "stdout")) "missing"))))
      (testing "an op the worker does not know is an error reply, not a dead worker"
        (let [reply (request! worker "levitate" "session" session)]
          (is (str/includes? (str (get reply "error")) "no worker op named levitate"))
          (is (= "3" (value! worker "run" "session" session "code" "1 + 2")))))
      (testing "confinement is the worker's own process state"
        (value! worker
                "confine"
                "session" session
                "code" (json/write-str
                         {"read" [root] "write" [root] "refusal" "not in this worker"}))
        (spit (str root "/inside.txt") "ok")
        (let [inside
              (json/read-str
                (value! worker
                        "run-block"
                        "session" session
                        "code" (str "print(open(" (pr-str (str root "/inside.txt")) ").read())")))

              outside
              (json/read-str
                (value! worker "run-block" "session" session "code" "open('/etc/hosts').read()"))

              process
              (json/read-str (value! worker
                                     "run-block"
                                     "session" session
                                     "code" "__import__('subprocess').run(['true'])"))]

          (is (= "ok" (str/trim (str (get inside "stdout")))))
          (is (str/includes? (str (get outside "error")) "outside the readable roots"))
          (is (str/includes? (str (get process "error")) "not in this worker"))))
      (testing "closing the session and hanging up ends the process cleanly"
        (value! worker "close" "session" session)
        (.close ^SocketChannel (:channel worker))
        (is (.waitFor ^Process (:process worker) 30 TimeUnit/SECONDS)
            (str "the worker outlived its parent: " (slurp (:log worker))))
        (is (zero? (.exitValue ^Process (:process worker))) (slurp (:log worker))))
      (finally (.destroyForcibly ^Process (:process worker))
               (.close ^SocketChannel (:channel worker))
               (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
               (Files/deleteIfExists (Path/of (:socket-path worker) (make-array String 0)))
               (.delete ^File (:log worker))))))

(defn- exercise-editable!
  "The same source import and reload through JVM and native worker boundaries."
  [argv & [jailed?]]
  (let [fixture
        (atom nil)

        worker
        (start! (fn [socket home]
                  (let [packages
                        (doto (io/file home ".vis/python/packages") .mkdirs)

                        source
                        (doto (io/file home "project/src") .mkdirs)

                        module
                        (io/file source "vis_editable_worker.py")]

                    (spit module "VALUE = 41\n")
                    (spit (io/file packages "fixture.pth") (str source "\n"))
                    (reset! fixture {:home home :packages packages :module module})
                    (argv socket home)))
                (when jailed? {:read-write [] :read-only []}))

        {:keys [home packages module]}
        @fixture

        session
        "editable-worker"]

    (try (value! worker
                 "confine"
                 "session" session
                 "code" (json/write-str
                          {"read" [home] "write" [home] "refusal" "fixture roots only"}))
         (value! worker "install-runtime" "session" session)
         (is (= "41"
                (value! worker
                        "eval"
                        "session" session
                        "code" "str(__import__('vis_editable_worker').VALUE)")))
         (is (= (str module)
                (value! worker
                        "eval"
                        "session" session
                        "code" "__import__('vis_editable_worker').__file__")))
         (let [mtime (.lastModified ^File module)]
           (spit module "VALUE = 42\n")
           (.setLastModified ^File module mtime))
         (value! worker
                 "exec"
                 "session" session
                 "code" (str "import package_paths; package_paths.refresh("
                             (pr-str (str packages))
                             ", reload=True)"))
         (is (= "42"
                (value! worker
                        "eval"
                        "session" session
                        "code" "str(__import__('vis_editable_worker').VALUE)")))
         (finally (.close ^SocketChannel (:channel worker))
                  (when-not (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
                    (.destroyForcibly ^Process (:process worker)))
                  (.delete ^File (:log worker))
                  (doseq [file (reverse (file-seq (io/file home)))]
                    (io/delete-file file true))))))

(harness/defbuilt-test editable-worker-class-test (exercise-editable! jvm-argv))

(harness/defbuilt-test editable-worker-native-test
                       (if-let [argv (image-argv)]
                         (doseq [jailed? [false true]]
                           (exercise-editable! argv jailed?))
                         (println
                           "SKIP editable-worker-native-test - run clojure -T:build worker-image")))

(harness/defbuilt-test worker-class-on-a-jvm-test (exercise! jvm-argv))

(harness/defbuilt-test worker-native-image-test
                       (if-let [argv (image-argv)]
                         (doseq [jailed? [false true]]
                           (testing (if jailed? "OS-jailed native worker" "native worker")
                             (exercise! argv jailed?)))
                         (println "SKIP worker-native-image-test - no"
                                  Worker/EXECUTABLE
                                  "beside the cdylib, run `clojure -T:build worker-image`")))

(harness/defbuilt-test
  cold-read-only-sources-worker-test
  (if-let [argv (image-argv)]
    (let [cold-home (harness/temp-dir "vis-worker-cold-sources")
          roots (vec (com.blockether.vispython.Sources/roots))
          worker (start! (fn [socket home]
                           (into [(first (argv socket home)) (str "-Duser.home=" cold-home) socket
                                  "--resolved-sources"]
                                 roots))
                         {:read-write []
                          :read-only (conj roots cold-home)
                          :deny-write (conj roots cold-home)})]

      (try (value! worker "install-runtime" "session" "cold")
           (is (= "42" (value! worker "eval" "session" "cold" "code" "str(6 * 7)")))
           (is (not (.exists (io/file cold-home ".vis/python/sources")))
               "A confined worker must not extract sources into the host cache")
           (is (some? (get (request! worker
                                     "exec"
                                     "session" "cold"
                                     "code" (str "open("
                                                 (pr-str (str cold-home "/denied"))
                                                 ", 'w').close()"))
                           "error"))
               "The cold home remains read-only")
           (finally (.destroyForcibly ^Process (:process worker))
                    (.close ^SocketChannel (:channel worker))
                    (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
                    (.delete ^File (:log worker)))))
    (println "SKIP cold-read-only-sources-worker-test - run clojure -T:build worker-image")))

(defn- exercise-tls!
  [argv]
  ;; Vis #185: compatibility changes only STRICT, for every ssl context factory.
  (doseq [strict [nil "true" "false"]]
    (let [worker (start! argv nil (if strict {"VIS_PYTHON_TLS_STRICT" strict} {}))]
      (try
        (value!
          worker
          "exec"
          "session" "tls"
          "code"
          (str
            "import ssl
"
            "contexts = [ssl.create_default_context(), ssl._create_default_https_context()]
"
            "custom = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
"
            "custom.verify_flags |= ssl.VERIFY_X509_STRICT | ssl.VERIFY_CRL_CHECK_LEAF | (1 << 30)
"
            "contexts.append(custom)
"
            "assert all(c.verify_mode == ssl.CERT_REQUIRED and c.check_hostname for c in contexts)
"
            "assert custom.verify_flags & (1 << 30)
assert custom.verify_flags & ssl.VERIFY_CRL_CHECK_LEAF
"))
        (is (= (if (= "false" strict) "[False, False, False]" "[True, True, True]")
               (value! worker
                       "eval"
                       "session" "tls"
                       "code"
                       "str([bool(c.verify_flags & ssl.VERIFY_X509_STRICT) for c in contexts])")))
        (is (= "True"
               (value! worker
                       "eval"
                       "session" "tls"
                       "code" "str(all(isinstance(c, ssl.SSLContext) for c in contexts))")))
        (finally (.close ^SocketChannel (:channel worker))
                 (when-not (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
                   (.destroyForcibly ^Process (:process worker)))
                 (.delete ^File (:log worker)))))))

(harness/defbuilt-test tls-worker-class-test (exercise-tls! jvm-argv))

(harness/defbuilt-test tls-worker-native-test
                       (if-let [argv (image-argv)]
                         (exercise-tls! argv)
                         (println
                           "SKIP tls-worker-native-test - run clojure -T:build worker-image")))

(defn- bounded-stack-request!
  [worker op & fields]
  (let [reply (future (apply request! worker op fields))]
    (try (let [answer (deref reply 3000 ::timeout)]
           (when (= ::timeout answer)
             (.destroyForcibly ^Process (:process worker))
             (throw (ex-info "stack diagnostic control did not reply" {:op op})))
           (is (nil? (get answer "error")) (str op " failed: " (get answer "error")))
           (get answer "value"))
         (finally (future-cancel reply)))))

(defn- exercise-stack-diagnostics!
  [argv & [jailed?]]
  (let [home
        (harness/temp-dir "vis-stack-artifact")

        work
        (harness/temp-dir "vis-stack-work")

        worker
        (start! argv (when jailed? {:read-write [home work] :read-only []}))

        artifact
        (io/file home "python-stacks.log")

        trigger
        (io/file work "trigger")

        entered
        (io/file work "entered")

        session
        "stack-diagnostics"]

    (try
      (if (str/starts-with? (Native/platform) "windows-")
        (is (= {"status" "unavailable" "reason" "unsupported-build"}
               (bounded-stack-request! worker "dump-stacks")))
        (do
          (is (= {"status" "unavailable" "reason" "not-configured"}
                 (bounded-stack-request! worker "dump-stacks")))
          (is (= {"status" "unavailable" "reason" "absolute-path-required"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" "relative.log")))
          (spit artifact "existing file stays intact")
          (is (= {"status" "unavailable" "reason" "open-failed"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" (str artifact))))
          (is (= "existing file stays intact" (slurp artifact)))
          (io/delete-file artifact)
          (is (= {"status" "ready"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" (str artifact))))
          (is (= "rw-------"
                 (java.nio.file.attribute.PosixFilePermissions/toString
                   (Files/getPosixFilePermissions (.toPath artifact)
                                                  (make-array java.nio.file.LinkOption 0)))))
          (is (zero? (.length artifact)) "healthy setup emits no stack content")
          (is (= {"status" "unavailable" "reason" "already-configured"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" (str artifact))))
          (is (= {"status" "written"} (bounded-stack-request! worker "dump-stacks")))
          (is (= {"status" "unavailable" "reason" "not-configured"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" "")))
          (is (= {"status" "unavailable" "reason" "not-configured"}
                 (bounded-stack-request! worker "dump-stacks")))
          (io/delete-file artifact)
          (is (= {"status" "ready"}
                 (bounded-stack-request! worker "stack-diagnostics" "code" (str artifact))))
          (value!
            worker
            "exec"
            "session" session
            "code"
            "import threading, time
stop_churn = threading.Event()
def churn_threads():
    while not stop_churn.is_set():
        t = threading.Thread(target=lambda: None)
        t.start()
        t.join()
churn = threading.Thread(target=churn_threads, daemon=True)
churn.start()")
          (dotimes [_ 30]
            (is (= {"status" "written"} (bounded-stack-request! worker "dump-stacks"))))
          (value! worker "exec" "session" session "code" "stop_churn.set()
churn.join()")
          (value! worker
                  "confine"
                  "code"
                  (json/write-str {"read" [work] "write" [work] "refusal" "fixture policy"}))
          (is (= {"status" "unavailable" "reason" "already-confined"}
                 (bounded-stack-request! worker
                                         "stack-diagnostics"
                                         "code"
                                         (str (io/file home "late.log")))))
          (is (string? (get (request! worker
                                      "exec"
                                      "session" session
                                      "code" (str "open(" (pr-str (str artifact)) ", 'w')"))
                            "error"))
              "opening the diagnostic file grants no guest filesystem access")
          (value! worker
                  "exec"
                  "session" session
                  "code" (str "import os, faulthandler, signal
"
                              "faulthandler.disable()
"
                              "signal_available = hasattr(signal, 'SIGUSR1')
"
                              "if signal_available: faulthandler.unregister(signal.SIGUSR1)
"
                              "def occupy_guest():
"
                              "    private_local = 'STACK_PRIVATE_LOCAL_SENTINEL'
"
                              "    while not os.path.exists("
                              (pr-str (str trigger))
                              "):
"
                              "        time.sleep(0.005)
"
                              "    with open("
                              (pr-str (str entered))
                              ", 'w') as marker:
"
                              "        marker.write('entered')
"
                              "    sum(range(10**18))
"
                              "threading.Thread(target=occupy_guest, daemon=True).start()"))
          (spit trigger "go")
          (loop [attempt 0]
            (when (and (not (.exists entered)) (< attempt 200))
              (Thread/sleep 5)
              (recur (inc attempt))))
          (is (.exists entered) "the background C loop was entered after exec replied")
          (is (= {"status" "written"} (bounded-stack-request! worker "dump-stacks")))
          (let [text (slurp artifact)]
            (is (str/includes? text "occupy_guest") "stack is present before worker termination")
            (is (str/includes? text "defined line"))
            (is (not (str/includes? text "STACK_PRIVATE_LOCAL_SENTINEL"))))
          (is (.isAlive ^Process (:process worker)) "capture does not kill a GIL-held worker")
          (is (= {"status" "written"} (bounded-stack-request! worker "dump-stacks")))
          (is (< (.length artifact) 65536) "repeated capture replaces the bounded artifact")))
      (finally (.close ^SocketChannel (:channel worker))
               (.destroyForcibly ^Process (:process worker))
               (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
               (.delete ^File (:log worker))
               (doseq [dir
                       [home work (:home worker)]

                       file
                       (reverse (file-seq (io/file dir)))]

                 (io/delete-file file true))))))

(harness/defbuilt-test stack-diagnostics-worker-class-test (exercise-stack-diagnostics! jvm-argv))

(harness/defbuilt-test
  stack-diagnostics-worker-native-test
  (if-let [argv (image-argv)]
    (doseq [jailed? [false true]]
      (exercise-stack-diagnostics! argv jailed?))
    (println "SKIP stack-diagnostics-worker-native-test - run clojure -T:build worker-image")))

(defn- exercise-stack-descriptor!
  [argv]
  (let [home
        (harness/temp-dir "vis-stack-descriptor")

        artifact
        (io/file home "stacks.log")

        replacement
        (io/file home "replacement.txt")

        worker
        (start! argv)]

    (try (spit replacement "replacement must survive")
         (is (= {"status" "ready"}
                (bounded-stack-request! worker "stack-diagnostics" "code" (str artifact))))
         (value! worker
                 "exec"
                 "session" "descriptor"
                 "code"
                 (str "import os
"
                      "wanted = os.stat("
                      (pr-str (str artifact))
                      ").st_ino
"
                      "diagnostic_fd = None
"
                      "for candidate in range(3, 256):
"
                      "    try:
"
                      "        if os.fstat(candidate).st_ino == wanted: diagnostic_fd = candidate
"
                      "    except OSError: pass
"
                      "assert diagnostic_fd is not None
"))
         (value! worker
                 "confine"
                 "code"
                 (json/write-str {"read" [home] "write" [home] "refusal" "fixture policy"}))
         (value! worker
                 "exec"
                 "session" "descriptor"
                 "code" (str "os.close(diagnostic_fd)
"
                             "replacement_fd = os.open("
                             (pr-str (str replacement))
                             ", os.O_RDWR)
"
                             "os.dup2(replacement_fd, diagnostic_fd)
"))
         (is (= {"status" "unavailable" "reason" "descriptor-replaced"}
                (bounded-stack-request! worker "dump-stacks")))
         (is (= {"status" "unavailable" "reason" "not-configured"}
                (bounded-stack-request! worker "stack-diagnostics" "code" "")))
         (is (= "True"
                (value! worker
                        "eval"
                        "session" "descriptor"
                        "code"
                        "os.fstat(diagnostic_fd).st_ino == os.fstat(replacement_fd).st_ino")))
         (is (= "replacement must survive" (slurp replacement)))
         (finally (.close ^SocketChannel (:channel worker))
                  (.destroyForcibly ^Process (:process worker))
                  (.waitFor ^Process (:process worker) 5 TimeUnit/SECONDS)
                  (.delete ^File (:log worker))
                  (doseq [dir
                          [home (:home worker)]

                          file
                          (reverse (file-seq (io/file dir)))]

                    (io/delete-file file true))))))

(harness/defbuilt-test stack-descriptor-worker-class-test
                       (when-not (str/starts-with? (Native/platform) "windows-")
                         (exercise-stack-descriptor! jvm-argv)))

(harness/defbuilt-test
  stack-descriptor-worker-native-test
  (when-not (str/starts-with? (Native/platform) "windows-")
    (if-let [argv (image-argv)]
      (exercise-stack-descriptor! argv)
      (println "SKIP stack-descriptor-worker-native-test - run clojure -T:build worker-image"))))
