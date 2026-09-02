(ns com.blockether.vis-python-runtime.native-test
  "How a platform artifact becomes a usable installation.

   A prebuild is a DIRECTORY — the cdylib and the interpreter tree it was linked
   against — so a jar that carries one has to be unpacked WHOLE: a library with
   no `python/` beside it is a runtime whose first import fails for a standard
   library nobody extracted. The jar is built here, so no published artifact is
   needed, and every extraction lands in a temporary directory rather than the
   machine's own `~/.vis/python/runtime`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython Native VisPythonException]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:private tag
  "A platform this host is NOT, so nothing here can collide with a real one."
  (if (= "linux-x64" (runtime/platform)) "darwin-arm64" "linux-x64"))

(defn- temp-dir ^Path [prefix]
  (.toAbsolutePath (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- prebuild-jar
  "A platform jar: the cdylib, an interpreter tree beside it, and one entry that
   belongs to another platform so the extraction has something to leave behind."
  ^java.io.File []
  (let [file (io/file (str (temp-dir "vis-native-jar")) "prebuild.jar")
        lib  (Native/libraryName tag)]
    (with-open [out (JarOutputStream. (io/output-stream file))]
      (doseq [[entry text] [[(str "prebuilds/" tag "/" lib) "cdylib"]
                            [(str "prebuilds/" tag "/python/bin/python3") "#!/bin/sh"]
                            [(str "prebuilds/" tag "/python/lib/python3.14/os.py") "# os"]
                            ["prebuilds/other-arch/libvispython.so" "not ours"]]]
        (.putNextEntry out (JarEntry. ^String entry))
        (.write out (.getBytes ^String text "UTF-8"))
        (.closeEntry out)))
    file))

(deftest materialize-unpacks-the-whole-prebuild-test
  (testing "the interpreter tree travels with the library, and only this platform's"
    (let [home  (.resolve (temp-dir "vis-native-home") "prebuild")
          found (Native/materialize (.toPath (prebuild-jar)) tag home)
          root  (.getParent (Path/of (.path found) (make-array String 0)))]
      (is (= "materialized" (.source found)))
      (is (= (str (.resolve home (Native/libraryName tag))) (.path found)))
      (is (.isFile (io/file (str root) "python" "lib" "python3.14" "os.py"))
          "a shipped standard library is what makes the first import work")
      (is (.canExecute (io/file (str root) "python" "bin" "python3"))
          "a jar carries no file mode, and pip runs the interpreter as a program")
      (is (not (.exists (io/file (str root) "libvispython.so.other")))
          "another platform's entries are not ours to unpack")))
  (testing "a second call answers the installation already there"
    (let [home  (.resolve (temp-dir "vis-native-home") "prebuild")
          jar   (.toPath (prebuild-jar))
          first-found  (Native/materialize jar tag home)
          second-found (Native/materialize jar tag home)]
      (is (= (.path first-found) (.path second-found)))))
  (testing "a jar with no prebuild for the platform is refused, not half-unpacked"
    (let [home (.resolve (temp-dir "vis-native-home") "prebuild")]
      (is (thrown? VisPythonException
                   (Native/materialize (.toPath (prebuild-jar)) "windows-x64" home))))))

(deftest use-library-wins-over-resolution-test
  (testing "a host that fetched the artifact itself names the path, since a JVM cannot set its own environment"
    (let [dir  (temp-dir "vis-native-use")
          lib  (io/file (str dir) (runtime/library-name))]
      (spit lib "cdylib")
      (try
        (runtime/use-library! (str dir))
        (is (= {:source :configured :path (str lib)} (runtime/resolve-library)))
        (runtime/use-library! (str lib))
        (is (= {:source :configured :path (str lib)} (runtime/resolve-library)))
        (finally (runtime/use-library! nil)))))
  (testing "a named path that holds no library is a refusal naming it"
    (try
      (runtime/use-library! (str (temp-dir "vis-native-empty")))
      (is (thrown? VisPythonException (runtime/resolve-library)))
      (finally (runtime/use-library! nil)))))
