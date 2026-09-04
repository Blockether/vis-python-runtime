(ns com.blockether.vis-python-runtime.log-test
  "What the runtime records, and how a host gets it (`native/vispython/vispython.c`,
   section \"Diagnostics\").

   The runtime RECORDS events and never writes a log: the host it is linked into
   already owns a file, a rotation and a format for lines like these. So these
   cases are about the PULL — what a drain answers, what happens when nobody
   drains, and what an event is never allowed to carry."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness]))

(use-fixtures
  :each
  (fn [run]
    (try (run)
         (finally
           ;; Recording is PROCESS state and a drained record is gone: leave both
           ;; the way the next case expects to find them.
           (when harness/built? (runtime/logs! nil) (runtime/logging! :off) (runtime/drain-log!))
           (harness/close-sessions!)))))

(defn- drained
  "Everything waiting, taken the way a host takes it: one buffer at a time until
   the answer comes back blank."
  []
  (loop [seen []]
    (let [text (runtime/drain-log!)]
      (if (str/blank? text) (str/join seen) (recur (conj seen text))))))

(harness/defbuilt-test logging-policy-test
                       (testing "a library records nothing until its host asks"
                         (is (= {:level :off :mirror? false} (runtime/logging! :off))))
                       (testing "the level answers in force, and a block is recorded under it"
                         (let [session (harness/block-session)]
                           (is (= {:level :info :mirror? false} (runtime/logging! :info)))
                           (harness/block session "print('recorded')")
                           (let [log (drained)]
                             (is (str/includes? log "\"event\":\"block\""))
                             (is (str/includes? log "\"level\":\"info\""))))))

(harness/defbuilt-test drain-takes-test
                       (testing
                         "a drained record is gone, so a host never files the same line twice"
                         (let [session (harness/block-session)]
                           (runtime/logging! :info)
                           (harness/block session "print('once')")
                           (is (not (str/blank? (drained))))
                           (is (str/blank? (runtime/drain-log!))))))

(harness/defbuilt-test log-carries-no-guest-text-test
                       (testing "what a block raised belongs to the block, not to the host's log"
                         (let [session (harness/block-session)]
                           (runtime/logging! :debug)
                           (harness/block session
                                          "raise ValueError('a sentence only the guest chose')")
                           (is (not (str/includes? (drained) "a sentence only the guest chose"))))))

(harness/defbuilt-test
  log-drop-test
  (testing "a ring nobody drains overwrites its oldest and reports the gap"
    (let [session (harness/block-session)]
      (runtime/logging! :debug)
      (dotimes [_ 1200]
        (runtime/run session "1"))
      (let [log (drained)
            lines (str/split-lines log)]

        (is (str/starts-with? log "{\"level\":\"warn\",\"event\":\"log_dropped\""))
        ;; 1024 records: a quarter of a megabyte that never grows, and enough
        ;; that a host draining on its own schedule loses nothing.
        (is (= 1024 (count (filter #(str/includes? % "\"event\":\"run\"") lines))))))))

(harness/defbuilt-test drain-beside-the-interpreter-test
                       (testing "a block holding the interpreter does not hold its own records back"
                         (let [session (harness/block-session)]
                           (runtime/logging! :info)
                           (let [running (future (harness/block session
                                                                "import time\ntime.sleep(1.5)"))]
                             (Thread/sleep 300)
                             (let [began (System/nanoTime)]
                               (runtime/drain-log!)
                               (is (< (/ (- (System/nanoTime) began) 1e6) 500)
                                   "the drain answered while the block still had the interpreter"))
                             @running))))

(harness/defbuilt-test
  drain-to-test
  (testing "a host keeps taking on its own, because the ring drops what nobody took"
    (let [session
          (harness/block-session)

          seen
          (atom [])]

      (runtime/logging! :info)
      (runtime/logs! #(swap! seen conj %) 25)
      (try (harness/block session "print('watched')")
           (is (loop [tries 60]
                 (cond (str/includes? (str/join @seen) "\"event\":\"block\"") true
                       (zero? tries) false
                       :else (do (Thread/sleep 50) (recur (dec tries)))))
               "the drainer handed the block's record over without being asked")
           (finally (runtime/logs! nil))))))
