(ns com.blockether.vis-python-runtime.confinement-test
  "The sandbox boundary in C (`native/vispython/vispython.c`).

   GraalPy confined the guest with a Truffle `FileSystem` the guest could not
   reach; CPython opens files with the whole process's credentials, so the
   boundary is an audit hook installed before the interpreter starts and a
   policy that lives in C. That is the point of these cases: they do not test a
   Python guard, they test one a block cannot rebind, delete or read.

   Confinement is PROCESS state, like the interpreter — every session runs under
   the policy set last — so every case here lifts it again."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness
             :refer [block confined-session temp-dir]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; A confined interpreter would refuse the next namespace's imports.
           (when harness/built? (runtime/confine! [] []))
           (harness/close-sessions!)))))

(defn- refused?
  "Whether the block was refused BY THE BOUNDARY, and not by something else."
  [answer]
  (and (some? (:error answer)) (str/includes? (str (:error answer)) "vis sandbox")))

(harness/defbuilt-test confined-read-test
  (let [inside  (temp-dir "vis-inside")
        outside (temp-dir "vis-outside")]
    (spit (str inside "/in.txt") "INSIDE")
    (spit (str outside "/out.txt") "OUTSIDE")
    (let [session (confined-session [inside] [])]
      (testing "a file under a readable root reads"
        (is (= "INSIDE" (str/trim (str (:stdout (block session
                                                       (str "print(open('" inside "/in.txt').read())"))))))))
      (testing "a file outside every root is refused"
        (is (refused? (block session (str "print(open('" outside "/out.txt').read())")))))
      (testing "the refusal reaches a block that never named the path itself"
        (is (refused? (block session "print(open('/etc/hosts').read())"))))
      (testing "listing a directory outside every root is refused"
        (is (refused? (block session (str "import os\nprint(os.listdir('" outside "'))")))))
      (testing "a relative escape out of the root is refused"
        (is (refused? (block session
                             (str "print(open('" inside "/../etc/hosts').read())")))))
      (testing "a symlink INSIDE the root pointing out of it is refused"
        (Files/createSymbolicLink (Path/of (str inside "/escape.txt") (make-array String 0))
                                  (Path/of (str outside "/out.txt") (make-array String 0))
                                  (make-array FileAttribute 0))
        (is (refused? (block session (str "print(open('" inside "/escape.txt').read())"))))))))

(harness/defbuilt-test confined-write-test
  (let [readable (temp-dir "vis-readable")
        writable (temp-dir "vis-writable")]
    (spit (str readable "/in.txt") "READ-ONLY")
    (let [session (confined-session [readable] [writable])]
      (testing "a write under a writable root lands"
        (is (nil? (:error (block session
                                 (str "open('" writable "/made.txt', 'w').write('ok')")))))
        (is (= "ok" (slurp (str writable "/made.txt")))))
      (testing "a writable root is readable too"
        (is (= "ok" (str/trim (str (:stdout (block session
                                                   (str "print(open('" writable "/made.txt').read())"))))))))
      (testing "a write into a READABLE root is refused"
        (is (refused? (block session (str "open('" readable "/nope.txt', 'w').write('x')"))))
        (is (not (.exists (java.io.File. (str readable "/nope.txt"))))))
      (testing "os.remove of a file in a readable root is refused"
        (is (refused? (block session
                             (str "import os\nos.remove('" readable "/in.txt')"))))
        (is (= "READ-ONLY" (slurp (str readable "/in.txt")))))
      (testing "os.rename out of the writable root is refused"
        (is (refused? (block session
                             (str "import os\nos.rename('" writable "/made.txt', '"
                                  readable "/moved.txt')"))))))))

(harness/defbuilt-test lifting-confinement-test
  (testing "a policy replaced by an empty one leaves the interpreter unconfined"
    (let [outside (temp-dir "vis-lifted")
          session (confined-session [(temp-dir "vis-only")] [])]
      (spit (str outside "/out.txt") "OUTSIDE")
      (is (refused? (block session (str "print(open('" outside "/out.txt').read())"))))
      (is (= {:read 0 :write 0} (runtime/confine! [] [])))
      (is (= "OUTSIDE" (str/trim (str (:stdout (block session
                                                      (str "print(open('" outside "/out.txt').read())"))))))))))

;; GraalPy wrapped the confined FileSystem with `allowLanguageHomeAccess`, so a
;; confined context could still read its own stdlib. Here the roots are the
;; host's, and a policy naming only the session's directories would refuse the
;; interpreter's next import — which is not a sandbox, it is a broken
;; interpreter. Deriving them is the runtime's job, not every host's.
(harness/defbuilt-test interpreter-roots-test
  (testing "a policy the host set without them still lets the interpreter import"
    (let [inside  (temp-dir "vis-import")
          session (harness/block-session)]
      (runtime/confine! [inside] [])
      (is (= "ok" (str/trim (str (:stdout (block session "import colorsys\nprint('ok')"))))))
      (testing "and its source is readable, so a cold import does not need the cache"
        (is (= "True" (str/trim (str (:stdout (block session (str "import colorsys\n"
                                                                  "print(len(open(colorsys.__file__).read()) > 0)"))))))))
      (is (refused? (block session "print(open('/etc/hosts').read())"))))))

;; The process surface used to be refused by `resources/vis-shims/posix.py`,
;; which put a module of fakes in `sys.modules["subprocess"]`: a guard written in
;; the language it guards, covering only the doors it knew to name. It lives in
;; the audit hook now, where a block cannot reach it.
(harness/defbuilt-test process-surface-test
  (let [session (confined-session [(temp-dir "vis-process")] [])]
    (testing "subprocess never spawns"
      (is (refused? (block session "import subprocess\nsubprocess.run(['/bin/echo', 'hi'])"))))
    (testing "os.system never spawns"
      (is (refused? (block session "import os\nos.system('/bin/echo hi')"))))
    (testing "os.popen never spawns"
      (is (refused? (block session "import os\nos.popen('/bin/echo hi').read()"))))
    (testing "the real module is left in place, so an `except` line still resolves"
      (is (= "True"
             (str/trim (str (:stdout (block session
                                            (str "import subprocess\n"
                                                 "print(issubclass(subprocess.CalledProcessError,"
                                                 " subprocess.SubprocessError))"))))))))))

(harness/defbuilt-test process-refusal-wording-test
  (testing "a host that words the refusal its own way is what the guest reads"
    (let [session (confined-session [(temp-dir "vis-worded")] [] "use shell(...) instead")]
      (is (str/includes? (str (:error (block session "import os\nos.system('/bin/echo hi')")))
                         "use shell(...) instead")))))

(harness/defbuilt-test native-symbol-test
  (let [session (confined-session [(temp-dir "vis-native")] [])]
    (testing "ctypes still IMPORTS, because a package that merely imports it must run"
      (is (= "ok" (str/trim (str (:stdout (block session "import ctypes\nprint('ok')")))))))
    (testing "reaching a symbol out of a loaded library is refused"
      (is (refused? (block session "import ctypes\nctypes.CDLL(None).system"))))
    (testing "an extension module the interpreter ships still imports"
      (is (= "ok" (str/trim (str (:stdout (block session "import _dbm\nprint('ok')")))))))))

(harness/defbuilt-test unconfined-process-test
  (testing "the refusal is the POLICY, not a ban compiled into the library"
    (let [session (harness/block-session)]
      (runtime/confine! [] [] "")
      (is (= "hi"
             (str/trim (str (:stdout (block session
                                            (str "import subprocess\n"
                                                 "print(subprocess.run(['/bin/echo', 'hi'],"
                                                 " capture_output=True).stdout.decode().strip())"))))))))))

(harness/defbuilt-test concurrent-confinement-test
  ;; The policy is PROCESS state and the host sets it from whatever thread it
  ;; happens to be on, while the audit hook reads it from whatever thread the
  ;; guest is on. Replacing it used to free the very roots a hook was comparing:
  ;; SIGSEGV inside `strcmp`, reported from a gateway that confined two sessions
  ;; at once on macOS arm64. So this case does not assert a refusal, it asserts
  ;; the PROCESS is still alive — a regression here does not fail, it aborts the
  ;; JVM — and that the policy standing at the end is the one set last.
  (let [inside   (temp-dir "vis-concurrent-inside")
        outside  (temp-dir "vis-concurrent-outside")
        sessions (mapv (fn [_] (harness/block-session)) (range 4))
        stop     (atom false)
        readers  (mapv (fn [session]
                         (Thread/startVirtualThread
                           (fn []
                             (while (not @stop)
                               (block session (str "import os\nos.listdir('" inside "')"))))))
                       sessions)
        writers  (mapv (fn [i]
                         (Thread/startVirtualThread
                           (fn []
                             (dotimes [_ 200]
                               (runtime/confine! [inside (if (even? i) outside inside)]
                                                 [inside]
                                                 "")))))
                       (range 4))]
    (spit (str inside "/in.txt") "INSIDE")
    (spit (str outside "/out.txt") "OUTSIDE")
    (run! (fn [^Thread t] (.join t)) writers)
    (reset! stop true)
    (run! (fn [^Thread t] (.join t)) readers)
    (runtime/confine! [inside] [inside] "")
    (testing "the interpreter survived every swap and the last policy is the one in force"
      (is (= "INSIDE" (str/trim (str (:stdout (block (first sessions)
                                                     (str "print(open('" inside "/in.txt').read())")))))))
      (is (refused? (block (first sessions) (str "print(open('" outside "/out.txt').read())")))))))
