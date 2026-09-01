(ns com.blockether.vis-python-runtime.network-capability-test
  "The network as a CAPABILITY: `vispython_network`, answered by the same audit
   hook as confinement.

   WHETHER a session may reach the network at all is decided in C, where a block
   cannot see it, rebind it or reach around it — a socket, a name lookup and a
   connection are all refused before they exist. WHICH hosts a session that HAS
   egress may reach is `network_guard.py`, which is guidance and lives in Python
   where a refusal can be legible. Like confinement the flag is PROCESS state, so
   every test here puts it back."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness]))

(defn- attempt
  "Run `body` — Python that answers a string — in `session`."
  [session body]
  (harness/ev session (str "def _p():\n" body "_p()")))

(def ^:private resolves
  "Resolve a name, answering `permitted`, `refused`, or the failure's own text."
  (str "    import socket\n"
       "    try:\n"
       "        socket.gethostbyname('localhost')\n"
       "        return 'permitted'\n"
       "    except PermissionError as e:\n"
       "        return str(e)\n"
       "    except OSError:\n"
       "        return 'permitted'\n"))

(def ^:private makes-socket
  "Make a socket with no address at all: the step before any policy about hosts."
  (str "    import socket\n"
       "    try:\n"
       "        socket.socket().close()\n"
       "        return 'permitted'\n"
       "    except PermissionError:\n"
       "        return 'refused'\n"))

(harness/defbuilt-test network-capability-test
  ;; One interpreter has one `socket`, so an earlier test's DOMAIN policy is still
  ;; installed here. This file is about the capability underneath it: `*` allows
  ;; every host in Python, and what refuses is C or nothing.
  (let [session (harness/guarded-session ["*"] [])]
    (try
      (testing "granted, the guest resolves names and opens sockets"
        (is (true? (runtime/network! true)))
        (is (= "permitted" (attempt session resolves)))
        (is (= "permitted" (attempt session makes-socket))))
      (testing "refused, there is no socket to have and no name to learn"
        (is (false? (runtime/network! false)))
        (is (= "refused" (attempt session makes-socket)))
        (is (re-find #"network is off" (attempt session resolves))
            "the library words its own refusal when the host gave none"))
      (testing "the sentence the guest reads is the host's when it wrote one"
        (runtime/network! false "Refused: this session was granted no network.")
        (is (= "Refused: this session was granted no network."
               (attempt session resolves))))
      (finally (runtime/network! true "")))))
