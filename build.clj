(ns build
  "Build/package tasks for vis-python-runtime. The small JVM API jar and every
   complete per-platform runtime are GitHub Release assets; nothing is published
   to Clojars or another Maven repository.

   The vendored CPython tree cannot live in a jar: it is tens of megabytes and
   needs symlinks and executable bits. `platform-archive` therefore writes
   `target/vis-python-runtime-<platform>-<version>.tar.gz`; a consumer downloads
   one release asset, unpacks it and calls `runtime/use-library!`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'com.blockether/vis-python-runtime)

(def native-platforms #{"linux-x64" "linux-arm64" "darwin-arm64" "darwin-x64" "windows-x64"})

(def native-libs
  {"linux-x64" ["libvispython.so" "libvisjail.so"]
   "linux-arm64" ["libvispython.so" "libvisjail.so"]
   "darwin-arm64" ["libvispython.dylib" "libvisjail.dylib"]
   "darwin-x64" ["libvispython.dylib" "libvisjail.dylib"]
   "windows-x64" ["vispython.dll" "visjail.dll"]})

(def worker-platforms
  "Platforms whose archive carries `vis-python-worker`, the interpreter worker
   compiled to a native image (`worker-image`). GraalVM CE publishes no
   darwin-x64 build, so that archive ships without one and a host there runs
   `com.blockether.vispython.Worker` on its own JVM instead."
  #{"linux-x64" "linux-arm64" "darwin-arm64" "windows-x64"})

(defn- worker-executable
  [platform]
  (if (= platform "windows-x64") "vis-python-worker.exe" "vis-python-worker"))

(defn- uv-executable
  [platform]
  (if (= platform "windows-x64") "python/Scripts/uv.exe" "python/bin/uv"))

(def version
  "The repo-root VIS_PYTHON_VERSION file, verbatim — the single version source,
   exactly as VIS_VERSION is for vis. No env override, no snapshot suffix, no git
   sha: which build produced an artifact is the image tag's job, never this string."
  (str/trim (slurp "VIS_PYTHON_VERSION")))

(def class-dir "target/classes")

;; The jar is assembled somewhere ELSE than `target/classes`, which `:deps/prep-lib`
;; owns: a consumer taking this library by :local/root has both on its classpath,
;; and a packaged copy of the Python sitting in the prep output would be resolved
;; in preference to `resources/` — a checkout would then run yesterday's files.
(def jar-file (format "target/%s.jar" (name lib)))

(def source-roots
  "Resource directories carrying the Python the runtime executes. They ship in
   the main jar under the SAME names a checkout has them on the classpath, so
   `Sources` resolves one layout and never two — and `SOURCES` beside them names
   every file, because a jar can be walked and a native image cannot."
  ["vis-python"])

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn- write-version-resource!
  [target-dir]
  (let [vfile (io/file target-dir "vis-python-runtime" "VERSION")]
    (io/make-parents vfile)
    (spit vfile version)))

(defn javac
  "Compile the Java bridge into `target/classes`.

   The bridge is JAVA, and the reason is the native image: every downcall is an
   `invokeExact` against a signature the compiler knows, and the host upcall's
   target is a static method found by name — neither is a reflective call the
   image would have to be told about. `:deps/prep-lib` in `deps.edn` names this
   function, so a consumer taking this library as a git dependency runs it with
   `clojure -X:deps prep` and never sees a source tree it cannot use."
  [_]
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            ;; The FFM calls are restricted by design and the runtime opts in
            ;; with `--enable-native-access`, so that warning is noise here.
            :javac-opts ["--release" "22" "-Xlint:all,-restricted"]})
  (write-version-resource! class-dir))

(defn- pom-data
  [description]
  [[:description description] [:url "https://github.com/Blockether/vis-python-runtime"]
   [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
   [:scm [:url "https://github.com/Blockether/vis-python-runtime"]
    [:connection "scm:git:https://github.com/Blockether/vis-python-runtime.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Blockether/vis-python-runtime.git"]]])

(def jar-class-dir "target/jar-classes")

(defn jar
  [_]
  (clean nil)
  (javac nil)
  (b/copy-dir {:src-dirs [class-dir] :target-dir jar-class-dir})
  (b/write-pom
    {:class-dir jar-class-dir
     :lib lib
     :version version
     :basis @basis
     :src-dirs ["src/clj"]
     :pom-data
     (pom-data
       "Embedded CPython for the Vis sandbox, vendored per platform and reached over FFM.")})
  ;; No prebuilds: the cdylib belongs to the per-platform native archives. The
  ;; Python DOES ship here — a consumer that took a jar has no `resources/`
  ;; directory to point `sys.path` at — and so does the namespaced VERSION,
  ;; because a bare `VERSION` resource would collide with another library's on a
  ;; shared classpath. SOURCES is tracked under resources so a git dependency and
  ;; a native-image see the same self-describing artifact before any jar exists.
  (b/copy-dir {:src-dirs ["src/clj"] :target-dir jar-class-dir})
  (doseq [root source-roots]
    ;; SOURCE only: `__pycache__` is per-machine bytecode compiled against one
    ;; interpreter and one absolute path, and a shipped copy of it is either
    ;; ignored or wrong.
    (b/copy-dir {:src-dirs [(str "resources/" root)]
                 :target-dir (str jar-class-dir "/" root)
                 :ignores [#".*__pycache__.*" #".*\.pyc$"]}))
  ;; The FFM registrations for our downcalls and the host upcall. They travel
  ;; INSIDE the jar because a consumer's native-image build reads
  ;; META-INF/native-image/<group>/<artifact>/ from the classpath by itself —
  ;; a flag in their build.clj is a contract we cannot keep for them.
  (b/copy-dir {:src-dirs ["resources/META-INF"] :target-dir (str jar-class-dir "/META-INF")})
  (b/copy-dir {:src-dirs ["resources/vis-python-runtime"]
               :target-dir (str jar-class-dir "/vis-python-runtime")})
  (write-version-resource! jar-class-dir)
  (b/jar {:class-dir jar-class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn- host-platform
  "This machine's `<os>-<arch>` tag, spelled the way `Native/platform` spells it —
   restated here because build.clj runs before `target/classes` exists."
  []
  (let [os
        (str/lower-case (System/getProperty "os.name"))

        arch
        (str/lower-case (System/getProperty "os.arch"))]

    (str (cond (str/includes? os "mac") "darwin"
               (str/includes? os "linux") "linux"
               (str/includes? os "windows") "windows"
               :else (throw (ex-info (str "Unsupported operating system: " os) {:os os})))
         "-"
         (case arch
           ("aarch64" "arm64")
           "arm64"

           ("x86_64" "amd64" "x64")
           "x64"

           (throw (ex-info (str "Unsupported architecture: " arch) {:arch arch}))))))

(def ^:private graal-pin
  "`.graalvm-version`, parsed: plain KEY=\"value\" lines, the same file the CI
   action sources. GRAAL_VERSION is what the launcher below must report."
  (delay (into {}
               (keep (fn [line]
                       (when-let [[_ k v] (re-matches #"\s*([A-Z0-9_]+)=\"?([^\"]*)\"?\s*" line)]
                         [k (str/trim v)])))
               (str/split-lines (slurp ".graalvm-version")))))

(defn- native-image-launcher
  "`bin/native-image` under GRAALVM_HOME, else JAVA_HOME, else the bare name on
   PATH — refused unless it reports the pinned GraalVM version, because the
   worker's FFM registrations are written against exactly that release."
  []
  (let [home
        (or (System/getenv "GRAALVM_HOME") (System/getenv "JAVA_HOME"))

        launcher
        (when home
          (io/file home
                   "bin"
                   (if (= (host-platform) "windows-x64") "native-image.cmd" "native-image")))

        command
        (if (and launcher (.isFile launcher))
          (.getAbsolutePath launcher)
          (if (= (host-platform) "windows-x64") "native-image.cmd" "native-image"))

        want
        (get @graal-pin "GRAAL_VERSION")

        {:keys [exit out]}
        (try (b/process {:command-args [command "--version"] :out :capture})
             (catch Exception e {:exit -1 :out (str e)}))]

    (when-not (and (zero? exit) (str/includes? (str out) want))
      (throw (ex-info (str "worker-image needs GraalVM CE " want
                           " (see .graalvm-version); " command
                           " answered: " (str/trim (str out)))
                      {:command command :want want :exit exit})))
    command))

(defn worker-image
  "Compile `com.blockether.vispython.Worker` into
   `resources/prebuilds/<platform>/vis-python-worker`, beside the cdylib it
   loads — where `Locations/worker` finds it and where `platform-archive` ships
   it. The classpath is the compiled bridge plus `resources/`: the FFM
   registrations and the resource globs the image needs ride in
   `META-INF/native-image/…` there, so this passes no reachability flag of its
   own. `-march=compatibility` because one archive serves every CPU of its
   platform; the 16 MiB stack because a CPython call runs on the very thread
   that made the downcall."
  [{:keys [platform]}]
  (let [platform (or (some-> platform
                             name)
                     (host-platform))]
    (when-not (worker-platforms platform)
      (throw (ex-info (str "no worker image for " platform " — GraalVM CE has no build there")
                      {:platform platform :known worker-platforms})))
    (javac nil)
    (let [out (io/file "resources/prebuilds" platform (worker-executable platform))
          args [(native-image-launcher) "-cp"
                (str/join java.io.File/pathSeparator [class-dir "resources"])
                "--enable-native-access=ALL-UNNAMED" "-H:+UnlockExperimentalVMOptions"
                "-R:StackSize=16777216" "-H:+ReportExceptionStackTraces" "-march=compatibility"
                "-Os" "-o" (.getPath out) "com.blockether.vispython.Worker"]]

      (io/make-parents out)
      (b/delete {:path (.getPath out)})
      (let [{:keys [exit]} (b/process {:command-args args})]
        (when-not (zero? exit)
          (throw (ex-info "native-image failed" {:platform platform :exit exit}))))
      (when-not (.isFile out)
        (throw (ex-info "native-image reported success but wrote nothing" {:path (.getPath out)})))
      (println "Built:" (.getPath out) (format "(%.1f MB)" (/ (.length out) 1048576.0)))
      (.getPath out))))

(defn windows-jail-probe
  "Build the test-only Windows JVM/native-image jail launcher. Its classpath uses
   the runtime's shipped FFM registrations, not extra reachability flags. The
   executable stays in target/, outside every published platform archive."
  [_]
  (when-not (= (host-platform) "windows-x64")
    (throw (ex-info "The Windows jail probe must be built on Windows x64" {})))
  (javac nil)
  (let [test-classes
        "target/test-classes"

        out
        (io/file "target/windows-jail-probe.exe")

        guest
        (io/file "target/windows-jail-guest.exe")]

    (b/delete {:path (.getPath guest)})
    (let [{:keys [exit]}
          (b/process {:command-args ["cl.exe" "/nologo" "/std:c11" "/W4" "/WX" "/O2" "/MT"
                                     "/Fotarget/windows-jail-guest.obj" (str "/Fe" (.getPath guest))
                                     "native/visjail/windows_probe.c" "/link" "advapi32.lib"
                                     "ws2_32.lib" "userenv.lib" "ole32.lib" "onecoreuap.lib"]})]
      (when-not (zero? exit)
        (throw (ex-info "Windows jail guest probe compilation failed" {:exit exit}))))
    (when-not (.isFile guest)
      (throw (ex-info "Windows jail guest build wrote no executable" {:path (.getPath guest)})))
    ;; tools.build omits the project's compiled classes from this classpath.
    (b/javac {:src-dirs ["test/java"]
              :class-dir test-classes
              :javac-opts ["--release" "22" "-Xlint:all,-restricted" "-classpath" class-dir]})
    (b/delete {:path (.getPath out)})
    (let [{:keys [exit]}
          (b/process {:command-args
                      [(native-image-launcher) "-cp"
                       (str/join java.io.File/pathSeparator [test-classes class-dir "resources"])
                       "--enable-native-access=ALL-UNNAMED" "-H:+UnlockExperimentalVMOptions"
                       "-R:StackSize=16777216" "-H:+ReportExceptionStackTraces"
                       "-march=compatibility" "-Os" "-o" (.getPath out)
                       "com.blockether.vispython.WindowsJailProbe"]})]
      (when-not (zero? exit)
        (throw (ex-info "Windows jail probe native-image failed" {:exit exit}))))
    (when-not (.isFile out)
      (throw (ex-info "Windows jail probe build wrote no executable" {:path (.getPath out)})))
    (println "Built:" (.getPath out))
    (.getPath out)))

(defn platform-archive
  "Write the release asset for one platform: everything under
   `resources/prebuilds/<platform>/` — the embedding library, vendored interpreter
   and native worker where available — as a gzipped tar. Every platform includes
   its process-jail library; Windows uses the explicit WindowsJail workspace API.
   Contents sit at the archive root, where Locations resolves them. tar preserves
   Unix symlinks and executable bits."
  [{:keys [platform]}]
  (let [platform (some-> platform
                         name)]
    (when-not (native-platforms platform)
      (throw (ex-info (str "Unknown native platform: " platform)
                      {:platform platform :known native-platforms})))
    (let [dir (io/file "resources/prebuilds" platform)
          libs (mapv #(io/file dir %) (native-libs platform))
          worker (io/file dir (worker-executable platform))
          out (io/file (format "target/%s-%s-%s.tar.gz" (name lib) platform version))]

      (doseq [src libs]
        (when-not (.isFile src)
          (throw (ex-info (str "runtime cdylib not found (build native/vispython first): " src)
                          {:platform platform :path (str src)}))))
      (when (and (worker-platforms platform) (not (.canExecute worker)))
        (throw (ex-info (str "worker image not found (run `clojure -T:build worker-image` first): "
                             worker)
                        {:platform platform :path (str worker)})))
      (when-not (.canExecute (io/file dir (uv-executable platform)))
        (throw (ex-info
                 "Bundled uv not found (run the platform native/vispython build script first)"
                 {:platform platform})))
      (doseq [license ["uv-LICENSE-APACHE" "uv-LICENSE-MIT"]]
        (when-not (.isFile (io/file dir "licenses" license))
          (throw (ex-info "Bundled uv license not found" {:platform platform :license license}))))
      (b/delete {:path (str out)})
      (io/make-parents out)
      ;; SOURCE only, as in the jar: `__pycache__` is bytecode compiled against
      ;; one absolute path on one machine, and a shipped copy of it is either
      ;; ignored or wrong.
      (let [{:keys [exit]} (b/process {:command-args ["tar" "-czf" (.getAbsolutePath out)
                                                      "--exclude" "__pycache__" "--exclude" "*.pyc"
                                                      "-C" (str dir) "."]})]
        (when-not (zero? exit) (throw (ex-info "tar failed" {:platform platform :exit exit}))))
      (println "Built:" (str out) (format "(%.1f MB)" (/ (.length out) 1048576.0)))
      (str out))))
