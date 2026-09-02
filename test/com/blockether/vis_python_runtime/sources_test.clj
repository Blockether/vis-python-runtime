(ns com.blockether.vis-python-runtime.sources-test
  "Where a packaged artifact's Python comes from.

   The runtime resolves its sources the way it resolves the cdylib: a checkout
   uses the files where they lie, anything packaged is extracted once per
   version. Both shapes are built here out of a temporary directory and a
   temporary jar, so neither needs a published artifact to be proven."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [com.blockether.vispython Sources]
           [java.net URL URLClassLoader]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:private listed
  ["vis-python/vis_runtime.py" "vis-python/auto_imports.py"])

(defn- temp-dir [prefix]
  (.toAbsolutePath (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write! [^Path root entry text]
  (let [file (io/file (str root) entry)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- manifest [] (str (str/join "\n" listed) "\n"))

(deftest tracked-source-manifest-covers-every-shipped-module-test
  (testing "a git dependency is self-describing before a jar is built"
    (let [resources (io/file "resources")
          expected (->> ["vis-python"]
                        (mapcat #(file-seq (io/file resources %)))
                        (filter #(and (.isFile ^java.io.File %)
                                      (str/ends-with? (.getName ^java.io.File %) ".py")))
                        (map #(-> (str (.relativize (.toPath resources) (.toPath ^java.io.File %)))
                                  (str/replace java.io.File/separator "/")))
                        sort
                        vec)
          actual (->> (slurp (io/file resources Sources/MANIFEST))
                      str/split-lines
                      (remove str/blank?)
                      vec)]
      (is (= expected actual)
          "resources/vis-python-runtime/SOURCES must change with the shipped Python tree"))))

(defn- exploded
  "A classpath DIRECTORY holding the manifest and every file it names."
  []
  (let [root (temp-dir "vis-sources-dir")]
    (write! root Sources/MANIFEST (manifest))
    (doseq [entry listed] (write! root entry (str "# " entry)))
    root))

(defn- jarred
  "A classpath JAR holding the manifest and every file it names."
  []
  (let [root (temp-dir "vis-sources-jar")
        file (io/file (str root) "sources.jar")]
    (with-open [out (JarOutputStream. (io/output-stream file))]
      (doseq [[entry text] (cons [Sources/MANIFEST (manifest)]
                                 (map (fn [e] [e (str "# " e)]) listed))]
        (.putNextEntry out (JarEntry. entry))
        (.write out (.getBytes ^String text "UTF-8"))
        (.closeEntry out)))
    file))

(defn- loader
  "A classloader over one classpath entry, isolated from this JVM's own."
  [thing]
  (URLClassLoader. (into-array URL [(.toURL (.toURI (io/file (str thing))))])
                   (ClassLoader/getPlatformClassLoader)))

(deftest exploded-classpath-is-used-in-place-test
  (testing "a directory on the classpath is imported from where it lies"
    (let [root  (exploded)
          cache (temp-dir "vis-sources-cache")
          roots (Sources/roots (loader (str root "/")) cache)]
      (is (= [(str root "/vis-python")] roots))
      (is (empty? (seq (.listFiles (io/file (str cache)))))
          "nothing is copied when the files are already files"))))

(deftest jarred-classpath-is-extracted-once-test
  (testing "a jar is extracted under the cache, and the second start reuses it"
    (let [jar   (jarred)
          cache (temp-dir "vis-sources-cache")
          roots (Sources/roots (loader jar) cache)]
      (is (= [(str cache "/vis-python")] roots))
      (is (= "# vis-python/auto_imports.py" (slurp (io/file (str cache) "vis-python/auto_imports.py"))))
      (testing "the marker, not a re-read, is what makes the second call free"
        (spit (io/file (str cache) "vis-python/auto_imports.py") "# edited")
        (is (= roots (Sources/roots (loader jar) cache)))
        (is (= "# edited" (slurp (io/file (str cache) "vis-python/auto_imports.py"))))))))

(deftest no-manifest-answers-nothing-test
  (testing "an artifact that ships no Python contributes no import directory"
    (is (empty? (Sources/roots (loader (temp-dir "vis-sources-empty")) (temp-dir "vis-cache"))))))

(deftest without-the-manifest-nothing-is-claimed-test
  (testing "a classpath entry that ships no manifest contributes no root, however it is shaped"
    ;; The manifest is TRACKED, so every real shape carries it - a checkout, a
    ;; git dependency, a :local/root, a jar, an image. Guessing at directory
    ;; names for a shape that does not exist is how a host's own
    ;; `resources/vis-python/` gets imported in place of ours.
    (let [root (temp-dir "vis-sources-nomanifest")]
      (doseq [entry listed] (write! root entry (str "# " entry)))
      (is (empty? (Sources/roots (loader (str root "/")) (temp-dir "vis-cache")))))))

;; The host embedding this library carries a directory of the same name, and
;; its own resources come FIRST on the classpath.
(deftest a-hosts-own-directories-are-not-mistaken-for-ours-test
  (testing "the roots are taken from the directory holding the manifest, not from the name"
    ;; The host's copy comes FIRST on the classpath and is named identically, so
    ;; a lookup by name answers with it. The manifest is what only this library
    ;; ships, and every entry is addressed relative to it.
    (let [host (temp-dir "vis-sources-host")
          ours (temp-dir "vis-sources-ours")]
      (write! host "vis-python/auto_imports.py" "# the host's own copy")
      (write! ours Sources/MANIFEST (manifest))
      (doseq [entry listed] (write! ours entry (str "# " entry)))
      (let [urls  (into-array URL (map #(.toURL (.toURI (io/file (str % "/"))))
                                       [host ours]))
            found (Sources/roots (URLClassLoader. urls (ClassLoader/getPlatformClassLoader))
                                 (temp-dir "vis-cache"))]
        (is (= [(str ours "/vis-python")] found))))))

;; The same shadowing hazard as above, but for a PACKAGED artifact: the entries
;; are addressed from the manifest, so a host directory earlier on the classpath
;; cannot supply them.
(deftest a-jar-is-read-beside-its-own-manifest-test
  (testing "a host's same-named files are not extracted in place of ours"
    (let [host  (temp-dir "vis-sources-host-jar")
          jar   (jarred)
          cache (temp-dir "vis-cache")]
      (let [urls (into-array URL [(.toURL (.toURI (io/file (str host "/"))))
                                  (.toURL (.toURI (io/file (str jar))))])]
        (Sources/roots (URLClassLoader. urls (ClassLoader/getPlatformClassLoader)) cache))
      (is (= "# vis-python/auto_imports.py" (slurp (io/file (str cache) "vis-python/auto_imports.py")))))))

(deftest release-workflow-uploads-the-built-jar-test
  (testing "the release job names the versionless jar emitted by build/jar"
    (is (str/includes? (slurp ".github/workflows/release.yml")
                       "path: target/vis-python-runtime.jar"))))
