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
    (is (= ["vispython.dll" "visjail.dll"] (get-in definitions ['native-libs "windows-x64"]))
        "Windows must ship the AppContainer enforcer alongside the interpreter"))
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
                     "clojure -T:build windows-jail-probe" "./scripts/verify-platform-archive.ps1"
                     "target/archive-check-windows-x64" "./scripts/test-windows.ps1"]]
      (is (str/includes? workflow command) (str path ": " command)))
    ;; CI 34870999620 had no downloadable Windows job log after cancellation.
    ;; Separate steps expose the active phase even when the runner stops reporting.
    (doseq [command ["./native/vispython/build.ps1" "clojure -T:build javac"
                     "clojure -T:build worker-image" "clojure -T:build windows-jail-probe"
                     "./scripts/test-windows.ps1"
                     "clojure -T:build platform-archive :platform windows-x64"
                     "./scripts/verify-platform-archive.ps1"]]
      (is (re-find (re-pattern (str "(?m)^      - name: [^\n]+\n        run: "
                                    (java.util.regex.Pattern/quote command)
                                    "$"))
                   workflow)
          (str path ": independently observable phase for " command)))
    (is (= 2 (count (re-seq #"\./scripts/test-windows\.ps1" workflow)))
        (str path ": execute before and after archive extraction"))
    (is
      (re-find
        #"(?s)\$env:VIS_PYTHON_NATIVE_PATH = \(Resolve-Path 'target/archive-check-windows-x64'\)\.Path\s+\./scripts/test-windows\.ps1"
        workflow)
      (str path ": rerun with the extracted native libraries"))
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
    (is (= 3 (count (re-seq #"-M:test:test-diagnostics" script)))
        "The OS jail, embedded native boundary and shared suites retain the watchdog")
    ;; ClojureTools treats a splatted -M option as a clojure.main filename.
    (is (re-find #"(?m)^\s*& clojure -M:test:test-diagnostics @testArguments\s*$" script)
        "Pass the CLI alias directly and splat only test-runner arguments")
    (doseq [namespace ["com.blockether.vis-python-runtime.asyncio-test"
                       "com.blockether.vis-python-runtime.test-diagnostics-test"
                       "com.blockether.vis-python-runtime.windows-jail-test"]]
      (is (str/includes? script namespace) namespace))
    (is (str/includes? (slurp "deps.edn") "com.blockether.vis-python-runtime.test-diagnostics"))))

(deftest windows-jail-packaging-and-probe-gate-test
  (let [native-script
        (slurp "native/vispython/build.ps1")

        test-script
        (slurp "scripts/test-windows.ps1")

        archive-script
        (slurp "scripts/verify-platform-archive.ps1")

        build
        (slurp "build.clj")]

    (is (str/includes? native-script "native/visjail/build.ps1"))
    (is (str/includes? native-script "-OutputDirectory $stage"))
    (is (< (str/index-of native-script "native/visjail/build.ps1")
           (str/index-of native-script "Remove-Item -LiteralPath $out"))
        "Build both libraries before replacing the published staging directory")
    (is (str/includes? archive-script "'visjail.dll'"))
    (doseq [text ["visjail.dll" "VIS_WINDOWS_JAIL_GUEST" "WindowsJailProbe"
                  "Invoke-JailProbe -Executable $launcher" "WaitForExit(180000)"
                  "$start.Environment['VIS_PYTHON_NATIVE_PATH'] = $runtime"]]
      (is (str/includes? test-script text) text))
    (doseq [text ["test/java" "target/test-classes" "target/windows-jail-probe.exe"
                  "com.blockether.vispython.WindowsJailProbe" "native/visjail/windows_probe.c"]]
      (is (str/includes? build text) text))
    (is (re-find #":javac-opts\s+\[[^\]]*\"-classpath\"\s+class-dir\b" build)
        "Probe compilation includes production classes; tools.build omits project paths")))
