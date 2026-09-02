(ns com.blockether.vis-python-runtime.jail-test
  "The process boundary itself: Java enters libvisjail over FFM, the library
   spawns a detached child, and the OS—not an argv convention—enforces the policy."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent TimeUnit]))

(defn- temp-dir ^Path []
  (.toRealPath (Files/createTempDirectory "visjail-" (make-array FileAttribute 0))
               (make-array java.nio.file.LinkOption 0)))

(defn- macos? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- seatbelt-profile [^Path root]
  (let [ancestors (take-while some? (iterate #(.getParent ^Path %) root))
        literals (apply str (map #(str "(literal \"" % "\")") ancestors))]
    (str "(version 1)(import \"system.sb\")(deny default)"
         "(allow process-fork process-exec)(allow sysctl-read)(allow ipc-posix-sem)"
         "(allow file-read-metadata" literals "(subpath \"" root "\"))"
         "(allow file-read* (subpath \"/usr\") (subpath \"/bin\")"
         " (subpath \"/System\") (subpath \"/Library\")"
         " (subpath \"/private/etc\") (subpath \"/private/var/select\")"
         " (subpath \"/opt/homebrew\") (subpath \"/usr/local\")"
         " (literal \"/dev/null\") (literal \"/dev/urandom\"))"
         "(allow file-write* (subpath \"" root "\")"
         " (literal \"/dev/null\") (literal \"/dev/tty\")"
         " (literal \"/dev/stdout\") (literal \"/dev/stderr\"))"
         "(allow file-ioctl)(deny network*)")))

(defn- linux-arguments [^Path root]
  (vec (concat ["--die-with-parent" "--proc" "/proc" "--dev" "/dev"]
               (mapcat #(vector "--ro-bind-try" % %)
                       ["/usr" "/bin" "/sbin" "/lib" "/lib64" "/etc"])
               ["--bind" (str root) (str root) "--unshare-net" "--"])))

(defn- options [^Path root & [extra]]
  (merge {:environment {"PATH" "/usr/bin:/bin" "HOME" (str root)
                        "TMPDIR" (str root) "LANG" "C"}
          :directory (str root)
          :seatbelt-profile (when (macos?) (seatbelt-profile root))
          :linux-arguments (when-not (macos?) (linux-arguments root))}
         extra))

(defn- run-process [command opts]
  (let [p (runtime/spawn-process! command opts)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))
        exit (.waitFor p)]
    {:exit exit :out @out :err @err}))

(deftest pipes-environment-and-seatbelt-or-bubblewrap-test
  (let [root (temp-dir)
        outside (io/file (System/getProperty "user.home")
                         (str ".visjail-denied-" (System/nanoTime)))
        inside (.resolve root "inside.txt")]
    (try
      (testing "ordinary process semantics survive the native boundary"
        (let [result (run-process
                      ["/bin/sh" "-c"
                       (str "printf '%s' \"$VALUE\" > " inside
                            "; printf stdout; printf stderr >&2; exit 7")]
                      (update (options root) :environment assoc "VALUE" "from-env"))]
          (is (= 7 (:exit result)))
          (is (= "stdout" (:out result)))
          (is (str/ends-with? (:err result) "stderr"))
          (is (= "from-env" (slurp (str inside))))))
      (testing "an ungranted host path stays unwritable"
        (let [result (run-process ["/bin/sh" "-c" (str "printf escaped > " outside)]
                                  (options root))]
          (is (not (zero? (:exit result))))
          (is (not (.exists outside)))))
      (testing "the process is a detached group leader and can be terminated as a tree"
        (let [p (runtime/spawn-process!
                 ["/bin/sh" "-c" "sleep 30 & wait"] (options root))]
          (.destroyForcibly p)
          (is (.waitFor p 5 TimeUnit/SECONDS))
          (is (not (.isAlive p)))))
      (finally
        (when (.exists outside) (io/delete-file outside true))
        (doseq [file (reverse (file-seq (.toFile root)))] (io/delete-file file true))))))

(deftest pty-round-trip-test
  (let [root (temp-dir)]
    (try
      (let [p (runtime/spawn-process!
               ["/bin/sh" "-c" "stty size; IFS= read -r value; printf 'got:%s\n' \"$value\""]
               (options root {:pty? true :merge-stderr? true :rows 31 :columns 97}))]
        (.write (.getOutputStream p) (.getBytes "hello\n" "UTF-8"))
        (.flush (.getOutputStream p))
        (is (= 0 (.waitFor p)))
        (let [out (slurp (.getInputStream p))]
          (is (str/includes? out "31 97"))
          (is (str/includes? out "got:hello"))))
      (finally
        (doseq [file (reverse (file-seq (.toFile root)))] (io/delete-file file true))))))
(deftest unconfined-process-still-uses-native-lifecycle-test
  (let [root (temp-dir)]
    (try
      (let [result (run-process ["/bin/sh" "-c" "printf %s \"$VALUE\""]
                                {:confined? false
                                 :environment {"PATH" "/usr/bin:/bin" "VALUE" "direct"}
                                 :directory (str root)})]
        (is (= {:exit 0 :out "direct" :err ""} result)))
      (finally
        (doseq [file (reverse (file-seq (.toFile root)))] (io/delete-file file true))))))
