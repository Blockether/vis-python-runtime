(ns com.blockether.vis-python-runtime.stdin-interrupt-test
  "The two process-wide controls a host holds over a running guest: what
   `sys.stdin` reads, and the one way out of a block that will not stop.

   Both exist because the embedded interpreter has no terminal of its own.
   Descriptor 0 belongs to the HOST, so a guest `input()` reads a terminal
   nobody is typing into and parks there holding the GIL — `stdin!` states what
   the guest's stream IS, and `\"\"` turns that stray `input()` into an
   `EOFError` instead of a hang. When a block spins instead of parking, a host
   future's cancel reaches only the JVM side: `interrupt!` is the only thing
   that reaches guest code, delivering `KeyboardInterrupt` at a bytecode
   boundary so a `while True:` unwinds, its `finally` runs and the session
   stays usable. `false` from it means nothing took the exception and the
   caller must retire the environment.

   Every wait here is bounded: a hanging test would be the very failure these
   cases are meant to catch."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block truthy]]
            [com.blockether.vis-python-runtime :as runtime]))

(use-fixtures :each
              (fn [run]
                (try (run)
                     (finally
                       ;; Leave the process on an EMPTY guest stdin, never on the real
                       ;; descriptor: a later case that reads it would park the suite.
                       (when harness/built? (runtime/stdin! ""))
                       (harness/close-sessions!)))))

(defn- ran
  "What a block PRINTED, trimmed — a block's one success channel."
  [session code]
  (str/trim (str (:stdout (block session code)))))

(harness/defbuilt-test
  stdin-reaches-the-guest-test
  (let [session (harness/block-session)]
    (testing "sys.stdin.read() answers the text the host stated"
      (is (true? (runtime/stdin! "piped-payload\n")))
      (is (= "stdin piped-payload"
             (ran session "import sys\nprint('stdin', sys.stdin.read().strip())"))))
    (testing "input() reads it a line at a time"
      (runtime/stdin! "alpha\nbeta\n")
      (is (= "alpha|beta" (ran session "print(input(), input(), sep='|')"))))
    (testing "sys.stdin.buffer reads the same text as UTF-8 bytes"
      ;; The stream is a real TextIOWrapper, so a guest reading bytes gets what
      ;; it gets under `python3` rather than an AttributeError.
      (runtime/stdin! "zażółć\n")
      (is (= "b'za\\xc5\\xbc\\xc3\\xb3\\xc5\\x82\\xc4\\x87'"
             (ran session "import sys\nprint(sys.stdin.buffer.read().strip())"))))))

(harness/defbuilt-test
  stdin-is-process-state-test
  (let [one
        (harness/block-session)

        two
        (harness/block-session)]

    (testing "one stream serves every session: the first reader drains it"
      (runtime/stdin! "only-once\n")
      (is (= "only-once" (ran one "import sys\nprint(sys.stdin.read().strip())")))
      (is (= "" (ran two "import sys\nprint(sys.stdin.read().strip())"))
          "the second session sees the same exhausted stream, not a fresh one"))
    (testing "a later statement re-points every session at the new text"
      (runtime/stdin! "again\n")
      (is (= "again" (ran two "import sys\nprint(sys.stdin.read().strip())"))))))

(harness/defbuilt-test
  stdin-empty-is-eof-test
  (let [session (harness/block-session)]
    (testing "an empty stream reads as EOF rather than blocking"
      (runtime/stdin! "")
      (is (= "read ['']" (ran session "import sys\nprint('read', [sys.stdin.read()])"))))
    (testing "input() on it raises EOFError, the sandbox's answer to a stray prompt"
      (let [answer (block session "input('name? ')")]
        (is (str/includes? (str (:error answer)) "EOFError")
            (str "expected an EOFError, got " (pr-str answer)))))))

(harness/defbuilt-test stdin-nil-restores-the-process-stream-test
                       (let [session (harness/block-session)]
                         (testing "nil hands the guest the process's own stdin back"
                           ;; Asserted by IDENTITY, never by reading it: reading the suite's real
                           ;; descriptor 0 is the hang this entry point exists to prevent.
                           (runtime/stdin! "something\n")
                           (is (false? (truthy session "import sys\nsys.stdin is sys.__stdin__")))
                           (is (true? (runtime/stdin! nil)))
                           (is (true? (truthy session "import sys\nsys.stdin is sys.__stdin__"))))))

(harness/defbuilt-test block-stdout-reaches-the-host-while-it-runs-test
                       (let [writes
                             (atom [])

                             _
                             (harness/bind-tools! {"__vis_capture_stdout__" (fn [args]
                                                                              (swap! writes conj
                                                                                (first args))
                                                                              nil)})

                             session
                             (harness/block-session)]

                         (runtime/install-sync-tool! session "__vis_capture_stdout__")
                         (testing "each write reaches the host without changing the block result"
                           (is (= "before cancel" (ran session "print('before cancel')")))
                           (is (= "before cancel\n" (apply str @writes))))))

(harness/defbuilt-test
  interrupt-unwinds-a-spinning-block-test
  (let [spinning
        (promise)

        session
        (harness/tool-session {"ready" (fn [_]
                                         (deliver spinning true)
                                         "go")})

        ;; The block runs on its own thread because `interrupt!` must be called
        ;; from anywhere BUT the thread it interrupts.
        answer
        (future (block session
                       (str "try:\n" "    await ready()\n"
                            "    while True:\n" "        pass\n"
                            "finally:\n" "    print('cleanup')")))]

    (testing "the block reaches its loop"
      (is (true? (deref spinning 30000 false)) "the tool never ran; nothing to interrupt"))
    (let [landed
          ;; Retried, because an interrupt aimed while the thread is still
          ;; inside the host call is not seen until that call returns.
          (loop [landed
                 false

                 attempts
                 0]

            (if (or (not= ::running (deref answer 100 ::running)) (>= attempts 200))
              landed
              (recur (or (runtime/interrupt!) landed) (inc attempts))))

          settled
          (deref answer 30000 ::hung)]

      (testing "a thread state takes the KeyboardInterrupt" (is (true? landed)))
      (testing "the spinning block unwinds instead of burning a core"
        (is (not= ::hung settled) "the block was still running 30s after the interrupt")
        (is (str/includes? (str (:error settled)) "KeyboardInterrupt")
            (str "expected a KeyboardInterrupt, got " (pr-str settled))))
      (testing "the block's finally runs on the way out"
        (is (str/includes? (str (:stdout settled)) "cleanup"))))
    (testing "the session survives its interrupted block"
      (is (= "2" (ran session "print(1 + 1)"))))))

;; Vis session 32bcc713: a long asyncio sleep outlived the host's block wall and
;; retired Python instead of yielding to the interrupt, ending the active turn.
;; A native `time.sleep` held the interpreter in C the same way.
(harness/defbuilt-test
  interrupt-unwinds-a-sleep-test
  (doseq [sleep-call ["await asyncio.sleep(3)" "await asyncio.wait_for(asyncio.sleep(3), 4)"
                      "time.sleep(3)"]]
    (let [sleeping (promise)
          session (harness/tool-session {"ready" (fn [_]
                                                   (deliver sleeping true)
                                                   "go")})
          answer (future (block session (str "import asyncio, time\nawait ready()\n" sleep-call)))]

      (is (true? (deref sleeping 30000 false)) "the block never reached sleep")
      (Thread/sleep 150)
      (let [landed (runtime/interrupt!)
            settled (deref answer 1200 ::hung)]

        (is (true? landed) sleep-call)
        (is (not= ::hung settled) "sleep ignored the host's interrupt window")
        (when (not= ::hung settled)
          (is (some? (:error settled)) (str sleep-call " => " (pr-str settled)))
          (when-not (str/includes? sleep-call "wait_for")
            (is (str/includes? (str (:error settled)) "KeyboardInterrupt")
                (str sleep-call " => " (pr-str settled))))
          (is (= "2" (ran session "print(1 + 1)"))))))))

;; Vis session 6d57806d: `await fut` on a future that nothing completes ignored
;; every interrupt, and each such block left a thread and an event loop behind.
;; Every sandbox primitive waited in ONE native acquire, which runs no bytecode.
(harness/defbuilt-test
  interrupt-unwinds-a-primitive-wait-test
  (doseq [[setup wait] [["fut = asyncio.get_running_loop().create_future()" "await fut"]
                        ["fut = asyncio.get_running_loop().create_future()" "eval('await fut')"]
                        ["ev = asyncio.Event()" "await ev.wait()"]
                        ["lk = asyncio.Lock()\nawait lk.acquire()" "await lk.acquire()"]
                        ["sem = asyncio.Semaphore(0)" "await sem.acquire()"]
                        ["q = asyncio.Queue()" "await q.get()"]
                        ["cond = asyncio.Condition()" "async with cond:\n    await cond.wait()"]
                        ["bar = asyncio.Barrier(2)" "await bar.wait()"]]]
    (let [waiting (promise)
          session (harness/tool-session {"ready" (fn [_]
                                                   (deliver waiting true)
                                                   "go")})
          answer (future (block session (str "import asyncio\n" setup "\nawait ready()\n" wait)))]

      (is (true? (deref waiting 30000 false)) "the block never reached its wait")
      (Thread/sleep 150)
      (let [landed (runtime/interrupt!)
            settled (deref answer 1200 ::hung)]

        (is (true? landed) wait)
        (is (not= ::hung settled) (str wait " ignored the host's interrupt window"))
        (when (not= ::hung settled)
          (is (str/includes? (str (:error settled)) "KeyboardInterrupt")
              (str wait " => " (pr-str settled)))
          (is (= "2" (ran session "print(1 + 1)"))))))))

;; Audit of Vis session 6d57806d: an interrupt that landed just after a condition
;; wait took its lock back left the lock taken with no owner. Every later
;; `async with cond:` then waited forever. The window is narrow, so the case runs
;; four times.
(harness/defbuilt-test
  interrupt-keeps-a-condition-usable-test
  (dotimes [attempt 4]
    (let [holding (promise)
          session (harness/tool-session {"ready" (fn [_]
                                                   (deliver holding true)
                                                   "go")})
          answer (future (block session
                                (str "import asyncio\ncond = asyncio.Condition()\n"
                                     "async def a():\n    async with cond:\n"
                                     "        await asyncio.wait_for(cond.wait(), 0.2)\n"
                                     "async def b():\n    await asyncio.sleep(0.05)\n"
                                     "    async with cond:\n        await ready()\n"
                                     "        await asyncio.sleep(1000)\n"
                                     "await asyncio.gather(a(), b())")))]

      (is (true? (deref holding 30000 false)) "the sibling never took the condition")
      (Thread/sleep 400)
      (let [settled (loop [n 0]
                      (runtime/interrupt!)
                      (let [r (deref answer 100 ::hung)]
                        (if (and (= ::hung r) (< n 50)) (recur (inc n)) r)))]
        (is (not= ::hung settled) (str "attempt " attempt " ignored the interrupts"))
        (is (= "False acquired"
               (ran session
                    (str "print(cond.locked(), end=' ')\n"
                         "await asyncio.wait_for(cond.acquire(), 1)\nprint('acquired')")))
            (str "attempt " attempt))))))

;; Re-audit of Vis session 6d57806d: exec text from a block body shows the
;; block's shadowed names in the session for the call. An interrupt must still
;; put the tool back.
(harness/defbuilt-test
  interrupt-restores-names-around-exec-test
  (let [waiting
        (promise)

        session
        (harness/tool-session {"ready" (fn [_]
                                         (deliver waiting true)
                                         "go")
                               "echo" (fn [[x]]
                                        x)})

        answer
        (future (block session
                       "echo = 'shadow'\nawait ready()\nexec('while True:\\n    n = echo')"))]

    (is (true? (deref waiting 30000 false)) "the block never reached exec")
    (Thread/sleep 150)
    (let [landed
          (runtime/interrupt!)

          settled
          (deref answer 1200 ::hung)]

      (is (true? landed))
      (is (not= ::hung settled) "exec ignored the host's interrupt window")
      (is (= "7" (ran session "print(await echo(7))"))))))

(harness/defbuilt-test
  sliced-waits-keep-their-contract-test
  (let [session (harness/tool-session {"echo" (fn [[x]]
                                                x)})]
    (testing "a bounded wait still times out after its whole timeout"
      (is (= "TimeoutError True"
             (ran session
                  (str "import asyncio, time\nev = asyncio.Event()\nt0 = time.monotonic()\n"
                       "try:\n    await asyncio.wait_for(ev.wait(), 0.35)\n"
                       "except TimeoutError as e:\n"
                       "    print(type(e).__name__, time.monotonic() - t0 >= 0.35)")))))
    (testing "a sibling's notify wakes a waiter, through gather"
      (is (= "[None, 'set']"
             (ran session
                  (str "import asyncio\nev = asyncio.Event()\n"
                       "async def setter():\n    await asyncio.sleep(0.25)\n    ev.set()\n"
                       "async def waiter():\n    await ev.wait()\n    return 'set'\n"
                       "print(await asyncio.gather(setter(), waiter()))")))))
    (testing "a queue hands every item over between gather children"
      (is (= "[None, [0, 1, 2]]"
             (ran session
                  (str "import asyncio\nq = asyncio.Queue(1)\n"
                       "async def prod():\n    for i in range(3):\n        await q.put(i)\n"
                       "async def cons():\n    return [await q.get() for _ in range(3)]\n"
                       "print(await asyncio.gather(prod(), cons()))")))))
    (testing "a contended lock with a deadline of 0 times out instead of raising ValueError"
      (is (= "TimeoutError"
             (ran session
                  (str "import asyncio\nlk = asyncio.Lock()\nawait lk.acquire()\n"
                       "try:\n    await asyncio.wait_for(lk.acquire(), 0)\n"
                       "except Exception as e:\n    print(type(e).__name__)")))))))

(harness/defbuilt-test
  sliced-sleep-keeps-the-time-sleep-contract-test
  (let [session (harness/block-session)]
    (testing "the whole delay still elapses"
      (is (= "True"
             (ran session
                  (str "import time\nstarted = time.monotonic()\ntime.sleep(0.35)\n"
                       "print(time.monotonic() - started >= 0.35)")))))
    (testing "invalid delays raise what the native sleep raises"
      (is (= "ValueError ValueError TypeError OverflowError"
             (ran session
                  (str "import time\nraised = []\n"
                       "for delay in (-1, float('nan'), '1', float('inf')):\n" "    try:\n"
                       "        time.sleep(delay)\n" "    except Exception as exc:\n"
                       "        raised.append(type(exc).__name__)\n" "print(*raised)")))))))

(harness/defbuilt-test interrupt-with-nothing-running-test
                       (testing "false when no thread is running guest code"
                         ;; The same answer a host gets when the interrupt could not reach the block
                         ;; — which is why `false` alone is never proof the environment is healthy.
                         (harness/block-session)
                         (is (false? (runtime/interrupt!)))))
