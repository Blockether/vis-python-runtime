(ns com.blockether.vis-python-runtime.form-eval-test
  "Per-form block semantics and the sandbox's ambient surface
   (`resources/vis-python/async_runtime.py`).

   A block is not a script. The source splits into top-level statements, the
   value of each assignment or bare expression is SETTLED where it stands,
   evaluation stops at the first form that raises, and the block's ONE success
   channel is what it PRINTED — a trailing expression nobody printed is gone.
   Tools are DEFERRED, which is what lets `await` run one anywhere and a bare
   call run in place, exactly once.

   The ambient surface is part of the same contract: the modules a block may use
   without importing arrive lazily on `builtins`, every other stdlib import
   reaches the block verbatim, and guest THREADS work because importlib's own
   machinery needs them.

   Ported from Vis' `env_python_form_eval_test`. What stayed there is the HOST's
   half: prose-leading SyntaxError classification, the error enrichment that
   turns a NameError into a hint, and every binding built from a Clojure
   callable."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; A session is a module the interpreter holds until it is dropped,
           ;; and these tests open one per case.
           (harness/close-sessions!)))))

(defn- out
  "The block's ONE success channel: what it PRINTED, trimmed. A block that
   printed nothing has no output at all — its own value is never echoed."
  [answer]
  (str/trim (str (:stdout answer))))

(defn- ran
  "Run `code` as a block in `session`, expecting it not to raise, and answer what
   it printed."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)) code)
    (out answer)))

(harness/defbuilt-test sandbox-auto-import-test
  (testing "the hot stdlib modules are there without an import"
    ;; One session: these names arrive on `builtins`, so what is under test is
    ;; the sandbox's ambient surface, not anything a block did before.
    (let [s (harness/block-session)]
      (is (= "'a b'" (ran s "print(shlex.quote('a b'))")))
      (is (= "a#b#" (ran s "print(re.sub(r'\\d+', '#', 'a12b3'))")))
      (is (= "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
             (ran s "print(hashlib.sha256(b'hello world').hexdigest())")))
      (is (= "{\"a\": 1, \"b\": 2}" (ran s "print(json.dumps({'b': 2, 'a': 1}, sort_keys=True))")))
      (is (= "a/b" (ran s "print(os.path.join('a', 'b'))")))
      (is (= "True" (ran s "print(isinstance(sys.maxsize, int))")))
      (is (= "{'a': 2, 'b': 1}" (ran s "print(dict(collections.Counter('aab')))")))
      (is (= "{'a': 1, 'b': 2}" (ran s "print(dict(Counter('abb')))")))
      (is (= "True"
             (ran s "print(pathlib.Path('a/b').name == 'b' and Path('a/b').name == 'b')")))
      (is (= "alpha [...]" (ran s "print(textwrap.shorten('alpha beta gamma', width=11))")))
      (is (= "aGk=" (ran s "print(base64.b64encode(b'hi').decode())")))
      (is (= "4.555806" (ran s "print(round(math.sqrt(2) + math.pi, 6))")))
      (is (= "True" (ran s "print(hasattr(glob, 'glob') and callable(glob.glob))")))
      (is (= "True" (ran s "print(hasattr(builtins, 'len') and builtins.len([1, 2]) == 2)"))))))

(harness/defbuilt-test per-form-eval-test
  ;; E1-E7 of the form-eval contract. There is no second, value-shaped channel:
  ;; a trailing bare expression is still EVALUATED — a bare tool call runs — but
  ;; its value is dropped.
  (let [s (harness/block-session)]
    (testing "E1 — a comment is not a form; an assignment and a bare name echo nothing"
      (is (= "" (ran s "# read it\ne1x = 41\ne1x"))))
    (testing "E2 — print() is the only channel a value comes back on"
      (is (= "42" (ran s "print(40 + 2)"))))
    (testing "E3 — a trailing expression nobody printed is gone"
      (is (= "" (ran s "e3a = 1\ne3b = 2\n(e3a, e3b)"))))
    (testing "E6 — a call expression runs; only what it printed comes back"
      (is (= "99" (ran s "e6 = str(99)\nprint(e6)"))))
    (testing "a def is one form; a following call evaluates"
      (is (= "7" (ran s "def e_f():\n    return 7\nprint(e_f())"))))
    (testing "a session is live: a later block still sees what an earlier one bound"
      (is (= "41" (ran s "print(e1x)"))))
    (testing "E7 — evaluation stops at the first erroring form; later forms do not run"
      (let [answer (block s "e7x = 1\ne7_boom\ne7y = 2")]
        (is (some? (:error answer)))
        (is (str/includes? (str (:error answer)) "e7_boom"))
        (is (= "False" (ran s "print('e7y' in globals())")))))))

(harness/defbuilt-test stdlib-import-passthrough-test
  ;; Only `asyncio` is rewritten. Every other stdlib import — including the three
  ;; once dropped as native-crash risks — reaches the block verbatim.
  (let [s (harness/block-session)]
    (testing "import ssl / select / selectors survives, and the modules work"
      (is (= "True True True"
             (ran s (str "import ssl, select, selectors\n"
                         "print(ssl.CERT_NONE == 0, callable(select.select), "
                         "selectors.SelectSelector is not None)")))))
    (testing "from ssl import ... binds the name instead of vanishing"
      (is (= "True" (ran s "from ssl import CERT_NONE\nprint(CERT_NONE == 0)"))))
    (testing "the preprocessor hands such a block back byte-for-byte"
      (is (= "True"
             (ran s (str "src = 'import ssl\\nx = 1\\n'\n"
                         "print(__vis_strip_protected_imports__(src) == src)")))))))

(harness/defbuilt-test guest-threads-test
  ;; Guest Python may CREATE threads — importlib's import machinery, `threading`,
  ;; and libraries that allocate a `_thread` lock all need them.
  (let [s (harness/block-session)]
    (testing "threading.Thread runs to completion"
      (is (= "7"
             (ran s (str "import threading\nout = []\n"
                         "t = threading.Thread(target=lambda: out.append(7))\n"
                         "t.start()\nt.join()\nprint(out[0])")))))
    (testing "_thread.allocate_lock works (the import-machinery / lock path)"
      (is (= "ok"
             (ran s (str "import _thread\nlk = _thread.allocate_lock()\n"
                         "lk.acquire()\nlk.release()\nprint('ok')")))))))

(defn- echo-session
  "A session whose one tool answers `<x>` — the moved tests' `echo`."
  []
  (let [s (harness/block-session)]
    (harness/tool! s "echo" "x" "    return '<' + str(x) + '>'")
    s))

(defn- tick-session
  "A session whose one tool COUNTS its calls, so a test can tell one run from
   two."
  []
  (let [s (harness/block-session)]
    (harness/tool! s "tick" "" "    CALLS.append(1)\n    return 'n' + str(len(CALLS))")
    s))

(harness/defbuilt-test deferred-tool-test
  ;; Async by default: tools are DEFERRED, so `await` runs one ANYWHERE
  ;; (including nested), a bare top-level call auto-settles, and `print` settles
  ;; an unawaited one instead of showing a thunk.
  (testing "await runs a NESTED deferred tool call"
    (is (= "<hi>" (ran (echo-session) "print(await echo('hi'))"))))
  (testing "a bare top-level call auto-settles (it RUNS, and its value is dropped)"
    (let [s (tick-session)]
      (is (= "" (ran s "CALLS = []\ntick()")))
      (is (= "1" (ran s "print(len(CALLS))")))))
  (testing "print auto-settles an UNawaited nested call — the value, not a hint"
    (is (= "<oops>" (ran (echo-session) "print(echo('oops'))"))))
  (testing "an awaited assignment persists in the live session across blocks"
    (let [s (echo-session)]
      (is (= "<x>" (ran s "kept = await echo('x')\nprint(kept)")))
      (is (= "<x>" (ran s "print(kept)")))))
  (testing "auto-settles a bare deferred assignment in an await-bearing program"
    ;; `c = await echo("a")` forces the async path; the bare `res = echo("b")`
    ;; has NO await, yet must RUN so `res` is the value and not a thunk.
    (is (= "<b>" (ran (echo-session) "c = await echo('a')\nres = echo('b')\nprint(res)"))))
  (testing "auto-settles a bare deferred assignment EXACTLY once (no double run)"
    (let [s (tick-session)]
      (is (= "n2" (ran s "CALLS = []\nc = await tick()\nres = tick()\nprint(res)")))
      (is (= "2" (ran s "print(len(CALLS))")))))
  (testing "await on an already-settled binding answers the value"
    ;; THE trap: `x = echo(...)` auto-settles, so `x` already holds the result,
    ;; and the stray `await` used to raise a TypeError about the settled value.
    (is (= "<a>" (ran (echo-session) "x = echo('a')\nprint(await x)"))))
  (testing "await on an already-settled binding does NOT re-run the tool"
    (let [s (tick-session)]
      (is (= "n1" (ran s "CALLS = []\nx = tick()\nprint(await x)")))
      (is (= "1" (ran s "print(len(CALLS))")))))
  (testing "await on a plain non-tool value is a no-op that returns it"
    (is (= "42" (ran (echo-session) "v = 41\nprint((await v) + 1)")))))

(defn- boom-session
  "A session whose `boom` tool RAISES and whose `echo` answers, so a case can
   show the block carrying on after catching one."
  []
  (let [session (harness/block-session)]
    (harness/tool! session "boom" "" "    raise RuntimeError('boom message')")
    (harness/tool! session "echo" "x" "    return '<' + str(x) + '>'")
    session))

;; Regression, issue #42: a tool that raised was NOT catchable in the block. The
;; failure arrived at the driver rather than at the coroutine's own await point,
;; so `try: await boom() except Exception:` never saw it and the turn ended on an
;; error the model had already written the handler for.
(harness/defbuilt-test tool-failure-catchable-test
  (testing "`except Exception` catches a tool failure and sees the clean message"
    (is (= "caught: boom message"
           (ran (boom-session)
                "try:\n    await boom()\nexcept Exception as e:\n    print('caught: ' + str(e))"))))
  (testing "`except BaseException` catches it too"
    (is (= "base: boom message"
           (ran (boom-session)
                "try:\n    await boom()\nexcept BaseException as e:\n    print('base: ' + str(e))"))))
  (testing "catching lets the block CONTINUE and run more tools"
    (is (= "<ok>"
           (ran (boom-session)
                (str "try:\n    await boom()\nexcept Exception:\n    pass\n"
                     "print(await echo('ok'))")))))
  (testing "an UNCAUGHT tool failure is the block's error, carrying the message"
    (let [answer (block (boom-session) "await boom()")]
      (is (= "" (out answer)))
      (is (str/includes? (str (:error answer)) "boom message")))))
