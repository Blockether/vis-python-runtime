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

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; Recording is PROCESS state and a drained record is gone: leave both
           ;; the way the next case expects to find them.
           (when harness/built?
             (runtime/logging! :off)
             (runtime/drain-log!))
           (harness/close-sessions!)))))

(defn- drained
  "Everything waiting, taken the way a host takes it: one buffer at a time until
   the answer comes back blank."
  []
  (loop [seen []]
    (let [text (runtime/drain-log!)]
      (if (str/blank? text)
        (str/join seen)
        (recur (conj seen text))))))

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
  (testing "a drained record is gone, so a host never files the same line twice"
    (let [session (harness/block-session)]
      (runtime/logging! :info)
      (harness/block session "print('once')")
      (is (not (str/blank? (drained))))
      (is (str/blank? (runtime/drain-log!))))))

(harness/defbuilt-test log-carries-no-guest-text-test
  (testing "what a block raised belongs to the block, not to the host's log"
    (let [session (harness/block-session)]
      (runtime/logging! :debug)
      (harness/block session "raise ValueError('a sentence only the guest chose')")
      (is (not (str/includes? (drained) "a sentence only the guest chose"))))))

(harness/defbuilt-test log-drop-test
  (testing "a ring nobody drains overwrites its oldest and reports the gap"
    (let [session (harness/block-session)]
      (runtime/logging! :debug)
      (dotimes [_ 300] (runtime/run session "1"))
      (is (str/starts-with? (drained) "{\"level\":\"warn\",\"event\":\"log_dropped\"")))))
