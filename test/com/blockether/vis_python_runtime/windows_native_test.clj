(ns com.blockether.vis-python-runtime.windows-native-test
  "Exercise the native ABI and Windows filesystem boundary through the public bridge."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.test-diagnostics :as diagnostics])
  (:import [com.blockether.vispython VisPythonException]
           [java.io File]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def ^:private windows? (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- temporary-directory
  ^Path [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- python-string [value] (json/write-str (str value) :escape-slash false))

(defn- read-expression [path] (str "open(" (python-string path) ", encoding='utf-8').read()"))

(defn- console-startup-probe
  [customization form]
  (let [directory
        (temporary-directory "vis-console-startup-")

        log
        (io/file (str directory) "probe.log")

        code
        (binding [*print-meta* true]
          (pr-str (list 'do '(require '[com.blockether.vis-python-runtime :as runtime]) form)))

        builder
        (doto (ProcessBuilder. ^java.util.List
                               [(str (io/file (System/getProperty "java.home") "bin" "java"))
                                "--enable-native-access=ALL-UNNAMED" "-cp"
                                (System/getProperty "java.class.path") "clojure.main" "-"])
          (.redirectErrorStream true)
          (.redirectOutput log))]

    (spit (str (.resolve directory "sitecustomize.py")) customization)
    (.put (.environment builder) "PYTHONPATH" (str directory))
    (.put (.environment builder) "PYTHONDONTWRITEBYTECODE" "1")
    (let [process (.start builder)]
      (try
        ;; Windows command-line parsing must not reinterpret quoted Clojure forms.
        (with-open [writer (io/writer (.getOutputStream process) :encoding "UTF-8")]
          (.write writer ^String code))
        (is (.waitFor process 30 TimeUnit/SECONDS) "console startup must not hang")
        (when (.isAlive process) (.destroyForcibly process) (.waitFor process 5 TimeUnit/SECONDS))
        {:exit (.exitValue process) :out (slurp log)}
        (finally (when (.isAlive process) (.destroyForcibly process))
                 (doseq [^File file (reverse (file-seq (.toFile directory)))]
                   (.delete file)))))))

(use-fixtures :each
              (fn [run]
                (diagnostics/stage! "Initialize native runtime")
                (runtime/initialize!)
                (when diagnostics/*stage*
                  ;; Timed dumps use CPython's watchdog, not its fatal-signal handlers:
                  ;; the embedding JVM must retain ownership of those handlers.
                  (runtime/exec!
                    "windows-test-diagnostics"
                    (str
                      "import faulthandler, sys\n"
                      "faulthandler.dump_traceback_later(90, repeat=True, file=sys.__stderr__)")))
                (try (run)
                     (finally (diagnostics/stage! "Reset native filesystem policy")
                              (runtime/confine! [] [])
                              (diagnostics/stage! "Reset native network, thread and log policy")
                              (runtime/network! true)
                              (runtime/threads! 100 0 8)
                              (runtime/logging! :off)
                              (runtime/drain-log!)
                              (when diagnostics/*stage*
                                (runtime/exec! "windows-test-diagnostics"
                                               "faulthandler.cancel_dump_traceback_later()"))
                              (diagnostics/stage! "Native fixture complete")))))

(deftest windows-console-preexisting-descriptors-are-guarded-test
  (when windows?
    (let
      [{:keys [exit out]}
       (console-startup-probe
         (str "import _io\n" "_io.vis_console_init = _io._WindowsConsoleIO.__init__\n"
              "_io.vis_console_instance = _io._WindowsConsoleIO.__new__(_io._WindowsConsoleIO)\n"
              "_io.vis_console_bound = _io.vis_console_instance.__init__\n")
         '(do
           (runtime/initialize!)
           (runtime/confine! [(System/getenv "PYTHONPATH")] [])
           (doseq
            [expression
             ["_io.vis_console_init(_io.vis_console_instance, 'CONIN$', 'r')"
              "_io.vis_console_bound('CONIN$', 'r')"]]
            (try
             (runtime/exec! "console-startup" (str "import _io\n" expression))
             (println "unexpected success")
             (catch Exception error (println (.getMessage error)))))))]
      (is (= 0 exit) out)
      (is (= 2 (count (re-seq #"vis sandbox: Windows console access is forbidden" out))) out)
      (is (not (str/includes? out "unexpected success")) out))))

(deftest windows-console-preexisting-subclasses-fail-startup-test
  (when windows?
    (let [{:keys [exit out]} (console-startup-probe
                               "import _io\nclass EarlyConsole(_io._WindowsConsoleIO):\n    pass\n"
                               '(dotimes
                                 [_ 2]
                                 (try
                                  (runtime/initialize!)
                                  (println "unexpected success")
                                  (catch Exception error (println (.getMessage error))))))]
      (is (= 0 exit) out)
      (is (= 2
             (count (re-seq #"Windows console guard refuses pre-existing console subclasses" out)))
          out)
      (is (not (str/includes? out "unexpected success")) out))))

(deftest invalid-policy-fails-closed-test
  ;; Windows rejects device/UNC roots; dropping every root must not unconfine the process.
  (let [outside (.resolve (temporary-directory "vis-invalid-policy") "private.txt")]
    (spit (str outside) "outside")
    (is (= "outside" (runtime/eval-str "invalid-policy" (read-expression outside))))
    (runtime/confine! [(apply str (repeat 40000 "x"))] [])
    (is (thrown-with-msg? VisPythonException
                          #"vis sandbox"
                          (runtime/eval-str "invalid-policy" (read-expression outside))))))

(deftest overlong-audit-path-fails-closed-test
  (runtime/confine! [(str (temporary-directory "vis-long-path"))] [])
  (is (thrown-with-msg? VisPythonException
                        #"vis sandbox"
                        (runtime/eval-str "long-path" "open('x' * 40000)"))))

(deftest windows-native-strings-and-thread-local-results-test
  (when windows?
    (is (= "zażółć 🐍" (runtime/eval-str "windows-unicode" "'zażółć 🐍'")))
    (testing "oversized results are recovered once on the calling thread"
      (let [answers (doall (for [n (range 8)]
                             (future (runtime/eval-str (str "windows-result-" n)
                                                       (str "'" n "' * 90000")))))]
        (doseq [[n answer] (map-indexed vector answers)]
          (let [result (deref answer 30000 ::timeout)]
            (is (not= ::timeout result) "native evaluation must release the GIL and return")
            (when-not (= ::timeout result) (is (= (apply str (repeat 90000 (str n))) result)))))))
    (is (= "42" (runtime/eval-str "windows-unicode" "sum([19, 23])")))))

(deftest windows-native-confinement-test
  (when windows?
    (let [inside
          (temporary-directory "vis-zażółć-")

          outside
          (temporary-directory "vis-outside-")

          readable
          (.resolve inside "żółw.txt")

          private
          (.resolve outside "secret.txt")

          created
          (.resolve inside "新しい.txt")]

      (spit (str readable) "inside" :encoding "UTF-8")
      (spit (str private) "outside" :encoding "UTF-8")
      (runtime/confine! [] [(str inside)])
      (is (= "inside" (runtime/eval-str "windows-files" (read-expression readable))))
      (is (= "2"
             (runtime/eval-str
               "windows-files"
               (str "open(" (python-string created) ", 'w', encoding='utf-8').write('ok')"))))
      (is (= "ok" (slurp (str created) :encoding "UTF-8")))
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-files" (read-expression private))))
      (doseq [[label path] [["alternate data stream" (str readable ":stream")]
                            ["DOS null alias" (str inside "\\NUL")]
                            ["DOS console alias" (str inside "\\CON.txt")]
                            ["device namespace" "\\\\.\\NUL"]
                            ["extended namespace" "\\\\?\\C:\\Windows\\win.ini"]
                            ["UNC share" "\\\\127.0.0.1\\share\\secret"]]]
        (diagnostics/stage! (str "Reject Windows " label))
        (is (thrown-with-msg? VisPythonException
                              #"vis sandbox"
                              (runtime/eval-str "windows-files" (read-expression path)))
            label)))))

(deftest windows-console-constructor-is-confined-test
  ;; CI 34843998016 blocked in a console read: CPython's WindowsConsoleIO does
  ;; not emit the open audit event. Guard automatic dispatch and raw type calls.
  (when windows?
    (let [session "windows-console-constructor"]
      (try (runtime/exec!
             session
             (str "import _io, io, pathlib\n"
                  "ConsoleIO = _io._WindowsConsoleIO\n" "raw_init = ConsoleIO.__init__\n"
                  "class DerivedConsole(ConsoleIO):\n    pass\n" "class SuperConsole(ConsoleIO):\n"
                  "    def __init__(self, *args, **kwargs):\n"
                  "        super().__init__(*args, **kwargs)\n"
                  "instance = ConsoleIO.__new__(ConsoleIO)\n" "bound_init = instance.__init__"))
           (is (thrown-with-msg? VisPythonException
                                 #"ValueError"
                                 (runtime/eval-str session "ConsoleIO('CONIN$', 'invalid')")))
           (runtime/confine! [(str (temporary-directory "vis-console-policy-"))] [])
           (doseq [expression ["open('CONIN$', 'r')" "io.open('CONOUT$', 'w')"
                               "pathlib.Path('CON').open()" "ConsoleIO('CONIN$', 'r')"
                               "ConsoleIO(file='CONIN$', mode='r')" "DerivedConsole('CONIN$', 'r')"
                               "SuperConsole(file='CONIN$', mode='r')"
                               "raw_init(instance, 'CONIN$', 'r')" "bound_init('CONIN$', 'r')"
                               "ConsoleIO.__init__(instance, file='CONIN$', mode='r')"]]
             (diagnostics/stage! (str "Reject Windows console constructor: " expression))
             (is (thrown-with-msg? VisPythonException
                                   #"vis sandbox"
                                   (runtime/eval-str session expression))
                 expression))
           (runtime/confine! [] [])
           (is (thrown-with-msg? VisPythonException
                                 #"ValueError"
                                 (runtime/eval-str session "ConsoleIO('CONIN$', 'invalid')")))
           (finally (runtime/close-session! session))))))

(deftest windows-native-junction-escape-test
  (when windows?
    (let [inside
          (temporary-directory "vis-junction-root-")

          outside
          (temporary-directory "vis-junction-outside-")

          junction
          (.resolve inside "escape")

          secret
          (.resolve outside "secret.txt")

          process
          (-> (ProcessBuilder. ^"[Ljava.lang.String;"
                               (into-array String
                                           ["cmd.exe" "/d" "/c" "mklink" "/J" (str junction)
                                            (str outside)]))
              (.redirectErrorStream true)
              (.start))

          output
          (do (when-not (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS)
                (.destroyForcibly process)
                (throw (ex-info "Junction creation timed out" {})))
              (slurp (.getInputStream process)))]

      (is (= 0 (.exitValue process)) output)
      (spit (str secret) "private")
      (runtime/confine! [] [(str inside)])
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-junction"
                                              (read-expression (.resolve junction "secret.txt")))))
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-junction"
                                              (str "open("
                                                   (python-string (.resolve junction "new.txt"))
                                                   ", 'w').write('escape')"))))
      (is (not (.exists (.toFile (.resolve outside "new.txt")))))
      (runtime/confine! [] [])
      (Files/delete junction))))

(deftest windows-native-policy-and-diagnostics-test
  (when windows?
    (runtime/confine! [(str (temporary-directory "vis-windows-policy-"))] [])
    (doseq [code
            ["__import__('os').system('echo forbidden')"
             "__import__('winreg').OpenKey(__import__('winreg').HKEY_CURRENT_USER, 'Software')"]]
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-policy" code))))
    (runtime/network! false)
    (is (thrown-with-msg? VisPythonException
                          #"vis sandbox"
                          (runtime/eval-str "windows-policy" "__import__('socket').socket()"))))
  (runtime/logging! :debug)
  ;; Only run/block calls produce evaluation records; eval-str is a raw ABI probe.
  (is (= "42" (runtime/run "windows-policy" "6 * 7")))
  (let [records (map json/read-str (str/split-lines (runtime/drain-log!)))]
    (is (seq records))
    (is (some #(= "run" (get % "event")) records))
    (is (every? #(number? (get % "ts")) records)))
  (is (= "" (runtime/drain-log!))))

(deftest windows-native-filesystem-errors-test
  ;; The Windows CI DLL link must not depend on the CRT's private error mapper.
  (let [directory
        (temporary-directory "vis-windows-errors-")

        missing
        (.resolve directory "missing")

        target
        (.resolve directory "target.txt")

        session
        "windows-filesystem-errors"]

    (runtime/trust! session)
    (try (is (= "[]"
                (runtime/run session
                             (str "__import__('_vis_fs').list(" (python-string directory) ")"))))
         (doseq [expression [(str "__import__('_vis_fs').list(" (python-string missing) ")")
                             (str "__import__('_vis_fs').move("
                                  (python-string missing)
                                  ", "
                                  (python-string target)
                                  ")")]]
           (is (thrown-with-msg? VisPythonException
                                 #"FileNotFoundError"
                                 (runtime/run session expression))))
         (is (not (.exists (.toFile target))))
         (finally (runtime/trust! session false) (Files/delete directory)))))

(deftest windows-native-trusted-files-test
  (when windows?
    (let [outside
          (temporary-directory "vis-trusted-zażółć-")

          session
          "windows-trusted-files"]

      (runtime/confine! [(str (temporary-directory "vis-trusted-policy-"))] [])
      (runtime/trust! session)
      (try (is (= ["new.txt" 2 "ok" true true]
                  (json/read-str
                    (runtime/run
                      session
                      (str "import _vis_fs\n"
                           "directory = " (python-string (.resolve outside "新しい"))
                           "\n" "_vis_fs.mkdir(directory)\n"
                           "source = directory + '/żółw.txt'\n" "copy = directory + '/copy.txt'\n"
                           "target = directory + '/new.txt'\n" "_vis_fs.write(source, 'ok')\n"
                           "_vis_fs.copy(source, copy)\n" "_vis_fs.move(copy, target)\n"
                           "_vis_fs.remove(source)\n"
                           "[_vis_fs.list(directory)[0], _vis_fs.stat(target)['size'], "
                           "_vis_fs.read(target).decode(), _vis_fs.remove(target), "
                           "_vis_fs.remove(directory)]")))))
           (finally (runtime/trust! session false))))))
