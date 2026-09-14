(ns com.blockether.vis-python-runtime.windows-test
  "Windows artifact layout and the real Windows FFM boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.worker-test :as worker-test])
  (:import [com.blockether.vispython Interpreter Jail Locations Native VisPythonException Worker]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  ^Path []
  (Files/createTempDirectory "vis-windows-layout-" (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path directory]
  (doseq [^java.io.File file (reverse (file-seq (.toFile directory)))]
    (io/delete-file file)))

(deftest windows-artifact-layout-test
  (let [directory
        (temp-dir)

        home
        (.resolve directory "python")

        python
        (.resolve home "python.exe")

        uv
        (.resolve home "Scripts/uv.exe")

        library
        (.resolve directory "vispython.dll")]

    (try (Files/createDirectories (.getParent uv) (make-array FileAttribute 0))
         (doseq [path [python uv library]]
           (spit (str path) "fixture"))
         (.setExecutable (.toFile uv) true)
         (is (= (str home) (Locations/pythonHome (str library))))
         (is (= (str python) (Locations/pythonExecutable (str home))))
         (is (= (str uv) (Locations/uvExecutable (str home))))
         (Native/use (str directory))
         (is (= (str library) (.path (Native/library "windows-x64"))))
         (finally (Native/use nil) (delete-tree! directory)))))

(deftest windows-process-jail-fails-closed-test
  (let [old-os (System/getProperty "os.name")]
    (try (System/setProperty "os.name" "Windows 11")
         (is (false? (Jail/supported)))
         (is (str/includes? (Jail/unsupportedReason) "not available"))
         (is (thrown? VisPythonException
                      (Jail/spawn ["cmd.exe" "/c" "echo must-not-run"] {} nil nil false false 0 0)))
         (finally (System/setProperty "os.name" old-os)))))

(deftest windows-native-bridge-test
  (if-not (str/starts-with? (Native/platform) "windows-")
    (println "SKIP Windows native ABI: exercised on the Windows CI runner")
    (let [session
          "windows-native-smoke"

          initial-path
          (System/getenv "PATH")]

      (is (= "vis-python-worker.exe" Worker/EXECUTABLE))
      (is (some? (runtime/resolve-library)) "Windows CI must build the DLL, never skip")
      (runtime/initialize!)
      (try
        (is (= "42" (runtime/eval-str session "40 + 2")))
        (is (= "Zażółć" (runtime/eval-str session "'Zażółć'")))
        (is (str/ends-with? (Interpreter/pythonExecutable) "python.exe"))
        (is
          (= "True"
             (runtime/eval-str
               session
               "bool(__import__('ssl').OPENSSL_VERSION and __import__('sqlite3').sqlite_version)")))
        (is (= initial-path (System/getenv "PATH")) "DLL loading never changes PATH")
        (finally (runtime/close-session! session))))))

(deftest windows-jvm-worker-protocol-test
  (if-not (str/starts-with? (Native/platform) "windows-")
    (println "SKIP Windows JVM worker: exercised on the Windows CI runner")
    (#'worker-test/exercise! #'worker-test/jvm-argv)))

(deftest windows-native-worker-protocol-test
  (if-not (str/starts-with? (Native/platform) "windows-")
    (println "SKIP Windows native worker: exercised on the Windows CI runner")
    (let [argv (#'worker-test/image-argv)]
      (is (some? argv) "Windows CI must build the native worker, never skip")
      (when argv (#'worker-test/exercise! argv)))))
