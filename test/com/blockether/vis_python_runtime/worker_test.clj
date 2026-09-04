(ns com.blockether.vis-python-runtime.worker-test
  "`vis-python-worker`: the interpreter as a program of its own.

   A host runs many sessions and gives each one a worker process, so what a
   worker must get right is the WIRE — one JSON line per message, requests from
   the parent, `host` requests back — and its life: connect first, serve until
   the parent hangs up, then leave. The cases drive a real worker over a real
   unix socket, the way vis does, and the same drive runs twice: against the
   class on this JVM, and against the native image when `worker-image` has
   built one beside the cdylib."
  (:require [clojure.data.json :as json]
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
    (let [value {"op" "run" "id" 7 "code" "print('zażółć \"q\" \\ \n')"
                 "nested" [1 2.5 true false nil {"k" []}]}
          text  (Json/write value)]
      (is (= value (Json/parse text)))
      (is (= value (json/read-str text)) "and clojure.data.json reads the same value")
      (is (= value (Json/parse (json/write-str value))) "and reads what clojure.data.json wrote")))
  (testing "an incomplete or trailing text is refused, never half-read"
    (is (thrown? Exception (Json/parse "{\"a\": 1")))
    (is (thrown? Exception (Json/parse "1 2")))
    (is (thrown? Exception (Json/object "[1]")))))

(defn- jvm-argv
  "Run the worker CLASS on this JVM, from this suite's own classpath."
  [socket]
  [(str (System/getProperty "java.home") File/separator "bin" File/separator "java")
   "--enable-native-access=ALL-UNNAMED"
   "-cp" (System/getProperty "java.class.path")
   "com.blockether.vispython.Worker" socket])

(defn- image-argv
  "Run the native image `worker-image` built, when there is one."
  []
  (when-let [executable (runtime/resolve-worker)]
    (fn [socket] [executable socket])))

(defn- start!
  "Listen on a fresh unix socket, start the worker with `argv` and answer the
   accepted connection together with the process and its log.

   `/tmp`, not the JDK's temp directory: a unix socket path is capped at 104
   bytes on macOS and the per-user temp directory alone spends half of that."
  [argv]
  (let [path    (str "/tmp/vis-worker-" (System/nanoTime) ".sock")
        server  (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                  (.bind (UnixDomainSocketAddress/of path)))
        log     (File/createTempFile "vis-worker" ".log")
        builder (doto (ProcessBuilder. ^java.util.List (argv path))
                  (.redirectErrorStream true)
                  (.redirectOutput log))
        _       (.put (.environment builder) Native/NATIVE_PATH_ENV (:path (runtime/resolve-library)))
        process (.start builder)
        accept  (future (.accept server))
        channel (deref accept 60000 ::timeout)]
    (when (= ::timeout channel)
      (.destroyForcibly process)
      (throw (ex-info "the worker never connected" {:argv (argv path) :log (slurp log)})))
    (.close server)
    (Files/deleteIfExists (Path/of path (make-array String 0)))
    {:process process
     :log     log
     :channel channel
     :reader  (BufferedReader. (InputStreamReader. (Channels/newInputStream ^SocketChannel channel) StandardCharsets/UTF_8))
     :writer  (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ^SocketChannel channel) StandardCharsets/UTF_8))
     :tools   (atom {})
     :sequence (atom 0)}))

(defn- send! [{:keys [^BufferedWriter writer]} message]
  (.write writer ^String (json/write-str message))
  (.write writer "\n")
  (.flush writer))

(defn- request!
  "Ask the worker one thing and answer its reply, serving every `host` request
   it makes on the way — a block calling a tool is waiting on this same socket."
  [{:keys [^BufferedReader reader tools sequence] :as worker} op & {:as fields}]
  (let [id (swap! sequence inc)]
    (send! worker (assoc fields "op" op "id" id))
    (loop []
      (let [line (.readLine reader)]
        (when (nil? line)
          (throw (ex-info "the worker hung up" {:op op :log (slurp (:log worker))})))
        (let [message (json/read-str line)]
          (cond
            (= "host" (get message "op"))
            (let [tool (get @tools (get message "tool"))
                  args (get (json/read-str (get message "payload")) "args")]
              (send! worker (if tool
                              {"id" (get message "id") "value" (json/write-str {"value" (tool args)})}
                              {"id" (get message "id") "error" (str "no tool named " (get message "tool"))}))
              (recur))

            (= id (get message "id")) message
            :else (recur)))))))

(defn- value! [worker op & {:as fields}]
  (let [reply (apply request! worker op (mapcat identity fields))]
    (is (nil? (get reply "error")) (str op " failed: " (get reply "error")))
    (get reply "value")))

(defn- exercise!
  "The whole drive, against whatever `argv` starts."
  [argv]
  (let [worker  (start! argv)
        session "worker-test"
        root    (harness/temp-dir "vis-worker-root")]
    (try
      (testing "the interpreter answers over the wire"
        (value! worker "install-runtime" "session" session)
        (is (= "2" (value! worker "run" "session" session "code" "1 + 1"))))

      (testing "a block reaches a host tool through the parent, and its answer comes back"
        (swap! (:tools worker) assoc "echo" (fn [args] (str "<" (first args) ">")))
        (value! worker "install-tool" "session" session "code" "echo")
        (let [answer (json/read-str (value! worker "run-block" "session" session
                                            "code" "print(await echo('hi'))"))]
          (is (nil? (get answer "error")) (str (get answer "error")))
          (is (= "<hi>" (str/trim (str (get answer "stdout")))))))

      (testing "a tool the parent refuses is a catchable failure in the block"
        (let [answer (json/read-str (value! worker "run-block" "session" session
                                            "code" (str "try:\n"
                                                        "    await missing()\n"
                                                        "except Exception as e:\n"
                                                        "    print('caught:', e)")))]
          (is (str/includes? (str (get answer "error") (get answer "stdout")) "missing"))))

      (testing "an op the worker does not know is an error reply, not a dead worker"
        (let [reply (request! worker "levitate" "session" session)]
          (is (str/includes? (str (get reply "error")) "no worker op named levitate"))
          (is (= "3" (value! worker "run" "session" session "code" "1 + 2")))))

      (testing "confinement is the worker's own process state"
        (value! worker "confine" "session" session
                "code" (json/write-str {"read" [root] "write" [root] "refusal" "not in this worker"}))
        (spit (str root "/inside.txt") "ok")
        (let [inside  (json/read-str (value! worker "run-block" "session" session
                                             "code" (str "print(open(" (pr-str (str root "/inside.txt")) ").read())")))
              outside (json/read-str (value! worker "run-block" "session" session
                                             "code" "open('/etc/hosts').read()"))
              process (json/read-str (value! worker "run-block" "session" session
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
      (finally
        (.destroyForcibly ^Process (:process worker))
        (.delete ^File (:log worker))))))

(harness/defbuilt-test worker-class-on-a-jvm-test
  (exercise! jvm-argv))

(harness/defbuilt-test worker-native-image-test
  (if-let [argv (image-argv)]
    (exercise! argv)
    (println "SKIP worker-native-image-test - no" Worker/EXECUTABLE
             "beside the cdylib, run `clojure -T:build worker-image`")))
