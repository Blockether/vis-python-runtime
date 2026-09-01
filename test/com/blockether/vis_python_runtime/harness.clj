(ns com.blockether.vis-python-runtime.harness
  "What a ported shim test needs and nothing else.

   Vis' shim tests read the same way whatever they cover: run a Python snippet
   in a sandbox context, expect `True`. Here the context is a session in the
   embedded interpreter, so `truthy` is the whole harness — one interpreter for
   the suite, one session per shim, and a skip when no cdylib has been built."
  (:require [clojure.test]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.ffi :as ffi]))

(def built?
  "False in a checkout where `native/vis-python/build.sh` has not run."
  (try (boolean (runtime/resolve-library))
       (catch clojure.lang.ExceptionInfo _ false)))

(defn session
  "A session of its own with the sandbox runtime and `shim` installed.

   One interpreter serves the whole suite, so a session is a fresh namespace
   AND a fresh module table: every shim loaded so far is dropped first, and a
   test that monkeypatches one hands nothing to the next test."
  [shim]
  (ffi/initialize!)
  (let [s (str "shim-" shim "-" (System/nanoTime))]
    (ffi/install-runtime! s)
    (ffi/exec! s "import vis_runtime")
    (ffi/eval-str s "vis_runtime.forget_shims()")
    (ffi/install-shim! s shim)
    s))

(defmacro defshim-test
  "A ported shim test: binds `session` to a session with `shim` installed, or
   prints a skip when the cdylib is missing. Keeps the moved bodies unchanged."
  [test-name shim & body]
  `(clojure.test/deftest ~test-name
     (if-not built?
       (println "SKIP" ~(str test-name) "- no cdylib, run native/vis-python/build.sh")
       (let [~'session (session ~shim)]
         ~@body))))

(defn ev
  "Run `code` in `session` and answer its value as Clojure data — the moved
   tests' `ev`, which read a sandbox result as data and compare against it."
  [session code]
  (ffi/run session code))

(defn truthy
  "Whether `code` answered Python `True`."
  [session code]
  (true? (ev session code)))

(defn ev-guarded
  "Like `ev`, with `sys.modules` restored afterwards. One interpreter means one
   module table, so a snippet that breaks an import on purpose — the way the
   moved load-independence tests do — must put the table back."
  [session code]
  (ffi/run session "import sys\n_vis_saved_modules = dict(sys.modules)\nNone")
  (try (ev session code)
       (finally
         (ffi/run session
                  "import sys\nsys.modules.clear()\nsys.modules.update(_vis_saved_modules)\nNone"))))

(defn fresh
  "A session with the runtime but NO shim installed: the moved tests that ran
   in a context of their own are the ones asserting on lazy import, so the
   shim must arrive through `import`, not before it."
  [shim]
  (ffi/initialize!)
  (let [s (str "fresh-" shim "-" (System/nanoTime))]
    (ffi/install-runtime! s)
    (ffi/exec! s "import vis_runtime")
    (ffi/eval-str s "vis_runtime.forget_shims()")
    s))
