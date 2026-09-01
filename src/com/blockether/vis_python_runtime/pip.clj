(ns com.blockether.vis-python-runtime.pip
  "Installing packages: the only way the sandbox ever gets one.

   The artifact BUNDLES NOTHING. It is an interpreter, its standard library and
   `pip`; every real distribution arrives here, into one directory beside the
   bytecode cache, and every session imports from it. So a shipped tree is the
   same bytes on every machine, a package the user chose survives the next
   release, and there is no requirements file anybody has to re-decide.

   Two things make this the HOST's job and never a block's. `pip` runs in a
   process of its own - the embedded interpreter is confined, and being one
   interpreter for the whole process it would carry an installer's imports and
   monkeypatches into every session after it. And installing reaches an index,
   which is precisely what a sandbox is for refusing: a block that could install
   could write its own next payload.

   Trust comes from the JVM. `pip` would otherwise verify TLS against the CA
   bundle vendored inside it, so a machine whose operator added a corporate root
   to the Java trust store - the only store this product's own HTTP client reads
   - would have a runtime that trusts two different sets of certificates and
   fails on one of them. `certificates-pem!` exports what the JVM trusts and
   pip is pointed at that file, so there is one trust decision on the machine."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [java.security KeyStore]
           [java.security KeyStore]
           [java.security.cert X509Certificate]
           [java.util Base64]
           [java.util.concurrent TimeUnit]
           [javax.net.ssl TrustManagerFactory X509TrustManager]))

(defn trust-anchors
  "Every certificate the JVM trusts, from the DEFAULT trust manager - so a root
   an operator added to `cacerts`, or pointed at with `javax.net.ssl.trustStore`,
   is included exactly as it is for the rest of the process."
  []
  (let [factory (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        ;; A nil KeyStore is what asks for the process's DEFAULT trust store,
        ;; and the hint has to sit on a local: nil takes no metadata.
        ^KeyStore default nil]
    (.init factory default)
    (->> (.getTrustManagers factory)
         (filter #(instance? X509TrustManager %))
         (mapcat #(seq (.getAcceptedIssuers ^X509TrustManager %)))
         (vec))))

(defn- pem
  "One certificate as PEM."
  [^X509Certificate certificate]
  (let [encoder (Base64/getMimeEncoder 64 (.getBytes "\n" "UTF-8"))]
    (str "-----BEGIN CERTIFICATE-----\n"
         (.encodeToString encoder (.getEncoded certificate))
         "\n-----END CERTIFICATE-----\n")))

(defn certificates-file
  "Where the exported trust store lives: beside the packages it is used to
   install."
  ^String []
  (when-let [packages (runtime/resolve-packages-dir)]
    (.getAbsolutePath (io/file (.getParentFile (io/file packages)) "cacert.pem"))))

(defn certificates-pem!
  "Write the JVM's trust anchors to `path` in PEM and answer it.

   Rewritten only when the anchors changed, because the path is handed to a
   subprocess and a file being rewritten under one is worth avoiding for
   nothing."
  ([] (certificates-pem! (certificates-file)))
  ([path]
   (let [wanted (str/join (map pem (trust-anchors)))
         file   (io/file path)]
     (when-not (and (.isFile file) (= wanted (slurp file)))
       (io/make-parents file)
       (spit file wanted))
     (.getAbsolutePath file))))

(defn install-command
  "The argv that installs `specs` into `target`.

   `--only-binary=:all:` is not a preference: an sdist runs its own `setup.py`
   at install time, on the host, outside every boundary this project has. A
   wheel is data that gets unpacked."
  [{:keys [python target cert upgrade?]} specs]
  (into (cond-> [python "-m" "pip" "install"
                 "--target" target
                 "--only-binary=:all:"
                 "--disable-pip-version-check"
                 "--no-input"]
          cert     (conj "--cert" cert)
          upgrade? (conj "--upgrade"))
        specs))

(defn install!
  "Install `specs` for the sandbox, answering `{:exit … :out … :command …}`.

   Defaults are the ones the runtime already resolves: the vendored
   interpreter, `runtime/resolve-packages-dir` as the target, the bytecode cache
   prefix, and the JVM's certificates exported to a file. A non-zero `:exit`
   comes back as data with pip's own output, because the caller is a CLI that
   has to print it."
  ([specs] (install! {} specs))
  ([{:keys [python target cert pycache-prefix timeout-ms upgrade?]
     :or   {timeout-ms 600000}}
    specs]
   (let [python  (or python (runtime/resolve-python-executable))
         target  (or target (runtime/resolve-packages-dir))
         cert    (or cert (certificates-pem!))
         pycache (or pycache-prefix (runtime/resolve-pycache-prefix))
         _       (when-not python
                   (throw (ex-info "no interpreter to run pip with" {:target target})))
         _       (when-not target
                   (throw (ex-info "no directory to install into" {:python python})))
         command (install-command {:python python :target target :cert cert :upgrade? upgrade?} specs)
         builder (doto (ProcessBuilder. ^java.util.List command)
                   (.redirectErrorStream true))
         env     (.environment builder)]
     ;; pip reads the target as an ordinary path, so it needs the same directory
     ;; on `sys.path` to see what is already installed - and nothing of the
     ;; machine's own Python may leak into it.
     (.put env "PYTHONPATH" (str target))
     (.put env "PYTHONNOUSERSITE" "1")
     (when cert
       (.put env "PIP_CERT" (str cert))
       (.put env "SSL_CERT_FILE" (str cert)))
     (when pycache
       (.put env "PYTHONPYCACHEPREFIX" (str pycache)))
     (let [process (.start builder)
           out     (slurp (.getInputStream process))
           done?   (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
       (when-not done?
         (.destroyForcibly process))
       {:exit    (if done? (.exitValue process) -1)
        :out     out
        :command command}))))
