(ns com.blockether.vis-python-runtime.module-runner-test
  "Embedded module execution preserves the host's fatal-signal handlers."
  (:require [clojure.java.io :as io]
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
             "import sys, tempfile, pathlib, builtins\n"
             "results = []\n" "with tempfile.TemporaryDirectory() as directory:\n"
             "    folder = pathlib.Path(directory)\n"
             "    (folder / 'module_once_probe.py').write_text('import builtins\\nbuiltins._vis_module_runs.append(__name__)\\n')\n"
             "    (folder / 'module_async_probe.py').write_text(\"import asyncio\\nasync def compute():\\n    await asyncio.sleep(0)\\n    return 42\\nprint('module-result', asyncio.run(compute()))\\nraise SystemExit(7)\\n\")\n"
             "    builtins._vis_module_runs = []\n"
             "    sys.path.insert(0, directory)\n" "    try:\n"
             "        results.append(__vis_run_module__('module_once_probe'))\n"
             "        results.append(list(builtins._vis_module_runs))\n"
             "        results.append(__import__('vis_runtime').run_sync_block(\"__vis_run_module__('module_async_probe')\", globals()))\n"
             "        results.append(globals().get('__vis_cli_exit__'))\n"
             "    finally:\n" "        sys.path.remove(directory)\n"
             "        sys.modules.pop('module_once_probe', None)\n"
             "        sys.modules.pop('module_async_probe', None)\n"
             "        del builtins._vis_module_runs\n" "results\n"))]
        (is (= 0 (nth result 0)))
        (is (= ["__main__"] (nth result 1)))
        (is (= {"stdout" "module-result 42\n" "error" nil} (nth result 2)))
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
             "import sys, tempfile, pathlib\n" "with tempfile.TemporaryDirectory() as directory:\n"
             "    folder = pathlib.Path(directory)\n"
             "    (folder / 'sibling_probe.py').write_text('VALUE = 42\\n')\n"
             "    script = folder / 'file_probe.py'\n"
             "    script.write_text(\"import sys, asyncio\\nfrom sibling_probe import VALUE\\nprint(__name__, __file__, sys.argv[1], VALUE, asyncio.run(asyncio.sleep(0, result=7)))\\nraise SystemExit(9)\\n\")\n"
             "    original_path, original_argv = list(sys.path), sys.argv\n"
             "    sys.argv = [str(script), 'argument']\n"
             "    try:\n"
             "        answer = __import__('vis_runtime').run_sync_block(f'__vis_run_file__({str(script)!r})', globals())\n"
             "        result = [answer, str(script), __vis_cli_exit__, sys.path == original_path, sys.argv == [str(script), 'argument']]\n"
             "    finally:\n"
             "        sys.argv = original_argv\n" "result\n"))]
        (is (= {"stdout" (str "__main__ " (second result) " argument 42 7\n") "error" nil}
               (first result)))
        (is (= 9 (nth result 2)))
        (is (true? (nth result 3)))
        (is (true? (nth result 4))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))
