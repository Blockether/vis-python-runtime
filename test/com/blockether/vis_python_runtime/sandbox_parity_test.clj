(ns com.blockether.vis-python-runtime.sandbox-parity-test
  "Until Vis pins this library, BOTH repositories carry the sandbox Python and a
   silent divergence would be the worst outcome of the move: Vis would ship one
   runtime and this repository would test another. So parity is a test, not a
   promise — every file here is compared byte for byte with the sibling Vis
   checkout, and an edit on either side fails the suite until it is made on both.

   The test disappears with the last copy: once Vis reads these files from this
   library's jar, delete this namespace instead of maintaining it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.security MessageDigest]))

(def ^:private mirrored-roots ["vis-python" "vis-shims"])

(defn- sibling-vis ^java.io.File []
  (io/file (System/getProperty "user.dir") ".." "vis" "resources"))

(defn- digest [^java.io.File f]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream f)]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update md buf 0 n)
              (recur))))))
    (vec (.digest md))))

(defn- python-files [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".py")))
       (into (sorted-map) (map (fn [^java.io.File f] [(.getName f) f])))))

(deftest sandbox-python-matches-vis-test
  (let [vis-resources (sibling-vis)]
    (if-not (.isDirectory vis-resources)
      (println "SKIP sandbox-python-matches-vis-test: no sibling Vis checkout at"
               (.getPath vis-resources))
      (doseq [root mirrored-roots]
        (testing root
          (let [ours   (python-files (io/file (System/getProperty "user.dir") "resources" root))
                theirs (python-files (io/file vis-resources root))]
            (is (= (keys theirs) (keys ours))
                "the same set of sandbox sources lives on both sides")
            (doseq [[name f] ours]
              (when-let [other (get theirs name)]
                (is (= (digest other) (digest f))
                    (str root "/" name " differs between this repository and Vis"))))))))))
