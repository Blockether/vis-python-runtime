(ns com.blockether.vis-python-runtime.session-defs-test
  "The helpers a session defines, across a PROCESS: `__vis_defs_snapshot__`,
   `__vis_restore_defs__` and the `defs()` verb a block reads them back with.

   A sandbox dies with its process. Without these three, a host restart lost
   every helper the session had refined while its transcript still showed them —
   the next call raised `NameError` against code the model could read but not
   run. So the runtime answers SOURCE TEXT that re-creates the session's own
   definitions, takes that text back in a fresh session, and lists what it holds.

   What each of these cases pins is a way that can go quietly wrong: a helper
   restored from RAW source is a different language from the one the session
   wrote (no `await` rewrite), one unparseable line costs the WHOLE toolbox
   rather than itself, a class or a closed-over constant left behind makes the
   restored helper a `NameError` on first call, a multi-megabyte global costs a
   `repr` per block for text the cap throws away, and a definition named after a
   bound tool writes straight over the tool for the rest of the process. The
   host on the other side only moves the text; every rule here is the runtime's."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]
            [com.blockether.vis-python-runtime :as runtime]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- ran
  "What a block PRINTED, trimmed — a block's one success channel."
  [session code]
  (str/trim (str (:stdout (block session code)))))

(defn- snapshot
  "The source text that re-creates `session`'s definitions elsewhere."
  [session]
  (harness/ev session "__vis_defs_snapshot__()"))

(defn- restore!
  "Replay `src` into `session`, answering how many definitions it ends up with.

   The text crosses as a JSON string literal, which is what the host would hand
   back after reading the file it wrote beside the session. Slash escaping is
   off because `\\/` is JSON's own, not Python's — a path in a restored constant
   would come back with a backslash in it."
  [session src]
  (runtime/exec! session (str "__vis_snapshot_text__ = " (json/write-str src :escape-slash false)))
  (harness/ev session "__vis_restore_defs__(__vis_snapshot_text__)"))

(defn- across-processes
  "Run `setup` in one session, carry its snapshot into a FRESH one, and answer
   `{:snapshot :restored :stdout}` — exactly the move a host restart makes."
  [setup probe]
  (let [written
        (harness/block-session)

        _
        (block written setup)

        text
        (snapshot written)

        fresh
        (harness/block-session)

        restored
        (restore! fresh text)]

    {:snapshot text :restored restored :stdout (ran fresh probe)}))

(harness/defbuilt-test
  session-defs-round-trip-test
  (let [{:keys [snapshot restored stdout]}
        (across-processes (str "ROOT = \"/tmp/vis-defs-probe\"\n"
                               "import json as J\n"
                               "def shout(s):\n    return s.upper()\n")
                          (str "print(shout(\"ok\"))\n" "print(ROOT, J.dumps([1]))\n"
                               "import inspect\n"
                               "print(inspect.getsource(shout).splitlines()[0])\n"))]
    (testing "a helper, its module alias and its constant come back in a fresh session"
      (is (= 1 restored))
      (is (str/includes? snapshot "def shout(s):"))
      (is (str/includes? stdout "OK"))
      (is (str/includes? stdout "/tmp/vis-defs-probe [1]")))
    (testing "restored source reads back like a local one"
      ;; This is what makes a helper REFINABLE next turn instead of re-pasted:
      ;; `inspect` (and `defs(\"name\")`) resolve it through the block source the
      ;; restore registered, not through a file that never existed.
      (is (str/includes? stdout "def shout(s):")))))

(harness/defbuilt-test session-defs-listed-as-restored-test
                       (let [written
                             (harness/block-session)

                             _
                             (block written "def widen(a, b=2):\n    return a * b\n")

                             fresh
                             (harness/block-session)

                             _
                             (restore! fresh (snapshot written))]

                         (testing "a restored helper is listed, and marked as restored"
                           (let [listed (ran fresh "print(defs())")]
                             (is (str/includes? listed "widen(a, b=2)"))
                             (is (str/includes? listed "(restored)"))))))

(harness/defbuilt-test
  session-defs-partial-restore-test
  (testing "every definition that still loads survives one statement that does not"
    ;; A shim this build no longer ships, or a default argument that no longer
    ;; resolves, must cost ITSELF — the whole-file exec falls back to statement
    ;; by statement.
    (let [fresh
          (harness/block-session)

          restored
          (restore! fresh
                    (str "import totally_missing_module as tm\n"
                         "BROKEN = undefined_name\n"
                         "def survivor(x):\n    return x * 2\n"))]

      (is (= 1 restored))
      (is (= "42" (ran fresh "print(survivor(21))")))))
  (testing "a snapshot that will not parse answers zero and leaves the sandbox usable"
    (let [fresh
          (harness/block-session)

          restored
          (restore! fresh "def broken(:\n    ???\n")]

      (is (= 0 restored))
      (is (= "2" (ran fresh "print(1 + 1)"))))))

(harness/defbuilt-test
  session-defs-nested-and-aliased-test
  ;; A helper defined inside `if:`/`try:`/another `def` reads back INDENTED, and
  ;; one indented line used to make the whole snapshot unparseable — which cost
  ;; the session EVERY helper, not just that one.
  (let [{:keys [snapshot restored stdout]}
        (across-processes
          (str "def outer():\n    def inner(a):\n        return a * 2\n\n    return inner\n"
               "twice = outer()\n"
               "if True:\n\n    def gated(x):\n        return x + 1\n"
               "def plain(x):\n    return x - 1\n")
          "print(twice(4), gated(1), plain(3))\n")]
    (testing "the whole toolbox comes back when a helper is nested or bound under another name"
      (is (= 4 restored))
      (is (= "8 2 2" stdout)))
    (testing
      "a closure is rebound to the name the session calls it by, and the private name dropped"
      ;; `twice = outer()` reads back as the source of `def inner`, so the chunk
      ;; has to bind `inner` to rebind `twice` — and a restored sandbox should
      ;; list the helpers the session HAS, not the private names inside them.
      (is (str/includes? snapshot "twice = inner"))
      (is (str/includes? snapshot "del inner")))))

(harness/defbuilt-test
  session-defs-carries-classes-and-config-test
  ;; Only functions and scalars were snapshotted once, so the class and the
  ;; config dict a helper closes over never came back: the restored helper
  ;; raised `NameError` on its first call — callable and useless.
  (let [{:keys [restored stdout]}
        (across-processes (str "from dataclasses import dataclass\n"
                               "CFG = {\"depth\": 2}\n"
                               "@dataclass\nclass Point:\n    x: int = 0\n    y: int = 0\n\n"
                               "class Node:\n    def __init__(self, v):\n        self.v = v\n\n"
                               "def origin():\n    return Point(1, 2)\n"
                               "def node(v):\n    return Node(v).v\n"
                               "def depth(p, cfg=CFG):\n    return cfg[\"depth\"] + p\n")
                          "print(origin(), node(7), depth(1))\n")]
    (testing "the class, the dataclass and the config its helpers need come back with them"
      (is (= 3 restored))
      (is (= "Point(x=1, y=2) 7 3" stdout)))))

(harness/defbuilt-test session-defs-skips-a-value-too-big-to-store-test
                       ;; The snapshot used to `repr` every global BEFORE checking its size, so one
                       ;; multi-megabyte string cost ~130ms per block to render text the cap then
                       ;; threw away.
                       (let [{:keys [snapshot stdout]}
                             (across-processes (str "blob = \"x\" * 400000\n"
                                                    "def small(x):\n    return x\n")
                                               "print(\"blob\" in globals(), small(1))\n")]
                         (testing "an oversized constant is never carried, and the helper still is"
                           (is (> 2000 (count snapshot)))
                           (is (= "False 1" stdout)))))

(harness/defbuilt-test session-defs-keeps-a-decorated-helper-test
                       ;; `functools.lru_cache` answers a wrapper with no `__code__` of its own, so a
                       ;; helper vanished from `defs()` and from the snapshot the moment it was
                       ;; decorated — the code object is read THROUGH `__wrapped__`.
                       (let [{:keys [restored stdout]}
                             (across-processes
                               (str "import functools\n"
                                    "@functools.lru_cache\ndef squared(n):\n    return n * n\n")
                               "print(squared(5))\nprint(\"squared\" in defs())\n")]
                         (testing "a decorated helper stays listed, and restores"
                           (is (= 1 restored))
                           (is (= "25\nTrue" stdout)))))

(harness/defbuilt-test
  session-defs-restores-with-the-block-rewrite-test
  ;; A restored helper used to be exec'd from RAW source, so it MISSED the
  ;; rewrite every locally-defined helper gets: `await` on an already-settled
  ;; value raised inside a helper that had worked all session, and a plain `def`
  ;; whose body awaits did not compile at all — dropped from the toolbox instead
  ;; of being promoted to `async def`.
  (let [{:keys [restored stdout]}
        (across-processes (str "async def unwrap(v):\n    r = await v\n    return r\n"
                               "def twice_unwrapped(v):\n    return await unwrap(v) * 2\n")
                          (str "print(await unwrap(41))\n" "print(await twice_unwrapped(21))\n"))]
    (testing "an awaiting helper is restored with the same rewrite a local one gets"
      (is (= 2 restored))
      (is (= "41\n42" stdout)))))

(harness/defbuilt-test session-defs-snapshot-is-empty-without-definitions-test
                       (let [session (harness/block-session)]
                         (block session "x = 1")
                         (testing "a session that defined nothing snapshots to nothing"
                           ;; Empty text is what tells the host to write no file and drop a stale one:
                           ;; a session with no helpers must not restore yesterday's.
                           (is (= "" (snapshot session))))))

(harness/defbuilt-test
  defs-verb-test
  ;; Listing your own helpers meant writing a `globals()`/`co_filename`
  ;; comprehension by hand every time, and reading one back meant remembering
  ;; `inspect`.
  (let [session
        (harness/block-session)

        empty
        (ran session "print(defs())")

        _
        (block session "from json import dumps\ndef widen(a, b=2):\n    return a * b\n")

        listed
        (ran session "print(defs())")

        source
        (ran session "print(defs(\"widen\"))")

        missing
        (ran session
             (str "try:\n" "    defs(\"nope\")\n"
                  "except NameError as exc:\n" "    print(\"refused:\", exc)\n"))]

    (testing "an empty session says what would fill the list"
      (is (str/includes? empty "no functions defined by this session yet")))
    (testing "the listing carries the signature, and only definitions this session wrote"
      (is (str/includes? listed "widen(a, b=2)"))
      ;; An IMPORTED function is not this session's definition: a `def` is
      ;; recognized by the synthetic `<prog:N>` filename of its code object.
      (is (not (str/includes? listed "dumps"))))
    (testing "one name answers that helper's source"
      (is (str/includes? source "def widen(a, b=2):")))
    (testing "a name this session never defined is refused, and names the ones it did"
      (is (str/includes? missing "refused:"))
      (is (str/includes? missing "widen")))))

(harness/defbuilt-test
  defs-docstring-surface-test
  ;; A helper the session wrote is a DOCUMENT: its docstring is what the listing
  ;; previews and what the host publishes as its page. `__vis_def_docs__` and
  ;; `__vis_def_calls__` are the runtime's side of that — the host reads them,
  ;; it does not compute them.
  (let [session
        (harness/block-session)

        _
        (block session
               (str "def kebab_to_snake(text):\n"
                    "    \"\"\"Rewrite a kebab-case identifier as snake_case.\n\n"
                    "    Splits on the hyphen the way the wire keys do, so a wire name\n"
                    "    and an engine keyword round-trip.\n" "    \"\"\"\n"
                    "    return text.replace('-', '_')\n\n" "def quiet(x):\n    return x\n"))

        listed
        (ran session "print(defs())")

        docs
        (harness/printed (block session "print(json.dumps(__vis_def_docs__()))"))

        calls
        (harness/printed (block session "print(json.dumps(__vis_def_calls__()))"))]

    (testing "the listing previews the docstring's first line, and counts what is missing"
      (is (str/includes? listed "Rewrite a kebab-case identifier as snake_case."))
      (is (str/includes? listed "1 has no docstring")))
    (testing "the whole docstring is readable per helper, and an undocumented one carries none"
      ;; The empty document is deliberate: it is what keeps a bare handle out of
      ;; a described search on the host's side.
      (is (str/includes? (get docs "kebab_to_snake") "Splits on the hyphen"))
      (is (= "" (get docs "quiet"))))
    (testing "every helper has a call line, documented or not"
      (is (= {"kebab_to_snake" "kebab_to_snake(text)" "quiet" "quiet(x)"} calls)))))

(harness/defbuilt-test
  tool-shadow-refusal-test
  ;; A helper named after a bound tool was accepted in silence and then quietly
  ;; dropped: the name is left out of the block wrapper's `global` list, so
  ;; `def patch(...)` lived and died inside its own block, was never snapshotted
  ;; — the snapshot skips protected names — and the next block silently got the
  ;; tool back. A helper the session cannot keep is refused where it is written.
  (let [session (harness/tool-session {"shadow_probe" (fn [_]
                                                        "REAL-TOOL")})]
    (testing "a top-level def named after a bound tool is refused, with the fix in the message"
      (let [refused (str (:error (block session "def shadow_probe(a):\n    return a\n")))]
        (is (str/includes? refused "`shadow_probe` is a bound tool"))
        (is (str/includes? refused "shadow_probe_mine"))))
    (testing "a class is refused the same way, and the sandbox's own verbs are protected too"
      (is (str/includes? (str (:error (block session "class defs:\n    pass\n")))
                         "`defs` is a bound tool")))
    (testing "a def nested in another function is an ordinary local, not a shadow"
      (is (= "7"
             (ran session
                  (str "def outer():\n" "    def defs(x):\n        return x\n"
                       "    return defs(7)\n" "print(outer())\n")))))
    (testing "a plain assignment is still a block-local shadow, and the tool is back next block"
      (is (= "a string" (ran session "shadow_probe = 'a string'\nprint(shadow_probe)")))
      (is (= "REAL-TOOL" (ran session "print(await shadow_probe('x'))"))))))

(harness/defbuilt-test
  restore-never-overwrites-a-bound-tool-test
  ;; The same trap across processes: a snapshot written before a tool existed
  ;; re-created `def patch(...)` straight over the real one for the whole
  ;; process, and the restored count never noticed because it skips protected
  ;; names. Statements are dropped by the names they BIND, so an alias line or a
  ;; constant goes the same way, and the next snapshot no longer carries them —
  ;; the file heals itself.
  (let [session
        (harness/tool-session {"probe_tool" (fn [_]
                                              "REAL-TOOL")})

        restored
        (restore! session
                  (str "def probe_tool(*a, **k):\n    return \"HIJACKED\"\n\n"
                       "defs = \"clobbered\"\n"
                       "def kept(n):\n    return n * 3\n"))

        out
        (ran session
             (str "print(kept(2))\n" "print(\"HIJACKED\" in str(defs()))\n"
                  "print(callable(defs))\n" "print(await probe_tool(\"x\"))\n"))

        dropped
        (harness/printed (block session "print(json.dumps(__vis_restore_dropped__))"))]

    (testing "only the definition whose name is free is restored, and counted"
      (is (= 1 restored))
      (is (str/includes? out "6")))
    (testing "the bound tool and the sandbox verb survive the replay intact"
      (is (str/includes? out "False"))
      (is (str/includes? out "True"))
      (is (str/includes? out "REAL-TOOL")))
    (testing "what the replay dropped is readable, by name"
      (is (= ["defs" "probe_tool"] dropped)))))
