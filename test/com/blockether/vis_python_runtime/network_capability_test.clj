(ns com.blockether.vis-python-runtime.network-capability-test
  "The network as a CAPABILITY: `vispython_network`, answered by the same audit
   hook as confinement.

   Whether a session may reach the network is decided in C, where a block cannot
   see it, rebind it or reach around it. Host and destination policy belongs to
   the process jail. Like confinement the flag is PROCESS state, so every test
   here puts it back."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness]))

(defn- attempt
  "Run `body` — Python that answers a string — in `session`."
  [session body]
  (harness/ev session (str "def _p():\n" body "_p()")))

(def ^:private resolves
  "Resolve a name, answering `permitted`, `refused`, or the failure's own text."
  (str "    import socket\n" "    try:\n"
       "        socket.gethostbyname('localhost')\n" "        return 'permitted'\n"
       "    except PermissionError as e:\n" "        return str(e)\n"
       "    except OSError:\n" "        return 'permitted'\n"))

(def ^:private makes-socket
  "Make a socket with no address at all: the step before any policy about hosts."
  (str "    import socket\n" "    try:\n"
       "        socket.socket().close()\n" "        return 'permitted'\n"
       "    except PermissionError:\n" "        return 'refused'\n"))

(harness/defbuilt-test
  network-capability-test
  (let [session (harness/block-session)]
    (try (testing "granted, the guest resolves names and opens sockets"
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
           (is (= "Refused: this session was granted no network." (attempt session resolves))))
         (finally (runtime/network! true "")))))

(harness/defbuilt-test
  local-async-without-network-test
  ;; Regression: asyncio's internal wakeup must not require a socket capability.
  (let [session (harness/block-session)]
    (harness/tool! session "echo" "x" "    return '<' + x + '>'")
    (runtime/exec!
      session
      "import asyncio as real_asyncio, threading, time
async def library_call():
    loop = real_asyncio.get_running_loop()
    assert real_asyncio.current_task() is not None
    timer = loop.create_future()
    loop.call_later(0.01, timer.set_result, 'timer')
    await timer
    result = loop.create_future()
    def complete():
        time.sleep(0.01)
        loop.call_soon_threadsafe(result.set_result, 'woken')
    thread = threading.Thread(target=complete)
    thread.start()
    try:
        return await result
    finally:
        thread.join()")
    (try (runtime/confine! [] [(harness/temp-dir "vis-local-async")])
         (runtime/network! false)
         (let [answer (harness/block session "print(await gather(library_call(), echo('host')))")]
           (is (nil? (:error answer)) (str (:error answer)))
           (is (= "['woken', '<host>']\n" (:stdout answer))))
         (is (= "refused" (attempt session makes-socket)))
         (finally (runtime/confine! [] []) (runtime/network! true "") (harness/close-sessions!)))))
