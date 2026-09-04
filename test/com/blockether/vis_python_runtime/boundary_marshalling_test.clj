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
