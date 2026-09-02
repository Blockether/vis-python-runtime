(ns com.blockether.vis-python-runtime.native-test
  "How a platform artifact becomes a usable installation.

   A prebuild is a DIRECTORY — the cdylib and the interpreter tree it was linked
   against — and it never travels inside a jar: it is the platform's release
   archive, unpacked by whoever consumes it. So the only resolution this library
   owes is the named path, which is what a host hands over after unpacking."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython VisPythonException]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^Path [prefix]
  (.toAbsolutePath (Files/createTempDirectory prefix (make-array FileAttribute 0))))

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
