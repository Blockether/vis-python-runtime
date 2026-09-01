(ns com.blockether.vis-python-runtime.sandbox-source-test
  "The acceptance criterion for the whole project, run as early as it can be:
   Vis' OWN sandbox sources have to load in the embedded interpreter with no
   edit. `async_runtime.py` is the hard one — 4.4k lines carrying the handle
   registry, the shell driver and the descriptor discipline — so it is the file
   that decides whether this is a runtime swap or a rewrite.

   Reads the sibling checkout (`../vis`) because that file is Vis' to own; this
   repository must never hold a copy that drifts from it. Without the sibling,
   and without a built cdylib, the test says it skipped rather than passing by
   doing nothing."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.ffi :as ffi]))

(def ^:private sandbox-source
  (io/file (System/getProperty "user.dir") ".." "vis" "resources" "vis-python" "async_runtime.py"))

(def ^:private built?
  (try (boolean (runtime/resolve-library))
       (catch clojure.lang.ExceptionInfo _ false)))

(deftest vis-async-runtime-loads-test
  (cond
    (not built?)
    (println "SKIP vis-async-runtime-loads-test: no cdylib, run native/vis-python/build.sh")

    (not (.isFile ^java.io.File sandbox-source))
    (println "SKIP vis-async-runtime-loads-test: no sibling Vis checkout at" (.getPath ^java.io.File sandbox-source))

    :else
    (testing "Vis' sandbox runtime source loads unmodified"
      (ffi/initialize!)
      (ffi/exec! (slurp sandbox-source))
      (is (= "True" (ffi/eval-str "'__VisShell__' in globals()"))
          "the shell handle type the host drives is defined after the load")
      (is (= "True" (ffi/eval-str "callable(globals().get('__vis_own__', None))"))
          "the handle-ownership registry is present, the one GraalPy needed a workaround for"))))
