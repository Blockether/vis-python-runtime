(ns com.blockether.vis-python-runtime.pip-config-test
  "Offline subprocess checks: actual pip must consume custom index/proxy settings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [com.blockether.vis-python-runtime.harness :refer [defbuilt-test]])
  (:import [com.blockether.vispython Interpreter]
           [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(defn- run-installer
  [python target environment]
  (let
    [code
     (str
       "(require '[com.blockether.vis-python-runtime :as r]) "
       "(let [result (r/pip-install! "
       (pr-str {:python python :target (str target) :timeout-ms 10000})
       " [\"vis-proxy-fixture-missing\"])] (print (:out result)) (flush) (System/exit (:exit result)))")

     builder
     (ProcessBuilder. ^java.util.List
                      (vec [(str (io/file (System/getProperty "java.home") "bin" "java"))
                            "--enable-native-access=ALL-UNNAMED" "-cp"
                            (System/getProperty "java.class.path") "clojure.main" "-e" code]))

     env
     (.environment builder)]

    ;; Never use the developer's repository URLs or proxy credentials in a fixture.
    (doseq [key
            (vec (.keySet env))

            :when (or (str/starts-with? key "PIP_")
                      (str/includes? (str/upper-case key) "PROXY")
                      (contains? #{"REQUESTS_CA_BUNDLE" "CURL_CA_BUNDLE" "SSL_CERT_FILE"} key))]

      (.remove env key))
    (.putAll env {"PIP_RETRIES" "0" "PIP_TIMEOUT" "3" "PIP_NO_CACHE_DIR" "1"})
    (.putAll env environment)
    (.redirectErrorStream builder true)
    (let [process (.start builder)]
      (try (if (.waitFor process 45 TimeUnit/SECONDS)
             {:exit (.exitValue process) :out (slurp (.getInputStream process))}
             {:exit -1 :out "installer test timed out"})
           (finally (.destroyForcibly process))))))

;; Regression: wrapper configuration must not override the operator's private index or proxy.
(defbuilt-test
  private-index-through-proxy-test
  (let [python
        (Interpreter/pythonExecutable)

        tmp
        (.toFile (Files/createTempDirectory "pip-proxy-test" (make-array FileAttribute 0)))

        requests
        (atom [])

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]

    (is (some? python) "requires the built interpreter")
    (.createContext server
                    "/"
                    (reify
                      HttpHandler
                        (handle [_ exchange]
                          (let [^HttpExchange exchange exchange]
                            (swap! requests conj (str (.getRequestURI exchange)))
                            (.sendResponseHeaders exchange 404 -1)
                            (.close exchange)))))
    (.start server)
    (try (let [proxy
               (str "http://127.0.0.1:" (.getPort (.getAddress server)))

               index
               "http://127.0.0.1:9/artifactory/api/pypi/python/simple"

               config
               (io/file tmp "pip.conf")]

           (spit config (str "[global]\nindex-url = " index "\nproxy = " proxy "\n"))
           (doseq [env [{"PIP_INDEX_URL" index "PIP_PROXY" proxy "PIP_CONFIG_FILE" "/dev/null"}
                        {"PIP_INDEX_URL" index
                         "HTTP_PROXY" proxy
                         "HTTPS_PROXY" proxy
                         "NO_PROXY" ""
                         "PIP_CONFIG_FILE" "/dev/null"} {"PIP_CONFIG_FILE" (str config)}]]
             (reset! requests [])
             (let [result (run-installer python (io/file tmp "packages") env)]
               (is (= 1 (:exit result)) "the fixture deliberately has no matching wheel")
               (is (some #{(str index "/vis-proxy-fixture-missing/")} @requests)
                   "real pip must request the configured index through the fixture proxy"))))
         (finally (.stop server 0)
                  (doseq [f (reverse (file-seq tmp))]
                    (io/delete-file f true))))))

;; Regression: the wrapper replaced PIP_CERT with its own generated bundle.
(defbuilt-test
  explicit-pip-cert-is-preserved-test
  (let [tmp
        (.toFile (Files/createTempDirectory "pip-cert-env" (make-array FileAttribute 0)))

        python
        (Interpreter/pythonExecutable)

        wrapper
        (io/file tmp "python-probe")]

    (try (spit wrapper
               (str "#!/bin/sh\nexec "
                    (pr-str python)
                    " -c \"import os; print(os.environ.get('PIP_CERT') == '/custom.pem')\"\n"))
         (.setExecutable wrapper true)
         (let [result
               (run-installer (str wrapper) (io/file tmp "packages") {"PIP_CERT" "/custom.pem"})]
           (is (= 0 (:exit result)))
           (is (= "True" (str/trim (:out result)))))
         (finally (doseq [f (reverse (file-seq tmp))]
                    (io/delete-file f true))))))
