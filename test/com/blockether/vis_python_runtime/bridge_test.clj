(ns com.blockether.vis-python-runtime.bridge-test
  "Proves the whole boundary end to end: a real CPython started inside this JVM
   through FFM, evaluating real Python, and reporting a real Python exception as
   data. Requires `native/vispython/build.sh` to have run — `resources/prebuilds/`
   is build output, so a checkout without it has no library to bind and the
   suite says so instead of pretending to pass."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython VisPythonException]))

(def built? (try (boolean (runtime/resolve-library)) (catch VisPythonException _ false)))

(deftest embedded-interpreter-test
  (if-not built?
    (println "SKIP embedded-interpreter-test: no cdylib, run native/vispython/build.sh")
    (do (testing "the interpreter starts and reports itself"
          (is (= (:path (runtime/resolve-library)) (:library (runtime/initialize!)))
              "the test binds the library the build just produced")
          (is (contains? #{:env :resource} (:source (runtime/resolve-library)))
              "a checkout binds the built cdylib, by override or off its own classpath")
          (is (str/starts-with? (runtime/python-version) "3.")
              "an embedded CPython 3.x is running inside this JVM"))
        (testing "the interpreter is rooted in the VENDORED tree, not the machine's Python"
          ;; A shipped artifact carries its own standard library beside the
          ;; cdylib. If `sys.prefix` ever points outside it, the sandbox is
          ;; borrowing whatever interpreter the machine happens to have — which is
          ;; exactly the dependency this library exists to remove.
          (let [home (runtime/resolve-python-home)]
            (when home
              (is (= home (:python-home (runtime/initialize!))))
              (is (str/starts-with? (runtime/eval-str "__main__" "__import__('sys').prefix") home)
                  "the standard library resolves inside the vendored tree"))))
        (testing "expressions evaluate and state survives between calls"
          (is (= "2" (runtime/eval-str "1 + 1")))
          (is (nil? (runtime/exec! "import ast\nparsed = ast.parse('x = 1')")))
          (is (= "1" (runtime/eval-str "len(parsed.body)"))
              "__main__ is one session, not a fresh interpreter per call"))
        (testing "a Python exception crosses as data, not as a crash"
          (let [thrown (try (runtime/eval-str "1 / 0") (catch VisPythonException e e))]
            (is (instance? VisPythonException thrown))
            (is (= "vispython_eval" (.get thrown "symbol")))
            (is (str/includes? (.getMessage ^Exception thrown) "division by zero"))))
        (testing "a later call still works, so the error left nothing pending"
          (is (= "ok" (runtime/eval-str "'ok'"))))
        (testing "sessions are separate namespaces over one interpreter"
          (runtime/exec! "session-a" "secret = 'a'")
          (runtime/exec! "session-b" "secret = 'b'")
          (is (= "a" (runtime/eval-str "session-a" "secret")))
          (is (= "b" (runtime/eval-str "session-b" "secret")))
          (is (= "False" (runtime/eval-str "'secret' in globals()"))
              "__main__ never saw either session's state")
          (runtime/exec! "session-a" "import json")
          (is (= "True" (runtime/eval-str "session-b" "'json' in __import__('sys').modules"))
              "imported modules ARE shared: a session is a namespace, not an interpreter")))))

(deftest run-answers-json-test
  (if-not built?
    (println "SKIP run-answers-json-test - no cdylib")
    (do (runtime/initialize!)
        (runtime/install-runtime! "json-session")
        (testing "a trailing expression's value comes back as JSON text"
          (is (= "3" (runtime/run "json-session" "a = 1\nb = 2\na + b")))
          (is (= "[1, 2.5, \"x\", true, null]"
                 (runtime/run "json-session" "[1, 2.5, 'x', True, None]")))
          (is (= "{\"q\": \"1\"}" (runtime/run "json-session" "{'q': '1'}")))
          (is (= "[1, 2]" (runtime/run "json-session" "{1, 2}"))
              "a set has no JSON shape of its own - it crosses as an array")
          (is (= "0.0" (runtime/run "json-session" "0.0"))
              "an integral float stays a float - the value Python actually has"))
        (testing "a program with no trailing expression answers null"
          (is (= "null" (runtime/run "json-session" "x = 41\nx += 1"))))
        (testing "a value with no JSON shape crosses as its str"
          (is (str/starts-with? (runtime/run "json-session" "object()") "\"<object object at")))
        (testing "a raise names the exception even when it carries no message"
          (is (= "AssertionError"
                 (try (runtime/run "json-session" "assert False")
                      (catch VisPythonException e (.get e "message")))))))))

(deftest answer-larger-than-the-message-buffer-test
  ;; The bridge reserves 8 KiB for an answer. An answer past it used to arrive
  ;; TRUNCATED - and, because the host reads JSON, that meant a block printing
  ;; more than 8 KiB lost everything it printed and reported nothing at all.
  ;; The whole text is kept in the runtime and fetched, never remade: asking the
  ;; call again for a bigger buffer would run the block a SECOND time.
  (if-not built?
    (println "SKIP answer-larger-than-the-message-buffer-test - no cdylib")
    (do (runtime/initialize!)
        (runtime/install-runtime! "big-session")
        (testing "a value on either side of the buffer crosses whole"
          (doseq [n [8000 8190 8191 8192 12000 1000000]]
            (is (= (+ (long n) 2) (count (runtime/run "big-session" (str "'x' * " n))))
                (str n " characters answer as a JSON string of the same length"))))
        (testing "a block's printed output is not capped either"
          (let [printed (runtime/run-block "big-session" "print('y' * 300000)")]
            (is (str/includes? printed "\"stdout\""))
            (is (< 300000 (count printed)))))
        (testing "the kept answer is handed over once, never served to the next call"
          (is (= "\"ok\"" (runtime/run "big-session" "'ok'")))
          (is (= "3" (runtime/run "big-session" "1 + 2")))))))

(defn- sync-block
  [session source]
  (json/read-str
    (runtime/run session
                 (str "__import__('vis_runtime').run_sync_block(" (pr-str source) ", globals())"))))

(deftest synchronous-block-test
  ;; JVM/SDK dogfooding: modules using asyncio.run must own their event loop.
  (if-not built?
    (println "SKIP synchronous-block-test - no cdylib")
    (do (runtime/initialize!)
        (let [session "sync-block-session"]
          (runtime/install-runtime! session)
          (try (runtime/exec! session "import threading\nexpected_thread = threading.get_ident()")
               (testing "module code owns asyncio on the same interpreter thread"
                 (let [source (str "import asyncio, threading\n"
                                   "async def compute():\n" "    await asyncio.sleep(0)\n"
                                   "    return threading.get_ident() == expected_thread\n"
                                   "print('module-result', asyncio.run(compute()))\n")
                       answer (sync-block session (str "exec(" (pr-str source) ", globals())"))]

                   (is (nil? (get answer "error")) (pr-str answer))
                   (is (= "module-result True\n" (get answer "stdout")))))
               (testing "printed output survives a failure and errors stay data"
                 (is (= {"stdout" "before-error\n" "error" "ValueError: sync failure"}
                        (sync-block session
                                    "print('before-error')\nraise ValueError('sync failure')"))))
               (testing "ordinary top-level await still works afterward"
                 (let [answer (json/read-str
                                (runtime/run-block
                                  session
                                  "import asyncio\nawait asyncio.sleep(0)\nprint('async-ok')"))]
                   (is (nil? (get answer "error")) (pr-str answer))
                   (is (= "async-ok\n" (get answer "stdout")))))
               (finally (runtime/close-session! session)))))))

(deftest synchronous-block-boundary-test
  (if-not built?
    (println "SKIP synchronous-block-boundary-test - no cdylib")
    (do
      (runtime/initialize!)
      (let [session
            "sync-block-boundary-session"

            calls
            (atom [])

            file
            (java.io.File/createTempFile "vis-sync-block-" ".txt")]

        (runtime/install-runtime! session)
        (runtime/bind-host! (fn [caller name _payload]
                              (swap! calls conj [caller name])
                              (json/write-str {"value" "host-ok"})))
        (try (runtime/install-sync-tool! session "sync_probe")
             (testing "callbacks retain the calling session"
               (is (= {"stdout" "host-ok\n" "error" nil}
                      (sync-block session "print(sync_probe())")))
               (is (= [[session "sync_probe"]] @calls)))
             (testing "the optional stdout sink sees the same writes"
               (runtime/exec! session
                              "capture_events = []\n__vis_capture_stdout__ = capture_events.append")
               (is (= {"stdout" "captured\n" "error" nil} (sync-block session "print('captured')")))
               (is (= "captured\n" (json/read-str (runtime/run session "''.join(capture_events)"))))
               (runtime/exec! session "del __vis_capture_stdout__"))
             (testing "an unclosed writer flushes even after a failure"
               (let [answer (sync-block session
                                        (str "held_writer = open("
                                             (pr-str (.getPath file))
                                             ", 'w')\n"
                                             "held_writer.write('flushed')\n"
                                             "raise ValueError('after-write')"))]
                 (is (= "ValueError: after-write" (get answer "error")))
                 (is (= "flushed" (slurp file)))))
             (testing "syntax errors and SystemExit use the same captured error shape"
               (is (str/starts-with? (get (sync-block session "if") "error") "SyntaxError:"))
               (is (= {"stdout" "before-exit\n" "error" "SystemExit: 7"}
                      (sync-block session "print('before-exit')\nraise SystemExit(7)"))))
             (testing "only printed output crosses, including output larger than the ABI buffer"
               (is (= {"stdout" "" "error" nil} (sync-block session "1234")))
               (let [answer (sync-block session "print('x' * 12000)")]
                 (is (nil? (get answer "error")))
                 (is (= (str (apply str (repeat 12000 "x")) "\n") (get answer "stdout")))))
             (finally (runtime/exec! session "globals().get('held_writer') and held_writer.close()")
                      (runtime/close-session! session)
                      (runtime/bind-host! nil)
                      (.delete file)))))))
