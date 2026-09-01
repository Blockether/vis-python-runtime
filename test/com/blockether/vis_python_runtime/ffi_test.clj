(ns com.blockether.vis-python-runtime.ffi-test
  "Proves the whole boundary end to end: a real CPython started inside this JVM
   through FFM, evaluating real Python, and reporting a real Python exception as
   data. Requires `native/vis-python/build.sh` to have run — `resources/prebuilds/`
   is build output, so a checkout without it has no library to bind and the
   suite says so instead of pretending to pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.ffi :as ffi]))

(def built?
  (try (boolean (runtime/resolve-library))
       (catch clojure.lang.ExceptionInfo _ false)))

(deftest embedded-interpreter-test
  (if-not built?
    (println "SKIP embedded-interpreter-test: no cdylib, run native/vis-python/build.sh")
    (do
      (testing "the interpreter starts and reports itself"
        (is (= :env (:source (:library (ffi/initialize!))))
            "the test binds the library the build just produced")
        (is (str/starts-with? (ffi/version) "3.")
            "an embedded CPython 3.x is running inside this JVM"))

      (testing "the interpreter is rooted in the VENDORED tree, not the machine's Python"
        ;; A shipped artifact carries its own standard library beside the
        ;; cdylib. If `sys.prefix` ever points outside it, the sandbox is
        ;; borrowing whatever interpreter the machine happens to have — which is
        ;; exactly the dependency this library exists to remove.
        (let [home (runtime/resolve-python-home)]
          (when home
            (is (= home (:python-home (ffi/initialize!))))
            (is (str/starts-with? (ffi/eval-str "__main__" "__import__('sys').prefix") home)
                "the standard library resolves inside the vendored tree"))))

      (testing "expressions evaluate and state survives between calls"
        (is (= "2" (ffi/eval-str "1 + 1")))
        (is (nil? (ffi/exec! "import ast\nparsed = ast.parse('x = 1')")))
        (is (= "1" (ffi/eval-str "len(parsed.body)"))
            "__main__ is one session, not a fresh interpreter per call"))

      (testing "a Python exception crosses as data, not as a crash"
        (let [thrown (try (ffi/eval-str "1 / 0") (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo thrown))
          (is (= "vis_python_eval" (:symbol (ex-data thrown))))
          (is (str/includes? (.getMessage ^Exception thrown) "division by zero"))))

      (testing "a later call still works, so the error left nothing pending"
        (is (= "ok" (ffi/eval-str "'ok'"))))

      (testing "sessions are separate namespaces over one interpreter"
        (ffi/exec! "session-a" "secret = 'a'")
        (ffi/exec! "session-b" "secret = 'b'")
        (is (= "a" (ffi/eval-str "session-a" "secret")))
        (is (= "b" (ffi/eval-str "session-b" "secret")))
        (is (= "False" (ffi/eval-str "'secret' in globals()"))
            "__main__ never saw either session's state")
        (ffi/exec! "session-a" "import json")
        (is (= "True" (ffi/eval-str "session-b" "'json' in __import__('sys').modules"))
            "imported modules ARE shared: a session is a namespace, not an interpreter")))))

(deftest run-answers-edn-data-test
  (if-not built?
    (println "SKIP run-answers-edn-data-test - no cdylib")
    (do
      (ffi/initialize!)
      (ffi/install-runtime! "edn-session")

      (testing "a trailing expression's value comes back as Clojure data"
        (is (= 3 (ffi/run "edn-session" "a = 1\nb = 2\na + b")))
        (is (= [1 2.5 "x" true nil] (ffi/run "edn-session" "[1, 2.5, 'x', True, None]")))
        (is (= {"q" "1"} (ffi/run "edn-session" "{'q': '1'}")))
        (is (= #{1 2} (ffi/run "edn-session" "{1, 2}")))
        (is (= 0.0 (ffi/run "edn-session" "0.0"))
            "an integral float stays a float - the value Python actually has"))

      (testing "a program with no trailing expression answers nil"
        (is (nil? (ffi/run "edn-session" "x = 41\nx += 1"))))

      (testing "a value with no EDN shape crosses as its str"
        (is (str/starts-with? (ffi/run "edn-session" "object()") "<object object at")))

      (testing "a raise names the exception even when it carries no message"
        (is (= "AssertionError"
               (-> (try (ffi/run "edn-session" "assert False")
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))
                   :message)))))))

(deftest shims-import-lazily-test
  (if-not built?
    (println "SKIP shims-import-lazily-test - no cdylib")
    (do
      (ffi/initialize!)
      (ffi/install-runtime! "lazy-session")
      (ffi/exec! "lazy-session" "import vis_runtime")
      (ffi/eval-str "lazy-session" "vis_runtime.forget_shims()")

      (testing "a bare import of a shim name resolves to the shim source"
        (is (false? (ffi/run "lazy-session" "'tabulate' in __import__('sys').modules")))
        (is (true? (ffi/run "lazy-session" "import tabulate\n'abc' in tabulate.tabulate([['abc']])"))))

      (testing "the stdlib always wins over a shim of the same name"
        (is (= "json" (ffi/run "lazy-session" "import json\njson.__name__"))))

      (testing "forgetting the shims hands the next import a pristine module"
        (ffi/run "lazy-session" "import tabulate\ntabulate.tabulate = 'patched'\nNone")
        (ffi/eval-str "lazy-session" "vis_runtime.forget_shims()")
        (is (= false (ffi/run "lazy-session" "import tabulate\ntabulate.tabulate == 'patched'"))))

      (testing "a shim that cannot load blames the shim AND the cause"
        (let [message (ffi/run "lazy-session"
                               (str "import sys\n"
                                    "saved = dict(sys.modules)\n"
                                    "sys.modules['json'] = None\n"
                                    "out = 'imported'\n"
                                    "try:\n"
                                    "    import urllib3\n"
                                    "except ImportError as e:\n"
                                    "    out = str(e)\n"
                                    "sys.modules.clear()\n"
                                    "sys.modules.update(saved)\n"
                                    "out"))]
          (is (str/includes? message "urllib3"))
          (is (str/includes? message "json")))))))
