;; Covers the session `eval` and `exec` shims in
;; `resources/vis-python/async_runtime.py`.
(ns com.blockether.vis-python-runtime.eval-exec-test
  "Top-level `await` in text that a block passes to `eval` or `exec`.

   A block runs as a coroutine, so `await cat(p)` is ordinary block code. The
   same line in `eval` or `exec` text was a SyntaxError, because Python compiles
   that text alone. The session shims compile refused text again with
   `PyCF_ALLOW_TOP_LEVEL_AWAIT` and drive the coroutine to its value.

   Text that compiles without that flag must keep exact builtin behavior. That
   includes its namespaces, its errors and the order of its checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- tools-session
  "A block session with `echo`, which answers its argument, and `boom`, which fails."
  []
  (harness/tool-session {"echo" (fn [[x]]
                                  x)
                         "boom" (fn [_]
                                  (throw (ex-info "boom refused" {})))}))

(defn- out
  "What `code` printed in `session`, trimmed, or the error text when it raised."
  [session code]
  (let [{:keys [stdout error]} (block session code)]
    (if error (str "ERROR " error) (str/trim (str stdout)))))

(harness/defbuilt-test
  eval-await-test
  (let [session (tools-session)]
    (testing "eval of awaiting text answers the awaited value"
      ;; Regression (vis session 8a61ff31): `eval("await memo.wake(...)")` in a
      ;; block raised `SyntaxError: 'await' outside function`.
      (is (= "a" (out session "print(eval(\"await echo('a')\"))"))))
    (testing "awaiting that value again is harmless, like any settled block value"
      (is (= "b" (out session "print(await eval(\"await echo('b')\"))"))))
    (testing "gather inside the text settles every call"
      (is (= "['x', 'y']" (out session "print(eval(\"await gather(echo('x'), echo('y'))\"))"))))
    (testing "a nested eval inside awaiting text settles too"
      (is (= "1" (out session "print(eval(\"await echo(eval('await echo(1)'))\"))"))))
    (testing "a comprehension in the block body sees its own variable"
      (is (= "[0, 1, 2]" (out session "print([eval('await echo(i)') for i in range(3)])"))))
    (testing "a helper's eval sees the helper's arguments"
      (is (= "d"
             (out session
                  "def via_helper(v):\n    return eval('await echo(v)')\nprint(via_helper('d'))"))))
    (testing "a tool failure in the text is the block's catchable tool error"
      (is (= "VisToolError boom refused"
             (out session
                  (str "try:\n    eval('await boom()')\n"
                       "except Exception as e:\n    print(type(e).__name__, e)")))))
    (testing "a StopIteration in awaiting text is an error, not its value"
      ;; As in CPython: a coroutine that raises StopIteration raises RuntimeError.
      (is (= "RuntimeError coroutine raised StopIteration"
             (out session
                  (str "try:\n    print(eval('(await echo(1)) and next(iter([]))'))\n"
                       "except Exception as e:\n    print(type(e).__name__, e)")))))
    (testing "an exception the awaiting text raises keeps its own type"
      (is (= "ZeroDivisionError"
             (out session
                  (str "try:\n    eval('await echo(1 / 0)')\n"
                       "except Exception as e:\n    print(type(e).__name__)")))))))

(harness/defbuilt-test
  exec-await-test
  (let [session (tools-session)]
    (testing "exec in the block body binds module names that later blocks read"
      (is (= "" (out session "exec(\"r1 = await echo('c')\\nr2 = r1 + '!'\")")))
      (is (= "c!" (out session "print(r2)"))))
    (testing "exec of awaiting text answers None, like builtin exec"
      (is (= "None" (out session "print(exec(\"await echo('n')\"))"))))
    (testing "exec with an explicit globals dict writes only into that dict"
      (is (= "e False"
             (out session
                  (str "ns = {'echo': echo}\n"
                       "exec(\"v_only = await echo('e')\", ns)\n"
                       "print(ns['v_only'], 'v_only' in globals())")))))
    (testing "the globals keyword works, as in builtin exec"
      (is (= "k"
             (out
               session
               "kw = {'echo': echo}\nexec(\"w = await echo('k')\", globals=kw)\nprint(kw['w'])"))))
    (testing "a helper's exec reads the helper's locals"
      ;; The old shim ran the text against its own frame, so this was a NameError.
      (is (= "5" (out session "def show():\n    secret = 5\n    exec('print(secret)')\nshow()"))))
    (testing "async for, async with and async comprehensions run like await"
      ;; Each takes another CPython refusal message: a new CPython that words
      ;; one differently turns that form back into a SyntaxError here.
      (is (= "['p'] w ['q']"
             (out session
                  (str "import contextlib\n"
                       "async def agen():\n    yield await echo('p')\n"
                       "@contextlib.asynccontextmanager\n"
                       "async def held():\n    yield await echo('w')\n"
                       "exec('seen = []\\nasync for v in agen():\\n    seen.append(v)')\n"
                       "exec('async with held() as w2:\\n    pass')\n"
                       "print(seen, w2, eval(\"[await echo(v) for v in ['q']]\"))")))))
    (testing "a repeated exec ends in the same state"
      (is (= "" (out session "exec(\"again = await echo(7)\")\nexec(\"again = await echo(7)\")")))
      (is (= "7" (out session "print(again)"))))))

(harness/defbuilt-test
  plain-text-semantics-test
  (let [session
        (tools-session)

        same-error
        (fn [call reference]
          ;; The shim's error text must equal what the builtin compile says.
          (out session
               (str
                 "def err_of(f):\n    try:\n        f()\n"
                 "    except BaseException as e:\n        return type(e).__name__ + ': ' + str(e)\n"
                 "print(err_of(lambda: "
                 call
                 ") == err_of(lambda: "
                 reference
                 "))")))]

    (testing "text without await gives the builtin result"
      (is
        (= "3 42 42"
           (out
             session
             "print(eval('  1 + 2'), eval(b'\\t40 + 2'), eval(compile('6 * 7', '<x>', 'eval')))"))))
    (testing "text that neither compile accepts raises the original SyntaxError"
      (is (= "True" (same-error "eval('1 +')" "compile('1 +', '<string>', 'eval')"))))
    (testing "await inside a lambda stays a SyntaxError"
      (is (= "True"
             (same-error "eval(\"lambda: await echo('x')\")"
                         "compile(\"lambda: await echo('x')\", '<string>', 'eval')"))))
    (testing "return in exec text stays a SyntaxError"
      (is (= "True" (same-error "exec('return 1')" "compile('return 1', '<string>', 'exec')"))))
    (testing "a str subclass is read as its text, not through its own methods"
      (is (= "1"
             (out
               session
               "S = type('S', (str,), {'lstrip': lambda s, *a: '999'})\nprint(eval(S('  1')))"))))
    (testing "text that is refused for another reason warns once, as in the builtin"
      (is (= "1"
             (out session
                  (str "import warnings\n" "with warnings.catch_warnings(record=True) as seen_w:\n"
                       "    warnings.simplefilter('always')\n"
                       "    try:\n        eval(\"'\\\\d' +\")\n"
                       "    except SyntaxError:\n        pass\n"
                       "print(sum(issubclass(w.category, SyntaxWarning) for w in seen_w))")))))
    (testing "a class attribute keeps eval a plain call, as the builtin is no descriptor"
      (is (= "3 None"
             (out session
                  "class K:\n    f = eval\n    g = exec\nprint(K().f('3'), K().g('pass'))"))))
    (testing "a call shape the builtin rejects raises the builtin's own TypeError"
      (is (= "True"
             (same-error "eval('1', {}, globals={})" "__vis_real_eval__('1', {}, globals={})")))
      (is (= "True" (same-error "eval()" "__vis_real_eval__()")))
      (is (= "True" (same-error "exec(\"await echo('x')\", 1)" "__vis_real_exec__('pass', 1)")))
      (is (= "True"
             (same-error "exec('pass', {}, {}, {})" "__vis_real_exec__('pass', {}, {}, {})"))))
    (testing "a value that holds eval or exec still pickles and copies"
      ;; The session saves its values with pickle: a dict that holds eval must
      ;; not turn unsaved because eval is a shim.
      (is (= "True True True True"
             (out session
                  (str "import pickle, copy, weakref, builtins\n"
                       "box = {'e': eval, 'x': exec}\n" "back = pickle.loads(pickle.dumps(box))\n"
                       "print(back['e'] is builtins.eval, back['x'] is builtins.exec,"
                       " copy.deepcopy(box)['e'] is eval, weakref.ref(eval)() is eval)")))))
    (testing "help(eval) documents eval, not the shim's class"
      (is (= "True True True"
             (out session
                  (str "import inspect, pydoc, types, builtins\n"
                       "print(pydoc.render_doc(eval, renderer=pydoc.plaintext)"
                       " == pydoc.render_doc(builtins.eval, renderer=pydoc.plaintext),"
                       " inspect.isroutine(exec), isinstance(eval, types.BuiltinFunctionType))")))))
    (testing "the signature is the builtin's"
      (is (= "True True"
             (out session
                  (str "import inspect\n"
                       "print(inspect.signature(eval) == inspect.signature(__vis_real_eval__),"
                       " inspect.signature(exec) == inspect.signature(__vis_real_exec__))")))))
    (testing "a bad globals argument fails before the text is read, as in the builtin"
      (is (= "TypeError"
             (out
               session
               "try:\n    eval('1 +', [])\nexcept Exception as e:\n    print(type(e).__name__)"))))
    (testing "a coroutine that the text only creates is returned, not run"
      (is (= "coroutine"
             ;; In a helper: a block assignment settles awaitables by design.
             (out session
                  (str "async def co():\n    return 1\n" "def made():\n    c = eval('co()')\n"
                       "    kind = type(c).__name__\n    c.close()\n    return kind\n"
                       "print(made())")))))))

(harness/defbuilt-test
  block-scope-test
  ;; Vis session 6d57806d: text that a block body passes to eval or exec lost
  ;; its names, and `exec('echo = 1')` replaced the `echo` tool for good.
  (let [session (tools-session)]
    (testing "a walrus in eval text binds a session name, like at module level"
      (is (= "5" (out session "eval('(w_ := 5)')\nprint(w_)")))
      (is (= "5" (out session "print(w_)"))))
    (testing "eval of exec code binds a session name"
      (is (= "3" (out session "eval(compile('e_ = 3', '', 'exec'))\nprint(e_)"))))
    (testing "text inside a comprehension reads its names and binds session names"
      (is (= "[0, 2, 4]" (out session "print([eval('i_ * 2') for i_ in range(3)])")))
      (is (= "1 False"
             (out session "[exec('t_ = i_') for i_ in range(2)]\nprint(t_, 'i_' in globals())"))))
    (testing "a comprehension's name does not replace a session name of the same name"
      (is (= "[0, 1] keep"
             (out session "c_ = 'keep'\nprint([eval('c_') for c_ in range(2)], c_)"))))
    (testing "text that rebinds a tool changes it only for that call"
      (is (= "1" (out session "exec('echo = 1\\nprint(echo)')")))
      (is (= "ok" (out session "print(await echo('ok'))"))))
    (testing "a function in the text sees the tool name that the text rebinds"
      ;; Audit R1: the function looked the name up in the session and got the tool.
      (is (= "5" (out session "exec('echo = 5\\ndef f_(): return echo\\nprint(f_())')")))
      (is (= "2" (out session "echo = 1\nexec('def g_(): return echo + 1\\nprint(g_())')"))))
    (testing "a helper that the text calls still sees the tool that the block shadows"
      ;; Re-audit N1: the block's value was put in the session for the whole call.
      (is (= "" (out session "def kind_():\n    return type(echo).__name__")))
      (is (= "function" (out session "echo = 'mine'\nprint(eval('kind_()'))"))))
    (testing "text that reaches names by string sees the block's names"
      ;; Re-audit F1: only the names in the text's code were placed, so a
      ;; nested eval or a locals() lookup missed the comprehension variable.
      (is (= "[0, 10, 20]" (out session "print([eval('eval(\"i_ * 10\")') for i_ in range(3)])")))
      (is (= "[0, 1, 2]" (out session "print([eval('locals()[\"i_\"]') for i_ in range(3)])")))
      (is (= "2" (out session "echo = 1\nprint(eval('eval(\"echo + 1\")'))"))))
    (testing "a write by string to a tool name changes it only for that call"
      ;; Re-audit F2: a nested exec or globals() write replaced the tool for good.
      (is (= "" (out session "exec('exec(\"echo = 3\")')\nexec('globals()[\"echo\"] = 3')")))
      (is (= "alive" (out session "print(await echo('alive'))")))
      (is (= "block 3" (out session "echo = 1\nexec('exec(\"echo = 3\")')\nprint('block', echo)"))))
    (testing "locals() in the text is the session's globals, as at module level"
      ;; Audit R2: it was a mapping that pickle, json and repr could not handle.
      (is (= "True" (out session "print(eval('locals()') is globals())"))))
    (testing "text sees and rebinds a tool name that the block shadows"
      (is (= "2 5"
             (out session "echo = 1\nexec('x9 = echo + 1')\nexec('echo = 5')\nprint(x9, echo)")))
      (is (= "still" (out session "print(await echo('still'))"))))
    (testing "exec text deletes a session name"
      (is (= "False" (out session "d_ = 1\nexec('del d_')\nprint('d_' in globals())"))))
    (testing "locals() in the text reads session names and tools"
      (is (= "7 True True"
             (out session
                  (str "z_ = 7\nprint(eval('locals()[\"z_\"]'), eval('\"z_\" in locals()'), "
                       "eval('\"echo\" in locals()'))")))))
    (testing "a helper's text still gets a copy of the helper's locals"
      (is (= "(1, 1)"
             (out
               session
               "def f():\n    a = 1\n    exec('a = 2')\n    return a, eval('a')\nprint(f())"))))))

(harness/defbuilt-test
  text-asyncio-import-test
  ;; Vis session 6d57806d: `import asyncio` in exec text bound the real module
  ;; over the session's shim, and every later `asyncio.gather` of tool calls
  ;; failed with "Task got bad yield".
  (let [session
        (tools-session)

        gathered
        "print(await asyncio.gather(echo(1), echo(2)))"]

    (testing "an import in exec text binds the shim and keeps the session's asyncio"
      (is (= "" (out session "exec('import asyncio')")))
      (is (= "[1, 2]" (out session gathered))))
    (testing "the shim from the text settles tool calls in the same text"
      (is
        (=
          "[1, 2]"
          (out
            session
            "exec('import asyncio as aio\\nr_ = await aio.gather(echo(1), echo(2))')\nprint(r_)"))))
    (testing "an import in a helper's exec text binds the shim"
      (is (= "True"
             (out session
                  (str "def g():\n    loc = {}\n    exec('import asyncio', globals(), loc)\n"
                       "    return loc['asyncio'] is __vis_asyncio__\nprint(g())")))))
    (testing "a star import and bytes text bind the shim's names"
      (is (= "True"
             (out session "exec('from asyncio import *')\nprint(sleep is __vis_asyncio__.sleep)")))
      (is (= "True" (out session "exec(b'import asyncio\\nprint(asyncio is __vis_asyncio__)')"))))
    (testing "text whose globals cannot see the shim keeps its own import"
      ;; Audit R8: the rewrite named `__vis_asyncio__`, which those globals lack.
      (is (= "ImportError"
             (out session
                  (str "try:\n    exec('import asyncio', {'__builtins__': {}})\n"
                       "except Exception as e:\n    print(type(e).__name__)")))))
    (testing "unparsable text keeps its own error"
      (is (= "ERROR SyntaxError: '(' was never closed (<string>, line 2)"
             (out session "exec('import asyncio\\nx = (')"))))))
