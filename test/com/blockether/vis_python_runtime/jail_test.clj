(ns com.blockether.vis-python-runtime.jail-test
  "The process boundary itself: a policy VALUE compiles here, Java enters
   libvisjail over FFM, and the OS — not an argv convention — enforces it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import
   [com.blockether.vispython Bubblewrap JailPolicy$Egress Seatbelt]
   [java.net InetAddress ServerSocket]
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent TimeUnit]))

(defn- temp-dir ^Path []
  (.toRealPath (Files/createTempDirectory "visjail-" (make-array FileAttribute 0))
               (make-array java.nio.file.LinkOption 0)))

(defn- macos? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- options [^Path root & [extra]]
  (merge {:environment {"PATH" "/usr/bin:/bin" "HOME" (str root)
                        "TMPDIR" (str root) "LANG" "C"}
          :directory (str root)
          :policy {:read-write [(str root)]}}
         extra))

(defn- run-process [command opts]
  (let [p (runtime/spawn-process! command opts)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))
        exit (.waitFor p)]
    {:exit exit :out @out :err @err}))

(defn- loopback-server ^ServerSocket []
  (ServerSocket. 0 8 (InetAddress/getLoopbackAddress)))

(defn- delete-tree [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))] (io/delete-file file true)))

(deftest policy-value-test
  (testing "lists are distinct, blank-free and never nil"
    (let [p (runtime/jail-policy {:read-write ["/a" "" "/a" nil]
                                  :unix-connect ["/tmp/control.sock" "" "/tmp/control.sock"]
                                  :inbound [80 80 443]})]
      (is (= ["/a"] (.readWrite p)))
      (is (= [] (.readOnly p)))
      (is (= ["/tmp/control.sock"] (.unixConnect p)))
      (is (= [80 443] (.inbound p)))
      (is (= JailPolicy$Egress/OFF (.egress p)))
      (is (false? (.keychain p)))))
  (testing "egress is one of three shapes"
    (is (= JailPolicy$Egress/OPEN (.egress (runtime/jail-policy {:network :open}))))
    (is (= 8080 (.proxyPort (.egress (runtime/jail-policy {:network {:proxy 8080}}))))))
  (testing "a port outside the TCP range is refused, not dropped"
    (is (thrown? IllegalArgumentException (runtime/jail-policy {:inbound [70000]})))
    (is (thrown? IllegalArgumentException (runtime/jail-policy {:network {:proxy 0}})))))

(deftest seatbelt-compiler-test
  (let [root (temp-dir)
        compile #(Seatbelt/compile (runtime/jail-policy (merge {:read-write [(str root)]} %)))]
    (try
      (testing "every profile imports the platform and starts from deny"
        (let [p (compile {})]
          (is (str/starts-with? p "(version 1)(import \"system.sb\")(deny default)"))
          (is (str/includes? p "(allow ipc-posix-sem)"))
          (is (str/includes? p (str "(allow file-read* file-write*")))
          (is (str/includes? p (str "(subpath \"" root "\")")))))
      (testing "the deny lists come after the allows so they win"
        (let [sub (str root "/secret")
              p (compile {:deny-write [sub] :deny-read [sub] :deny-exec [sub]})]
          (is (< (str/index-of p "(allow file-read* file-write*") (str/index-of p "(deny file-write*")))
          (is (str/includes? p (str "(deny file-read*(subpath \"" sub "\"))")))
          (is (str/includes? p (str "(deny process-exec*(subpath \"" sub "\"))")))))
      (testing "a deny target that does not exist keeps its spelling"
        (is (str/includes? (compile {:deny-read ["~/.does-not-exist"]})
                           (str "(subpath \"" (System/getProperty "user.home") "/.does-not-exist\")"))))
      (testing "ancestors of a granted root are metadata literals, never subpaths"
        ;; The temp dir itself is always granted, so grant a nested child and
        ;; look at ITS parent.
        (let [nested (Files/createDirectories (.resolve root "nested") (make-array FileAttribute 0))
              p (compile {:read-write [(str nested)]})]
          (is (str/includes? p (str "(literal \"" root "\")")))
          (is (not (str/includes? p (str "(subpath \"" root "\")"))))))
      (testing "network: loopback listeners always, one proxy or nothing outbound"
        (let [off (compile {})
              proxied (compile {:network {:proxy 5555} :inbound [4200]})]
          (is (str/includes? off "(deny network*)"))
          (is (str/includes? off "(allow network-inbound (local ip \"localhost:*\"))"))
          (is (not (str/includes? off "network-outbound")))
          (is (str/includes? proxied "(allow network-outbound (remote ip \"localhost:5555\"))"))
          (is (str/includes? proxied "(allow network-inbound (local ip \"*:4200\"))"))
          (is (str/includes? (compile {:network :open}) "(allow network*)"))))
      (testing "one exact Unix control socket is reachable after the network deny"
        (let [socket (str root "/control.sock")
              _ (spit socket "")
              p (compile {:unix-connect [socket]})]
          (is (str/includes? p (str "(remote unix-socket(path \"" socket "\"))")))
          (is (< (str/index-of p "(deny network*)")
                 (str/index-of p "(remote unix-socket")))))
      (testing "keychain opens the Security services and the keychain databases"
        (let [p (compile {:keychain? true})]
          (is (str/includes? p "(allow mach-lookup(global-name \"com.apple.SecurityServer\")"))
          (is (str/includes? p "com.apple.trustd.agent")))
        (is (not (str/includes? (compile {}) "mach-lookup"))))
      (finally (delete-tree root)))))

(deftest bubblewrap-compiler-test
  (let [root (temp-dir)
        compile #(vec (Bubblewrap/compile (runtime/jail-policy (merge {:read-write [(str root)]} %))))]
    (try
      (testing "the argument list is bubblewrap's policy flags ending in --"
        (let [args (compile {})]
          (is (= ["--die-with-parent" "--proc" "/proc" "--dev" "/dev"] (subvec args 0 5)))
          (is (= "--" (peek args)))
          (is (some #{["--bind-try" (str root) (str root)]} (partition 3 1 args)))
          (is (some #{["--ro-bind-try" "/usr" "/usr"]} (partition 3 1 args)))))
      (testing "the network is unshared only when no bridge owns the namespace"
        (is (some #{"--unshare-net"} (compile {})))
        (is (not-any? #{"--unshare-net"} (compile {:network {:proxy 5555}})))
        (is (not-any? #{"--unshare-net"} (compile {:inbound [4200]})))
        (is (not-any? #{"--unshare-net"} (compile {:network :open}))))
      (testing "the bridge takes the proxy port and the first inbound port"
        (let [p (runtime/jail-policy {:network {:proxy 5555} :inbound [4200 4300]})]
          (is (= 5555 (Bubblewrap/proxyPort p)))
          (is (= 4200 (Bubblewrap/inboundPort p)))
          (is (= 0 (Bubblewrap/proxyPort (runtime/jail-policy {:network :open}))))))
      (testing "deny-read masks a directory with tmpfs and a file with /dev/null"
        (let [dir (str (Files/createDirectory (.resolve root "hidden") (make-array FileAttribute 0)))
              file (str (Files/createFile (.resolve root "secret") (make-array FileAttribute 0)))
              args (compile {:deny-read [dir file]})]
          (is (some #{["--tmpfs" dir]} (partition 2 1 args)))
          (is (some #{["--ro-bind-try" "/dev/null" file]} (partition 3 1 args)))))
      (finally (delete-tree root)))))

(deftest native-bridge-does-not-interpret-policy-arguments-test
  (let [source (slurp "native/visjail/visjail.c")]
    (is (not (str/includes? source "--unshare-net"))
        "the native transport receives bridge ports but never parses compiler output")))

(deftest pipes-environment-and-seatbelt-or-bubblewrap-test
  (let [root (temp-dir)
        outside (io/file (System/getProperty "user.home")
                         (str ".visjail-denied-" (System/nanoTime)))
        inside (.resolve root "inside.txt")]
    (try
      (testing "ordinary process semantics survive the native boundary"
        (let [result (run-process
                      ["/bin/sh" "-c"
                       (str "printf '%s' \"$VALUE\" > " inside
                            "; printf stdout; printf stderr >&2; exit 7")]
                      (update (options root) :environment assoc "VALUE" "from-env"))]
          (is (= 7 (:exit result)))
          (is (= "stdout" (:out result)))
          (is (str/ends-with? (:err result) "stderr"))
          (is (= "from-env" (slurp (str inside))))))
      (testing "a confined child is marked so a descendant never applies a second profile"
        (is (= "1\n" (:out (run-process ["/bin/sh" "-c" "echo $VIS_SEATBELT_ACTIVE"] (options root))))))
      (testing "an ungranted host path stays unwritable"
        (let [result (run-process ["/bin/sh" "-c" (str "printf escaped > " outside)]
                                  (options root))]
          (is (not (zero? (:exit result))))
          (is (not (.exists outside)))))
      (testing "a deny-read path under a granted root stays unreadable"
        (let [secret (.resolve root "secret.txt")
              _ (spit (str secret) "hidden")
              result (run-process ["/bin/sh" "-c" (str "cat " secret)]
                                  (options root {:policy {:read-write [(str root)]
                                                          :deny-read [(str secret)]}}))]
          (is (not (str/includes? (:out result) "hidden")))))
      (testing "the process is a detached group leader and can be terminated as a tree"
        (let [p (runtime/spawn-process!
                 ["/bin/sh" "-c" "sleep 30 & wait"] (options root))]
          (.destroyForcibly p)
          (is (.waitFor p 5 TimeUnit/SECONDS))
          (is (not (.isAlive p)))))
      (finally
        (when (.exists outside) (io/delete-file outside true))
        (delete-tree root)))))

(deftest filtered-egress-crosses-only-the-native-loopback-bridge-test
  (let [root (temp-dir)
        allowed (loopback-server)
        denied (loopback-server)
        allowed-port (.getLocalPort allowed)
        denied-port (.getLocalPort denied)
        responder (future
                    (with-open [socket (.accept allowed)
                                ^java.io.BufferedReader reader (io/reader (.getInputStream socket))
                                writer (io/writer (.getOutputStream socket))]
                      (when (= "ping" (.readLine reader))
                        (.write writer "pong\n")
                        (.flush writer))
                      :responded))]
    (try
      (let [script (str "exec 3<>/dev/tcp/127.0.0.1/" allowed-port "\n"
                        "printf 'ping\\n' >&3\n"
                        "IFS= read -r reply <&3\n"
                        "if exec 4<>/dev/tcp/127.0.0.1/" denied-port " 2>/dev/null; "
                        "then printf '%s:escaped' \"$reply\"; "
                        "else printf '%s:blocked' \"$reply\"; fi")
            opts (options root {:policy {:read-write [(str root)] :network {:proxy allowed-port}}})
            result (run-process ["/bin/bash" "-c" script] opts)]
        (is (= 0 (:exit result)) (:err result))
        (is (= "pong:blocked" (:out result))
            "the configured loopback bridge is reachable, every other host socket is not")
        (is (= :responded (deref responder 5 ::timeout))
            "the host-side proxy endpoint must receive the bridged connection"))
      (finally
        (.close allowed)
        (.close denied)
        (delete-tree root)))))

(deftest loopback-listener-is-reachable-from-the-host-test
  (when (macos?)
    (let [root (temp-dir)]
      (try
        (let [port (with-open [s (loopback-server)] (.getLocalPort s))
              p (runtime/spawn-process!
                 ["/usr/bin/nc" "-l" "127.0.0.1" (str port)]
                 (options root))
              connected (loop [n 50]
                          (let [ok (try (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
                                          (.isConnected s))
                                        (catch java.io.IOException _ false))]
                            (cond ok true
                                  (or (zero? n) (not (.isAlive p))) false
                                  :else (do (Thread/sleep 100) (recur (dec n))))))]
          (is connected "a confined server may accept on any loopback port with nothing listed")
          (.destroyForcibly p)
          (.waitFor p 5 TimeUnit/SECONDS))
        (finally (delete-tree root))))))

(deftest pty-round-trip-test
  (let [root (temp-dir)]
    (try
      (let [p (runtime/spawn-process!
               ["/bin/sh" "-c" "stty size; IFS= read -r value; printf 'got:%s\n' \"$value\""]
               (options root {:pty? true :merge-stderr? true :rows 31 :columns 97}))]
        (.write (.getOutputStream p) (.getBytes "hello\n" "UTF-8"))
        (.flush (.getOutputStream p))
        (is (= 0 (.waitFor p)))
        (let [out (slurp (.getInputStream p))]
          (is (str/includes? out "31 97"))
          (is (str/includes? out "got:hello"))))
      (finally (delete-tree root)))))

(deftest unconfined-process-still-uses-native-lifecycle-test
  (let [root (temp-dir)]
    (try
      (let [result (run-process ["/bin/sh" "-c" "printf %s \"$VALUE\"; printf %s \"$VIS_SEATBELT_ACTIVE\""]
                                {:environment {"PATH" "/usr/bin:/bin" "VALUE" "direct"}
                                 :directory (str root)})]
        (is (= {:exit 0 :out "direct" :err ""} result)))
      (finally (delete-tree root)))))

(deftest unenforceable-host-refuses-the-spawn-test
  (is (nil? (runtime/jail-unsupported-reason)) "this suite runs where the jail is enforceable")
  (is (false? (runtime/jailed?)))
  (is (thrown? IllegalArgumentException
               (runtime/spawn-process! ["/bin/true"] {:policy {:network {:proxy 70000}}}))
      "a policy that cannot be compiled never reaches the kernel"))
