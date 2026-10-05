(ns com.blockether.vis-python-runtime.session-defs-test
  "The helpers and variables a session defines, across a PROCESS: `__vis_defs_snapshot__`,
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

;; `import xml.dom.minidom` binds only `xml`, and a snapshot that wrote `import xml`
;; back left the submodule unloaded in a fresh PROCESS: `xml.dom.minidom` was an
;; AttributeError there. These sessions share one interpreter, so the probe passes
;; either way; the snapshot text pins the fix.
(harness/defbuilt-test
  session-defs-dotted-import-test
  (let [{text :snapshot out :stdout}
        (across-processes "import xml.dom.minidom\nimport json.tool as jt\n"
                          "print(xml.dom.minidom.parseString('<a/>').documentElement.tagName)\n")

        again
        (harness/block-session)

        _
        (restore! again text)]

    (testing "the snapshot imports the dotted path with its root"
      (is (= "a" out))
      (is (str/includes? text "import xml\n"))
      (is (str/includes? text "import xml.dom.minidom\n"))
      (is (str/includes? text "import json.tool as jt\n")))
    (testing "a restored session keeps the dotted path for its next snapshot"
      (is (str/includes? (snapshot again) "import xml.dom.minidom\n")))))

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
                             (is (str/includes? listed "widen(a, b=<int>)"))
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

(harness/defbuilt-test
  session-defs-skips-a-value-too-big-to-store-test
  ;; The snapshot used to `repr` every global BEFORE checking its size, so one
  ;; multi-megabyte string cost ~130ms per block to render text the cap then threw
  ;; away. A value it cannot carry is NAMED with its reason, so the restart notice
  ;; says what is gone instead of leaving a NameError to find it (Blockether/vis#305).
  (let [{:keys [snapshot stdout]}
        (across-processes (str "blob = \"x\" * 2000000\n"
                               "gen = (i for i in range(3))\n"
                               "def small(x):\n    return x\n")
                          (str "print(\"blob\" in globals(), \"gen\" in globals(), small(1))\n"
                               "lost = __vis_restore_report__[\"lost\"]\n" "print(sorted(lost))\n"
                               "print(lost[\"blob\"])\n" "print(lost[\"gen\"])\n"))]
    (testing "an oversized value is never carried, and the helper still is"
      (is (> 2000 (count snapshot)))
      (is (str/starts-with? stdout "False False 1")))
    (testing "each name left behind is reported with its reason"
      (is (str/includes? stdout "['blob', 'gen']"))
      (is (str/includes? stdout "over the 1 MiB limit for one saved value"))
      (is (str/includes? stdout "generator cannot be pickled")))))

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

(harness/defbuilt-test session-defs-snapshot-is-empty-without-session-names-test
                       (let [session (harness/block-session)]
                         (block session "print(1)")
                         (testing "a session that bound nothing snapshots to nothing"
                           ;; Empty text is what tells the host to write no file and drop a stale one:
                           ;; a session with nothing to keep must not restore yesterday's.
                           (is (= "" (snapshot session))))
                         (block session "x = 1")
                         (testing "one plain variable is enough to keep a snapshot"
                           (is (str/includes? (snapshot session) "x = 1")))
                         (block session "del x")
                         (testing "and deleting it empties the snapshot again"
                           (is (= "" (snapshot session))))))

(harness/defbuilt-test
  session-defs-carry-variables-test
  ;; Blockether/vis#305: a sandbox restart kept the helpers but dropped every
  ;; variable, so `vis_src` and `ext` — two Paths the transcript still showed —
  ;; raised NameError on the next turn. A value now travels as a pickle, and a
  ;; session class or helper inside it travels by name.
  (let [{:keys [restored stdout]}
        (across-processes (str "from pathlib import Path\n" "from dataclasses import dataclass\n"
                               "vis_src = Path(\"/tmp/vis/src\")\n" "ext = vis_src / \"ext\"\n"
                               "@dataclass\nclass Point:\n    x: int = 0\n    y: int = 0\n\n"
                               "pts = [Point(1, 2)]\n"
                               "def scale(p, k=2):\n    return Point(p.x * k, p.y * k)\n\n"
                               "handlers = {\"scale\": scale}\n"
                               "ROOT = Path(\"/tmp/root\")\n"
                               "def under(rel, base=ROOT):\n    return base / rel\n")
                          (str "print(vis_src, ext)\n" "print(handlers[\"scale\"](pts[0]))\n"
                               "print(under(\"a\"))\n" "report = __vis_restore_report__\n"
                               "print(report[\"variables\"], report[\"classes\"], "
                               "report[\"functions\"], report[\"lost\"])\n"))]
    (testing "Paths, a session-class instance and a dict holding a helper come back"
      (is (str/starts-with? stdout "/tmp/vis/src /tmp/vis/src/ext\nPoint(x=2, y=4)\n")))
    (testing "a helper whose default is a pickled value is re-created after that value"
      (is (= 2 restored))
      (is (str/includes? stdout "/tmp/root/a")))
    (testing "the restore report names what came back, and nothing is lost"
      (is (str/ends-with? stdout
                          (str "['ROOT', 'ext', 'handlers', 'pts', 'vis_src'] ['Point'] "
                               "['scale', 'under'] {}"))))))

(harness/defbuilt-test
  session-defs-carry-tool-results-test
  ;; Regression Blockether/vis#317: a tool result never reached the snapshot. Its
  ;; record class is built for each result and bound to no module name, so pickle
  ;; could not find it. A runtime class inside a value named the old session module,
  ;; and a restarted sandbox has a new one. The writer closes first, as in a restart.
  (let [issue
        {"__vis_object__" "Issue"
         "__vis_attrs__" {"number" 317 "labels" ["bug"] "meta" {"state" "open"}}}

        written
        (harness/tool-session {"tracker.find" (fn [_]
                                                {"__vis_object__" "IssueSearch"
                                                 "__vis_sequence_field__" "results"
                                                 "__vis_attrs__"
                                                 {"query" "pickle" "results" [issue] "total" 1}})
                               "tracker.rows" (fn [_]
                                                [{"number" 317}])})

        local
        (ran written
             (str "import copy, pickle\n"
                  "res = await tracker.find('pickle')\n" "rows = await tracker.rows()\n"
                  "kept = {'res': res, 'all': [res], 'rows': rows}\n"
                  "back = pickle.loads(pickle.dumps(res))\n"
                  "deep = copy.deepcopy(res)\n"
                  "print(type(back).__name__, back.results[0].meta['state'], deep.total)\n"))

        listing
        (ran written "print(defs())")

        text
        (snapshot written)

        _
        (runtime/close-session! written)

        fresh
        (harness/block-session)

        _
        (restore! fresh text)

        stdout
        (ran fresh
             (str
               "print(__vis_restore_report__['lost'])\n"
               "import dataclasses\n" "issue = res.results[0]\n"
               "print(type(res).__name__, res.total, [i.number for i in res], res['query'])\n"
               "print(type(issue).__name__, issue.labels, issue.meta['state'])\n"
               "print(type(issue.meta) is __VisDict__, type(kept['rows']) is __VisResultList__)\n"
               "print(kept['all'][0] is kept['res'], kept['rows'][0]['number'])\n"
               "try:\n    res.total = 2\n"
               "except dataclasses.FrozenInstanceError:\n    print('frozen')\n"))]

    (testing "a record pickles and copies by value inside its session"
      (is (= "IssueSearch open 1" local)))
    (testing "the snapshot saves a record and a container that holds one"
      (is (str/includes? listing "res: IssueSearch | saved"))
      (is (not (str/includes? listing "not saved"))))
    (testing "records and runtime results come back under a new session module"
      (is (= (str "{}\nIssueSearch 1 [317] pickle\nIssue ['bug'] open\nTrue True\nTrue 317\n"
                  "frozen")
             stdout)))))

(harness/defbuilt-test
  session-defs-keep-small-result-types-test
  ;; Follow-up to Blockether/vis#317: a small value was saved as a literal, and a
  ;; literal names only its base type. A small tool result came back as a plain
  ;; dict, list or str, so its first `.field` read failed. A list that holds itself
  ;; came back as `[[Ellipsis]]`. These values go to pickle; a plain constant stays
  ;; readable source.
  (let [written
        (harness/tool-session {"tracker.meta" (fn [_]
                                                {"state" "open" "count" 2})
                               "tracker.rows" (fn [_]
                                                [{"number" 317}])
                               "tracker.title" (fn [_]
                                                 "Snapshot types")})

        local
        (ran written
             (str "meta = await tracker.meta()\n" "rows = await tracker.rows()\n"
                  "title = await tracker.title()\n" "pair = {'meta': meta}\n"
                  "loop = []\n" "loop.append(loop)\n"
                  "CFG = {'a': [1, 2]}\n"
                  "print(type(meta).__name__, type(rows).__name__, type(title).__name__)\n"))

        text
        (snapshot written)

        _
        (runtime/close-session! written)

        fresh
        (harness/block-session)

        _
        (restore! fresh text)

        stdout
        (ran fresh
             (str "print(__vis_restore_report__['lost'])\n"
                  "print(type(meta).__name__, meta.state, meta.count)\n"
                  "print(type(rows).__name__, rows[0].number, rows.get('op'))\n"
                  "print(type(title).__name__, title.get('op'), title)\n"
                  "print(type(pair['meta']).__name__, pair['meta'].state)\n"
                  "print(loop[0] is loop, CFG)\n"))]

    (testing "the tools answer the runtime result types"
      (is (= "__VisDict__ __VisResultList__ __VisResultStr__" local)))
    (testing "a plain constant stays readable source"
      (is (str/includes? text "CFG = {'a': [1, 2]}")))
    (testing "small results and a list that holds itself keep their type after a restart"
      (is (= (str "{}\n__VisDict__ open 2\n__VisResultList__ 317 None\n"
                  "__VisResultStr__ None Snapshot types\n__VisDict__ open\nTrue {'a': [1, 2]}")
             stdout)))))

(harness/defbuilt-test
  session-defs-values-share-one-budget-test
  ;; The snapshot is rewritten after every block, so all values together are
  ;; capped too, and the value that no longer fits is named, not dropped quietly.
  (let [session
        (harness/block-session)

        _
        (block session
               (str/join "\n"
                         (for [i (range 5)]
                           (format "part_%d = \"%d\" * 900000" i i))))

        text
        (snapshot session)

        listing
        (ran session "print(defs())")]

    (testing "values are saved until the budget is full, then reported as not saved"
      (is (< (count text) (* 6 1024 1024)))
      (is (str/includes? listing "part_3: str, 900000 chars | saved"))
      (is (str/includes? listing "part_4: str, 900000 chars | not saved: str does not fit")))))

(harness/defbuilt-test session-defs-del-drops-and-frees-a-variable-test
                       ;; `del name` is how a session retires a variable: the next snapshot must not
                       ;; carry it, and its memory must go now — a reference cycle would otherwise
                       ;; hold it until some later collection.
                       (let [session
                             (harness/block-session)

                             _
                             (block session
                                    (str "import weakref\n"
                                         "class Node:\n    pass\n\n" "node = Node()\n"
                                         "node.me = node\n" "probe = weakref.ref(node)\n"))

                             before
                             (snapshot session)

                             _
                             (block session "del node")

                             after
                             (snapshot session)

                             alive
                             (ran session "print(probe() is not None)")]

                         (testing "the deleted variable leaves the snapshot"
                           (is (str/includes? before "'node'"))
                           (is (not (str/includes? after "'node'"))))
                         (testing "and its memory is released at once, reference cycle included"
                           (is (= "False" alive)))))

(harness/defbuilt-test
  session-defs-report-a-value-whose-helper-is-gone-test
  ;; A value that points at a session helper loads only once that helper is
  ;; back. When the helper cannot be re-created, the value is reported lost with
  ;; its reason instead of surfacing later as a NameError.
  (let [written
        (harness/block-session)

        _
        (block written "def shout(s):\n    return s.upper()\n\nhandlers = {\"shout\": shout}\n")

        text
        (str/replace (snapshot written) "def shout(s):\n    return s.upper()\n" "")

        fresh
        (harness/block-session)

        restored
        (restore! fresh text)

        lost
        (ran fresh "print(__vis_restore_report__[\"lost\"])")]

    (is (= 0 restored))
    (is (str/includes? lost
                       "'handlers': \"could not be restored: UnpicklingError: needs 'shout'"))))

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
      (is (str/includes? empty "nothing defined by this session yet")))
    (testing "the listing carries the signature, and only definitions this session wrote"
      (is (str/includes? listed "widen(a, b=<int>)"))
      ;; An IMPORTED function is not this session's definition: a `def` is
      ;; recognized by the synthetic `<prog:N>` filename of its code object.
      (is (not (str/includes? listed "dumps"))))
    (testing "one name answers that helper's source"
      (is (str/includes? source "def widen(a, b=2):")))
    (testing "a missing name suggests a bounded search instead of dumping the catalogue"
      (is (str/includes? missing "refused:"))
      (is (str/includes? missing "defs(pattern=")))))

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
  defs-bounded-index-test
  ;; Improve 4353: one large default inflated every aligned row, producing
  ;; multi-megabyte catalogues. Discovery must never represent runtime values.
  (let
    [session
     (harness/block-session)

     setup
     (block
       session
       (str
         "payload = 'PRIVATE_DEFAULT_SENTINEL' * 10000\n" "class Opaque:\n"
         "    def __repr__(self):\n" "        raise AssertionError('default repr must not run')\n"
         "opaque = Opaque()\n"
         "def typed(a: opaque, /, b=opaque, *args, c=payload, **kwargs) -> opaque:\n"
         "    return a\n"
         (str/join
           "\n"
           (for [i (range 240)]
             (format
               "def helper_%03d(x=payload):\n    \"\"\"Searchable marker %03d.\"\"\"\n    return x\n"
               i
               i)))))

     answer
     (block
       session
       (str/join
         "\n"
         ["listing = defs()" "assert len(listing) < 6500, len(listing)"
          "assert len([line for line in listing.splitlines() if line.startswith('  ')]) == 20"
          "assert '241 functions and 3 variables' in listing and '244 matches' in listing"
          "assert 'offset=20' in listing" "assert 'PRIVATE_DEFAULT_SENTINEL' not in listing"
          "assert 'helper_000(x=<str>)' in listing" "assert 'helper_020(' not in listing"
          "filtered = defs(pattern='^helper_02', limit=3, offset=2)"
          "assert all(('helper_%03d(' % i) in filtered for i in [22, 23, 24])"
          "assert 'helper_021(' not in filtered and 'helper_025(' not in filtered"
          "assert '10 matches' in filtered and 'offset=5' in filtered"
          "assert 'helper_239(' in defs(pattern='marker 239')"
          "assert '0 matches' in defs(pattern='MARKER')" "assert '0 shown' in defs(offset=1000)"
          "assert 'def helper_239(x=payload):' in defs('helper_239')" "calls = __vis_def_calls__()"
          "assert calls['typed'] == 'typed(a, /, b=<Opaque>, *args, c=<str>, **kwargs)'"
          "assert max(map(len, calls.values())) <= 120" "try:" "    defs('not_here')"
          "except NameError as exc:" "    message = str(exc)"
          "    assert len(message) < 300 and 'defs(pattern=' in message"
          "    assert 'helper_239' not in message" "else:"
          "    raise AssertionError('missing helper was accepted')" "print('bounded')"]))]

    (is (nil? (:error setup)) (pr-str setup))
    (is (nil? (:error answer)) (pr-str answer))
    (is (= "bounded" (str/trim (:stdout answer))))))

(harness/defbuilt-test
  defs-field-and-dependency-bounds-test
  (let [session
        (harness/block-session)

        setup
        (block session
               (str "def large("
                    (str/join ", "
                              (for [i (range 80)]
                                (str "parameter_" i "=None")))
                    "):\n    \"\"\""
                    (apply str (repeat 500 "Long gist. "))
                    "\"\"\"\n    return "
                    (str/join " + "
                              (for [i (range 25)]
                                (format "dependency_%02d" i)))
                    "\n"))

        answer
        (block session
               (str/join
                 "\n"
                 ["call = __vis_def_calls__()['large']"
                  "assert 100 < len(call) <= 120 and call.endswith('…'), len(call)"
                  "listing = defs()" "assert len(listing) < 500"
                  "gist = listing.splitlines()[1].rsplit(' | ', 1)[-1]"
                  "assert 60 < len(gist) <= 72 and gist.endswith('…'), len(gist)"
                  "details = defs('large', details=True)"
                  "assert '20 shown of 25' in details and len(details) < 3000"
                  "assert 'dependency_19 [missing]' in details and 'dependency_20 [' not in details"
                  "assert len(defs('large')) > 5000" "print('field bounds')"]))]

    (is (nil? (:error setup)) (pr-str setup))
    (is (nil? (:error answer)) (pr-str answer))
    (is (= "field bounds" (str/trim (:stdout answer))))))

(harness/defbuilt-test
  defs-index-validation-test
  (let
    [session
     (harness/block-session)

     answer
     (block
       session
       (str/join
         "\n"
         ["def kept(x): return x"
          "for options in [{'limit': 0}, {'limit': 101}, {'limit': True}, {'limit': 2.5}, {'offset': -1}, {'offset': True}, {'offset': '1'}, {'pattern': '['}, {'pattern': 3}, {'details': True}]:"
          "    try:" "        defs(**options)" "    except (TypeError, ValueError):" "        pass"
          "    else:" "        raise AssertionError('invalid listing options accepted')"
          "for options in [{'pattern': 'kept'}, {'limit': 2}, {'offset': 1}]:" "    try:"
          "        defs('kept', **options)" "    except ValueError:" "        pass" "    else:"
          "        raise AssertionError('mixed source and listing options accepted')"
          "assert 'kept(x)' in defs(limit=100)" "print('validated')"]))]

    (is (nil? (:error answer)) (pr-str answer))
    (is (= "validated" (str/trim (:stdout answer))))))

(harness/defbuilt-test
  defs-source-details-test
  (let
    [session
     (harness/block-session)

     answer
     (block
       session
       (str/join
         "\n"
         ["class OpaqueMeta(type):" "    @property"
          "    def __name__(cls): raise AssertionError('type name hook must not run')"
          "class Opaque(metaclass=OpaqueMeta):"
          "    def __repr__(self): raise AssertionError('state repr must not run')"
          "state = Opaque()" "offset = 3" "def helper(value):" "    def nested(): return state"
          "    return value + offset + missing_state + len([]) + nested()" "import hashlib"
          "source = defs('helper')" "details = defs('helper', details=True)"
          "assert hashlib.sha256(source.encode()).hexdigest() in details"
          "assert 'offset [int, present]' in details" "assert 'state [Opaque, present]' in details"
          "assert 'missing_state [missing]' in details" "assert 'liveness unknown' in details"
          "assert 'value [' not in details and 'nested [' not in details and 'len [' not in details"
          "assert '__vis_' not in details and 'return value' not in details" "def factory(seed):"
          "    def inner(value): return value + seed + offset" "    return inner"
          "captured = factory(2)" "captured_details = defs('captured', details=True)"
          "assert 'seed [int, captured]' in captured_details"
          "assert 'seed [missing]' not in captured_details"
          "assert 'offset [int, present]' in captured_details" "del offset"
          "assert 'offset [missing]' in defs('helper', details=True)"
          "assert defs('helper') == source" "def defaulted(client=state): return client"
          "default_source = defs('defaulted')"
          "default_hash = hashlib.sha256(default_source.encode()).hexdigest()"
          "before_defaults = defs('defaulted', details=True)" "defaulted.__defaults__ = (3,)"
          "after_defaults = defs('defaulted', details=True)"
          "assert default_hash in before_defaults and default_hash in after_defaults"
          "assert 'state [' not in after_defaults and 'client [' not in after_defaults"
          "assert 'defaulted(client=<int>)' in after_defaults"
          "assert 'Default/decorator expressions are not analyzed' in after_defaults"
          "print('details')"]))]

    (is (nil? (:error answer)) (pr-str answer))
    (is (= "details" (str/trim (:stdout answer))))))

(harness/defbuilt-test
  defs-refine-delete-restore-test
  (let [session
        (harness/block-session)

        _
        (block session "def calc(value):\n    return value + 1\n")

        original
        (ran session "print(defs('calc', details=True))")

        _
        (block session "def calc(value):\n    return value + 2\n\ndef obsolete():\n    return 0\n")

        refined
        (ran session "print(defs('calc', details=True))")

        _
        (block session "del obsolete")

        text
        (snapshot session)

        fresh
        (harness/block-session)

        restored
        (restore! fresh text)

        details
        (ran fresh "print(defs('calc', details=True))")

        fingerprint
        #(second (re-find #"Source SHA-256: ([a-f0-9]{64})" %))]

    (is (some? (fingerprint original)))
    (is (not= (fingerprint original) (fingerprint refined)))
    (is (= (fingerprint refined) (fingerprint details)))
    (is (= 1 restored))
    (is (not (str/includes? text "obsolete")))
    (is (str/includes? details "(restored)"))
    (is (= "4" (ran fresh "print(calc(2))")))))

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
