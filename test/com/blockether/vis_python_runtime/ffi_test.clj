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
        (is (= :env (:source (ffi/initialize!)))
            "the test binds the library the build just produced")
        (is (str/starts-with? (ffi/version) "3.")
            "an embedded CPython 3.x is running inside this JVM"))

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
        (is (= "ok" (ffi/eval-str "'ok'")))))))
