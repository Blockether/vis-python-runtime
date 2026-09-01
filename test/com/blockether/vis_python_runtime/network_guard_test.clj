(ns com.blockether.vis-python-runtime.network-guard-test
  "The sandbox's cooperative network policy (`resources/vis-python/network_guard.py`).

   A GUARDRAIL, not a boundary: it patches `socket` in the very interpreter the
   model drives, so it steers accidental and cooperative egress while the hard
   control stays the host's (the process jail, and the gateway proxy that owns
   verb and path). What is asserted here is the POLICY: which host a session may
   resolve, and that a raw IP cannot skip DNS to reach a denied one.

   Ported from Vis, minus the two cases that were never about this file: the
   capability itself (`network OFF` means no sockets at all) and the proxy/CA
   environment are the host's, and stay in Vis."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime.harness :as harness]))

(def ^:private metadata-hosts
  "The cloud-metadata SSRF endpoints Vis denies by default. The DEFAULT list is
   the host's configuration (`env-python/default-denied-domains`); the guard only
   enforces the list it is handed, so a test that wants them denied says so."
  ["169.254.169.254" "metadata.google.internal" "metadata.goog" "metadata"])

(defn- resolution
  "Resolve `host` in `session` and classify: `:blocked` when the guard refused,
   `:permitted` when it did not — a name that fails to resolve for the network's
   own reasons still got PAST the policy, which is what this file measures."
  [session host]
  (keyword (harness/ev session
                       (str "def _p():\n"
                            "    import socket\n"
                            "    try:\n"
                            "        socket.gethostbyname(" (pr-str host) ")\n"
                            "        return 'permitted'\n"
                            "    except PermissionError:\n"
                            "        return 'blocked'\n"
                            "    except OSError:\n"
                            "        return 'permitted'\n"
                            "_p()"))))

(defn- raw-connect
  "Classify a RAW socket connect to `host`, which never touches DNS."
  [session host]
  (keyword (harness/ev session
                       (str "def _p():\n"
                            "    import socket\n"
                            "    s = socket.socket()\n"
                            "    s.settimeout(0.2)\n"
                            "    try:\n"
                            "        s.connect((" (pr-str host) ", 9))\n"
                            "        return 'permitted'\n"
                            "    except PermissionError:\n"
                            "        return 'blocked'\n"
                            "    except Exception:\n"
                            "        return 'permitted'\n"
                            "    finally:\n"
                            "        s.close()\n"
                            "_p()"))))

(defmacro ^:private defguard-test
  "A policy test: binds `session` to a session confined to `allowed`/`denied`."
  [test-name allowed denied & body]
  `(harness/defbuilt-test ~test-name
     (let [~'session (harness/guarded-session ~allowed ~denied)]
       ~@body)))

(defguard-test star-allowlist-test
  ["*"] metadata-hosts
  (testing "a `*` allowlist permits everything the denylist does not name"
    (is (= :permitted (resolution session "localhost")))
    (is (= :blocked (resolution session "169.254.169.254"))
        "the metadata endpoint is denied even under `*`")
    (is (= :blocked (resolution session "metadata.google.internal")))))

(defguard-test allowlist-confines-test
  ["example.com"] []
  (testing "an allowlist confines the session to the hosts it names"
    (is (= :permitted (resolution session "example.com")))
    (is (= :permitted (resolution session "www.example.com"))
        "a subdomain of an allowed host is allowed")
    (is (= :blocked (resolution session "example.com.evil.test"))
        "a host that merely CONTAINS an allowed name is not a subdomain of it")
    (is (= :blocked (resolution session "evil.test")))))

(defguard-test specific-allow-beats-deny-star-test
  ["example.com"] ["*"]
  (testing "deny everything EXCEPT the allowlist"
    (is (= :permitted (resolution session "www.example.com")))
    (is (= :blocked (resolution session "evil.test")))))

(defguard-test specific-deny-beats-allow-star-test
  ["*"] ["example.com"]
  (testing "allow everything EXCEPT the denylist"
    (is (= :blocked (resolution session "example.com")))
    (is (= :blocked (resolution session "www.example.com")))
    (is (= :permitted (resolution session "localhost")))))

(defguard-test connect-level-test
  ["*"] (into ["127.0.0.1"] metadata-hosts)
  (testing "enforced at connect() too, so a raw IP cannot skip DNS"
    ;; The headline target of the default denylist is an IP literal, and
    ;; `socket.connect((ip, port))` never resolves a name: guarding only
    ;; `getaddrinfo` would leave it reachable.
    (is (= :blocked (raw-connect session "127.0.0.1")))
    (is (= :blocked (raw-connect session "169.254.169.254")))))

(harness/defbuilt-test policy-is-replaced-not-stacked-test
  ;; One interpreter, one `socket`: the guard used to wrap it again per install,
  ;; so a session's policy was every earlier session's policy AND its own, and a
  ;; host the current session allows stayed blocked forever. The wrapping happens
  ;; once now and the policy is a holder the check reads.
  (let [strict (harness/guarded-session ["example.com"] [])]
    (is (= :blocked (resolution strict "evil.test")))
    (let [open (harness/guarded-session ["*"] [])]
      (is (= :permitted (resolution open "evil.test"))
          "the later session's policy replaced the earlier one")
      (is (= :permitted (resolution strict "evil.test"))
          "and it replaced it for every session, because there is one socket"))))

;; Regression: a connect() carries the ADDRESS an allowed lookup just answered,
;; never the name the block asked for, and the guard checked that literal against
;; the DOMAIN allowlist — so every request to an ALLOWED host was refused the
;; moment its name resolved ("network host '2606:4700::…' is blocked").
(harness/defbuilt-test resolved-address-reaches-connect-test
  (let [session (harness/guarded-session ["localhost"] [])]
    (is (= :blocked (raw-connect session "127.0.0.1"))
        "an address nobody resolved is not one of the names the policy allows")
    (is (= :permitted (resolution session "localhost")))
    (is (= :permitted (raw-connect session "127.0.0.1"))
        "the address the allowed lookup answered is the one the connection uses")
    (is (= :blocked (raw-connect session "127.0.0.2"))
        "and only that address: another literal is still refused"))
  (let [session (harness/guarded-session ["localhost"] ["127.0.0.1"])]
    (is (= :permitted (resolution session "localhost")))
    (is (= :blocked (raw-connect session "127.0.0.1"))
        "a DENIED address stays denied however it was learned")))
