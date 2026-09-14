(ns runtime-docs
  "Build the Java and Clojure API reference with Javadoc and Codox."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [codox.main :as codox]
            [net.cgrand.enlive-html :as html])
  (:import [javax.tools ToolProvider]))

(defn check-links!
  "Reject broken local file and fragment links in the generated HTML tree."
  [directory]
  (let [pages (into {}
                    (for [^java.io.File file (file-seq (io/file directory))
                          :when (str/ends-with? (.getName file) ".html")
                          :let [nodes (html/html-resource file)]]

                      [(.getCanonicalFile file)
                       {:links (for [node (html/select nodes [:*])
                                     attribute [:href :src]
                                     :let [link (get-in node [:attrs attribute])]
                                     :when link]

                                 link)
                        :ids (set (keep #(or (get-in % [:attrs :id])
                                             (when (= :a (:tag %)) (get-in % [:attrs :name])))
                                        (html/select nodes [:*])))}]))]
    (when (empty? pages)
      (throw (ex-info "No generated HTML documentation found" {:directory directory})))
    (doseq [[^java.io.File page {:keys [links]}] pages
            link links
            :let [uri (java.net.URI. link)]
            :when (and (nil? (.getScheme uri)) (nil? (.getAuthority uri)))
            :let [path (.getPath uri)
                  ^java.io.File target (if (str/blank? path)
                                         page
                                         (.getCanonicalFile (io/file (.getParentFile page) path)))
                  target (if (.isDirectory target) (io/file target "index.html") target)
                  fragment (.getFragment uri)]]

      (when-not (.exists ^java.io.File target)
        (throw (ex-info "Broken documentation file link" {:file (str page) :href link})))
      (when (and (seq fragment)
                 (contains? pages target)
                 (not (contains? (:ids (get pages target)) fragment)))
        (throw (ex-info "Broken documentation fragment link" {:file (str page) :href link}))))
    (count pages)))

(defn build!
  "Generate target/docs; fail on malformed Javadoc or an invalid Java example."
  [_]
  (let [version
        (str/trim (slurp "VIS_PYTHON_VERSION"))

        ^javax.tools.DocumentationTool javadoc
        (ToolProvider/getSystemDocumentationTool)

        ^javax.tools.JavaCompiler compiler
        (ToolProvider/getSystemJavaCompiler)

        ^java.util.spi.ToolProvider jar
        (.orElseThrow (java.util.spi.ToolProvider/findFirst "jar"))]

    (when-not (and javadoc compiler)
      (throw (ex-info "Documentation requires a full JDK, not a JRE" {})))
    ;; Rebuild only generated documentation so removed APIs cannot linger in an archive.
    (when (.exists (io/file "target/docs"))
      (doseq [^java.io.File file (reverse (file-seq (io/file "target/docs")))]
        (java.nio.file.Files/delete (.toPath file))))
    (io/make-parents "target/docs/index.html")
    (when-not (zero? (.run javadoc
                           nil
                           System/out
                           System/err
                           (into-array String
                                       ["-d" "target/docs/java" "-sourcepath" "src/java" "-encoding"
                                        "UTF-8" "-docencoding" "UTF-8" "-charset" "UTF-8"
                                        "-Xdoclint:all,-missing" "-Werror" "-notimestamp"
                                        "-windowtitle" (str "vis-python-runtime " version)
                                        "-doctitle" (str "vis-python-runtime " version " Java API")
                                        "com.blockether.vispython"])))
      (throw (ex-info "Javadoc failed" {})))
    (codox/generate-docs
      {:source-paths ["src/clj"]
       :namespaces '[com.blockether.vis-python-runtime]
       :doc-files ["doc/getting-started.md" "doc/embedding.md" "doc/windows-jail.md"]
       :output-path "target/docs/clojure"
       :metadata {:doc/format :markdown}
       :project {:name "vis-python-runtime"
                 :version version
                 :description "Embed CPython in Java and Clojure."}
       :source-uri
       "https://github.com/Blockether/vis-python-runtime/blob/{git-commit}/{filepath}#L{line}"})
    (io/copy (io/file "doc/index.html") (io/file "target/docs/index.html"))
    (doseq [example ["Example" "WindowsJailExample"]]
      (let [source (str "doc/examples/" example ".java")]
        (io/make-parents (str "target/docs/examples/" example ".java"))
        (io/copy (io/file source) (io/file (str "target/docs/examples/" example ".java")))
        (io/make-parents (str "target/docs-examples/" example ".class"))
        (when-not (zero? (.run compiler
                               nil
                               System/out
                               System/err
                               (into-array String
                                           ["-encoding" "UTF-8" "-cp" "target/classes" "-d"
                                            "target/docs-examples" source])))
          (throw (ex-info "Java documentation example failed to compile" {:example example})))))
    (println "Checked local links in" (check-links! "target/docs") "HTML pages")
    (doseq [[directory archive]
            [["target/docs" (str "target/vis-python-runtime-api-docs-" version ".zip")]
             ["target/docs/java" (str "target/vis-python-runtime-" version "-javadoc.jar")]]]
      (when-not (zero? (.run jar
                             System/out
                             System/err
                             (into-array String
                                         ["--create" "--file" archive "--no-manifest" "-C" directory
                                          "."])))
        (throw (ex-info "Documentation archive failed" {:archive archive}))))
    (println "API documentation: target/docs/index.html")))
