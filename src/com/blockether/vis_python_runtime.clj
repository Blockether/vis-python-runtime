(ns com.blockether.vis-python-runtime
  "Embedded CPython for the Vis sandbox: the JVM half.

   Vis runs sandbox Python — `packages/vis-agent` plus every shim in
   `resources/vis-shims/` — inside GraalPy today, which costs roughly 300 MB in
   the native image. This library replaces that engine with a VENDORED CPython
   reached through the JDK Foreign Function & Memory API over a first-party C
   ABI (`native/vis-python`), so the image carries a cdylib and an interpreter
   tree beside it instead of a Truffle language inside it.

   Nothing here links at build time. The library is resolved when it is first
   needed: `VIS_PYTHON_NATIVE_PATH` wins (a file, or a directory holding the
   platform's file), otherwise the classpath resource
   `prebuilds/<platform>/<file>` that the per-platform artifact
   `com.blockether/vis-python-runtime-native-<platform>` carries."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def native-path-env
  "Name of the environment variable that overrides library resolution."
  "VIS_PYTHON_NATIVE_PATH")

(def version
  "This library's version: the `vis-python-runtime/VERSION` resource the build
   writes from the repo-root VIS_PYTHON_VERSION file, verbatim, else \"dev\" in a
   source checkout where no build has run."
  (or (some-> (io/resource "vis-python-runtime/VERSION") slurp str/trim)
      "dev"))

(defn- os-tag [^String os-name]
  (let [n (str/lower-case (or os-name ""))]
    (cond
      (or (str/includes? n "mac") (str/includes? n "darwin")) "darwin"
      (str/includes? n "linux")                               "linux"
      (str/includes? n "windows")                             "windows"
      :else (throw (ex-info (str "Unsupported operating system: " os-name)
                            {:os-name os-name})))))

(defn- arch-tag [^String os-arch]
  (let [a (str/lower-case (or os-arch ""))]
    (cond
      (#{"aarch64" "arm64"} a)          "arm64"
      (#{"x86_64" "amd64" "x64"} a)     "x64"
      :else (throw (ex-info (str "Unsupported architecture: " os-arch)
                            {:os-arch os-arch})))))

(defn platform
  "The platform tag prebuilt artifacts are named by, `<os>-<arch>`, e.g.
   `darwin-arm64`. Throws `ex-info` for an OS or architecture we do not build."
  ([] (platform (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name os-arch] (str (os-tag os-name) "-" (arch-tag os-arch))))

(def ^:private library-names
  {"darwin"  "libvispython.dylib"
   "linux"   "libvispython.so"
   "windows" "vispython.dll"})

(defn library-name
  "The cdylib file name for a platform tag."
  ([] (library-name (platform)))
  ([platform-tag]
   (let [os (first (str/split platform-tag #"-"))]
     (or (library-names os)
         (throw (ex-info (str "Unknown platform tag: " platform-tag)
                         {:platform platform-tag}))))))

(defn- env-library [platform-tag file-name]
  (when-let [raw (some-> (System/getenv native-path-env) str/trim not-empty)]
    (let [f (io/file raw)
          f (if (.isDirectory f) (io/file f file-name) f)]
      (when-not (.isFile f)
        (throw (ex-info (str native-path-env " is set but holds no runtime library: " (.getPath f))
                        {:env native-path-env :path (.getPath f) :platform platform-tag})))
      {:source :env :path (.getAbsolutePath f)})))

(defn- resource-library [platform-tag file-name]
  (when-let [url (io/resource (str "prebuilds/" platform-tag "/" file-name))]
    (if (= "file" (.getProtocol url))
      {:source :resource :path (.getAbsolutePath (io/file (.toURI url)))}
      ;; Inside a jar: FFM needs a filesystem path, so extract once per version.
      (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                               (str "vis-python-runtime-" version))
                  (.mkdirs))
            out (io/file dir file-name)]
        (with-open [in (.openStream url)]
          (io/copy in out))
        {:source :resource :path (.getAbsolutePath out)}))))

(defn resolve-library
  "Where the runtime cdylib is, as `{:source :env|:resource :path \"…\"}`.
   Checks `VIS_PYTHON_NATIVE_PATH` first, then the bundled classpath resource;
   throws `ex-info` naming both when neither answers."
  ([] (resolve-library (platform)))
  ([platform-tag]
   (let [file-name (library-name platform-tag)]
     (or (env-library platform-tag file-name)
         (resource-library platform-tag file-name)
         (throw (ex-info (str "No vis-python runtime library for " platform-tag
                              " — set " native-path-env " or add the "
                              "com.blockether/vis-python-runtime-native-" platform-tag
                              " artifact to the classpath.")
                         {:platform platform-tag
                          :file file-name
                          :env native-path-env
                          :resource (str "prebuilds/" platform-tag "/" file-name)}))))))

(def python-home-env
  "Name of the environment variable that overrides the vendored interpreter."
  "VIS_PYTHON_HOME")

(defn resolve-python-home
  "The vendored CPython tree this library should start, as an absolute path, or
   nil to let CPython search for itself.

   `VIS_PYTHON_HOME` wins. Otherwise the tree is the `python/` directory BESIDE
   the resolved cdylib, which is how a shipped platform artifact is laid out:
   the library and the interpreter it was linked against travel together, so an
   installation carries its own standard library instead of borrowing whatever
   Python the machine has. A source checkout that built against a system
   interpreter has no such directory, and gets nil."
  ([] (resolve-python-home (resolve-library)))
  ([{:keys [path]}]
   (or (some-> (System/getenv python-home-env) str/trim not-empty)
       (let [vendored (io/file (.getParentFile (io/file ^String path)) "python")]
         (when (.isDirectory vendored)
           (.getAbsolutePath vendored))))))
