(ns com.blockether.vis-python-runtime.windows-jail-test
  "Real Windows private-workspace API checks; the JVM/native-image probe covers attacks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython VisPythonException WindowsJail]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(defn- windows? [] (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- temp-dir
  ^Path []
  (Files/createTempDirectory "vis-windows-jail-api-" (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path directory]
  (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
      (Files/deleteIfExists path))))

(defn- guest-path
  ^Path []
  (let [path (Path/of (or (System/getenv "VIS_WINDOWS_JAIL_GUEST") "target/windows-jail-guest.exe")
                      (make-array String 0))]
    (when-not (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
      (throw (ex-info "Windows CI must build the native jail guest, never skip"
                      {:path (str path)})))
    (.toAbsolutePath path)))

(deftest windows-jail-options-fail-closed-test
  (doseq [option [:policy :network :read-write :read-only :deny-read :unix-connect :keychain]]
    (is (thrown? IllegalArgumentException
                 (runtime/spawn-windows-process! nil ["must-not-run.exe"] {option true}))
        "unsupported capabilities are rejected before any native launch")))

(deftest windows-jail-platform-test
  (when-not (windows?)
    (is (false? (WindowsJail/supported)))
    (is (some? (WindowsJail/unsupportedReason)))
    (let [directory (temp-dir)]
      (try (is (thrown? VisPythonException (WindowsJail/create directory)))
           (with-open [children (Files/list directory)]
             (is (zero? (.count children)) "unsupported platforms create no private directory"))
           (finally (delete-tree! directory))))))

(deftest windows-jail-staging-and-lifecycle-test
  (if-not (windows?)
    (println "SKIP Windows jail API: exercised on the Windows CI runner")
    (let [parent
          (temp-dir)

          source
          (.resolve parent "input.txt")]

      (try (spit (str source) "original")
           (with-open [jail (runtime/windows-jail (str parent))]
             (is (= #{:directory :application :work :temporary}
                    (set (keys (runtime/windows-jail-directories jail)))))
             (is (not= parent (.directory jail)))
             (doseq [^Path directory [(.applicationDirectory jail) (.workDirectory jail)
                                      (.temporaryDirectory jail)]]
               (is (Files/isDirectory directory (make-array java.nio.file.LinkOption 0))))
             (testing "invalid destinations fail before partial staging"
               (doseq [destination ["../escape" "C:/escape" "file:stream" "NUL" "a/./b" "a/../b"
                                    "trailing." "trailing " "CON.txt" ""]]
                 (is (thrown? IllegalArgumentException (.stage jail source destination)))))
             (is (= (.resolve (.applicationDirectory jail) "input.txt")
                    (.stage jail source "input.txt")))
             (is (= "original" (slurp (str (.resolve (.applicationDirectory jail) "input.txt")))))
             (let [executable (runtime/stage-windows-jail! jail (str (guest-path)) "guest.exe")
                   ^Process process (runtime/spawn-windows-process! jail [executable "token"] {})]

               (try (.close (.getOutputStream process))
                    (is (.waitFor process 10 TimeUnit/SECONDS)
                        "native guest has a bounded lifetime")
                    (is (zero? (.exitValue process)))
                    (is (str/includes? (slurp (.getInputStream process)) "PASS"))
                    (is (false? (.supportsNormalTermination process)))
                    (is (thrown? IllegalStateException (.stage jail source "after-launch.txt")))
                    (finally (.destroyForcibly process)
                             (.close (.getInputStream process))
                             (.close (.getErrorStream process)))))
             (.close jail)
             (.close jail)
             (is (thrown? IllegalStateException (.stage jail source "after-close.txt"))))
           (is (= "original" (slurp (str source))) "host source survives staging and close")
           (finally (delete-tree! parent))))))

(deftest windows-jail-hardlink-staging-fails-closed-test
  (if-not (windows?)
    (println "SKIP Windows staging hardlink check: exercised on the Windows CI runner")
    (let [parent
          (temp-dir)

          source
          (.resolve parent "source.txt")

          link
          (.resolve parent "linked.txt")]

      (try (spit (str source) "original")
           (Files/createLink link source)
           (with-open [jail (WindowsJail/create parent)]
             (is (thrown? VisPythonException (.stage jail link "input.txt")))
             (is (thrown? IllegalStateException (.stage jail source "retry.txt"))))
           (is (= "original" (slurp (str source))))
           (finally (delete-tree! parent))))))
