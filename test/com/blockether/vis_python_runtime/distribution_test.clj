(ns com.blockether.vis-python-runtime.distribution-test
  "A REAL distribution, installed by pip, obeys the sandbox's guards.

   The shims exist because the previous engine could not run a wheel. CPython
   can, so the question that decides whether a shim can go is not whether
   `import numpy` works — it is whether numpy's own file access is confined and
   whether `requests` egress is policed. Both guards act at the INTERPRETER, not
   at the library: a package that never heard of vis is bound by them anyway.
   These cases are that proof, measured against wheels downloaded from PyPI, so
   they skip loudly when there is no index."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block temp-dir]])
  (:import [java.net InetSocketAddress Socket]))

(defn- index-reachable?
  "Whether PyPI answers, so a case that needs it can skip instead of failing for
   a reason that is not about this code."
  []
  (try (with-open [socket (Socket.)]
         (.connect socket (InetSocketAddress. "pypi.org" 443) 3000)
         true)
       (catch Exception _ false)))

(def ^:private wheels
  "One directory of real wheels for the whole namespace: `requests` for egress
   and `numpy` for a compiled extension that opens files itself."
  (delay
    (let [target (temp-dir "vis-distribution")
          answer (runtime/pip-install! {:target target} ["requests" "numpy"])]
      (when-not (zero? (:exit answer))
        (throw (ex-info "pip could not install the wheels these cases measure"
                        {:exit (:exit answer) :out (:out answer)})))
      target)))

(defn- importable!
  "Put `target` on this session's `sys.path` BEFORE the policy is composed —
   `vispython_confine` reads `sys.path` to keep imports working, so a package
   directory named afterwards would be unreadable."
  [session target]
  (runtime/exec! session (str "import sys\nsys.path.insert(0, " (pr-str target) ")")))

(defn- forget-wheels!
  "Take the wheel directory back off `sys.path` and drop every module that came
   from it.

   `sys.path` and `sys.modules` are the INTERPRETER's, not a session's, so a
   real distribution left importable here is importable in every namespace that
   runs afterwards — and the shim tests then measure the real package instead of
   the shim (measured: `requests` installed here made every httpx case fail on
   the real transport). The same reason the fixture puts confinement back."
  []
  (when (realized? wheels)
    (runtime/exec!
     runtime/default-session
     (str "import sys\n"
          "target = " (pr-str @wheels) "\n"
          "sys.path[:] = [entry for entry in sys.path if entry != target]\n"
          "for name, module in list(sys.modules.items()):\n"
          "    origin = getattr(module, '__file__', None) or ''\n"
          "    if origin.startswith(target):\n"
          "        del sys.modules[name]\n"))))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; Confinement is PROCESS state over one interpreter, so a case that
           ;; sets it hands it to the next namespace unless it is put back.
           (runtime/confine! [] [])
           (forget-wheels!)
           (harness/close-sessions!)))))

(harness/defbuilt-test a-wheel-is-confined-like-the-rest-test
  (if-not (index-reachable?)
    (println "SKIPPED a-wheel-is-confined-like-the-rest-test: pypi.org is not reachable")
    (let [root    (temp-dir "vis-distribution-root")
          session (harness/block-session)]
      (importable! session @wheels)
      (runtime/confine! [root] [root])
      (testing "a compiled extension imports and runs under the policy"
        (let [answer (block session "import numpy\nprint(numpy.zeros(3).sum())")]
          (is (nil? (:error answer)) (str (:error answer)))
          (is (= "0.0" (str/trim (str (:stdout answer)))))))
      (testing "and its own file access is refused outside the roots, like any other open"
        (let [answer (block session "import numpy\nnumpy.loadtxt('/etc/hosts')")]
          (is (str/includes? (str (:error answer)) "PermissionError")
              (str "numpy read outside the roots: " (:stdout answer) (:error answer))))))))

(harness/defbuilt-test a-wheel-obeys-the-network-policy-test
  (if-not (index-reachable?)
    (println "SKIPPED a-wheel-obeys-the-network-policy-test: pypi.org is not reachable")
    (let [session (harness/guarded-session ["example.com"] ["169.254.169.254"])]
      (importable! session @wheels)
      (testing "requests reaches a denied host through the guarded socket, and is refused"
        (let [answer (block session
                            (str "import requests\n"
                                 "try:\n"
                                 "    requests.get('http://169.254.169.254/', timeout=1)\n"
                                 "    print('permitted')\n"
                                 "except Exception as e:\n"
                                 "    print('blocked' if 'vis: network host' in str(e) else 'escaped: ' + str(e))"))]
          (is (nil? (:error answer)) (str (:error answer)))
          (is (= "blocked" (str/trim (str (:stdout answer))))))))))
