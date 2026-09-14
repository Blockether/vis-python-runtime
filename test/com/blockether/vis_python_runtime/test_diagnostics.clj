(ns com.blockether.vis-python-runtime.test-diagnostics
  "Progress and bounded stack diagnostics around the existing Cognitect test runner."
  (:require [clojure.test :as test]
            [cognitect.test-runner :as runner])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:dynamic *stage* nil)

(defn stage!
  "Name a fixture operation when the diagnostic runner is active."
  [label]
  (when *stage* (*stage* label)))

(defn- dump-threads!
  [label]
  (binding [*out* *err*]
    (println "TEST TIMEOUT:" label)
    (println "JVM thread dump:")
    (doseq [[^Thread thread trace] (Thread/getAllStackTraces)]
      (println (.getName thread) (.getState thread))
      (doseq [frame trace]
        (println "  at" (str frame))))
    (flush)))

(defn with-watchdog
  "Report test/fixture progress; dump JVM stacks and exit 124 on stalled progress.

   Only disposable test JVMs use this runner. Halt avoids waiting on native calls
   or shutdown hooks that may be the very reason the test stopped making progress."
  [timeout-ms run]
  (let [timeout-ns
        (.toNanos TimeUnit/MILLISECONDS (long timeout-ms))

        progress
        (atom [(System/nanoTime) "Starting tests"])

        stopped
        (CountDownLatch. 1)

        report
        test/report

        stage
        (fn [label]
          (reset! progress [(System/nanoTime) label])
          (binding [*out* *err*]
            (println label)
            (flush)))

        watchdog
        (Thread. (fn []
                   (loop []

                     (let [[started label :as observed]
                           @progress

                           remaining
                           (- timeout-ns (- (System/nanoTime) (long started)))]

                       (when-not (.await stopped (max 1 remaining) TimeUnit/NANOSECONDS)
                         (if (and (not (pos? remaining)) (compare-and-set! progress observed nil))
                           (do (dump-threads! label) (.halt (Runtime/getRuntime) 124))
                           (recur))))))
                 "vis-test-watchdog")]

    (.setDaemon watchdog true)
    (.start watchdog)
    (try (binding [*stage* stage]
           (with-redefs [test/report (fn [{:keys [type var ns] :as event}]
                                       (case type
                                         :begin-test-ns
                                         (stage (str "NAMESPACE " (ns-name ns)))

                                         :begin-test-var
                                         (stage (str "BEGIN " (symbol var)))

                                         :end-test-var
                                         (stage (str "END " (symbol var)))

                                         nil)
                                       (report event))]
             (run)))
         (finally (.countDown stopped) (.join watchdog 5000)))))

(defn -main [& arguments] (with-watchdog 120000 #(apply runner/-main arguments)))
