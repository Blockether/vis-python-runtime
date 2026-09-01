(ns com.blockether.vis-python-runtime-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- line-count [dir extension]
  (->> (file-seq (io/file dir))
       (filter #(str/ends-with? (.getName %) extension))
       (map #(count (remove str/blank? (str/split-lines (slurp %)))))
       (reduce + 0)))

;; The bridge is Java and this namespace is its skin: argument shapes, keyword
;; maps, EDN. Nothing else is a compiler error, so it is a test.
(deftest skin-test
  (let [java  (line-count "java" ".java")
        clj   (line-count "src" ".clj")
        forms (->> (str/split (slurp "src/com/blockether/vis_python_runtime.clj") #"\n(?=\()")
                   (filter #(str/starts-with? % "(defn"))
                   (map (juxt #(first (str/split-lines %))
                              #(count (remove str/blank? (str/split-lines %))))))]
    (testing "most of the runtime is Java"
      (is (<= 4.0 (/ (double java) clj))
          (str "java " java " lines against clojure " clj " - logic belongs in the bridge")))
    (testing "no function in the skin grows a body"
      ;; The bound counts the docstring too, which is where a skin's lines belong.
      (is (empty? (map first (filter #(< 22 (second %)) forms)))
          (str "too long: " (pr-str (map first (filter #(< 22 (second %)) forms))))))))
