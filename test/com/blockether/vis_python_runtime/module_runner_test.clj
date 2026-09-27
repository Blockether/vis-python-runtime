(ns com.blockether.vis-python-runtime.module-runner-test
  "Embedded module execution preserves the host's fatal-signal handlers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :refer [defbuilt-test ev]]))

(defbuilt-test
  pytest-signal-policy-test
  ;; pytest's faulthandler replaces JVM signal handlers and crashes embedded SDK tests.
  (runtime/initialize!)
  (let [session "module-runner-signals"]
    (runtime/trust! session)
    (try
      (runtime/exec! session (slurp (io/resource "vis-python/module_runner.py")))
      (let
        [result
         (ev
           session
           (str
             "import sys, types, runpy\n"
             "from unittest.mock import patch\n" "results = []\n"
             "for name, code in [('pytest', 0), ('pytest', 1), ('pytest', 5), ('other_module', 3)]:\n"
             "    module = types.ModuleType(name)\n"
             "    module.__file__ = '/test/module.py'\n"
             "    original = ['launcher', '-q', '--', 'test_example.py']\n"
             "    def execute(*args, **kwargs):\n" "        results.append(list(sys.argv))\n"
             "        raise SystemExit(code)\n"
             "    with patch.object(sys, 'argv', original), patch.dict(sys.modules, {name: module}), patch.object(runpy, 'run_module', execute):\n"
             "        results.append(__vis_run_module__(name))\n"
             "        results.append(__vis_cli_exit__)\n"
             "        results.append(sys.argv is original and original == ['launcher', '-q', '--', 'test_example.py'])\n"
             "results"))]
        (is (= (vec (mapcat (fn [code]
                              [["launcher" "-p" "no:faulthandler" "-q" "--" "test_example.py"] code
                               code true])
                            [0 1 5]))
               (subvec result 0 12)))
        (is (= [["launcher" "-q" "--" "test_example.py"] 3 3 true] (subvec result 12))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))

(defbuilt-test
  module-executes-once-as-main-test
  ;; JVM/SDK dogfooding: importing before runpy duplicated side effects and lost SystemExit.
  (runtime/initialize!)
  (let [session "module-runner-once"]
    (runtime/trust! session)
    (try
      (runtime/install-runtime! session)
      (runtime/exec! session (slurp (io/resource "vis-python/module_runner.py")))
      (let
        [result
         (ev
           session
           (str
             "import sys, tempfile, pathlib, builtins, io\n" "from unittest.mock import patch\n"
             "results = []\n" "with tempfile.TemporaryDirectory() as directory:\n"
             "    folder = pathlib.Path(directory)\n"
             "    (folder / 'module_once_probe.py').write_text('import builtins\\nbuiltins._vis_module_runs.append(__name__)\\n')\n"
             "    (folder / 'module_async_probe.py').write_text(\"import asyncio\\nasync def compute():\\n    await asyncio.sleep(0)\\n    return 42\\nprint('module-result', asyncio.run(compute()))\\nraise SystemExit(7)\\n\")\n"
             "    builtins._vis_module_runs = []\n"
             "    sys.path.insert(0, directory)\n" "    try:\n"
             "        results.append(__vis_run_module__('module_once_probe'))\n"
             "        results.append(list(builtins._vis_module_runs))\n"
             "        with patch.object(sys, '__stdout__', io.StringIO()) as out:\n"
             "            answer = __import__('vis_runtime').run_sync_block(\"__vis_run_module__('module_async_probe')\", globals())\n"
             "        results.append([answer, out.getvalue()])\n"
             "        results.append(globals().get('__vis_cli_exit__'))\n"
             "    finally:\n" "        sys.path.remove(directory)\n"
             "        sys.modules.pop('module_once_probe', None)\n"
             "        sys.modules.pop('module_async_probe', None)\n"
             "        del builtins._vis_module_runs\n" "results\n"))]
        (is (= 0 (nth result 0)))
        (is (= ["__main__"] (nth result 1)))
        ;; A program owns the process stdout; the block capture stays empty.
        (is (= [{"stdout" "" "error" nil} "module-result 42\n"] (nth result 2)))
        (is (= 7 (nth result 3))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))

(defbuilt-test
  file-executes-as-main-test
  ;; Regression #287: file mode must run __main__ with its script directory importable.
  (runtime/initialize!)
  (let [session "file-runner-main"]
    (runtime/trust! session)
    (try
      (runtime/install-runtime! session)
      (runtime/exec! session (slurp (io/resource "vis-python/module_runner.py")))
      (let
        [result
         (ev
           session
           (str
             "import sys, tempfile, pathlib, io\n" "from unittest.mock import patch\n"
             "with tempfile.TemporaryDirectory() as directory:\n"
             "    folder = pathlib.Path(directory)\n"
             "    (folder / 'sibling_probe.py').write_text('VALUE = 42\\n')\n"
             "    script = folder / 'file_probe.py'\n"
             "    script.write_text(\"import sys, asyncio\\nfrom sibling_probe import VALUE\\nprint(__name__, __file__, sys.argv[1], VALUE, asyncio.run(asyncio.sleep(0, result=7)))\\nraise SystemExit(9)\\n\")\n"
             "    original_path, original_argv = list(sys.path), sys.argv\n"
             "    sys.argv = [str(script), 'argument']\n" "    try:\n"
             "        with patch.object(sys, '__stdout__', io.StringIO()) as out:\n"
             "            answer = __import__('vis_runtime').run_sync_block(f'__vis_run_file__({str(script)!r})', globals())\n"
             "        result = [[answer, out.getvalue()], str(script), __vis_cli_exit__, sys.path == original_path, sys.argv == [str(script), 'argument']]\n"
             "    finally:\n"
             "        sys.argv = original_argv\n" "result\n"))]
        (is (= [{"stdout" "" "error" nil} (str "__main__ " (second result) " argument 42 7\n")]
               (first result)))
        (is (= 9 (nth result 2)))
        (is (true? (nth result 3)))
        (is (true? (nth result 4))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))

(defbuilt-test
  code-runs-as-python-main-test
  ;; `vis-agent python -c` and stdin run like `python`: a fresh __main__ on the process
  ;; stdout, CPython's SystemExit rules, tracebacks on stderr without runner frames, and
  ;; non-daemon threads by default, as started from CPython's main thread.
  (runtime/initialize!)
  (let [session "code-runner-main"]
    (runtime/trust! session)
    (try
      (runtime/install-runtime! session)
      (runtime/exec! session (slurp (io/resource "vis-python/module_runner.py")))
      (let
        [[streams message failure syntax closed dedented missing inner restored]
         (ev
           session
           (str
             "import io, sys, tempfile, pathlib\n"
             "from unittest.mock import patch\n" "import threading\n"
             "daemonic = threading.current_thread().daemon\n" "session_only = True\n"
             "class Kept(io.BytesIO):\n" "    def close(self):\n"
             "        self.kept = self.getvalue()\n" "        super().close()\n"
             "def run(call):\n" "    raw, err = Kept(), io.StringIO()\n"
             "    out = io.TextIOWrapper(raw, encoding='utf-8')\n"
             "    with patch.object(sys, '__stdout__', out), patch.object(sys, 'stderr', err):\n"
             "        answer = __import__('vis_runtime').run_sync_block(call, globals())\n"
             "    if not raw.closed:\n"
             "        out.flush()\n" "    written = raw.kept if raw.closed else raw.getvalue()\n"
             "    return [answer, __vis_cli_exit__, written.decode(), err.getvalue()]\n"
             "def code(source, filename='<string>', dedent=False):\n"
             "    return run(f'__vis_run_code__({source!r}, {filename!r}, dedent={dedent!r})')\n"
             "results = [code(\"import sys, threading\\nprint(__name__, 'session_only' in globals(), sys.modules['__main__'].__dict__ is globals(), threading.Thread(target=len).daemon, flush=True)\\nsys.stdout.buffer.write(b'raw\\\\n')\\nprint('note', file=sys.stderr)\\nsys.exit(3)\\n\"),\n"
             "           code(\"raise SystemExit('bye')\\n\"),\n"
             "           code(\"def fail():\\n    raise ValueError('boom')\\nfail()\\n\", '<stdin>'),\n"
             "           code(\"def f(:\\n\"),\n"
             "           code(\"import sys\\nsys.stdout.write('done')\\nsys.stdout.close()\\n\"),\n"
             "           code(\"\\n    print('dedented')\\n\", dedent=True)]\n"
             "with tempfile.TemporaryDirectory() as directory:\n"
             "    pathlib.Path(directory, 'inner_import_probe.py').write_text('import vis_no_such_dependency_probe\\n')\n"
             "    sys.path.insert(0, directory)\n"
             "    try:\n"
             "        results.append(run(\"__vis_run_module__('vis_no_such_module_probe')\"))\n"
             "        results.append(run(\"__vis_run_module__('inner_import_probe')\"))\n"
             "    finally:\n"
             "        sys.path.remove(directory)\n"
             "        sys.modules.pop('inner_import_probe', None)\n"
             "results.append(threading.current_thread().daemon == daemonic)\n" "results\n"))
         ok {"stdout" "" "error" nil}]

        (is (= [ok 3 "__main__ False True False\nraw\n" "note\n"] streams))
        (is (true? restored) "the runner restores the calling thread's daemon status")
        (is (= [ok 1 "" "bye\n"] message))
        (let [[answer exit out err] failure]
          (is (= [ok 1 ""] [answer exit out]))
          (is (str/starts-with? err "Traceback (most recent call last):\n") err)
          (is (= 2 (count (re-seq #"  File " err))) err)
          (is (str/includes? err "File \"<stdin>\", line 3, in <module>") err)
          (is (str/includes? err "raise ValueError('boom')") err)
          (is (str/ends-with? err "ValueError: boom\n") err))
        (let [[answer exit out err] syntax]
          (is (= [ok 1 ""] [answer exit out]))
          (is (str/includes? err "File \"<string>\", line 1") err)
          (is (str/includes? err "SyntaxError") err)
          (is (not (str/includes? err "Traceback")) err))
        (is (= [ok 0 "done" ""] closed))
        (is (= [ok 0 "dedented\n" ""] dedented))
        (is (= [ok 1 "" "vis-agent python: No module named vis_no_such_module_probe\n"] missing))
        (let [[answer exit out err] inner]
          (is (= [ok 1 ""] [answer exit out]))
          (is (= 1 (count (re-seq #"  File " err))) err)
          (is (str/includes? err "inner_import_probe.py\", line 1, in <module>") err)
          (is (str/includes? err
                             "ModuleNotFoundError: No module named 'vis_no_such_dependency_probe'")
              err)))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))
