(ns build
  "Build/deploy for vis-python-runtime. The `com.blockether/vis-python-runtime`
   jar is small — one namespace plus a namespaced VERSION. The embedded CPython
   cdylib ships as per-platform artifacts such as
   `com.blockether/vis-python-runtime-native-darwin-arm64`, each carrying the
   prebuilt library under `prebuilds/<platform>/`.

   NOT DONE, and Phase 5 of PLAN.md owns it: `native-jar` copies the cdylib
   ALONE, while `build.sh` now vendors a whole CPython tree beside it. A jar
   cannot carry that tree faithfully — it holds no symlinks and no permission
   bits — so the platform artifact needs an archive resource extracted once,
   the way the cdylib itself is extracted today. Until that lands, a published
   native jar resolves no vendored interpreter and the interpreter falls back
   to the machine's own standard library."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/vis-python-runtime)
(def native-platforms #{"linux-x64" "linux-arm64" "darwin-arm64" "darwin-x64" "windows-x64"})
(def native-libs {"linux-x64"    "libvispython.so"
                  "linux-arm64"  "libvispython.so"
                  "darwin-arm64" "libvispython.dylib"
                  "darwin-x64"   "libvispython.dylib"
                  "windows-x64"  "vispython.dll"})

(def version
  "The repo-root VIS_PYTHON_VERSION file, verbatim — the single version source,
   exactly as VIS_VERSION is for vis. No env override, no snapshot suffix, no git
   sha: which build produced an artifact is the image tag's job, never this string."
  (str/trim (slurp "VIS_PYTHON_VERSION")))

(def class-dir "target/classes")
(def native-class-dir "target/native-classes")
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
  ["vispython" "vis-python"])
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn javac
  "Compile the Java bridge into `target/classes`.

   The bridge is JAVA, and the reason is the native image: every downcall is an
   `invokeExact` against a signature the compiler knows, and the host upcall's
   target is a static method found by name — neither is a reflective call the
   image would have to be told about. `:deps/prep-lib` in `deps.edn` names this
   function, so a consumer taking this library as a git dependency runs it with
   `clojure -X:deps prep` and never sees a source tree it cannot use."
  [_]
  (b/javac {:src-dirs ["java"]
            :class-dir class-dir
            :basis @basis
            ;; The FFM calls are restricted by design and the runtime opts in
            ;; with `--enable-native-access`, so that warning is noise here.
            :javac-opts ["--release" "22" "-Xlint:all,-restricted"]}))

(defn- pom-data [description]
  [[:description description]
   [:url "https://github.com/Blockether/vis-python-runtime"]
   [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
   [:scm [:url "https://github.com/Blockether/vis-python-runtime"]
    [:connection "scm:git:https://github.com/Blockether/vis-python-runtime.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Blockether/vis-python-runtime.git"]]])

(def jar-class-dir "target/jar-classes")

(defn jar [_]
  (clean nil)
  (javac nil)
  (b/copy-dir {:src-dirs [class-dir] :target-dir jar-class-dir})
  (b/write-pom {:class-dir jar-class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data (pom-data "Embedded CPython for the Vis sandbox — vendored per platform, reached over FFM, without Truffle.")})
  ;; No prebuilds: the cdylib belongs to the per-platform native jars. The
  ;; Python DOES ship here — a consumer that took a jar has no `resources/`
  ;; directory to point `sys.path` at — and so does the namespaced VERSION,
  ;; because a bare `VERSION` resource would collide with another library's on a
  ;; shared classpath.
  (b/copy-dir {:src-dirs ["src"] :target-dir jar-class-dir})
  (doseq [root source-roots]
    ;; SOURCE only: `__pycache__` is per-machine bytecode compiled against one
    ;; interpreter and one absolute path, and a shipped copy of it is either
    ;; ignored or wrong.
    (b/copy-dir {:src-dirs   [(str "resources/" root)]
                 :target-dir (str jar-class-dir "/" root)
                 :ignores    [#".*__pycache__.*" #".*\.pyc$"]}))
  ;; The FFM registrations for our downcalls and the host upcall. They travel
  ;; INSIDE the jar because a consumer's native-image build reads
  ;; META-INF/native-image/<group>/<artifact>/ from the classpath by itself —
  ;; a flag in their build.clj is a contract we cannot keep for them.
  (b/copy-dir {:src-dirs ["resources/META-INF"] :target-dir (str jar-class-dir "/META-INF")})
  (let [vfile  (io/file jar-class-dir "vis-python-runtime" "VERSION")
        listed (->> source-roots
                    (mapcat (fn [root]
                              (let [dir (io/file jar-class-dir root)]
                                (->> (file-seq dir)
                                     (filter #(and (.isFile ^java.io.File %)
                                                   (str/ends-with? (.getName ^java.io.File %) ".py")))
                                     (map #(str root "/" (.relativize (.toPath dir) (.toPath ^java.io.File %))))))))
                    sort)]
    (io/make-parents vfile)
    (spit vfile version)
    (spit (io/file jar-class-dir "vis-python-runtime" "SOURCES") (str (str/join "\n" listed) "\n")))
  (b/jar {:class-dir jar-class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn- native-lib [platform]
  (symbol "com.blockether" (str "vis-python-runtime-native-" platform)))

(defn native-jar [{:keys [platform]}]
  (let [platform (some-> platform name)]
    (when-not (native-platforms platform)
      (throw (ex-info (str "Unknown native platform: " platform) {:platform platform :known native-platforms})))
    (let [fname (native-libs platform)
          src   (format "resources/prebuilds/%s/%s" platform fname)
          lib*  (native-lib platform)
          jar*  (format "target/%s.jar" (name lib*))]
      (b/delete {:path native-class-dir})
      (b/delete {:path jar*})
      (when-not (.exists (io/file src))
        (throw (ex-info (str "runtime cdylib not found (build native/vispython first): " src)
                        {:platform platform :path src})))
      (b/write-pom {:class-dir native-class-dir
                    :lib lib*
                    :version version
                    :basis @basis
                    :src-dirs []
                    :pom-data (pom-data (format "Prebuilt embedded-CPython cdylib (FFM) for %s." platform))})
      ;; The WHOLE platform directory: the cdylib plus the vendored interpreter
      ;; tree beside it, because `Locations/pythonHome` resolves `python/` next
      ;; to the resolved library and a shipped artifact carries its own standard
      ;; library rather than borrowing the machine's.
      (b/copy-dir {:src-dirs   [(format "resources/prebuilds/%s" platform)]
                   :target-dir (format "%s/prebuilds/%s" native-class-dir platform)})
      (b/jar {:class-dir native-class-dir :jar-file jar*})
      (println "Built:" jar* "version:" version)
      jar*)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir jar-class-dir})}))

(defn deploy-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar*     (native-jar {:platform platform})
        lib*     (native-lib platform)]
    (dd/deploy {:installer :remote :artifact jar* :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir jar-class-dir})}))

(defn install-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar*     (native-jar {:platform platform})
        lib*     (native-lib platform)]
    (dd/deploy {:installer :local :artifact jar* :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))
