(ns com.blockether.vis-python-runtime.sandbox-source-test
  "The acceptance criterion for the whole project: the sandbox runtime this
   repository now carries loads in the embedded interpreter with no edit.
   `async_runtime.py` is the hard one — 4.4k lines carrying the handle registry,
   the shell driver and the descriptor discipline — so it is the file that
   decides whether this is a runtime swap or a rewrite.

   It is COMPILED from that file and executed INTO the session namespace:
   `resources/vis-python/` is an import root, and `vis_runtime.install` execs the
   module's own source there so a block sees the runtime's names as globals.
   Skips, loudly, when no cdylib has been built."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.ffi :as ffi]))

(def ^:private built?
  (try (boolean (runtime/resolve-library))
       (catch clojure.lang.ExceptionInfo _ false)))

(deftest sandbox-runtime-imports-test
  (if-not built?
    (println "SKIP sandbox-runtime-imports-test: no cdylib, run native/vis-python/build.sh")
    (testing "the sandbox runtime installs into a session unmodified"
      (ffi/initialize!)
      (let [session "vis-sandbox"
            installed (ffi/install-runtime! session)]
        (is (< 150 installed)
            "the whole public surface of the runtime landed in the session")
        (is (str/includes? (ffi/eval-str session "__vis_run_async__.__code__.co_filename")
                           "vis-python-runtime")
            "the code executed into the session came from THIS repository's resources")
        (is (= "True" (ffi/eval-str session "'__VisShell__' in globals()"))
            "the shell handle type the host drives is defined")
        (is (= "True" (ffi/eval-str session "callable(__vis_own__)"))
            "the handle-ownership registry is present, the one GraalPy needed a workaround for")))))
