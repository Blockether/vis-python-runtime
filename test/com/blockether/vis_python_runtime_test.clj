(ns com.blockether.vis-python-runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython VisPythonException]))

(deftest version-test
  (is (re-matches #"\d+\.\d+\.\d+" (str/trim (slurp "VIS_PYTHON_VERSION")))
      "repo-root VIS_PYTHON_VERSION is the single version source and is semver-shaped")
  (is (contains? #{"dev"} runtime/version)
      "a source checkout has no built VERSION resource, so the version reads dev"))

(deftest platform-test
  (testing "os and architecture spellings we actually meet"
    (is (= "darwin-arm64" (runtime/platform "Mac OS X" "aarch64")))
    (is (= "darwin-x64" (runtime/platform "Mac OS X" "x86_64")))
    (is (= "linux-arm64" (runtime/platform "Linux" "arm64")))
    (is (= "linux-x64" (runtime/platform "Linux" "amd64")))
    (is (= "windows-x64" (runtime/platform "Windows 11" "amd64"))))
  (testing "an unbuilt target is a refusal, never a guessed tag"
    (is (thrown? VisPythonException (runtime/platform "SunOS" "amd64")))
    (is (thrown? VisPythonException (runtime/platform "Linux" "riscv64"))))
  (testing "the running JVM resolves to something we build for"
    (is (contains? #{"darwin-arm64" "darwin-x64" "linux-arm64" "linux-x64" "windows-x64"}
                   (runtime/platform)))))

(deftest library-name-test
  (is (= "libvispython.dylib" (runtime/library-name "darwin-arm64")))
  (is (= "libvispython.so" (runtime/library-name "linux-x64")))
  (is (= "vispython.dll" (runtime/library-name "windows-x64")))
  (is (thrown? VisPythonException (runtime/library-name "plan9-arm64"))))

(deftest resolve-library-missing-test
  (testing "no override and no prebuilt artifact names both ways to supply one"
    (when-not (System/getenv runtime/native-path-env)
      (let [data (try (runtime/resolve-library "linux-riscv") nil
                      (catch VisPythonException e (.data e)))]
        (is (= "linux-riscv" (get data "platform")))
        (is (= runtime/native-path-env (get data "env")))
        (is (str/starts-with? (get data "resource") "prebuilds/linux-riscv/"))))))
