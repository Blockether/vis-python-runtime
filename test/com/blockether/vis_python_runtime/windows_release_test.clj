(ns com.blockether.vis-python-runtime.windows-release-test
  "Release gates complement actual Windows execution in CI; no host OS is mocked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io PushbackReader]))

(defn- build-definitions
  []
  (with-open [reader (PushbackReader. (io/reader "build.clj"))]
    (into {}
          (keep (fn [form]
                  (when (and (seq? form) (= 'def (first form))) [(second form) (last form)])))
          (take-while #(not= ::eof %) (repeatedly #(read reader false ::eof))))))

(deftest windows-archive-contract-test
  (let [definitions (build-definitions)]
    (is (contains? (get definitions 'native-platforms) "windows-x64"))
    (is (contains? (get definitions 'worker-platforms) "windows-x64"))
    (is (= ["vispython.dll"] (get-in definitions ['native-libs "windows-x64"]))
        "Windows must not ship a pretend OS process enforcer"))
  (testing "existing Unix archives retain their native enforcers"
    (doseq [[platform libraries]
            (get (build-definitions) 'native-libs)

            :when (not= platform "windows-x64")]

      (is (some #(str/starts-with? % "libvisjail.") libraries)))))

(deftest windows-build-and-release-gates-test
  (doseq [path
          [".github/workflows/ci.yml" ".github/workflows/release.yml"]

          :let [workflow
                (slurp path)]]

    (is (str/includes? workflow "runs-on: windows-2022") path)
    (doseq [command ["./native/vispython/build.ps1" "clojure -T:build worker-image"
                     "./scripts/verify-platform-archive.ps1" "target/archive-check-windows-x64"
                     "./scripts/test-windows.ps1"]]
      (is (str/includes? workflow command) (str path ": " command)))
    (is (str/includes? workflow "clojure -X:docs") path)
    (is (str/includes? workflow "clojure -M:docs:test -d doc -n runtime-docs-test") path)
    (is (str/includes? workflow "target/vis-python-runtime-api-docs-*.zip") path)
    (is (str/includes? workflow "target/vis-python-runtime-*-javadoc.jar") path))
  (is (str/includes? (slurp ".github/workflows/release.yml") "needs: [native, windows, jar]")
      "publication must wait for Windows execution and API documentation"))

(deftest windows-build-inputs-are-pinned-test
  (doseq [[path name] [[".cpython-version" "CPYTHON_SHA256_WINDOWS_X64"]
                       [".uv-version" "UV_SHA256_WINDOWS_X64"]
                       [".graalvm-version" "GRAAL_SHA256_windows_x64"]]]
    (is (re-find (re-pattern (str "(?m)^" name "=\"?[0-9a-f]{64}\"?$")) (slurp path)) path)))

(deftest windows-test-diagnostics-gate-test
  (let [script (slurp "scripts/test-windows.ps1")]
    (is (= 2 (count (re-seq #"-M:test:test-diagnostics" script)))
        "Both the isolated native boundary and shared suites must retain the watchdog")
    (doseq [namespace ["com.blockether.vis-python-runtime.asyncio-test"
                       "com.blockether.vis-python-runtime.test-diagnostics-test"]]
      (is (str/includes? script namespace) namespace))
    (is (str/includes? (slurp "deps.edn") "com.blockether.vis-python-runtime.test-diagnostics"))))
