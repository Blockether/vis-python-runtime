(ns com.blockether.vis-python-runtime.sandbox-source-test
  "The acceptance criterion for the whole project, run as early as it can be:
   Vis' OWN sandbox runtime has to load in the embedded interpreter with no
   edit. `async_runtime.py` is the hard one — 4.4k lines carrying the handle
   registry, the shell driver and the descriptor discipline — so it is the file
   that decides whether this is a runtime swap or a rewrite.

   It is IMPORTED, not executed as a string: the directory holding it becomes an
   import root and `vis_runtime.install` copies its public names into the
   session. Reads the sibling checkout (`../vis`) because that file is Vis' to
   own; this repository must never hold a copy that drifts from it. Without the
   sibling, and without a built cdylib, the test says it skipped rather than
   passing by doing nothing."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.ffi :as ffi]))

(def ^:private sandbox-dir
  (io/file (System/getProperty "user.dir") ".." "vis" "resources" "vis-python"))

(def ^:private built?
  (try (boolean (runtime/resolve-library))
       (catch clojure.lang.ExceptionInfo _ false)))

(deftest vis-async-runtime-imports-test
  (cond
    (not built?)
    (println "SKIP vis-async-runtime-imports-test: no cdylib, run native/vis-python/build.sh")

    (not (.isFile (io/file sandbox-dir "async_runtime.py")))
    (println "SKIP vis-async-runtime-imports-test: no sibling Vis checkout at"
             (.getPath ^java.io.File sandbox-dir))

    :else
    (testing "Vis' sandbox runtime installs into a session unmodified"
      (ffi/initialize! {:source-paths [(.getAbsolutePath ^java.io.File sandbox-dir)]})
      (let [session "vis-sandbox"
            installed (ffi/install-runtime! session)]
        (is (< 150 installed)
            "the whole public surface of the runtime landed in the session")
        (is (= "True" (ffi/eval-str session "'__VisShell__' in globals()"))
            "the shell handle type the host drives is defined")
        (is (= "True" (ffi/eval-str session "callable(__vis_own__)"))
            "the handle-ownership registry is present, the one GraalPy needed a workaround for")
        (is (= "True" (ffi/eval-str session "__import__('vis_runtime').SANDBOX_MODULE == 'async_runtime'"))
            "the runtime package itself imported from this repository's python/ root")))))
