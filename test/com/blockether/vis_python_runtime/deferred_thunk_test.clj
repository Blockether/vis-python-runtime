(ns com.blockether.vis-python-runtime.deferred-thunk-test
  "The `__vis_deferred__` thunk's OWN surface — what a `__vis_Call__` does before
   anybody awaits it (`resources/vis-python/async_runtime.py`).

   `form-eval-test` pins the happy path: `await` runs a tool, a bare top-level
   call settles, `print` settles. This file pins the awkward half, which is where
   the papercuts live:

   - INLINE SETTLE. `shell(...)['out']`, `len(r)`, `'k' in r` and `r.get(...)` on
     a still-deferred call are single-expression uses of ONE result, so they
     settle in place instead of raising `'__vis_Call__' object is not
     subscriptable`. The names internal plumbing probes with `hasattr`
     (`send`/`throw`/`close`/`keys`) stay ABSENT, so a probe can never silently
     run the tool, and a non-slot attribute still raises rather than settling.
   - THE REPR. An unawaited call that leaks into output prints a loud hint naming
     the tool, and printing that hint must not run the tool.
   - STATEMENT DEPTH. The rewrite settle-wraps every assign / expr / return
     statement at EVERY depth, not just `tree.body`, so `for p in paths:
     patch(p)` edits once per iteration and `def read(p): return grep(p)` hands
     back a result. A call in EXPRESSION position still defers — that is the seam
     `gather` batches through — and so does a statement inside an `async def`,
     where holding an awaitable is the idiom.
   - SCOPE. Auto-settle drives OUR thunks and nothing else: a generator binding
     is not exhausted, and an object whose `__getattr__` answers every name is
     not mistaken for a coroutine.

   Ported from vis' `env-python-test` deferred-call-inline-settle /
   nested-statement-settle suites and the auto-settle cases of
   `env-python-form-eval-test`. The two vis cases that assert vis' OWN
   tool-failure op-error envelope stayed there; here a stub host tool stands in
   for a vis tool, because what is under test is the runtime's side."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; A session is a module the interpreter holds until it is dropped.
           (harness/close-sessions!)))))

(defn- ran
  "Run `code` as a block, expect it not to raise, and answer what it PRINTED."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)) code)
    (str/trim (str (:stdout answer)))))

;; ---------------------------------------------------------------------------
;; Inline use of a thunk. These build the thunk with `__vis_deferred__` over a
;; local lambda rather than over a host tool: the surface under test is the
;; thunk's, and a local lambda lets a case SEE whether settling ran it.
;; The thunk is held INSIDE A LIST because a list element is expression
;; position — a bare statement would be settled by the rewrite before the case
;; could look at it.
;; ---------------------------------------------------------------------------

(harness/defbuilt-test deferred-call-inline-settle-test
  (let [s (harness/block-session)]
    (testing "subscript / len / in on an un-awaited call settle it in place"
      ;; The papercut this kills: `git(x)['stdout']` used to die with
      ;; "'__vis_Call__' object is not subscriptable".
      (is (= "['__vis_Call__', 'hi', 2, True, False]"
             (ran s (str "def _t():\n"
                         "    box = [__vis_deferred__("
                         "lambda: {'stdout': 'hi', 'exit': 0}, 'faketool')()]\n"
                         "    kind = type(box[0]).__name__\n"
                         "    return [kind, box[0]['stdout'], len(box[0]),"
                         " 'exit' in box[0], 'zzz' in box[0]]\n"
                         "print(_t())")))))
    (testing "attribute use settles too, but a hasattr probe never runs the tool"
      ;; Attribute access is the same single-expression use as subscript, so it
      ;; settles — EXCEPT on the names internal plumbing probes with `hasattr`
      ;; (`send`/`throw`/`close` for the coroutine driver, `keys` for the
      ;; mapping test), which must stay absent.
      (is (= "[[False, False, False, False], 0, 'hi', 1]"
             (ran s (str "def _t():\n"
                         "    ran = []\n"
                         "    box = [__vis_deferred__("
                         "lambda: ran.append(1) or {'stdout': 'hi'}, 'faketool')()]\n"
                         "    probes = [hasattr(box[0], n)"
                         " for n in ('send', 'throw', 'close', 'keys')]\n"
                         "    return [probes, len(ran), box[0].get('stdout'), len(ran)]\n"
                         "print(_t())")))))
    (testing "an un-awaited call repr's a LOUD hint and the repr never ran it"
      (let [out (ran s (str "def _t():\n"
                            "    ran = []\n"
                            "    box = [__vis_deferred__("
                            "lambda: ran.append(1) or {'k': 1}, 'faketool')()]\n"
                            "    return [repr(box[0]), ran]\n"
                            "print(_t())"))]
        (is (str/includes? out "unawaited async tool call"))
        (is (str/includes? out "faketool"))
        ;; the trailing empty list is the proof the repr did not settle
        (is (str/ends-with? out ", []]"))))
    (testing "no blanket __getattr__ auto-run: a non-slot attribute raises"
      (is (= "safe"
             (ran s (str "def _t():\n"
                         "    box = [__vis_deferred__(lambda: {'stdout': 'hi'}, 'faketool')()]\n"
                         "    try:\n"
                         "        box[0].stdout\n"
                         "        return 'leaked'\n"
                         "    except AttributeError:\n"
                         "        return 'safe'\n"
                         "print(_t())")))))))

;; ---------------------------------------------------------------------------
;; Statement-depth settle. A stub HOST tool stands in for a vis tool: the
;; runtime's own `bind-host!` / `install-tool!` door, counted on the Clojure
;; side so a case can tell one run from three.
;; ---------------------------------------------------------------------------

(defn- nested-session
  "A session with `nested_ok` (records its arguments, answers a mapping) and
   `nested_boom` (refuses), plus the atom `nested_ok` records into."
  []
  (let [calls   (atom [])
        session (harness/tool-session
                 {"nested_ok"   (fn [args] (swap! calls conj (vec args)) {"op" "nested_ok"})
                  "nested_boom" (fn [_]
                                  (throw (ex-info "nested_boom refused - nothing was written."
                                                  {})))})]
    [session calls]))

(harness/defbuilt-test nested-statement-settle-test
  ;; Regression: only TOP-LEVEL statements were settle-wrapped, so a bare tool
  ;; call inside a loop built one `__vis_Call__` per iteration and ran NONE of
  ;; them: `for p in paths: patch(p, edits)` reported nothing and edited nothing.
  (testing "a bare call statement in a loop RUNS, once per iteration"
    (let [[s calls] (nested-session)]
      (is (= "ns done" (ran s (str "for ns_i in (1, 2, 3):\n"
                                   "    nested_ok(ns_i)\n"
                                   "print('ns done')"))))
      (is (= 3 (count @calls)))))
  ;; Regression: a nested assignment bound the THUNK itself, so `r` held a
  ;; `__vis_Call__` and every later use of it read as a broken tool.
  (testing "a nested assignment in a loop / `try:` / a `def` body binds the RESULT"
    (let [[s calls] (nested-session)]
      (is (= "[True, True, True, True]"
             (ran s (str "ns_seen = []\n"
                         "def ns_helper(i):\n"
                         "    r = nested_ok(i)\n"
                         "    ns_seen.append(isinstance(r, dict))\n"
                         "for ns_i in (1, 2):\n"
                         "    ns_x = nested_ok(ns_i)\n"
                         "    ns_seen.append(isinstance(ns_x, dict))\n"
                         "try:\n"
                         "    ns_y = nested_ok(3)\n"
                         "    ns_seen.append(isinstance(ns_y, dict))\n"
                         "except Exception:\n"
                         "    ns_seen.append('refused')\n"
                         "ns_helper(4)\n"
                         "print(ns_seen)"))))
      (is (= 4 (count @calls)))))
  ;; Regression: a host refusal reached the guest as a foreign exception that was
  ;; NOT an `Exception`, so `except Exception:` could not catch it and the
  ;; refusal escaped the very handler written for it.
  (testing "a refusal inside `try:` is caught by `except Exception:`, message intact"
    (let [[s _] (nested-session)]
      (is (= "caught VisToolError :: nested_boom refused - nothing was written."
             (ran s (str "try:\n"
                         "    ns_r = nested_boom(1)\n"
                         "    ns_out = 'LEAKED ' + type(ns_r).__name__\n"
                         "except Exception as e:\n"
                         "    ns_out = 'caught ' + type(e).__name__ + ' :: ' + str(e)\n"
                         "print(ns_out)"))))))
  (testing "a call in EXPRESSION position still defers, so `gather` keeps its batch"
    (let [[s _] (nested-session)]
      (is (= "['__vis_Call__', 'nested_ok']"
             (ran s (str "ns_box = [nested_ok(9)]\n"
                         "ns_kind = type(ns_box[0]).__name__\n"
                         "ns_val = await ns_box[0]\n"
                         "print([ns_kind, ns_val['op']])"))))))
  ;; Regression: a `return` was not settle-wrapped either, so `def edit(p): return
  ;; patch(p)` handed the CALLER a thunk and `"x" + edit(...)` died with a
  ;; TypeError naming `__vis_Call__` instead of making the edit.
  (testing "a `def` that RETURNS a tool call hands back the result, not the thunk"
    (let [[s _] (nested-session)]
      (is (= "[True, 1]"
             (ran s (str "def ns_read(i):\n"
                         "    return nested_ok(i)\n"
                         "print([isinstance(ns_read(5), dict), len(ns_read(6))])"))))))
  ;; An `async def` body is the ONE place a statement keeps its thunk: holding an
  ;; awaitable and awaiting it a line later is the whole idiom there.
  (testing "inside an `async def` a statement still defers, so a coroutine can hold it"
    (let [[s _] (nested-session)]
      (is (= "['__vis_Call__', 'nested_ok']"
             (ran s (str "ns_seen2 = []\n"
                         "async def ns_coro():\n"
                         "    t = nested_ok(7)\n"
                         "    ns_seen2.append(type(t).__name__)\n"
                         "    v = await t\n"
                         "    ns_seen2.append(v['op'])\n"
                         "await ns_coro()\n"
                         "print(ns_seen2)"))))))
  ;; Regression: `return` inside an `async def` was the ONE statement never
  ;; settle-wrapped, so `async def m(): g = grep(...); return sess, g` handed the
  ;; caller a tuple whose second slot was a raw `__vis_Call__`. It survived into
  ;; the NEXT block, where `json.dumps(g)` refused an object nobody created.
  (testing "an `async def` RETURNS its results, never a thunk it never awaited"
    (let [[s calls] (nested-session)]
      (is (= "['k', True, 'nested_ok']"
             (ran s (str "async def ns_pair():\n"
                         "    g = nested_ok(11)\n"
                         "    return 'k', g\n"
                         "ns_k, ns_v = await ns_pair()\n"
                         "print([ns_k, isinstance(ns_v, dict), ns_v['op']])"))))
      (is (= 1 (count @calls)))))
  ;; Regression: only a BARE `return tool(...)` settled; a call one level inside
  ;; the container the helper answered with still reached the caller as a thunk.
  (testing "a returned container settles the calls inside it"
    (let [[s calls] (nested-session)]
      (is (= "[True, 'nested_ok']"
             (ran s (str "def ns_box3():\n"
                         "    return {'hits': [nested_ok(12)]}\n"
                         "ns_b = ns_box3()\n"
                         "print([isinstance(ns_b['hits'][0], dict), ns_b['hits'][0]['op']])"))))
      (is (= 1 (count @calls))))))

;; ---------------------------------------------------------------------------
;; What auto-settle must NOT touch.
;; ---------------------------------------------------------------------------

(harness/defbuilt-test auto-settle-scope-test
  ;; Auto-settle drives OUR deferred thunks only. Driving anything that answers
  ;; `.send` exhausted real generators and bound `None` instead.
  (testing "a generator binding is not driven to exhaustion by the auto-settle"
    (let [s (harness/block-session)]
      (is (= "" (ran s "gen = (i for i in range(3))")))
      (is (= "[0, 1, 2]" (ran s "print(list(gen))")))))
  ;; The same trap through a catch-all `__getattr__`: bs4's `Tag.__getattr__`
  ;; answers ANY missing non-dunder attribute, so the INSTANCE probe
  ;; `hasattr(v, "send")` was true and auto-settle handed the soup to the
  ;; coroutine driver, where `soup.send(None)` died with "'NoneType' object is
  ;; not callable" on EVERY top-level `soup = BeautifulSoup(...)`. Pinned with a
  ;; local class: a suite test never reaches the network for bs4.
  (testing "an object whose __getattr__ answers everything is not driven as a coroutine"
    (is (= "Anything None"
           (ran (harness/block-session)
                (str "class Anything:\n"
                     "    def __getattr__(self, n):\n"
                     "        return None\n"
                     "obj = Anything()\n"
                     "print(type(obj).__name__, obj.send)"))))))
