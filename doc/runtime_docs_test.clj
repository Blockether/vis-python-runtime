(ns runtime-docs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [runtime-docs :as docs])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest local-documentation-links-test
  (let [directory
        (.toFile (Files/createTempDirectory "runtime-doc-links-" (make-array FileAttribute 0)))

        index
        (io/file directory "index.html")

        api
        (io/file directory "api.html")]

    (try (spit index "<html><body><a href='api.html#entry'>API</a></body></html>")
         (spit api "<html><body><h1 id='entry'>API</h1></body></html>")
         (is (= 2 (docs/check-links! directory)))
         (spit index "<html><body><a href='missing.html'>Missing</a></body></html>")
         (is
           (thrown-with-msg? clojure.lang.ExceptionInfo #"file link" (docs/check-links! directory)))
         (spit index "<html><body><a href='api.html#missing'>Missing</a></body></html>")
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"fragment link"
                               (docs/check-links! directory)))
         (spit index "<html><body><a href='https://example.com/remote'>External</a></body></html>")
         (is (= 2 (docs/check-links! directory)))
         (finally (doseq [^java.io.File file (reverse (file-seq directory))]
                    (.delete file))))))
