(ns com.blockether.vis-python-runtime.host-test
  "The door from the sandbox back to the host: `bind-host!`, `install-tool!` and
   what a block sees when it calls one.

   A tool is host code the guest calls — `grep(...)` reads as Python and runs as
   Clojure. GraalPy passed the host object in as a foreign proxy; CPython has no
   such object, so the door is one function pointer the host registers (an FFM
   upcall stub) and one builtin module the guest calls it through. Only TEXT
   crosses: the JSON envelope is the runtime's, and the C boundary reads none of
   it.

   What these cases pin is everything that shape can get wrong: arguments and
   values arriving as data, deferral (a tool is awaited, gathered and settled
   like any other), a host failure arriving as a catchable exception with the
   host's own message, an answer larger than the buffer costing a retry but
   never a second RUN of the tool, and UTF-8 surviving both crossings."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]
            [com.blockether.vis-python-runtime :as runtime]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally (harness/close-sessions!)))))

(defn- ran
  "What a block PRINTED, trimmed — a block's one success channel."
  [session code]
  (str/trim (str (:stdout (block session code)))))

(defn- echo
  "The tool every case here reaches for: `<x>`."
  [args]
  (str "<" (first args) ">"))

(harness/defbuilt-test host-tool-arguments-test
  (let [session (harness/tool-session {"describe" (fn [args] {"args" args})})]
    (testing "arguments arrive at the host as data, keywords folded into a trailing map"
      ;; The fold is the sandbox's own (`__vis_Call__`), and it is why a vis tool
      ;; reads `find("x", paths=[…])` as one options map rather than as kwargs.
      (is (= {"args" ["hi" 2 {"deep" true}]}
             (harness/printed (block session "print(json.dumps(await describe('hi', 2, deep=True)))")))))
    (testing "the host's value arrives as a mapping, not as a repr"
      (is (= "__VisDict__" (ran session "print(type(await describe()).__name__)"))
          "a real dict, subclassed only so a missing key names the keys there are"))))

(harness/defbuilt-test host-tool-deferred-test
  ;; A tool is not an ordinary function: calling one hands back a thunk, which is
  ;; the seam `await`, `gather` and top-level auto-settle are built on. A host
  ;; tool has to be deferred the same way a Python-side one is.
  (let [calls   (atom 0)
        session (harness/tool-session {"tick" (fn [_] (swap! calls inc))
                                       "echo" echo})]
    (testing "await runs a nested host call"
      (is (= "<hi>" (ran session "print(await echo('hi'))"))))
    (testing "a bare top-level call runs exactly once"
      (reset! calls 0)
      (is (= "" (ran session "tick()")))
      (is (= 1 @calls)))
    (testing "gather settles two host calls in one block"
      (is (= "['<a>', '<b>']" (ran session "print(await gather(echo('a'), echo('b')))"))))))

(harness/defbuilt-test host-failure-catchable-test
  (let [session (harness/tool-session {"boom" (fn [_] (throw (ex-info "host said no" {})))
                                       "echo" echo})]
    (testing "`except Exception` sees the host's own message"
      (is (= "caught: host said no"
             (ran session (str "try:\n"
                               "    await boom()\n"
                               "except Exception as e:\n"
                               "    print('caught:', e)")))))
    (testing "the block carries on after catching one"
      (is (= "<after>"
             (ran session (str "try:\n"
                               "    await boom()\n"
                               "except Exception:\n"
                               "    pass\n"
                               "print(await echo('after'))")))))))

(harness/defbuilt-test host-reply-larger-than-the-buffer-test
  ;; A reply bigger than the buffer C offered is a RETRY with room, never a
  ;; second run: a tool that wrote a file would have written it twice.
  (let [calls   (atom 0)
        session (harness/tool-session {"big" (fn [_]
                                               (swap! calls inc)
                                               (str/join (repeat 200000 "x")))})]
    (testing "the whole answer comes back"
      (is (= "200000" (ran session "print(len(await big()))"))))
    (testing "the tool ran once"
      (is (= 1 @calls)))))

(harness/defbuilt-test host-utf8-test
  (let [session (harness/tool-session {"shout" (fn [args] (str (first args) " zażółć"))})]
    (testing "text survives both crossings, characters and not bytes"
      (is (= "gęślą zażółć" (ran session "print(await shout('gęślą'))")))
      (is (= "12" (ran session "print(len(await shout('gęślą')))"))))))

(harness/defbuilt-test host-door-is-text-test
  (let [session (harness/tool-session {"echo" echo})]
    (testing "the raw door carries text and answers text"
      (is (= "{\"value\":\"<x>\"}"
             (harness/ev session "import vis_runtime\nvis_runtime.host_call('echo', '{\"args\": [\"x\"], \"kwargs\": {}}')"))))
    (testing "a name the host does not know is the host's answer, not a crash"
      (is (str/includes? (harness/ev session "import vis_runtime\nvis_runtime.host_call('nope', '{}')")
                         "no tool named nope")))))

(harness/defbuilt-test host-tool-is-protected-test
  (let [session (harness/tool-session {"echo" echo})]
    (testing "a bound tool is a name the block may not shadow"
      (is (harness/truthy session "'echo' in __vis_protected_names__")))))

(harness/defbuilt-test unbound-host-test
  ;; Unbinding must leave the guest with an exception it can read, not with a
  ;; call into a pointer nobody owns any more.
  (let [session (harness/tool-session {"echo" echo})]
    (try
      (runtime/bind-host! nil)
      (is (str/includes? (str (:error (block session "print(await echo('x'))")))
                         "no host is bound"))
      (finally
        (harness/bind-tools! {"echo" echo})))
    (testing "rebinding brings the same session's tool back"
      (is (= "<x>" (ran session "print(await echo('x'))"))))))

(harness/defbuilt-test host-tool-names-its-session-test
  ;; One interpreter holds many sessions and the door is ONE function, so the
  ;; envelope has to say which session called: a host binding `shell` for two
  ;; workspaces binds two different functions under one name.
  (let [seen (atom [])]
    (try
      (runtime/bind-host!
       (fn [session nm _payload]
         (swap! seen conj [nm session])
         (json/write-str {"value" session})))
      (let [one (harness/block-session)
            two (harness/block-session)]
        (runtime/install-tool! one "whose")
        (runtime/install-tool! two "whose")
        (testing "the tool answers each caller with its own session"
          (is (= one (ran one "print(await whose())")))
          (is (= two (ran two "print(await whose())"))))
        (testing "the host saw the name once per session, never a blank"
          (is (= [["whose" one] ["whose" two]] @seen))))
      (finally
        (harness/bind-tools! {"echo" echo})))))

(harness/defbuilt-test the-caller-is-the-interpreters-answer-test
  ;; The escalation this closes, measured before it was closed: `host_call` is an
  ;; ordinary function of `vis_runtime` and the session in its envelope is JSON
  ;; the GUEST writes, so a block that named a neighbour's session reached the
  ;; neighbour's tools — a block confined to a temp directory read, through a
  ;; tool bound in another namespace, a file the policy had refused it one
  ;; statement earlier. The session now comes from the interpreter: the nearest
  ;; calling frame whose globals is a namespace this library created.
  (let [callers (atom [])]
    (try
      (runtime/bind-host!
       (fn [session nm _payload]
         (swap! callers conj [session nm])
         (json/write-str {"value" (str "served " session)})))
      (let [privileged (harness/block-session)
            attacker (harness/block-session)]
        (runtime/install-tool! privileged "secret")
        (testing "the session that owns the tool reaches it"
          (is (= (str "served " privileged)
                 (str/trim (str (:stdout (block privileged "print(await secret())")))))))
        (testing "a block naming ANOTHER session in the envelope is answered as itself"
          ;; The forged envelope still crosses — the boundary carries text and reads
          ;; none of it — but the host is told who really called, so the neighbour's
          ;; name buys nothing.
          (let [answer (harness/ev attacker
                                   (str "import vis_runtime, json\n"
                                        "vis_runtime.host_call('secret', json.dumps("
                                        "{'session': " (pr-str privileged) ", 'args': []}))"))]
            (is (str/includes? (str answer) attacker)
                "the host was told the forged session, not the caller")
            (is (not (str/includes? (str answer) privileged)))))
        (testing "nor by running its code inside the other session's globals"
          ;; The attack that defeats an identity taken from the calling FRAME: a
          ;; session is a module in `sys.modules`, so its globals dict is there
          ;; for the taking and code exec'd into it runs in a frame that belongs
          ;; to the victim. Measured against the frame-walking version of this,
          ;; which answered with the victim. What the runtime was ASKED to run is
          ;; what answers now, and `exec` does not change that.
          (let [answer (harness/ev attacker
                                   (str "import sys, vis_runtime, json\n"
                                        "g = sys.modules[" (pr-str privileged) "].__dict__\n"
                                        "src = 'import vis_runtime, json\\n"
                                        "out = vis_runtime.host_call(\"secret\", json.dumps({\"args\": []}))'\n"
                                        "exec(compile(src, '<x>', 'exec'), g)\n"
                                        "g['out']"))]
            (is (str/includes? (str answer) attacker))
            (is (not (str/includes? (str answer) privileged)))))
        (testing "every call the host saw named the session that actually made it"
          (is (= #{privileged attacker} (set (map first @callers))))))
      (finally (runtime/bind-host! nil)))))
