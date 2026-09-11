(ns com.blockether.vis-python-runtime.executable-test
  "Executable identity and ordinary CPython re-execution from an embedded host."
  (:require [clojure.test :refer [is]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :refer [defbuilt-test ev]])
  (:import [com.blockether.vispython Interpreter]))

(defbuilt-test
  bundled-executable-test
  ;; Vis #199: the JVM/native host is not a Python-compatible launcher.
  (runtime/initialize!)
  (let [session "executable-identity"]
    (runtime/trust! session)
    (try
      (let
        [result
         (ev
           session
           (str
             "import os, pathlib, subprocess, sys, tempfile\n"
             "def run_python(executable, *args, **kwargs):\n"
             "    kwargs.setdefault('env', dict(os.environ))['PYTHONDONTWRITEBYTECODE'] = '1'\n"
             "    child = subprocess.run([executable, *args], capture_output=True, text=True, timeout=15, **kwargs)\n"
             "    return [child.returncode, child.stdout.strip(), child.stderr]\n"
             "with tempfile.TemporaryDirectory(prefix='vis-executable-') as directory:\n"
             "    link = pathlib.Path(directory) / 'python3'\n"
             "    link.symlink_to(sys.executable)\n"
             "    (pathlib.Path(directory) / 'child_probe.py').write_text('print(456)')\n"
             "    child_env = dict(os.environ, PYTHONPATH=directory)\n"
             "    result = {\n" "        'executable': sys.executable,\n"
             "        'base_executable': sys._base_executable,\n"
             "        'code': run_python(sys.executable, '-c', 'print(123)'),\n"
             "        'module': run_python(sys.executable, '-m', 'json.tool', input='123'),\n"
             "        'base': run_python(sys._base_executable, '-c', 'print(123)'),\n"
             "        'symlink': run_python(str(link), '-c', 'print(123)'),\n"
             "        'pythonpath': run_python(sys.executable, '-m', 'child_probe', env=child_env),\n"
             "    }\n" "result"))]
        (is (= (Interpreter/pythonExecutable) (get result "executable")))
        (is (= (get result "executable") (get result "base_executable")))
        (doseq [mode ["code" "module" "base" "symlink"]]
          (is (= [0 "123" ""] (get result mode)) mode))
        (is (= [0 "456" ""] (get result "pythonpath"))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))
