(ns com.blockether.vis-python-runtime.threads-test
  "The thread boundary in C (`native/vispython/vispython.c`).

   Sessions share one interpreter, so they share one GIL and one pool: a pool per
   session would multiply threads by sessions and buy nothing. What the guest can
   do to that arrangement is the point of these cases — the pool is not a module
   global a block can resize, and the cap is checked from the audit hook, so a
   thread a block starts for itself spends from the same budget."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block ev]]))

(use-fixtures :each
              (fn [run]
                (try (run)
                     (finally
                       ;; Thread policy is PROCESS state: a case that narrows it would
                       ;; narrow every case after it.
                       (when harness/built?
                         (runtime/threads! 100 0 8)
                         ;; Recording is process state too, and a drained record is gone.
                         (runtime/logging! :off)
                         (runtime/drain-log!))
                       (harness/close-sessions!)))))

(def ^:private workers
  "What the pool sizes itself at: blocking work, four wide gathers, not cores."
  32)

(harness/defbuilt-test thread-policy-test
                       (testing "the defaults are the host's, not the machine's"
                         (is (= {:cap 100 :workers workers :quota 8} (runtime/threads! 0 0 0))))
                       (testing "the guest reads the same policy the host set"
                         (let [session
                               (harness/block-session)

                               seen
                               (ev session "import _vis_host\n_vis_host.threads()")]

                           (is (= 100 (get seen "cap")))
                           (is (= 8 (get seen "quota")))
                           (is (= workers (get seen "workers")))
                           (is (<= 1 (get seen "live"))))))

(harness/defbuilt-test par-overlaps-test
                       (testing "thunks that release the GIL run at the same time"
                         (let [session
                               (harness/block-session)

                               elapsed
                               (ev session
                                   (str "import time, vis_runtime\n" "start = time.monotonic()\n"
                                        "vis_runtime.par([lambda: time.sleep(0.25)] * 4)\n"
                                        "time.monotonic() - start"))]

                           (is (< elapsed 0.6) "four quarter-second sleeps overlapped"))))

(harness/defbuilt-test par-order-test
                       (testing "values answer in the order of their thunks, not of their finishing"
                         (let [session (harness/block-session)]
                           (is (= [1 2 3]
                                  (ev session
                                      (str "import time, vis_runtime\n"
                                           "def slow(n):\n" "    def run():\n"
                                           "        time.sleep(0.15 / n)\n" "        return n\n"
                                           "    return run\n"
                                           "vis_runtime.par([slow(1), slow(2), slow(3)])")))))))

(harness/defbuilt-test
  par-failure-test
  (testing "a failure surfaces at once, while a slow sibling is still running"
    (let [session
          (harness/block-session)

          answer
          (block session
                 (str "import time, vis_runtime\n" "def slow():\n"
                      "    time.sleep(0.5)\n" "def fail():\n"
                      "    raise ValueError('thunk refused')\n" "start = time.monotonic()\n"
                      "try:\n" "    vis_runtime.par([fail, slow])\n"
                      "except ValueError as e:\n"
                      "    print(str(e), time.monotonic() - start < 0.4)"))]

      ;; `gather` cancels the siblings of a failed child, so it has to hear about
      ;; the failure while they are still running.
      (is (= "thunk refused True" (str/trim (str (:stdout answer)))))))
  (testing "when two fail it is the FIRST of them, by position, that is raised"
    (let [session
          (harness/block-session)

          answer
          (block session
                 (str "import time, vis_runtime\n" "def boom(delay, message):\n"
                      "    def run():\n" "        time.sleep(delay)\n"
                      "        raise ValueError(message)\n" "    return run\n"
                      "try:\n" "    vis_runtime.par([boom(0.2, 'first'), boom(0.0, 'second')])\n"
                      "except ValueError as e:\n" "    print(str(e))"))]

      (is (= "first" (str/trim (str (:stdout answer))))))))

(harness/defbuilt-test nested-par-test
                       (testing "a par inside a par child runs inline instead of deadlocking"
                         (let [session (harness/block-session)]
                           (is (= [[1 2] [1 2]]
                                  (ev session
                                      (str "import vis_runtime\n" "def inner():\n"
                                           "    return vis_runtime.par([lambda: 1, lambda: 2])\n"
                                           "vis_runtime.par([inner, inner])")))))))

(harness/defbuilt-test
  par-quota-test
  (testing "one gather may hold only its quota of the pool"
    (let [session
          (harness/block-session)

          timing
          (str "import time, vis_runtime\n" "start = time.monotonic()\n"
               "vis_runtime.par([lambda: time.sleep(0.1)] * 3)\n" "time.monotonic() - start")]

      (runtime/threads! 0 0 1)
      (is (<= 0.3 (ev session timing)) "a quota of one serialized the three sleeps")
      (runtime/threads! 0 0 8)
      (is (< (ev session timing) 0.3) "the quota back at eight overlapped them"))))

(harness/defbuilt-test thread-cap-test
                       (testing "a thread the guest starts for itself is refused by the cap"
                         (let [session (harness/block-session)]
                           (is (= {:cap 1 :workers 1 :quota 8} (runtime/threads! 1 0 0)))
                           (let [answer (block session
                                               (str
                                                 "import threading\n"
                                                 "threading.Thread(target=lambda: None).start()"))]
                             (is (str/includes? (str (:error answer)) "threads at once")))))
                       (testing "the cap is not a filesystem policy: it holds unconfined"
                         (is (= {:read 0 :write 0} (runtime/confine! [] [])))))

;; The EXTENSIONS' process is not the sandbox's: the code it runs is the host's
;; own, so it runs unconfined and uncapped, and one call has to say that without
;; the pool collapsing with the budget.
(harness/defbuilt-test
  uncapped-process-test
  (testing "a cap of -1 lifts the budget and leaves the pool its full size"
    (is (= {:cap -1 :workers workers :quota 8} (runtime/threads! -1 0 0))))
  (testing "a thread the guest starts for itself is not refused"
    (let [session
          (harness/block-session)

          answer
          (block session
                 (str "import threading\n" "done = threading.Event()\n"
                      "threading.Thread(target=done.set).start()\n" "print(done.wait(5))"))]

      (is (nil? (:error answer)))
      (is (str/includes? (str (:stdout answer)) "True"))))
  (testing "the guest reads the lifted cap"
    (let [session (harness/block-session)]
      (is (= -1 (get (ev session "import _vis_host\n_vis_host.threads()") "cap")))))
  (testing "an uncapped process is an unconfined one"
    (is (= {:read 0 :write 0} (runtime/confine! [] [])))))

;; The pool is bounded and shared, so "every worker is busy" is a state a session
;; must survive without waiting on another session's work.
(harness/defbuilt-test
  saturated-pool-test
  (testing "a gather that finds the pool full runs its own thunks and finishes"
    (let [session (harness/block-session)]
      ;; A quota wide enough for one gather to hold every worker at once.
      (runtime/threads! 0 0 40)
      (runtime/logging! :info)
      (runtime/drain-log!)
      (let [answer (block session
                          (str "import _vis_host, threading, time, vis_runtime\n"
                               "gate = threading.Event()\n"
                               "def hold():\n" "    gate.wait(10)\n"
                               "def spare():\n" "    return 'ran'\n"
                               "answer = {}\n" "def other():\n"
                               "    answer['v'] = vis_runtime.par([spare, spare])\n"
                               "holder = threading.Thread(\n"
                               "    target=lambda: vis_runtime.par([hold] * 40), daemon=True)\n"
                               "holder.start()\n"
                               "time.sleep(0.5)\n" "queued = _vis_host.threads()['queued']\n"
                               "caller = threading.Thread(target=other)\n" "caller.start()\n"
                               "caller.join(3)\n" "gate.set()\n"
                               "holder.join(5)\n" "print(answer.get('v'), queued > 0)"))]
        ;; Without caller-runs the second gather waits for a worker that only the
        ;; first gather can free, and the join times out with nothing to show.
        (is (= "['ran', 'ran'] True" (str/trim (str (:stdout answer)))))
        ;; The caller taking its work back is the moment worth watching from
        ;; outside, so it is the one the host's log has to carry.
        (is (str/includes? (runtime/drain-log!) "\"event\":\"caller_runs\""))))))
