(ns com.blockether.vis-python-runtime.error-position-test
  "WHERE a failing block says it failed: `__vis_error_pos__` and the host-driven
   `__vis_err_pos_now__` walk in `resources/vis-python/async_runtime.py`.

   A block's error is the model's own Python exception — that is the whole
   contract, and everything here defends it. The position walk is a SECOND,
   optional answer: after a block raised, the host asks for the deepest
   block-source frame and renders a caret under it. The walk is deliberately
   NOT run inside the guest `except`, because touching `tb_frame`/`f_code` on a
   warm interpreter can raise a fault no guest `except` catches — and when it
   ran there, that fault REPLACED the real exception, so every failing block in
   a warm session surfaced as one opaque internal host fault instead of the
   model's error. Running it on the HOST side makes the fault catchable, which
   is what makes the degradation the point: a broken walk may cost the caret,
   never the message.

   So this file decides four things: a failing block reports its own exception;
   the walk names the deepest frame of the block that failed, the columns of the
   failing expression and the helper frames under it; the walk RELEASES the
   exception it walked (a traceback pins
   frames, and a stash that is never dropped keeps a whole failed block alive);
   and every way the position can go missing — a compile error with no block
   frame, an exception that never propagated, a walk that raises — costs the
   caret alone.

   Ported from vis' `env-python-test/block-error-fidelity-test`. What stayed
   there is the host's half: the `:phase` the boundary tags the failure with,
   and the caret rendering itself."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]])
  (:import [com.blockether.vispython VisPythonException]))

(use-fixtures :each
              (fn [run]
                (try (run)
                     (finally
                       ;; A session is a module the interpreter holds until it is dropped,
                       ;; and a stashed exception pins the frames of the block it came from.
                       (harness/close-sessions!)))))

(defn- err
  "The error text a block answered, or nil when it did not raise."
  [session code]
  (:error (block session code)))

(defn- position
  "What the HOST asks for once a block has failed: the deepest block-source
   position as `[line col end-col]`, or nil when the walk found no such frame.

   This is a plain call into the session's globals because that is exactly how
   the host reaches it — the boundary answers `{:stdout … :error …}` and nothing
   more, so the position is a separate, failure-tolerant read."
  [session]
  (harness/ev session "__vis_err_pos_now__()"))

(defn- caret
  "The source text a position points at: its line, sliced by its column span.

   Columns are 0-based `co_positions` offsets, so slicing the source with them
   is what a caret renderer does — and asserting on the SLICE says what the
   position means instead of freezing three integers."
  [source [line col end-col]]
  (subs (nth (str/split-lines source) (dec (long line))) col end-col))

(harness/defbuilt-test
  block-error-is-the-model-s-own-test
  (let [session (harness/block-session)]
    (testing "an uncaught error comes back as the block's own Python exception"
      (is (= "ValueError: probe-real" (err session "raise ValueError('probe-real')"))))
    (testing "a warm session reports each failure as itself, not as one opaque fault"
      ;; The regression this file exists for: once the position walk had run
      ;; inside the guest `except` on a JIT-warmed interpreter, EVERY failing
      ;; block came back as the same internal host fault. Distinct errors in a
      ;; row from one session is what that looked like when it worked.
      (is (= ["ZeroDivisionError: division by zero" "KeyError: 'nope'"
              "TypeError: unsupported operand type(s) for +: 'int' and 'str'"]
             [(err session "1 / 0") (err session "{}['nope']") (err session "1 + 'x'")])))
    (testing "what the block printed before it failed comes back with the error"
      (is (= {:stdout "before\n" :error "RuntimeError: after"}
             (block session "print('before')\nraise RuntimeError('after')"))))))

(harness/defbuilt-test
  error-position-walk-test
  (let [session (harness/block-session)]
    (testing "the position names the failing line and the columns of the failing expression"
      (let [source "x = 1\nprint('running')\ny = x + None\n"]
        (is (= "TypeError: unsupported operand type(s) for +: 'int' and 'NoneType'"
               (err session source)))
        (is (= [3 4 12 []] (position session)))
        (is (= "x + None" (caret source (position session))))))
    (testing "library frames are skipped: the position is the deepest BLOCK frame"
      ;; The failure happens several frames inside `json`, and none of those is
      ;; a place the model can edit.
      (let [source "print('a')\njson.loads('{')"]
        (is (str/starts-with? (str (err session source)) "JSONDecodeError:"))
        (is (= "json.loads('{')" (caret source (position session))))))
    (testing "the deepest block frame wins: inside the helper, not at the call site"
      (let [source "def boom(n):\n    return n / 0\n\nboom(3)"]
        (is (= "ZeroDivisionError: division by zero" (err session source)))
        (is (= "n / 0" (caret source (position session))))))
    (testing "a helper from an earlier block keeps the position in the failing block"
      ;; Every block source is registered under its own `<prog:N>` name, and the
      ;; host renders the position against the block that FAILED. A helper's line
      ;; numbers another source, so the helper comes back as its own frame, its
      ;; line counted from the `def` the way `defs(name)` shows it.
      (let [defining "def helper():\n    raise RuntimeError('deep')"
            calling "print('x')\nhelper()"]

        (is (nil? (err session defining)))
        (is (= "RuntimeError: deep" (err session calling)))
        (let [[line col end-col helpers :as pos] (position session)]
          (is (= [2 0 8] [line col end-col]))
          (is (= "helper()" (caret calling pos)))
          (is (= [{"name" "helper"
                   "line" 2
                   "col" 4
                   "end_col" 30
                   "text" "    raise RuntimeError('deep')"
                   "repeats" 1}]
                 helpers)))))))

(defn- helper-caret
  "The source text one helper frame points at: its line, sliced by its columns."
  [{:strs [text col end_col]}]
  (subs text col end_col))

(harness/defbuilt-test
  helper-frames-test
  (let [session (harness/block-session)]
    (testing "a helper chain three blocks deep keeps each helper's own frame"
      ;; `inner`, `middle` and `outer` come from three earlier blocks, each with its
      ;; own line numbers, and the failing block calls only `outer`.
      (is (nil? (err session "def inner():\n    raise ValueError('deep')")))
      (is (nil? (err session "def middle():\n    checked = True\n    return inner()")))
      (is (nil? (err session "def outer():\n    return middle()")))
      (let [calling "print('go')\nresult = outer()"]
        (is (= "ValueError: deep" (err session calling)))
        (let [[line _ _ helpers :as pos] (position session)]
          (is (= 2 line))
          (is (= "outer()" (caret calling pos)))
          (is (= [["outer" 2 "middle()"] ["middle" 3 "inner()"]
                  ["inner" 2 "raise ValueError('deep')"]]
                 (mapv (fn [h]
                         [(get h "name") (get h "line") (helper-caret h)])
                       helpers))))))
    (testing "a recursion collapses into one frame with its count"
      (is (nil? (err session
                     "def down(n):\n    if n:\n        return down(n - 1)\n    return 1 / 0")))
      (is (= "ZeroDivisionError: division by zero" (err session "down(50)")))
      (is (= [["down" 3 50 "down(n - 1)"] ["down" 4 1 "1 / 0"]]
             (mapv (fn [h]
                     [(get h "name") (get h "line") (get h "repeats") (helper-caret h)])
                   (nth (position session) 3)))))
    (testing "a long chain keeps its ends around the count of the frames between"
      (is
        (nil?
          (err
            session
            "def ping(n):\n    return pong(n - 1) if n else 1 / 0\n\ndef pong(n):\n    return ping(n - 1)")))
      (is (= "ZeroDivisionError: division by zero" (err session "ping(20)")))
      (let [helpers (nth (position session) 3)]
        (is (= 9 (count helpers)))
        (is (= ["ping" "pong"] (mapv #(get % "name") (take 2 helpers))))
        (is (= {"omitted" 13} (nth helpers 2)))
        (is (= "1 / 0" (helper-caret (peek helpers))))))
    (testing "a callback into the failing block positions in the callback"
      ;; The deepest frame of the failing block is the callback's, so no helper
      ;; frame is under it: `apply` is above that position.
      (is (nil? (err session "def apply(fn):\n    return fn()")))
      (let [calling "def callback():\n    return 1 / 0\n\napply(callback)"]
        (is (= "ZeroDivisionError: division by zero" (err session calling)))
        (is (= "1 / 0" (caret calling (position session))))
        (is (= [] (nth (position session) 3)))))))

(harness/defbuilt-test error-position-lifecycle-test
                       (let [session (harness/block-session)]
                         (testing "the walk releases the exception it walked"
                           ;; A traceback pins its frames, so the stash the guest side leaves behind
                           ;; would keep a whole failed block alive until the next failure replaced
                           ;; it. The host's read is what drops it.
                           (is (= "ValueError: pinned" (err session "raise ValueError('pinned')")))
                           (is (false? (harness/ev session "__vis_err_obj__ is None")))
                           (is (= [1 0 26 []] (position session)))
                           (is (true? (harness/ev session "__vis_err_obj__ is None"))))
                         (testing "asking a second time answers the same position, not nothing"
                           ;; The host may read it more than once (rendering, then logging); the
                           ;; computed position outlives the exception it came from.
                           (is (= [1 0 26 []] (position session))))
                         (testing "a block that did not fail has no position"
                           (is (nil? (err session "print('fine')")))
                           (is (nil? (position session))))))

(harness/defbuilt-test
  error-position-degradation-test
  (testing "a broken walk costs the caret, never the model's error"
    ;; vis' `block-error-fidelity-test` degradation case, which is the runtime's
    ;; own: the walk is replaced by one that raises, and the failing block must
    ;; still report the exception the model wrote. The fault surfaces at the
    ;; HOST call — where it is catchable — instead of replacing the error it was
    ;; describing.
    (let [session (harness/block-session)]
      (try (runtime/exec! session
                          (str "__vis_saved_pos_now__ = __vis_err_pos_now__\n"
                               "def __vis_broken_pos__():\n"
                               "    raise RuntimeError('simulated fault')\n"
                               "globals()['__vis_err_pos_now__'] = __vis_broken_pos__"))
           (is (= "ValueError: probe-degraded" (err session "raise ValueError('probe-degraded')")))
           (is (thrown-with-msg? VisPythonException
                                 #"RuntimeError: simulated fault"
                                 (position session)))
           (finally
             ;; The runtime mirrors every `__vis_` global onto `builtins` when a
             ;; block starts, so the sabotage outlives this session unless both
             ;; copies are put back — one interpreter serves the whole suite.
             (runtime/exec! session
                            (str "globals()['__vis_err_pos_now__'] = __vis_saved_pos_now__\n"
                                 "import builtins\n"
                                 "builtins.__vis_err_pos_now__ = __vis_saved_pos_now__"))))
      (testing "and the next failure positions normally again"
        (is (= "ValueError: after" (err session "raise ValueError('after')")))
        (is (= [1 0 25 []] (position session))))))
  (testing "a compile error has no block frame, so it costs the caret alone"
    ;; The SyntaxError is raised by the runtime's own compile step; no block
    ;; frame exists to walk, and the message is still the model's.
    (let [session (harness/block-session)]
      (is (str/includes? (str (err session "x = (1\n")) "'(' was never closed"))
      (is (nil? (position session)))))
  (testing "an exception that never propagated has no position"
    ;; The Python `__traceback__` is the ONLY place the failing position
    ;; survives — the async trampoline unwinds the guest stack before the host
    ;; ever sees the throw — so an exception that was never raised has none.
    (let [session (harness/block-session)]
      (is (nil? (harness/ev session "__vis_error_pos__(ValueError('never raised'))"))))))

(harness/defbuilt-test
  tool-failure-host-data-test
  ;; `__vis_err_host_data__` is the position walk's sibling read: whatever the
  ;; host attached to the tool failure the block died of. The host is bound here
  ;; directly rather than through `harness/bind-tools!` because that helper
  ;; answers a bare `error` string and this contract is about `error_data`.
  (runtime/bind-host! (fn [_session _name _payload]
                        (json/write-str {"error" "tool refused: no such path"
                                         "error_data" {"kind" "not-found" "path" "/nope"}})))
  (let [session (harness/block-session)]
    (runtime/install-tool! session "cat")
    (testing "a host tool's failure kills the block with the host's own message"
      (is (= "VisToolError: tool refused: no such path"
             (err session "print('go')\ntext = await cat('/nope')"))))
    (testing "the data the host attached to the failure is readable beside the position"
      (is (= {"kind" "not-found" "path" "/nope"} (harness/ev session "__vis_err_host_data__()"))))
    (testing "the position points at the failing tool call in the block"
      (is (= [2 7 25 []] (position session))))
    (testing "the host data must be read BEFORE the position, which releases the failure"
      ;; NOT order-free, whatever `__vis_err_host_data__`'s comment says: the
      ;; position read drops the stashed exception, and the data lives on that
      ;; exception. A host that renders the caret first loses the tool's data.
      (is (= "VisToolError: tool refused: no such path" (err session "await cat('/nope')")))
      (is (= [1 0 18 []] (position session)))
      (is (nil? (harness/ev session "__vis_err_host_data__()"))))
    (testing "a block that died of its own Python has no host data"
      (is (= "ValueError: mine" (err session "raise ValueError('mine')")))
      (is (nil? (harness/ev session "__vis_err_host_data__()"))))))
