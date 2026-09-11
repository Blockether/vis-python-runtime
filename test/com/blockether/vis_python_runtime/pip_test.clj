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
           [com.blockether.vispython Locations Trust]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

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
                           (let [site (->> (file-seq (io/file home))
                                           (filter #(= "site-packages" (.getName ^java.io.File %)))
                                           (first))
                                 names (when site
                                         (->> (.listFiles ^java.io.File site)
                                              (map #(.getName ^java.io.File %))
                                              (remove #(str/ends-with? % ".dist-info"))
                                              (remove bundled-with-the-interpreter)
                                              (sort)))]

                             (is (empty? names)
                                 (str "the artifact bundles packages it should install with pip: "
                                      names))))))

(harness/defbuilt-test
  packages-directory-is-importable-test
  (let [{:keys [packages]}
        (runtime/initialize!)

        session
        (harness/block-session)]

    (testing
      "what pip installs is on sys.path, so a real distribution shadows the shim of that name"
      (is (some? packages))
      (is (= "True"
             (runtime/eval-str session (str (pr-str packages) " in __import__('sys').path"))))
      (testing "and wiring it is idempotent: a second start does not duplicate a path entry"
        (runtime/initialize!)
        (is (= "1"
               (runtime/eval-str
                 session
                 (str "str(__import__('sys').path.count(" (pr-str packages) "))"))))))))

(defn- with-editable-site
  "Isolate package-site regressions from the user's installed distributions."
  [f]
  (let [root
        (io/file (temp-dir "vis-editable"))

        packages
        (doto (io/file root "packages") .mkdirs)

        source
        (doto (io/file root "source with spaces") .mkdirs)

        session
        (str "editable-" (System/nanoTime))]

    (try (runtime/initialize! {:packages (.getCanonicalPath packages)})
         (runtime/confine! [(str root)] [(str root)] "fixture roots only")
         (f session packages source)
         (finally (runtime/confine! [] [] "")
                  (doseq [file (reverse (file-seq root))]
                    (io/delete-file file true))
                  (runtime/exec! session
                                 (str "import package_paths, sys\npackage_paths.refresh("
                                      (pr-str (str packages))
                                      ", reload=True)\nsys.path[:] = [p for p in sys.path if p != "
                                      (pr-str (str packages))
                                      "]\n" "sys.modules.pop('vis_editable_hook', None)\n"))
                  (runtime/initialize!)
                  (runtime/close-session! (str session "-next"))
                  (runtime/close-session! session)))))

(harness/defbuilt-test absent-package-site-remains-idempotent-test
                       ;; Blockether/vis#175: site.addsitedir must not duplicate a not-yet-created target.
                       (with-editable-site
                         (fn [session packages _source]
                           (io/delete-file packages)
                           (runtime/install-runtime! session)
                           (runtime/initialize! {:packages (.getCanonicalPath packages)})
                           (is (= "1"
                                  (runtime/eval-str session
                                                    (str "str(__import__('sys').path.count("
                                                         (pr-str (.getCanonicalPath packages))
                                                         "))")))))))

(harness/defbuilt-test
  editable-reload-preserves-injected-modules-test
  ;; Blockether/vis#194: __file__ is not proof that a host module is import-backed.
  (with-editable-site
    (fn [session packages source]
      (spit (io/file packages "fixture.pth") (str source "\n"))
      (runtime/install-runtime! session)
      (runtime/exec!
        session
        (str
          "import sys, types, importlib.machinery, package_paths\n"
          "__file__ = "
          (pr-str (str (io/file source "extension.py")))
          "\n"
          "sdk = types.ModuleType('vis_injected_sdk')\n"
          "sdk.__file__ = "
          (pr-str (str (io/file source "sdk.py")))
          "\n"
          "sdk.__spec__ = importlib.machinery.ModuleSpec('vis_injected_sdk', None)\n"
          "sdk.host = object()\n"
          "sys.modules[sdk.__name__] = sdk\n"
          "original_session = sys.modules[__name__]\n"
          "package_paths.refresh("
          (pr-str (str packages))
          ", reload=True)\n"
          "assert sys.modules.get(sdk.__name__) is sdk, 'injected SDK was evicted'\n"
          "assert sys.modules.get(__name__) is original_session, 'active session was evicted'\n"))
      (is (= "True"
             (runtime/eval-str session "str(sys.modules['vis_injected_sdk'].host is sdk.host)")))
      (runtime/exec! session "sys.modules.pop('vis_injected_sdk', None)"))))

(harness/defbuilt-test
  editable-pth-is-importable-test
  ;; Blockether/vis#175: a site path alone does not activate an editable install.
  (with-editable-site
    (fn [session packages source]
      (let [module
            (io/file source "vis_editable_fixture.py")

            refresh
            (str "import package_paths; package_paths.refresh("
                 (pr-str (str packages))
                 ", reload=True)")]

        (spit module "VALUE = 175\n")
        (spit (io/file packages "fixture.pth") (str "# editable source\n\n" source "\n"))
        (runtime/install-runtime! session)
        (runtime/exec!
          session
          "import vis_editable_fixture, json\noriginal_json = json\nimport py_compile\npy_compile.compile(vis_editable_fixture.__file__)\n")
        (is (= "175" (runtime/eval-str session "str(vis_editable_fixture.VALUE)")))
        (is (= (str module) (runtime/eval-str session "vis_editable_fixture.__file__")))
        (let [mtime (.lastModified module)]
          (spit module "VALUE = 176\n")
          (.setLastModified module mtime))
        (runtime/install-runtime! (str session "-next"))
        (is (= "175" (runtime/eval-str session "str(__import__('vis_editable_fixture').VALUE)"))
            "Another namespace bootstrap must not silently reload imported source")
        (runtime/exec! session refresh)
        (is (= "176" (runtime/eval-str session "str(__import__('vis_editable_fixture').VALUE)"))
            "Reload bypasses same-size, same-timestamp bytecode")
        (is (= "True" (runtime/eval-str session "str(original_json is __import__('json'))"))
            "Ordinary dependencies retain module identity")))))

(harness/defbuilt-test
  editable-import-hook-test
  (with-editable-site
    (fn [session packages source]
      (let [module
            (io/file source "vis_editable_hooked.py")

            metadata
            (io/file packages "fixture-1.dist-info/direct_url.json")]

        (spit module "VALUE = 41\n")
        (io/make-parents metadata)
        (spit metadata
              (str "{\"dir_info\":{\"editable\":true},\"url\":" (pr-str (str (.toURI source))) "}"))
        (spit (io/file packages "vis_editable_hook.py")
              (str "import sys\nfrom importlib.util import spec_from_file_location\n"
                   "calls = 0\nclass Finder:\n"
                   "    @classmethod\n    def find_spec(cls, fullname, path=None, target=None):\n"
                   "        if fullname == 'vis_editable_hooked':\n"
                   "            return spec_from_file_location(fullname, "
                   (pr-str (str module))
                   ")\n"
                   "def install():\n    global calls\n    calls += 1\n"
                   "    if Finder not in sys.meta_path:\n        sys.meta_path.append(Finder)\n"))
        (spit (io/file packages "fixture.pth")
              "import vis_editable_hook; vis_editable_hook.install()\n")
        (doseq [s [session (str session "-next")]]
          (runtime/install-runtime! s))
        (is (= "41" (runtime/eval-str session "str(__import__('vis_editable_hooked').VALUE)")))
        (is (= "1" (runtime/eval-str session "str(__import__('vis_editable_hook').calls)"))
            "Unchanged .pth hooks execute once per interpreter")
        (let [mtime (.lastModified module)]
          (spit module "VALUE = 42\n")
          (.setLastModified module mtime))
        (runtime/exec! session
                       (str "import package_paths; package_paths.refresh("
                            (pr-str (str packages))
                            ", reload=True)"))
        (is (= "42" (runtime/eval-str session "str(__import__('vis_editable_hooked').VALUE)")))
        (io/delete-file (io/file packages "fixture.pth"))
        (runtime/exec! session
                       (str "package_paths.refresh(" (pr-str (str packages)) ", reload=True)"))
        (is (= "False"
               (runtime/eval-str
                 session
                 "str(__import__('vis_editable_hook').Finder in __import__('sys').meta_path)")))))))

(harness/defbuilt-test
  newly-installed-pth-is-discovered-test
  (with-editable-site
    (fn [session packages source]
      (runtime/install-runtime! session)
      (spit (io/file source "vis_editable_later.py") "VALUE = 7\n")
      (spit (io/file packages "later.pth") (str source "\n"))
      (runtime/install-runtime! (str session "-next"))
      (is (= "7" (runtime/eval-str session "str(__import__('vis_editable_later').VALUE)")))
      (io/delete-file (io/file packages "later.pth"))
      (runtime/exec! session
                     (str "import package_paths; package_paths.refresh("
                          (pr-str (str packages))
                          ", reload=True)"))
      (is (= "False"
             (runtime/eval-str
               session
               (str "str(" (pr-str (str source)) " in __import__('sys').path)")))))))

(harness/defbuilt-test
  editable-pth-keeps-filesystem-policy-test
  (with-editable-site
    (fn [session packages source]
      (spit (io/file source "vis_editable_denied.py") "VALUE = 1\n")
      (spit (io/file packages "denied.pth") (str source "\n"))
      (runtime/confine! [(str packages)] [(str packages)] "fixture roots only")
      (runtime/install-runtime! session)
      (is (thrown-with-msg? com.blockether.vispython.VisPythonException
                            #"PermissionError|ModuleNotFoundError"
                            (runtime/eval-str session "__import__('vis_editable_denied')"))
          "An editable path does not grant access outside the host's readable roots"))))

(deftest trust-comes-from-the-jvm-test
  (let [anchors
        (Trust/trustAnchors)

        path
        (str (io/file (temp-dir "vis-cert") "cacert.pem"))

        written
        (runtime/certificates-pem! path)]

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

(deftest package-directory-test (is (= (Locations/packagesDir) (runtime/packages-dir))))

(deftest install-command-test
  (let [command (runtime/pip-command {:python "/p/bin/python3" :target "/t" :cert "/c/cacert.pem"}
                                     ["six==1.17.0"])]
    (testing
      "an sdist is refused: it would run its own setup.py on the host, outside every boundary"
      (is (some #{"--only-binary=:all:"} command)))
    (testing "pip verifies against the JVM's certificates, not the bundle vendored inside it"
      (is (= ["--cert" "/c/cacert.pem"]
             (->> command
                  (drop-while #(not= "--cert" %))
                  (take 2)))))
    (testing "and installs into the directory the runtime imports from"
      (is (= ["--target" "/t"]
             (->> command
                  (drop-while #(not= "--target" %))
                  (take 2))))
      (is (= "six==1.17.0" (last command))))))

(harness/defbuilt-test
  installs-a-real-distribution-test
  (if-not (index-reachable?)
    (println "SKIPPED installs-a-real-distribution-test: pypi.org is not reachable")
    (let [target
          (temp-dir "vis-packages")

          pycache
          (temp-dir "vis-packages-cache")

          answer
          (runtime/pip-install! {:target target :pycache-prefix pycache} ["six==1.17.0"])

          session
          (harness/block-session)]

      (testing "pip installs over TLS the JVM's own certificates verified"
        (is (= 0 (:exit answer)) (:out answer)))
      (testing "and a block imports the real distribution from there"
        (let [printed (block session
                             (str "import sys\n"
                                  "sys.path.insert(0, "
                                  (pr-str target)
                                  ")\n"
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
