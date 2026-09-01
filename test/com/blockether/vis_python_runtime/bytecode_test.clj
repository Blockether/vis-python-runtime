(ns com.blockether.vis-python-runtime.bytecode-test
  "Where compiled bytecode goes.

   CPython caches what it compiles beside the source, in `__pycache__`. For a
   VENDORED interpreter that is the wrong place three times over: the tree is
   shipped and hashed, it may be read-only, and the bytecode nearly doubles it
   on disk (measured on darwin-arm64: 11.8 MB of .pyc against 18.4 MB of stdlib
   source) while being invalid the moment the artifact moves. So the artifact
   ships none and the interpreter starts with a `pycache_prefix` under the
   user's own directory: the first run compiles what it imports, every run after
   that imports at cached speed, and the shipped tree never changes.

   Under confinement that prefix is writable - it is the interpreter's cache,
   not the guest's data. A refusal there would not break an import, because the
   import machinery swallows the PermissionError; it would silently pay the
   compile on every run, which is exactly the cost this file exists to prove is
   paid once."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness
             :refer [block confined-session temp-dir]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; A confined interpreter would refuse the next namespace's imports.
           (when harness/built? (runtime/confine! [] []))
           (harness/close-sessions!)))))

(harness/defbuilt-test shipped-tree-carries-no-bytecode-test
  (testing "the vendored interpreter ships no bytecode"
    (when-let [home (runtime/resolve-python-home)]
      (let [cached (->> (file-seq (io/file home))
                        (filter #(or (= "__pycache__" (.getName ^java.io.File %))
                                     (str/ends-with? (.getName ^java.io.File %) ".pyc")))
                        (map str)
                        (take 3))]
        (is (empty? cached)
            (str "the artifact carries bytecode it should compile on the host instead: " cached))))))

(harness/defbuilt-test prefix-is-in-force-test
  (let [{:keys [pycache-prefix]} (runtime/initialize!)
        session (harness/block-session)]
    (testing "the running interpreter caches where the host said, not beside the source"
      (is (= pycache-prefix (runtime/eval-str session "__import__('sys').pycache_prefix"))))
    (testing "and the default is the user's own directory"
      (when-not (System/getenv runtime/pycache-prefix-env)
        (is (str/ends-with? (str pycache-prefix) "/.vis/python/pycache"))))))

(harness/defbuilt-test cached-bytecode-lands-in-the-prefix-test
  (let [{:keys [pycache-prefix]} (runtime/initialize!)
        source-dir (temp-dir "vis-bytecode")
        module     (str "vis_bytecode_probe_" (System/nanoTime))
        session    (confined-session [source-dir] [])]
    (spit (io/file source-dir (str module ".py")) "VALUE = 42\n")
    (testing "a module a confined block imports is compiled and answered"
      (is (= "42" (str/trim (str (:stdout (block session
                                                 (str "import sys\n"
                                                      "sys.path.insert(0, " (pr-str source-dir) ")\n"
                                                      "import " module "\n"
                                                      "print(" module ".VALUE)"))))))))
    (testing "the cache went to the prefix, and no __pycache__ appeared beside the source"
      (is (not (.exists (io/file source-dir "__pycache__"))))
      (is (some #(str/includes? (str %) module)
                (file-seq (io/file pycache-prefix (subs source-dir 1))))))
    (testing "the cache is part of the policy in force, so the answer counts it"
      (is (= 1 (:write (runtime/confine! [source-dir] [])))))))
