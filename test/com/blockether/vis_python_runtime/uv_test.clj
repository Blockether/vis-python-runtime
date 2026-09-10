(ns com.blockether.vis-python-runtime.uv-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.blockether.vis-python-runtime :as runtime]))

(deftest bundled-uv-runs-without-path-test
  ;; Vis #183: uv is part of the platform artifact, not an operator prerequisite.
  (let [home
        (runtime/resolve-python-home)

        uv
        (io/file home "bin/uv")]

    (is (some? home))
    (is (.canExecute uv) "The shipped Python tree must carry an executable uv")
    (is (= (.getAbsolutePath uv) (runtime/uv-executable)))
    (when (.canExecute uv)
      (let [builder (ProcessBuilder. ^java.util.List [(str uv) "--version"])]
        (.clear (.environment builder))
        (.put (.environment builder) "PATH" "/nonexistent")
        (let [process (.start (.redirectErrorStream builder true))
              out (slurp (.getInputStream process))]

          (is (= 0 (.waitFor process)))
          (is (= (second (re-find #"(?m)^UV_VERSION=(.+)$" (slurp ".uv-version")))
                 (second (re-find #"^uv ([^\s]+)" out)))))))))
