(ns com.blockether.vis-python-runtime.block-output-test
  "What a block wrote comes back as its output, by every route Python offers:
   stdout, stderr (errors, warnings and logging), a stream that an earlier
   block kept, and a thread that writes after its block ended."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(harness/defbuilt-test
  stderr-comes-back-with-the-block-test
  (let [session (harness/block-session)]
    (testing "stderr, logging, warnings and tracebacks come back in write order"
      (let [{:keys [stdout error]}
            (block session
                   (str "import sys, logging, warnings, traceback\n"
                        "print('one')\n" "print('two', file=sys.stderr)\n"
                        "logging.warning('three')\n" "warnings.warn('four')\n"
                        "try:\n    1 / 0\nexcept ZeroDivisionError:\n    traceback.print_exc()\n"
                        "print('six')"))]
        (is (nil? error))
        (is (= ["one" "two" "three" "four" "ZeroDivisionError" "six"]
               (filterv #(str/includes? stdout %)
                 ["one" "two" "three" "four" "ZeroDivisionError" "six"])))
        (is (< (str/index-of stdout "two")
               (str/index-of stdout "three")
               (str/index-of stdout "four")
               (str/index-of stdout "ZeroDivisionError")
               (str/index-of stdout "six")))))
    (testing "stderr written before a failure comes back with the error"
      (is (= {:stdout "warned\n" :error "RuntimeError: after"}
             (block
               session
               "import sys\nprint('warned', file=sys.stderr)\nraise RuntimeError('after')"))))))

(harness/defbuilt-test
  terminal-session-keeps-process-stderr-test
  (let [session (harness/block-session)]
    (is (nil? (:error (block session "__vis_keep_stderr__ = True"))))
    (is (= {:stdout "fd 2\n" :error nil}
           (block session "import sys\nsys.stderr.write('to terminal\\n')\nprint('fd', sys.stderr.fileno())")))))

(harness/defbuilt-test
  kept-stream-reaches-the-running-block-test
  (let [session (harness/block-session)]
    (is (nil? (:error (block session "import sys\nkept_out = sys.stdout\nkept_err = sys.stderr"))))
    (is (= {:stdout "out later\nerr later\n" :error nil}
           (block session "kept_out.write('out later\\n')\nprint('err later', file=kept_err)")))))

(harness/defbuilt-test
  write-after-the-block-test
  (let [session (harness/block-session)]
    (is (nil?
          (:error
            (block
              session
              (str "import threading, time\n"
                   "late = threading.Thread(target=lambda: (time.sleep(0.2), print('late line')))\n"
                   "late.start()")))))
    (Thread/sleep 600)
    (let [{:keys [stdout]} (block session "late.join()\nprint('now')")]
      (testing "the next block shows it first, with a note"
        (is (str/includes? stdout "late line"))
        (is (str/includes? stdout "after its block ended"))
        (is (< (str/index-of stdout "late line") (str/index-of stdout "now")))))
    (testing "it shows once"
      (is (= {:stdout "again\n" :error nil} (block session "print('again')"))))))

(harness/defbuilt-test
  late-write-stays-in-its-session-test
  (let [writer
        (harness/block-session)

        other
        (harness/block-session)]

    (is (nil?
          (:error
            (block
              writer
              (str
                "import threading, time\n"
                "threading.Thread(target=lambda: (time.sleep(0.2), print('secret'))).start()")))))
    (Thread/sleep 600)
    (is (= {:stdout "mine\n" :error nil} (block other "print('mine')")))
    (is (= {:stdout "later\n" :error nil} (block writer "print('later')")))))
