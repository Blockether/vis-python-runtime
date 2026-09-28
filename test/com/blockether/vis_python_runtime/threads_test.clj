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

(def ^:private failing-thunks
  "The thunks the failure cases combine. `rec(n)` records that it RAN, which is what
   tells a sibling that settled before the error from one that never started or was
   still running when the caller moved on."
  (str "import time, vis_runtime\n" "ran = []\n"
       "def rec(n, delay=0):\n" "    def run():\n"
       "        time.sleep(delay)\n" "        ran.append(n)\n"
       "        return n\n" "    return run\n"
       "def fail():\n" "    raise ValueError('thunk refused')\n"
       "def interrupt():\n" "    raise KeyboardInterrupt('stopped')\n"))

(defn- par-printed
  "What `code`, run after `failing-thunks` in a fresh session, printed."
  [code]
  (str/trim (str (:stdout (block (harness/block-session) (str failing-thunks code))))))

(harness/defbuilt-test
  par-failure-test
  ;; A sibling still writing after the caller has moved on is how parallel writes
  ;; end up interleaved with the code that handles their error.
  (testing "an ordinary failure is raised only after its running siblings finished"
    (is (= "thunk refused [1]"
           (par-printed (str "try:\n" "    vis_runtime.par([fail, rec(1, 0.3)])\n"
                             "except ValueError as e:\n" "    print(str(e), ran)")))))
  (testing "every thunk still runs, including those past the quota window"
    (runtime/threads! 0 0 1)
    (is (= "thunk refused [1, 2]"
           (par-printed (str "try:\n" "    vis_runtime.par([fail, rec(1), rec(2)])\n"
                             "except ValueError as e:\n" "    print(str(e), ran)"))))
    (runtime/threads! 0 0 8))
  (testing "when two fail it is the FIRST of them, by position, that is raised"
    (is (= "first"
           (par-printed (str "def boom(delay, message):\n"
                             "    def run():\n" "        time.sleep(delay)\n"
                             "        raise ValueError(message)\n" "    return run\n"
                             "try:\n"
                             "    vis_runtime.par([boom(0.2, 'first'), boom(0.0, 'second')])\n"
                             "except ValueError as e:\n" "    print(str(e))")))))
  (testing "an interrupt stops the batch and wins over an earlier failure"
    ;; A quota of one starts each thunk only after the one before it settled, so
    ;; `fail` had raised and `rec(1)` had not started when the interrupt came.
    (runtime/threads! 0 0 1)
    (is (= "KeyboardInterrupt('stopped') []"
           (par-printed (str "try:\n" "    vis_runtime.par([fail, interrupt, rec(1)])\n"
                             "except BaseException as e:\n" "    print(repr(e), ran)"))))
    (runtime/threads! 0 0 8))
  (testing "a par nested in a par child follows the same rule"
    (is (= "thunk refused [1, 2, 3]"
           (par-printed (str "def inner():\n" "    return vis_runtime.par([fail, rec(1), rec(2)])\n"
                             "try:\n" "    vis_runtime.par([inner, rec(3)])\n"
                             "except ValueError as e:\n" "    print(str(e), sorted(ran))"))))
    (runtime/threads! 0 0 1)
    (is (= "KeyboardInterrupt('stopped') []"
           (par-printed (str "def inner():\n" "    return vis_runtime.par([interrupt, rec(1)])\n"
                             "try:\n" "    vis_runtime.par([inner, rec(2)])\n"
                             "except BaseException as e:\n" "    print(repr(e), ran)"))))
    (runtime/threads! 0 0 8)))

;; Vis session b7ff5cee: the host's interrupt reached only the thread waiting in
;; `par`, which runs no Python until its batch settles. Every running thunk went
;; on to its end, and thunks the quota had held back started after the interrupt.
(def ^:private slow-thunks
  "Thunks and a coroutine that take three seconds, asleep or spinning, recording
   when they START as well as when they finish."
  (str "import asyncio\n" "started = []\n"
       "def slow(n):\n" "    def run():\n"
       "        started.append(n)\n" "        time.sleep(3)\n"
       "        ran.append(n)\n" "        return n\n"
       "    return run\n" "def spin(n):\n"
       "    def run():\n" "        started.append(n)\n"
       "        end = time.monotonic() + 3\n" "        while time.monotonic() < end:\n"
       "            pass\n" "        ran.append(n)\n"
       "        return n\n" "    return run\n"
       "async def nap(n):\n" "    started.append(n)\n"
       "    await asyncio.sleep(3)\n" "    ran.append(n)\n"))

(defn- interrupted
  "Run `code` in a fresh session with `tools` once its `ready()` has answered,
   interrupt it ONCE 300 ms later, and answer how the block ended and how long
   that took."
  [code tools]
  (let [ready
        (promise)

        session
        (harness/tool-session (assoc tools
                                "ready" (fn [_]
                                          (deliver ready true)
                                          "go")))

        answer
        (future (block session (str failing-thunks slow-thunks "await ready()\n" code)))]

    (is (true? (deref ready 30000 false)) "the block never reached its gather")
    (Thread/sleep 300)
    (let [landed
          (runtime/interrupt!)

          start
          (System/nanoTime)

          settled
          (deref answer 5000 ::hung)]

      {:landed landed
       :ms (quot (- (System/nanoTime) start) 1000000)
       :settled settled
       :session session})))

(harness/defbuilt-test
  par-interrupt-test
  (testing "the interrupt reaches every running thunk and starts no other"
    (runtime/threads! 0 0 2)
    (let [{:keys [landed ms settled session]}
          (interrupted (str "try:\n"
                            "    vis_runtime.par([slow(0), spin(1), slow(2), spin(3), slow(4)])\n"
                            "except Exception as e:\n" "    print('swallowed', repr(e))\n"
                            "finally:\n" "    print(sorted(started), ran)")
                       {})]
      (is (true? landed))
      (is (not= ::hung settled) "the block outlived its interrupt by five seconds")
      (is (< ms 1200) (str "the block took " ms " ms to unwind"))
      (is (str/includes? (str (:error settled)) "KeyboardInterrupt") (pr-str settled))
      (is (= "[0, 1] []" (str/trim (str (:stdout settled)))))
      (is (= "2" (str/trim (str (:stdout (block session "print(1 + 1)"))))) "the session survives"))
    (runtime/threads! 0 0 8))
  (testing "the block takes the interrupt once"
    (let [{:keys [settled]} (interrupted (str "try:\n"
                                              "    vis_runtime.par([slow(0), spin(1)])\n"
                                              "except KeyboardInterrupt:\n"
                                              "    print('interrupted')\n" "print('went on', ran)")
                                         {})]
      (is (nil? (:error settled)) (pr-str settled))
      (is (= "interrupted\nwent on []" (str/trim (str (:stdout settled)))))))
  (testing "a gather hands the interrupt on, where `except Exception` cannot catch it"
    (let [{:keys [ms settled]} (interrupted
                                 (str "try:\n" "    await asyncio.gather(nap(0), nap(1))\n"
                                      "except Exception as e:\n" "    print('swallowed', repr(e))\n"
                                      "finally:\n" "    print(sorted(started), ran)")
                                 {})]
      (is (< ms 1200) (str "the gather took " ms " ms to unwind"))
      (is (str/includes? (str (:error settled)) "KeyboardInterrupt") (pr-str settled))
      (is (= "[0, 1] []" (str/trim (str (:stdout settled)))))))
  (testing "a call that fails after the interrupt still ends the gather in it"
    ;; Vis fails the host calls still in flight once its interrupt has landed.
    (let [{:keys [ms settled]} (interrupted
                                 (str "try:\n" "    await asyncio.gather(hold(), nap(1))\n"
                                      "except Exception as e:\n" "    print('swallowed', repr(e))")
                                 {"hold" (fn [_]
                                           (Thread/sleep 600)
                                           (throw (ex-info "the call was cut off" {})))})]
      (is (< ms 1200) (str "the gather took " ms " ms to unwind"))
      (is (str/includes? (str (:error settled)) "KeyboardInterrupt") (pr-str settled))
      (is (= "" (str/trim (str (:stdout settled))))))))

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
