(ns com.blockether.vis-python-runtime.test-diagnostics-test
  "Exercise the CI watchdog in disposable JVMs, including a real stalled test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.blockether.vis-python-runtime.harness :as harness])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(defn- run-probe
  [code]
  (let [log
        (File/createTempFile "vis-test-diagnostics-" ".log")

        java
        (str (io/file (System/getProperty "java.home") "bin" "java"))

        process
        (.start (doto (ProcessBuilder. ^java.util.List
                                       [java "--enable-native-access=ALL-UNNAMED" "-cp"
                                        (System/getProperty "java.class.path") "clojure.main" "-e"
                                        code])
                  (.redirectErrorStream true)
                  (.redirectOutput log)))]

    (try (is (.waitFor process 20 TimeUnit/SECONDS) "the diagnostic probe must terminate")
         (when (.isAlive process) (.destroyForcibly process) (.waitFor process 5 TimeUnit/SECONDS))
         {:exit (.exitValue process) :out (slurp log)}
         (finally (when (.isAlive process) (.destroyForcibly process)) (.delete log)))))

(defn- diagnostic-probe
  [form]
  (run-probe
    (binding [*print-meta* true]
      (pr-str
        (list 'do
              '(require
                '[com.blockether.vis-python-runtime.test-diagnostics :as diagnostics]
                '[clojure.test :as test])
              form)))))

(deftest progress-and-watchdog-cleanup-test
  (let
    [result
     (diagnostic-probe
       '(do
         (test/deftest passing-probe (test/is true))
         (println
          :value
          (diagnostics/with-watchdog
           5000
           (fn [] (diagnostics/stage! "fixture setup") (test/run-tests 'user) 42)))
         (println
          :watchdogs
          (count
           (filter
            #(= "vis-test-watchdog" (.getName ^Thread %))
            (keys (Thread/getAllStackTraces)))))))]
    (is (zero? (:exit result)) (:out result))
    (doseq [text ["fixture setup" "BEGIN user/passing-probe" "END user/passing-probe" ":value 42"
                  ":watchdogs 0"]]
      (is (str/includes? (:out result) text) (:out result)))))

(deftest thrown-run-stops-watchdog-test
  (let
    [result
     (diagnostic-probe
       '(do
         (try
          (diagnostics/with-watchdog 5000 #(throw (ex-info "probe" {})))
          (catch Exception error (println :caught (.getMessage error))))
         (println
          :watchdogs
          (count
           (filter
            #(= "vis-test-watchdog" (.getName ^Thread %))
            (keys (Thread/getAllStackTraces)))))))]
    (is (zero? (:exit result)) (:out result))
    (is (str/includes? (:out result) ":caught probe") (:out result))
    (is (str/includes? (:out result) ":watchdogs 0") (:out result))))

(deftest stalled-test-dumps-stacks-and-exits-test
  ;; Windows CI 34834014772 exhausted its job limit without identifying a test.
  (let [result (diagnostic-probe '(do
                                   (test/deftest stalled-probe (Thread/sleep 10000))
                                   (diagnostics/with-watchdog 500 #(test/run-tests 'user))))]
    (is (= 124 (:exit result)) (:out result))
    (doseq [text ["BEGIN user/stalled-probe" "TEST TIMEOUT" "JVM thread dump" "main" "sleep"]]
      (is (str/includes? (:out result) text) (:out result)))))

(deftest stalled-fixture-is-also-bounded-test
  (let [result (diagnostic-probe
                 '(diagnostics/with-watchdog
                   500
                   (fn [] (diagnostics/stage! "fixture initialization") (Thread/sleep 10000))))]
    (is (= 124 (:exit result)) (:out result))
    (is (str/includes? (:out result) "TEST TIMEOUT: fixture initialization") (:out result))))

(harness/defbuilt-test
  stalled-python-dumps-python-and-jvm-stacks-test
  (let
    [result
     (diagnostic-probe
       '(do
         (require '[com.blockether.vis-python-runtime :as runtime])
         (runtime/initialize!)
         (diagnostics/with-watchdog
          1000
          (fn
           []
           (runtime/exec!
            "python-watchdog-probe"
            (str
             "import faulthandler, sys\n"
             "faulthandler.dump_traceback_later(0.1, file=sys.__stderr__)"))
           (diagnostics/stage! "Sleeping in embedded Python")
           (runtime/eval-str "python-watchdog-probe" "__import__('time').sleep(10)")))))]
    (is (= 124 (:exit result)) (:out result))
    (doseq [text ["Timeout (" "in <module>" "TEST TIMEOUT: Sleeping in embedded Python"
                  "JVM thread dump" "Interpreter.eval"]]
      (is (str/includes? (:out result) text) (:out result)))))
