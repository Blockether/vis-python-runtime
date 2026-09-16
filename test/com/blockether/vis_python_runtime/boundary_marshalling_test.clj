(ns com.blockether.vis-python-runtime.boundary-marshalling-test
  "How a value crosses the host/guest boundary in either direction
   (`resources/vis-python/async_runtime.py`'s `__vis_pyify__`, and
   `resources/vis-python/vis_runtime.py`'s `_tool_arg`).

   Inbound, `__vis_pyify__` runs over the value of EVERY top-level statement, so
   it is the one function that can quietly change a value the model just built.
   Its rule is narrow on purpose: rebuild ONLY a foreign host proxy into a real
   dict/list, and leave everything native alone. The rule it replaced —
   rebuilding by an allowlist of shapes — silently downgraded set/tuple/frozenset
   to list and a dict subclass to dict, so a plain `s = set(); s.add(1)` came back
   as `'list' object has no attribute 'add'`. These cases pin that a container the
   model built keeps its type, its methods, and its IDENTITY across the settle.

   Outbound, an argument JSON cannot carry reaches the host as text, and a path
   the model spelled as an OBJECT is still a path: every `os.PathLike` crosses as
   its FILESYSTEM string, at any depth of the arguments, `pathlib` or duck-typed
   alike. A path-like whose `__fspath__` REFUSES is not a path — it crosses as
   its `str`, and the call it appears in still completes.

   Ported from Vis' `env_python_test`. What stayed there is the HOST's half: vis
   binds its own tools, so the tool here is a stub bound through the runtime's
   own host mechanism."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- ran
  "Run `code` as a block, expecting it not to raise, and answer what it PRINTED,
   trimmed — a block's one success channel."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)) code)
    (str/trim (str (:stdout answer)))))

;; Regression Blockether/vis#240: generated records share attribute and field-name access.
(harness/defbuilt-test
  generated-record-subscription-test
  (let [session (harness/tool-session
                  {"ledger.status" (fn [[state]]
                                     {"__vis_object__" "BuildStatus"
                                      "__vis_attrs__" {"state" state
                                                       "items" [{"__vis_object__" "Job"
                                                                 "__vis_attrs__" {"number" 42}}]
                                                       "keys" "keys field"
                                                       "values" "values field"
                                                       "get" nil}})
                   "ledger.empty" (fn [_]
                                    {"__vis_object__" "Empty" "__vis_attrs__" {}})})]
    (testing "top-level and nested records keep field values and mapping-name collisions"
      (is (= "records readable"
             (ran session
                  (str "import dataclasses\n"
                       "item = await ledger.status('ready')\n"
                       "assert item['state'] == item.state == 'ready'\n"
                       "assert item['items'] is item.items\n"
                       "assert item['items'][0]['number'] == item.items[0].number == 42\n"
                       "assert item['keys'] == item.keys == 'keys field'\n"
                       "assert item['values'] == item.values == 'values field'\n"
                       "assert item['get'] is item.get is None\n"
                       "again = await ledger.status('again')\n"
                       "assert again['state'] == 'again' and item['state'] == 'ready'\n"
                       "assert dataclasses.is_dataclass(item)\n"
                       "assert not hasattr(item, '__dict__')\n" "print('records readable')")))))
    (testing "unknown names report available fields, never expose class attributes"
      (is
        (=
          "missing fields explained"
          (ran
            session
            (str
              "for key in ('missing', '__class__', '__getitem__'):\n"
              "    try:\n        item[key]\n" "    except KeyError as exc:\n"
              "        assert key in str(exc) and 'BuildStatus' in str(exc)\n"
              "        assert all(name in str(exc) for name in ('state', 'items', 'keys', 'values', 'get'))\n"
              "    else:\n        raise AssertionError('unknown field accepted')\n"
              "empty = await ledger.empty()\n"
              "try:\n    empty['missing']\n"
              "except KeyError as exc:\n    assert '(none)' in str(exc)\n"
              "else:\n    raise AssertionError('empty record accepted a field')\n"
              "print('missing fields explained')")))))
    ;; Regression Blockether/vis#259: a guessed attribute is explained like a guessed key.
    (testing "unknown attributes report available fields and keep getattr fallbacks"
      (is
        (=
          "missing attributes explained"
          (ran
            session
            (str
              "for name in ('methods', '__dict__'):\n"
              "    try:\n        getattr(item, name)\n" "    except AttributeError as exc:\n"
              "        assert name in str(exc) and 'BuildStatus' in str(exc)\n"
              "        assert all(field in str(exc) for field in ('state', 'items', 'keys', 'values', 'get'))\n"
              "    else:\n        raise AssertionError('unknown attribute accepted')\n"
              "assert not hasattr(item, 'methods')\n"
              "assert getattr(item, 'methods', 'fallback') == 'fallback'\n"
              "try:\n    item.items[0].missing\n"
              "except AttributeError as exc:\n    assert 'Job has no field' in str(exc) and 'number' in str(exc)\n"
              "else:\n    raise AssertionError('nested record accepted an attribute')\n"
              "try:\n    empty.missing\n"
              "except AttributeError as exc:\n    assert '(none)' in str(exc)\n"
              "else:\n    raise AssertionError('empty record accepted an attribute')\n"
              "print('missing attributes explained')")))))
    (testing "non-string indices and both assignment forms remain unsupported"
      (is (= "records frozen"
             (ran session
                  (str "for key in (0, -1, None, ['state'], slice(None)):\n"
                       "    try:\n        item[key]\n"
                       "    except TypeError as exc:\n        assert 'string' in str(exc)\n"
                       "    else:\n        raise AssertionError('non-string field accepted')\n"
                       "try:\n    item['state'] = 'changed'\n" "except TypeError:\n    pass\n"
                       "else:\n    raise AssertionError('mutable subscription')\n"
                       "try:\n    item.items[0].number = 99\n"
                       "except dataclasses.FrozenInstanceError:\n    pass\n"
                       "else:\n    raise AssertionError('mutable nested record')\n"
                       "assert item['state'] == 'ready' and item['items'][0]['number'] == 42\n"
                       "print('records frozen')")))))))

;; Regression Blockether/vis#256: only an explicit public list field grants sequence behavior.
(harness/defbuilt-test
  generated-record-sequence-test
  (let [page
        {"__vis_object__" "Page" "__vis_attrs__" {"title" "First"}}

        envelope
        {"__vis_object__" "PageList"
         "__vis_sequence_field__" "results"
         "__vis_attrs__" {"results" [page page] "total" 20}}

        session
        (harness/tool-session {"ledger.pages" (fn [[empty?]]
                                                (cond-> envelope
                                                  empty?
                                                  (assoc-in ["__vis_attrs__" "results"] [])))
                               "ledger.plain" (fn [_]
                                                (dissoc envelope "__vis_sequence_field__"))
                               "ledger.invalid" (fn [[field value]]
                                                  (-> envelope
                                                      (assoc "__vis_sequence_field__" field)
                                                      (assoc-in ["__vis_attrs__" "results"]
                                                                value)))})]

    (is
      (= "sequence behavior verified"
         (ran session
              (str "import dataclasses\n" "r = await ledger.pages(False)\n"
                   "assert [p.title for p in r] == ['First', 'First']\n"
                   "assert len(r) == 2 and r.total == 20 and bool(r)\n"
                   "assert r[0] is r.results[0] and r[-1] is r.results[-1]\n"
                   "assert r[:] == r.results and r[::-1] == r.results[::-1]\n"
                   "assert r['results'] is r.results and r['total'] == 20\n"
                   "assert next(iter(r))['title'] == 'First'\n"
                   "assert dataclasses.is_dataclass(r[0])\n"
                   "assert not hasattr(r, '__dict__') and not hasattr(r, 'append')\n"
                   "for key in (2, -3):\n" "    try:\n        r[key]\n"
                   "    except IndexError:\n        pass\n"
                   "    else:\n        raise AssertionError('out of bounds accepted')\n"
                   "for key in (None, 1.5, [], {}):\n" "    try:\n        r[key]\n"
                   "    except TypeError:\n        pass\n"
                   "    else:\n        raise AssertionError('invalid index accepted')\n"
                   "try:\n    r['missing']\n"
                   "except KeyError as exc:\n    assert 'available fields' in str(exc)\n"
                   "else:\n    raise AssertionError('unknown field accepted')\n"
                   "try:\n    r.results = []\n"
                   "except dataclasses.FrozenInstanceError:\n    pass\n"
                   "else:\n    raise AssertionError('field assignment accepted')\n"
                   "try:\n    r[0] = None\n" "except TypeError:\n    pass\n"
                   "else:\n    raise AssertionError('item assignment accepted')\n"
                   "empty = await ledger.pages(True)\n"
                   "assert not empty and len(empty) == 0 and list(empty) == []\n"
                   "print('sequence behavior verified')"))))
    (is (= "plain record stays field-only"
           (ran session
                (str
                  "plain = await ledger.plain()\n" "assert plain['results'][0].title == 'First'\n"
                  "for operation in (lambda: iter(plain), lambda: len(plain), lambda: plain[0]):\n"
                  "    try:\n        operation()\n"
                  "    except TypeError:\n        pass\n"
                  "    else:\n        raise AssertionError('implicit sequence behavior')\n"
                  "try:\n    list(plain)\n"
                  "except TypeError as exc:\n    assert 'not iterable' in str(exc)\n"
                  "else:\n    raise AssertionError('legacy iteration fallback')\n"
                  "print('plain record stays field-only')"))))
    (is (= "invalid declarations refused"
           (ran session
                (str
                  "for field, value in [(None, []), (0, []), ('missing', []), ('_private', []), "
                  "('results', None), ('results', {}), ('results', 'abc')]:\n"
                  "    try:\n        await ledger.invalid(field, value)\n"
                  "    except (TypeError, ValueError) as exc:\n"
                  "        assert 'sequence' in str(exc).lower()\n"
                  "    else:\n        raise AssertionError('invalid sequence metadata accepted')\n"
                  "print('invalid declarations refused')"))))))

(harness/defbuilt-test
  pyify-container-preservation-test
  (let [session (harness/block-session)]
    (testing "a set/tuple/frozenset/defaultdict the block built keeps its native type"
      ;; `hasattr(s, 'add')` is the regression itself: the allowlist rebuild
      ;; handed back a list, and the next `s.add(...)` was an AttributeError.
      (is (= ["set=set add=True" "tuple=tuple" "frozenset=frozenset" "defaultdict=defaultdict"]
             (str/split-lines
               (ran session
                    (str "s = set()\n" "s.add(1); s.add(1); s.add(2)\n"
                         "t = (1, 2, 3)\n" "fs = frozenset([1, 1, 2])\n"
                         "from collections import defaultdict\n"
                         "dd = defaultdict(list); dd['x'].append(9)\n"
                         "print('set='+type(s).__name__, 'add='+str(hasattr(s,'add')))\n"
                         "print('tuple='+type(t).__name__)\n"
                         "print('frozenset='+type(fs).__name__)\n"
                         "print('defaultdict='+type(dd).__name__)"))))))
    (testing "a dict subclass is not flattened into a plain dict"
      (is (= "Counter 2"
             (ran session
                  (str "c = Counter('aab')\n" "print(type(c).__name__, c.most_common(1)[0][1])")))))
    (testing "a native set persists as a set, and stays mutable, ACROSS blocks"
      ;; Each block settles its own top-level values, so a container that
      ;; survives two blocks has been through `__vis_pyify__` twice.
      (ran session "acc = set()\nacc.add('a')")
      (is (= "kind=set vals=['a', 'b']"
             (ran session
                  (str "acc.add('b'); acc.add('a')\n"
                       "print('kind='+type(acc).__name__, 'vals='+str(sorted(acc)))")))))
    (testing "settle hands back the SAME object, never a rebuilt copy"
      ;; Rebuilding a native list would break aliasing silently: `alias` would be
      ;; a snapshot, and a later `append` on the original would never show up.
      (ran session "xs = []\nalias = xs")
      (is (= "True [1]" (ran session "xs.append(1)\nprint(alias is xs, alias)"))))))

(harness/defbuilt-test
  pathlike-argument-boundary-test
  (let [seen
        (atom [])

        session
        (harness/tool-session {"pathlike_probe" (fn [args]
                                                  (swap! seen conj args)
                                                  {"ok" true})})

        probe
        (fn [code]
          (reset! seen [])
          (ran session code))]

    (testing "a Path argument reaches the tool as its filesystem string"
      (probe "pathlike_probe(Path('/tmp/vis/q.clj'))")
      (is (= [["/tmp/vis/q.clj"]] @seen)))
    (testing "Paths convert at every depth of an options dict"
      (probe (str "pathlike_probe({'paths': [Path('/tmp/vis/a.clj'), '/tmp/vis/b.clj'],\n"
                  "                'path': Path('/tmp/vis')})"))
      (is (= [[{"paths" ["/tmp/vis/a.clj" "/tmp/vis/b.clj"] "path" "/tmp/vis"}]] @seen)))
    (testing "every os.PathLike answers the same duck-type, not just pathlib"
      (probe (str "class VisTestPathLike:\n" "    def __fspath__(self):\n"
                  "        return '/tmp/vis/duck.clj'\n" "pathlike_probe(VisTestPathLike())"))
      (is (= [["/tmp/vis/duck.clj"]] @seen)))
    (testing "a refusing __fspath__ leaves the value alone instead of failing the call"
      ;; A path-like whose `__fspath__` raises is not a path: the call must not
      ;; fail over it, and the argument must not be turned into one. It crosses
      ;; as its `str` — the honest limit of a text boundary — never as the
      ;; filesystem string it refused to answer.
      (is (= "survived"
             (probe (str "class VisTestBadPathLike:\n"
                         "    def __fspath__(self):\n" "        raise ValueError('no path here')\n"
                         "pathlike_probe(VisTestBadPathLike())\n" "print('survived')"))))
      (is (= 1 (count @seen)))
      (is (str/includes? (str (ffirst @seen)) "VisTestBadPathLike")))))
