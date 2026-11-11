(ns com.blockether.vis-python-runtime.owned-module-test
  "Source modules belong to the session that loads them."
  (:require [clojure.test :refer [is]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :refer [defbuilt-test ev]]))

(defbuilt-test
  owned-module-lifecycle-test
  (runtime/initialize!)
  (let [owner
        "owned-module-owner"

        observer
        "owned-module-observer"]

    (runtime/trust! owner)
    (runtime/trust! observer)
    (try
      (runtime/exec! owner "import vis_runtime, sys
")
      (is (= [42 true true "entry.py" "fixture-root"]
             (ev owner
                 (str "value = 42
"
                      "m = vis_runtime.exec_module(globals(), 'extension', "
                      "'value = 7\\nlist = dict = None\\nclass Item: pass', 'entry.py', "
                      "attributes={'__vis_ext_dir__': 'fixture-root'})
"
                      "[value, sys.modules[m.Item.__module__] is m, m.__dict__ is not globals(), "
                      "m.__file__, m.__vis_ext_dir__]"))))
      (is
        (=
          [true true]
          (ev
            owner
            (str
              "previous = m
"
              "try:
"
              "    vis_runtime.exec_module(globals(), 'extension', 'raise ValueError(1)', 'entry.py')
"
              "except ValueError:
    pass
"
              "[sys.modules[m.__name__] is previous, m.value == 7]"))))
      (is (= [8 true]
             (ev owner
                 (str
                   "m = vis_runtime.exec_module(globals(), 'extension', 'value = 8', 'entry.py')
"
                   "[m.value, sys.modules[m.__name__] is m]"))))
      (is
        (=
          [true true]
          (ev
            owner
            (str
              "empty = vis_runtime.exec_module(globals(), 'empty', '', 'empty.py')
"
              "try:
"
              "    vis_runtime.exec_module(globals(), 'failed', 'raise SystemExit(2)', 'failed.py')
"
              "except SystemExit:
    pass
"
              "[empty.__name__ in sys.modules, __name__ + '__failed' not in sys.modules]"))))
      (runtime/close-session! owner)
      (runtime/close-session! owner)
      (is (= []
             (ev observer
                 "[k for k in __import__('sys').modules if k.startswith('owned-module-owner__')]")))
      (finally (runtime/close-session! owner) (runtime/close-session! observer)))))

(defbuilt-test
  source-cache-preserves-code-test
  (runtime/initialize!)
  (let [session "owned-source-cache"]
    (runtime/trust! session)
    (try
      (is
        (=
          [1 2 true "Missing" true]
          (ev
            session
            (str
              "import vis_code, vis_runtime, __future__, sys, tempfile, os, marshal\n"
              "first = vis_code.code('value = 1', 'source.py')\n"
              "second = vis_code.code('value = 2', 'source.py')\n"
              "plain = {}; exec(first, plain)\n" "changed = {}; exec(second, changed)\n"
              "future = {}; vis_runtime.exec_source(future, 'def f(x: Missing): pass', 'source.py', flags=__future__.annotations.compiler_flag)\n"
              "prefix = sys.pycache_prefix\n"
              "try:\n" "    with tempfile.TemporaryDirectory() as folder:\n"
              "        sys.pycache_prefix = folder\n"
              "        cache = os.path.join(folder, 'bad.bin')\n"
              "        with open(cache, 'wb') as stream: marshal.dump({}, stream)\n"
              "        rejected = vis_code._load(cache) is None\n"
              "finally:\n    sys.pycache_prefix = prefix\n"
              "[plain['value'], changed['value'], vis_code.code('value = 1', 'source.py') is first, future['f'].__annotations__['x'], rejected]"))))
      (finally (runtime/close-session! session)))))

(defbuilt-test
  owned-module-close-during-load-test
  (runtime/initialize!)
  (doseq [reopen
          [false true]

          failure
          [false true]]

    (let [owner
          "owned-race-owner"

          observer
          "owned-race-observer"]

      (runtime/trust! owner)
      (runtime/trust! observer)
      (try
        (runtime/exec! owner "value = 1")
        (is
          (=
            [true true true true (if failure ["SystemExit"] [])]
            (ev
              observer
              (str
                "reopen = "
                (if reopen "True" "False")
                "\n"
                "source = 'started.set(); resume.wait(5); "
                (if failure "raise SystemExit(2)" "value = 9")
                "'\n"
                "import vis_runtime as v, sys, threading, types\nowner = sys.modules['owned-race-owner']\nprevious = v.exec_module(owner.__dict__, 'entry', 'value=1', 'entry.py')\nstarted = threading.Event(); resume = threading.Event(); errors = []\ndef work():\n    try:\n        v.exec_module(owner.__dict__, 'entry', source, 'entry.py', attributes={'started': started, 'resume': resume})\n    except BaseException as error:\n        errors.append(type(error).__name__)\nt = threading.Thread(target=work)\nt.start()\ntry:\n    assert started.wait(5)\n    try:\n        v.exec_module(owner.__dict__, 'entry', '', 'entry.py')\n        refused = False\n    except RuntimeError:\n        refused = True\n    v.close_session(owner.__name__)\n    if reopen:\n        fresh = types.ModuleType(owner.__name__)\n        sys.modules[fresh.__name__] = fresh\n        replacement = v.exec_module(fresh.__dict__, 'entry', 'value=77', 'entry.py')\nfinally:\n    resume.set()\n    t.join(5)\nif reopen:\n    correct = sys.modules.get(replacement.__name__) is replacement and replacement.value == 77\nelse:\n    correct = 'owned-race-owner__entry' not in sys.modules and 'owned-race-owner' not in v._OWNED_MODULES\n[refused, correct, not t.is_alive(), 'owned-race-owner__entry' not in v._MODULE_LOADS, errors]\n"))))
        (finally (runtime/close-session! owner) (runtime/close-session! observer))))))

(defbuilt-test
  source-cache-optimization-test
  (runtime/initialize!)
  (let [session "source-cache-optimization"]
    (runtime/trust! session)
    (try
      (is
        (=
          ["True module docs" "False module docs" "False None" "True module docs"]
          (ev
            session
            "import os, pathlib, subprocess, sys, tempfile, vis_code\nsource = '\"module docs\"\\nvalue = __debug__'\nwith tempfile.TemporaryDirectory(prefix='vis-source-cache-') as folder:\n    env = dict(os.environ, PYTHONPATH=str(pathlib.Path(vis_code.__file__).parent))\n    probe = 'import sys; sys.pycache_prefix=' + repr(folder) + '; import vis_code; n={}; exec(vis_code.code(' + repr(source) + ', \"source.py\"), n); print(n[\"value\"], n.get(\"__doc__\"))'\n    result = []\n    for flags in ([], ['-O'], ['-OO'], []):\n        child = subprocess.run([sys.executable, *flags, '-c', probe], env=env, capture_output=True, text=True, timeout=15)\n        assert child.returncode == 0, child.stderr\n        result.append(child.stdout.strip())\nresult\n")))
      (finally (runtime/close-session! session)))))

(defbuilt-test
  module-cache-optimization-test
  (runtime/initialize!)
  (let [session "module-cache-optimization"]
    (runtime/trust! session)
    (try
      (is
        (=
          ["True module docs" "False module docs" "False None" "True module docs"]
          (ev
            session
            "import os, pathlib, subprocess, sys, tempfile, vis_runtime\nwith tempfile.TemporaryDirectory(prefix='vis-module-cache-') as folder:\n    pathlib.Path(folder, 'optimization_probe.py').write_text('\"module docs\"\\nvalue = __debug__')\n    env = dict(os.environ, PYTHONPATH=os.pathsep.join([folder, str(pathlib.Path(vis_runtime.__file__).parent)]))\n    probe = 'import sys; sys.pycache_prefix=' + repr(folder) + '; import vis_runtime; n={}; exec(vis_runtime.module_code(\"optimization_probe\"), n); print(n[\"value\"], n.get(\"__doc__\"))'\n    result = []\n    for flags in ([], ['-O'], ['-OO'], []):\n        child = subprocess.run([sys.executable, *flags, '-c', probe], env=env, capture_output=True, text=True, timeout=15)\n        assert child.returncode == 0, child.stderr\n        result.append(child.stdout.strip())\nresult\n")))
      (finally (runtime/close-session! session)))))
