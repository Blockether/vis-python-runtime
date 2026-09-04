(ns com.blockether.vis-python-runtime.pip-test
  "How the sandbox gets a package: pip, run by the host, into the user's own
   directory.

   The artifact bundles nothing, so these cases are the whole supply chain. Two
   of them need an index and say so loudly when there is none; the rest hold
   with the network unplugged."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block temp-dir]])
  (:import [java.net InetSocketAddress Socket]
           [java.security.cert CertificateFactory]
           [com.blockether.vispython Locations Pip]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally (harness/close-sessions!)))))

(def ^:private bundled-with-the-interpreter
  "What python-build-standalone's own tree carries, which is the installer and
   nothing else."
  #{"pip" "setuptools" "pkg_resources" "wheel" "_distutils_hack" "distutils-precedence.pth"
    "README.txt" "__pycache__"})

(defn- index-reachable?
  "Whether PyPI answers, so a case that needs it can skip instead of failing for
   a reason that is not about this code."
  []
  (try (with-open [socket (Socket.)]
         (.connect socket (InetSocketAddress. "pypi.org" 443) 3000)
         true)
       (catch Exception _ false)))

(harness/defbuilt-test artifact-bundles-nothing-test
  (testing "the shipped interpreter carries pip and no package"
    (when-let [home (runtime/resolve-python-home)]
      (let [site  (->> (file-seq (io/file home))
                       (filter #(= "site-packages" (.getName ^java.io.File %)))
                       (first))
            names (when site
                    (->> (.listFiles ^java.io.File site)
                         (map #(.getName ^java.io.File %))
                         (remove #(str/ends-with? % ".dist-info"))
                         (remove bundled-with-the-interpreter)
                         (sort)))]
        (is (empty? names)
            (str "the artifact bundles packages it should install with pip: " names))))))

(harness/defbuilt-test packages-directory-is-importable-test
  (let [{:keys [packages]} (runtime/initialize!)
        session (harness/block-session)]
    (testing "what pip installs is on sys.path, so a real distribution shadows the shim of that name"
      (is (some? packages))
      (is (= "True" (runtime/eval-str session (str (pr-str packages) " in __import__('sys').path"))))
      (testing "and wiring it is idempotent: a second start does not duplicate a path entry"
        (runtime/initialize!)
        (is (= "1" (runtime/eval-str session
                                     (str "str(__import__('sys').path.count(" (pr-str packages) "))"))))))))

(deftest trust-comes-from-the-jvm-test
  (let [anchors (Pip/trustAnchors)
        path    (str (io/file (temp-dir "vis-cert") "cacert.pem"))
        written (runtime/certificates-pem! path)]
    (testing "the JVM's trust store is what pip will verify against"
      (is (seq anchors))
      (is (= path written))
      (let [parsed (.generateCertificates (CertificateFactory/getInstance "X.509")
                                          (io/input-stream written))]
        (is (= (count anchors) (count parsed))
            "every certificate the JVM trusts has to survive the export")))
    (testing "an unchanged trust store is not rewritten under a running subprocess"
      (let [stamp (.lastModified (io/file written))]
        (Thread/sleep 10)
        (runtime/certificates-pem! path)
        (is (= stamp (.lastModified (io/file written))))))))

(deftest package-directory-test
  (is (= (Locations/packagesDir) (runtime/packages-dir))))

(deftest install-command-test
  (let [command (runtime/pip-command {:python "/p/bin/python3" :target "/t" :cert "/c/cacert.pem"}
                                     ["six==1.17.0"])]
    (testing "an sdist is refused: it would run its own setup.py on the host, outside every boundary"
      (is (some #{"--only-binary=:all:"} command)))
    (testing "pip verifies against the JVM's certificates, not the bundle vendored inside it"
      (is (= ["--cert" "/c/cacert.pem"] (->> command (drop-while #(not= "--cert" %)) (take 2)))))
    (testing "and installs into the directory the runtime imports from"
      (is (= ["--target" "/t"] (->> command (drop-while #(not= "--target" %)) (take 2))))
      (is (= "six==1.17.0" (last command))))))

(harness/defbuilt-test installs-a-real-distribution-test
  (if-not (index-reachable?)
    (println "SKIPPED installs-a-real-distribution-test: pypi.org is not reachable")
    (let [target  (temp-dir "vis-packages")
          pycache (temp-dir "vis-packages-cache")
          answer  (runtime/pip-install! {:target target :pycache-prefix pycache} ["six==1.17.0"])
          session (harness/block-session)]
      (testing "pip installs over TLS the JVM's own certificates verified"
        (is (= 0 (:exit answer)) (:out answer)))
      (testing "and a block imports the real distribution from there"
        (let [printed (block session (str "import sys\n"
                                          "sys.path.insert(0, " (pr-str target) ")\n"
                                          "import six\n"
                                          "print(six.__file__)"))]
          (is (nil? (:error printed)) (str (:error printed)))
          (is (str/starts-with? (str/trim (str (:stdout printed))) target))))
      (testing "installation compiles into the cache prefix, never into the package directory"
        (is (empty? (->> (file-seq (io/file target))
                         (filter #(= "__pycache__" (.getName ^java.io.File %)))
                         (map str))))
        (is (seq (->> (file-seq (io/file pycache))
                      (filter #(str/ends-with? (.getName ^java.io.File %) ".pyc")))))))))
