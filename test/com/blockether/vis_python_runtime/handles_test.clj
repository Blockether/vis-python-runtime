(ns com.blockether.vis-python-runtime.handles-test
  "What the interpreter does with a handle the block lets go of.

   CPython closes dropped handles and flushes their buffers through reference
   counting. These cases keep that behavior explicit without a second descriptor
   registry in the guest runtime."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block block-session]])
  (:import [com.sun.management UnixOperatingSystemMXBean]
           [java.lang.management ManagementFactory OperatingSystemMXBean]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- open-descriptors
  "Descriptors THIS process holds, straight from the JVM: the only honest measure
   of a leak, because a leak by definition escapes whatever Python is counting."
  ^long []
  (let [^OperatingSystemMXBean bean (ManagementFactory/getOperatingSystemMXBean)]
    (if (instance? UnixOperatingSystemMXBean bean)
      (.getOpenFileDescriptorCount ^UnixOperatingSystemMXBean bean)
      -1)))

(defn- temp-file
  ^String [text]
  (let [dir
        (str (.toAbsolutePath (Files/createTempDirectory "vis-handles"
                                                         (make-array FileAttribute 0))))

        file
        (str dir "/probe.txt")]

    (spit file text)
    file))

(harness/defbuilt-test
  dropped-handles-cost-no-descriptors-test
  (let [session
        (block-session)

        file
        (temp-file "probe\n")]

    ;; warm the import machinery first, so the count is about the loop and not
    ;; about whatever `open` pulls in the first time it runs
    (block session (str "open(" (pr-str file) ").read()"))
    (let [before
          (open-descriptors)

          _
          (block session (str "for _ in range(400):\n    open(" (pr-str file) ").read()"))

          after
          (open-descriptors)]

      (testing "400 handles the block drops cost the process nothing"
        (is (< (- after before) 20)
            (str "descriptors grew from "
                 before
                 " to "
                 after
                 " - the interpreter stopped reclaiming dropped handles"))))))

(harness/defbuilt-test held-handles-return-when-the-block-lets-go-test
                       (let [session
                             (block-session)

                             file
                             (temp-file "probe\n")

                             before
                             (open-descriptors)]

                         (block session
                                (str "held = [open(" (pr-str file) ") for _ in range(150)]"))
                         (testing "handles the block holds are real descriptors"
                           (is (< 100 (- (open-descriptors) before))))
                         (block session "held = None")
                         (testing "and they come back the moment the last reference goes"
                           (is (< (- (open-descriptors) before) 20)))))

(harness/defbuilt-test a-dropped-write-is-on-disk-test
                       (let [session
                             (block-session)

                             file
                             (temp-file "")]

                         (block session (str "open(" (pr-str file) ", 'w').write('WROTE')"))
                         (testing "the CPython idiom writes, with no flush of ours in between"
                           (is (= "WROTE" (slurp file))))))

(harness/defbuilt-test a-held-write-is-flushed-when-the-block-ends-test
                       ;; The one thing refcounting does NOT do, and the only reason `__vis_open__`
                       ;; still exists: a handle the block keeps is a buffer nobody has emptied. The
                       ;; block ends, the next thing to look at that file is a tool or the host, and
                       ;; it must not read an empty file.
                       (let [session
                             (block-session)

                             file
                             (temp-file "")

                             answer
                             (block session
                                    (str "f = open(" (pr-str file) ", 'w')\nf.write('HELD')"))]

                         (testing "what the block wrote through a handle it never closed is on disk"
                           (is (nil? (:error answer)))
                           (is (= "HELD" (slurp file))))))

(harness/defbuilt-test
  the-ceiling-refuses-before-the-process-wedges-test
  ;; The shared JVM descriptor table refuses a new handle before a full process
  ;; prevents `shell` from spawning. Lower the ceiling and ask for one more.
  (let [session
        (block-session)

        file
        (temp-file "probe\n")

        lower
        "import os\nos.environ['VIS_PY_MAX_OPEN_FILES'] = '8'\n"

        restore
        "import os\nos.environ.pop('VIS_PY_MAX_OPEN_FILES', None)"]

    (try (testing "the refusal names the cause, the fix and the escape hatch"
           (let [message (str (:error (block session (str lower "open(" (pr-str file) ")"))))]
             (is (str/includes? message "too many open files"))
             (is (str/includes? message "with open("))
             (is (str/includes? message "VIS_PY_MAX_OPEN_FILES"))))
         (testing "and it is an ordinary catchable OSError, not a killed block"
           (let [answer (block session
                               (str lower
                                    "import errno\ncode = None\n" "try:\n"
                                    "    open(" (pr-str file)
                                    ")\n" "except OSError as e:\n"
                                    "    code = e.errno\n" "print(code == errno.EMFILE)"))]
             (is (nil? (:error answer)))
             (is (= "True" (str/trim (str (:stdout answer)))))))
         (finally (block session restore)))))

(harness/defbuilt-test
  subprocess-redirects-need-no-repair-test
  ;; CPython owns redirects and reports native process ids without guest repair.
  (let [session
        (block-session)

        into-file
        (temp-file "")

        from-file
        (temp-file "hello-stdin\n")

        answer
        (block session
               (str "import os, subprocess\n" "subprocess.run(['/bin/echo', 'redirected'],"
                    " stdout=open(" (pr-str into-file)
                    ", 'w'))\n" "read = subprocess.run(['/bin/cat'],"
                    " stdin=open(" (pr-str from-file)
                    "), capture_output=True)\n" "child = subprocess.Popen(['/bin/sleep', '0'])\n"
                    "pid = child.pid\n" "child.wait()\n"
                    "print(read.stdout.decode().strip())\n"
                    "print(pid > 0 and pid != os.getpid())"))]

    (testing "a file redirect reaches the file, and is complete when the call returns"
      (is (nil? (:error answer)))
      (is (= "redirected\n" (slurp into-file))))
    (testing "a stdin redirect reaches the child, and the pid is the OS's own"
      (is (= ["hello-stdin" "True"] (str/split-lines (str/trim (str (:stdout answer)))))))))

(harness/defbuilt-test
  open-survives-a-host-that-deletes-a-runtime-alias-test
  ;; The runtime is exec'd into the SESSION's globals, so every name in this file
  ;; is one a host statement can rebind or delete. vis seeds the guest environment
  ;; with `import os as __vis_os__ ... del __vis_os__`, which unbound the alias the
  ;; ceiling probe read, and every `open` in that session then died with a
  ;; NameError no block could explain. Nothing on the hot path may depend on a
  ;; name from outside this file. The session is built the way a host builds one -
  ;; install, then seed, then run - because that order is what exposed it.
  (let [session
        (str "alias-" (System/nanoTime))

        file
        (temp-file "probe\n")]

    (runtime/initialize!)
    (runtime/install-runtime! session)
    ;; The host's own `del` clears the session global; the builtins copy goes with
    ;; it because `__vis_pin_runtime__` mirrors every `__vis_*` name there once a
    ;; block has run, and in a live gateway the seeding happens BEFORE the first
    ;; block - so a session that only cleared the global would still find the name
    ;; and this case would pass for a reason the product does not have.
    (runtime/exec! session
                   (str "import builtins\n" "import os as __vis_os__\n"
                        "del __vis_os__\n" "_ = builtins.__dict__.pop('__vis_os__', None)"))
    (let [answer (block session (str "print(open(" (pr-str file) ").read().strip())"))]
      (testing "a block still opens files after the host took that name away"
        (is (nil? (:error answer)))
        (is (= "probe" (str/trim (str (:stdout answer)))))))))
