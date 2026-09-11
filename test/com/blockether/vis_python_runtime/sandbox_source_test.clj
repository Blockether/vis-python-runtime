(ns com.blockether.vis-python-runtime.sandbox-source-test
  "The acceptance criterion for the whole project: the sandbox runtime this
   repository now carries loads in the embedded interpreter with no edit.
   `async_runtime.py` is the hard one — 4k lines carrying the shell driver, the
   awaitable settling and the deferred-tool protocol — so it is the file that
   decides whether this is a runtime swap or a rewrite.

   It is COMPILED from that file and executed INTO the session namespace:
   `resources/vis-python/` is an import root, and `vis_runtime.install` execs the
   module's own source there so a block sees the runtime's names as globals.
   Skips, loudly, when no cdylib has been built."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython Sources VisPythonException]))

(def ^:private built? (try (boolean (runtime/resolve-library)) (catch VisPythonException _ false)))

(deftest sandbox-runtime-imports-test
  (if-not built?
    (println "SKIP sandbox-runtime-imports-test: no cdylib, run native/vispython/build.sh")
    (testing "the sandbox runtime installs into a session unmodified"
      (runtime/initialize!)
      (let [session
            "vis-sandbox"

            installed
            (runtime/install-runtime! session)]

        (is (= runtime/version (runtime/eval-str session "VIS_PYTHON_RUNTIME_VERSION"))
            "the runtime global reports the embedded library, not an installed Python package")
        (is (= "True"
               (runtime/eval-str session
                                 "'VIS_PYTHON_RUNTIME_VERSION' in __vis_protected_names__")))
        (is (< 150 installed) "the whole public surface of the runtime landed in the session")
        ;; Packaged resources live in a content-addressed cache, not the checkout.
        (let [source (io/file (runtime/eval-str session "__vis_run_async__.__code__.co_filename"))]
          (is (some #(= (.getCanonicalFile source)
                        (.getCanonicalFile (io/file % "async_runtime.py")))
                    (Sources/roots))
              "the compiled file belongs to this artifact's declared source roots")
          (is (= (slurp (io/resource "vis-python/async_runtime.py")) (slurp source))
              "the executed source matches the shipped resource without modification"))
        (is (= "True" (runtime/eval-str session "'__VisShell__' in globals()"))
            "the shell handle type the host drives is defined")
        (is
          (= "True" (runtime/eval-str session "callable(__vis_flush_writes__)"))
          "the flush that puts a held handle's bytes on disk before a tool reads them is present")))))
