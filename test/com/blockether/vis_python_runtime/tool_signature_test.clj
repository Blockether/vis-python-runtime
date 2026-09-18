(ns com.blockether.vis-python-runtime.tool-signature-test
  "What a stamped tool tells `inspect` and `typing` about itself
   (`__vis_tool_proto__` / `__vis_stamp_tool__` in
   `resources/vis-python/async_runtime.py`).

   The host seeds `__vis_sigs__` with one signature TEXT per tool — parameters,
   annotations and return, as `inspect.signature` prints them — and the stamp
   turns it into a signature-only prototype behind `__wrapped__` plus the same
   annotations on the wrapper itself. Every annotation is read statically
   against a fixed vocabulary: the text never runs, and a name outside the
   vocabulary stays the forward-reference string it already is."
  (:require [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run)
                     (finally
                       ;; A session is a module the interpreter holds until it is dropped.
                       (harness/close-sessions!)))))

(defn- seed!
  "Publish `nm` in `session` as a deferred tool over a permissive lambda, with
   `sig` seeded as its host-declared signature text — the order the engine
   binds in, signature first."
  [session nm sig]
  (runtime/exec! session
                 (str "__vis_sigs__ = dict(globals().get('__vis_sigs__') or {})\n"
                      "__vis_sigs__["
                      (pr-str nm)
                      "] = "
                      (pr-str sig)
                      "\n"
                      nm
                      " = __vis_deferred__(lambda *a, **k: {'args': list(a), 'kw': k}, "
                      (pr-str nm)
                      ")")))

(defn- facts
  "Run `code` as a block, expect it not to raise, and read the JSON it printed."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)) code)
    (harness/printed answer)))

(harness/defbuilt-test
  stamped-tool-signature-test
  (let [s (harness/block-session)]
    (seed! s "greet" "(name: str, /, *, loud: bool = ..., note: str | None = None) -> 'Results'")
    (testing "inspect.signature answers the declared parameters, types and return"
      (is (= {"sig" "(name: str, /, *, loud: bool = Ellipsis, note: str | None = None) -> 'Results'"
              "name" true
              "loud" true
              "note" "str | None"
              "return" "Results"
              "call" {"args" ["bob"] "kw" {"loud" true}}}
             (facts s
                    (str "import inspect, json\n"
                         "a = greet.__annotations__\n"
                         ;; A bare call statement settles; the wrapper still calls through.
                         "call = greet('bob', loud=True)\n"
                         "print(json.dumps({'sig': str(inspect.signature(greet)),"
                         " 'name': a['name'] is str, 'loud': a['loud'] is bool,"
                         " 'note': repr(a['note']), 'return': a['return']," " 'call': call}))")))))
    (testing "typing.get_type_hints reads the same annotations; a record stays a forward reference"
      (is (= {"unresolved" "name 'Results' is not defined" "resolved" true}
             (facts s
                    (str "import json, typing\n" "class Results: pass\n"
                         "try:\n" "    typing.get_type_hints(greet)\n"
                         "    unresolved = 'evaluated'\n" "except NameError as e:\n"
                         "    unresolved = str(e)\n"
                         "hints = typing.get_type_hints(greet, localns={'Results': Results})\n"
                         "print(json.dumps({'unresolved': unresolved,"
                         " 'resolved': hints['return'] is Results}))")))))))

(harness/defbuilt-test
  annotation-vocabulary-test
  (let [s (harness/block-session)]
    (seed! s
           "shapes"
           (str "(x: Literal['a', 1, None], y: tuple[int, ...],"
                " z: Sequence[Mapping[str, int]], cb: Callable[[int], str],"
                " p: os.PathLike, m: re.Pattern, *rest: Any, **kw: dict[str, list[int]])"
                " -> collections.abc.Iterator[bytes]"))
    (testing
      "builtins, typing forms, abstract collections and stdlib protocols resolve to the objects"
      (is
        (= {"sig" (str "(x: Literal['a', 1, None], y: tuple[int, ...],"
                       " z: collections.abc.Sequence[collections.abc.Mapping[str, int]],"
                       " cb: collections.abc.Callable[[int], str], p: os.PathLike,"
                       " m: re.Pattern, *rest: Any, **kw: dict[str, list[int]])"
                       " -> collections.abc.Iterator[bytes]")
            "objects" [true true true true true true true true true]
            "hints" ["cb" "kw" "m" "p" "rest" "return" "x" "y" "z"]}
           (facts
             s
             (str "import collections.abc, inspect, json, os, re, typing\n"
                  "a = shapes.__annotations__\n"
                  "objects = [a['x'] == typing.Literal['a', 1, None]," " a['y'] == tuple[int, ...],"
                  " a['z'] == collections.abc.Sequence[collections.abc.Mapping[str, int]],"
                  " a['cb'] == collections.abc.Callable[[int], str],"
                  " a['p'] is os.PathLike, a['m'] is re.Pattern,"
                  " a['rest'] is typing.Any, a['kw'] == dict[str, list[int]],"
                  " a['return'] == collections.abc.Iterator[bytes]]\n"
                  "print(json.dumps({'sig': str(inspect.signature(shapes)),"
                  " 'objects': objects," " 'hints': sorted(typing.get_type_hints(shapes))}))")))))))

(harness/defbuilt-test
  annotation-text-never-runs-test
  (let [s (harness/block-session)]
    (runtime/exec! s "probe_calls = []\ndef probe(x):\n    probe_calls.append(x)\n    return int")
    (seed! s "guarded" "(a: probe(1), b: Unknown, c: Weird.Thing, d: 'Rec' | None) -> Nope")
    (testing "a call, an unknown name and an unknown attribute stay text; nothing was evaluated"
      (is
        (= {"sig"
            "(a: 'probe(1)', b: 'Unknown', c: 'Weird.Thing', d: ForwardRef('Rec') | None) -> 'Nope'"
            "strings" ["probe(1)" "Unknown" "Weird.Thing" "Nope"]
            "probe_calls" []}
           (facts s
                  (str "import inspect, json\n"
                       "a = guarded.__annotations__\n"
                       "print(json.dumps({'sig': str(inspect.signature(guarded)),"
                       " 'strings': [a['a'], a['b'], a['c'], a['return']],"
                       " 'probe_calls': probe_calls}))")))))))

(harness/defbuilt-test
  restamp-after-signature-change-test
  (let [s (harness/block-session)]
    (seed! s "greet" "(name: str) -> 'Results'")
    (runtime/exec! s "kept = greet")
    (testing "a re-seeded signature reaches the reference a block already holds"
      ;; The engine's per-turn re-seed: replace the text, drop the stale
      ;; prototype, stamp again.
      (runtime/exec! s
                     (str
                       "__vis_sigs__['greet'] = '(name: str, repeat: int = ...) -> \\'Results\\''\n"
                       "del greet.__wrapped__\n"
                       "__vis_stamp_tools__(['greet'])"))
      (is (= {"same" true "sig" "(name: str, repeat: int = Ellipsis) -> 'Results'" "repeat" true}
             (facts s
                    (str "import inspect, json\n" "print(json.dumps({'same': kept is greet,"
                         " 'sig': str(inspect.signature(kept)),"
                         " 'repeat': kept.__annotations__['repeat'] is int}))")))))))

(harness/defbuilt-test
  signature-text-fallbacks-test
  (let [s (harness/block-session)]
    (seed! s "host" "(target=None)")
    (seed! s "bare" "()")
    (seed! s "broken" "nonsense(")
    (runtime/exec! s "unseeded = __vis_deferred__(lambda *a, **k: None, 'unseeded')")
    (testing "an untyped host signature carries no annotations; unusable text leaves the trampoline"
      (is (= {"host" ["(target=None)" {}]
              "bare" ["()" {}]
              "broken" ["(*a, **k)" {} false]
              "unseeded" ["(*a, **k)" {} false]}
             (facts s
                    (str
                      "import inspect, json\n" "def shape(fn):\n"
                      "    return [str(inspect.signature(fn)), fn.__annotations__]\n"
                      "print(json.dumps({'host': shape(host), 'bare': shape(bare),"
                      " 'broken': shape(broken) + [hasattr(broken, '__wrapped__')],"
                      " 'unseeded': shape(unseeded) + [hasattr(unseeded, '__wrapped__')]}))")))))))
