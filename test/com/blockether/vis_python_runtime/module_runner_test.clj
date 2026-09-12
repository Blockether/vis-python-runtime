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
             "        results.append(__vis_module_exit__)\n"
             "        results.append(sys.argv is original and original == ['launcher', '-q', '--', 'test_example.py'])\n"
             "results"))]
        (is (= (vec (mapcat (fn [code]
                              [["launcher" "-p" "no:faulthandler" "-q" "--" "test_example.py"] code
                               code true])
                            [0 1 5]))
               (subvec result 0 12)))
        (is (= [["launcher" "-q" "--" "test_example.py"] 3 3 true] (subvec result 12))))
      (finally (runtime/trust! session false) (runtime/close-session! session)))))
