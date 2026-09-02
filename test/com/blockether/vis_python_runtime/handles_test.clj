(ns com.blockether.vis-python-runtime.handles-test
  "What the interpreter does with a handle the block lets go of.

   This replaces the descriptor registry `async_runtime.py` used to carry (and
   the 272-line suite that pinned it). That machinery was written for GraalPy,
   which does not refcount: a dropped `open()` there kept its process descriptor
   forever and its buffered bytes were never written, so the sandbox reclaimed
   and flushed by hand. CPython refcounts, and these cases are the measurement
   that says so — a regression here means the machinery has to come back, and
   nothing else in the suite would notice."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block block-session]])
  (:import [com.sun.management UnixOperatingSystemMXBean]
           [java.lang.management ManagementFactory OperatingSystemMXBean]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each (fn [run] (try (run) (finally (harness/close-sessions!)))))

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
  (let [dir (str (.toAbsolutePath (Files/createTempDirectory "vis-handles"
                                                            (make-array FileAttribute 0))))
        file (str dir "/probe.txt")]
    (spit file text)
    file))

(harness/defbuilt-test dropped-handles-cost-no-descriptors-test
  (let [session (block-session)
        file (temp-file "probe\n")]
    ;; warm the import machinery first, so the count is about the loop and not
    ;; about whatever `open` pulls in the first time it runs
    (block session (str "open(" (pr-str file) ").read()"))
    (let [before (open-descriptors)
          _ (block session (str "for _ in range(400):\n    open(" (pr-str file) ").read()"))
          after (open-descriptors)]
      (testing "400 handles the block drops cost the process nothing"
        (is (< (- after before) 20)
            (str "descriptors grew from " before " to " after
                 " - the interpreter stopped reclaiming dropped handles"))))))

(harness/defbuilt-test held-handles-return-when-the-block-lets-go-test
  (let [session (block-session)
        file (temp-file "probe\n")
        before (open-descriptors)]
    (block session (str "held = [open(" (pr-str file) ") for _ in range(150)]"))
    (testing "handles the block holds are real descriptors"
      (is (< 100 (- (open-descriptors) before))))
    (block session "held = None")
    (testing "and they come back the moment the last reference goes"
      (is (< (- (open-descriptors) before) 20)))))

(harness/defbuilt-test a-dropped-write-is-on-disk-test
  (let [session (block-session)
        file (temp-file "")]
    (block session (str "open(" (pr-str file) ", 'w').write('WROTE')"))
    (testing "the CPython idiom writes, with no flush of ours in between"
      (is (= "WROTE" (slurp file))))))

(harness/defbuilt-test a-held-write-is-flushed-when-the-block-ends-test
  ;; The one thing refcounting does NOT do, and the only reason `__vis_open__`
  ;; still exists: a handle the block keeps is a buffer nobody has emptied. The
  ;; block ends, the next thing to look at that file is a tool or the host, and
  ;; it must not read an empty file.
  (let [session (block-session)
        file (temp-file "")
        answer (block session (str "f = open(" (pr-str file) ", 'w')\nf.write('HELD')"))]
    (testing "what the block wrote through a handle it never closed is on disk"
      (is (nil? (:error answer)))
      (is (= "HELD" (slurp file))))))

(harness/defbuilt-test the-ceiling-refuses-before-the-process-wedges-test
  ;; The one half of the old descriptor machinery that was never about GraalPy:
  ;; the table is shared with the JVM, and a block that fills it stops `shell`
  ;; from forking at all. The ceiling turns that into a Python error the block
  ;; can read, so the test lowers it under what the process already holds and
  ;; asks for one more handle.
  (let [session (block-session)
        file (temp-file "probe\n")
        lower "import os\nos.environ['VIS_PY_MAX_OPEN_FILES'] = '8'\n"
        restore "import os\nos.environ.pop('VIS_PY_MAX_OPEN_FILES', None)"]
    (try
      (testing "the refusal names the cause, the fix and the escape hatch"
        (let [message (str (:error (block session (str lower "open(" (pr-str file) ")"))))]
          (is (str/includes? message "too many open files"))
          (is (str/includes? message "with open("))
          (is (str/includes? message "VIS_PY_MAX_OPEN_FILES"))))
      (testing "and it is an ordinary catchable OSError, not a killed block"
        (let [answer (block session (str lower
                                         "import errno\ncode = None\n"
                                         "try:\n"
                                         "    open(" (pr-str file) ")\n"
                                         "except OSError as e:\n"
                                         "    code = e.errno\n"
                                         "print(code == errno.EMFILE)"))]
          (is (nil? (:error answer)))
          (is (= "True" (str/trim (str (:stdout answer)))))))
      (finally (block session restore)))))
