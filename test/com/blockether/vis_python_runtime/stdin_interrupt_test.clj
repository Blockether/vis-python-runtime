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

(harness/defbuilt-test stdin-reaches-the-guest-test
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

(harness/defbuilt-test stdin-is-process-state-test
  (let [one (harness/block-session)
        two (harness/block-session)]
    (testing "one stream serves every session: the first reader drains it"
      (runtime/stdin! "only-once\n")
      (is (= "only-once" (ran one "import sys\nprint(sys.stdin.read().strip())")))
      (is (= "" (ran two "import sys\nprint(sys.stdin.read().strip())"))
          "the second session sees the same exhausted stream, not a fresh one"))
    (testing "a later statement re-points every session at the new text"
      (runtime/stdin! "again\n")
      (is (= "again" (ran two "import sys\nprint(sys.stdin.read().strip())"))))))

(harness/defbuilt-test stdin-empty-is-eof-test
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

(harness/defbuilt-test interrupt-unwinds-a-spinning-block-test
  (let [spinning (promise)
        session  (harness/tool-session {"ready" (fn [_] (deliver spinning true) "go")})
        ;; The block runs on its own thread because `interrupt!` must be called
        ;; from anywhere BUT the thread it interrupts.
        answer   (future (block session (str "try:\n"
                                             "    await ready()\n"
                                             "    while True:\n"
                                             "        pass\n"
                                             "finally:\n"
                                             "    print('cleanup')")))]
    (testing "the block reaches its loop"
      (is (true? (deref spinning 30000 false)) "the tool never ran; nothing to interrupt"))
    (let [landed
          ;; Retried, because an interrupt aimed while the thread is still
          ;; inside the host call is not seen until that call returns.
          (loop [landed false attempts 0]
            (if (or (not= ::running (deref answer 100 ::running)) (>= attempts 200))
              landed
              (recur (or (runtime/interrupt!) landed) (inc attempts))))

          settled
          (deref answer 30000 ::hung)]
      (testing "a thread state takes the KeyboardInterrupt"
        (is (true? landed)))
      (testing "the spinning block unwinds instead of burning a core"
        (is (not= ::hung settled) "the block was still running 30s after the interrupt")
        (is (str/includes? (str (:error settled)) "KeyboardInterrupt")
            (str "expected a KeyboardInterrupt, got " (pr-str settled))))
      (testing "the block's finally runs on the way out"
        (is (str/includes? (str (:stdout settled)) "cleanup"))))
    (testing "the session survives its interrupted block"
      (is (= "2" (ran session "print(1 + 1)"))))))

(harness/defbuilt-test interrupt-with-nothing-running-test
  (testing "false when no thread is running guest code"
    ;; The same answer a host gets when the interrupt could not reach the block
    ;; — which is why `false` alone is never proof the environment is healthy.
    (harness/block-session)
    (is (false? (runtime/interrupt!)))))
