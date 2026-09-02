(ns com.blockether.vis-python-runtime.vis-dict-test
  "What a tool result says when the model reaches for a key it does not have,
   and what `.get` answers whatever shape the tool returned.

   Every map that crosses the host boundary is rebuilt as `__VisDict__`, a real
   dict whose ONLY difference is `__missing__`. That difference is the whole
   reason the subclass exists: result shapes are per-tool by design (shell ->
   out/exit, run_tests -> output), so a bare `KeyError: 'output'` reads as \"the
   tool broke\" — the model then guesses a second name and spins for a turn.
   Naming the tool, every key it DID return and the near miss ends the guessing
   at the first wrong guess.

   The same reach in its silent form is `.get`, and it has to be uniform: a tool
   may answer a LIST of rows or a bare STRING, not only a dict, so the top-level
   settle re-types those to probeable subclasses. Without that, the documented
   `res.get('op')` sweep dies with `'list' object has no attribute 'get'` on the
   one result that was not a map.

   These cases live here because the message and the probe are the RUNTIME's:
   the consumer only sees them after a pin bump."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally (harness/close-sessions!)))))

(defn- ran
  "What a block PRINTED, trimmed — a block's one success channel."
  [session code]
  (str/trim (str (:stdout (block session code)))))

(harness/defbuilt-test missing-key-names-the-shape-test
  ;; A stub host tool stands in for a real one: the point is the runtime's side
  ;; of the seam — the map it rebuilds — not any particular tool.
  (let [session (harness/tool-session
                 {"tool_result" (fn [_] (array-map "op" "shell"
                                                   "stdout" "hi"
                                                   "exit" 0
                                                   "nested" {"a" 1}))
                  "bare_map"    (fn [_] {"alpha" 1})})
        said    (->> (ran session
                          (str "r = await tool_result()\n"
                               "try:\n    r['output']\nexcept KeyError as e:\n    print('MISS', e)\n"
                               "try:\n    r['stdou']\nexcept KeyError as e:\n    print('NEAR', e)\n"
                               "try:\n    r['nested']['b']\nexcept KeyError as e:\n    print('NEST', e)\n"
                               "print('GET', r.get('output'), isinstance(r, dict))"))
                     str/split-lines
                     ;; One line per reach, tagged, so each contract reads the
                     ;; answer to ITS OWN miss instead of the whole stdout.
                     (map #(str/split % #" " 2))
                     (into {}))]
    (testing "a missing key names the tool that answered and EVERY key it did return"
      (is (str/includes? (said "MISS") "'output' is not a key of 'shell' result"))
      (is (str/includes? (said "MISS") "Keys: 'op', 'stdout', 'exit', 'nested'")))
    (testing "a near miss is offered by name, so the second guess is not needed"
      (is (str/includes? (said "NEAR") "Did you mean 'stdout'?")))
    (testing "a nested map describes its OWN shape, not the result it sits in"
      ;; The rebuild is recursive: `r['nested']` is a `__VisDict__` too, so the
      ;; miss inside it answers with `Keys: 'a'` rather than the outer keys.
      (is (str/includes? (said "NEST") "'b' is not a key of this result map. Keys: 'a'."))
      (is (not (str/includes? (said "NEST") "stdout"))
          "the nested miss must not be answered with the outer result's keys"))
    (testing "`.get` stays silent on the same reach, and the value is still a real dict"
      (is (= "None True" (said "GET"))))
    (testing "a map with no 'op' cannot name a tool, so it names itself"
      (is (str/includes?
           (ran session (str "m = await bare_map()\n"
                             "try:\n    m['beta']\nexcept KeyError as e:\n    print(e)"))
           "'beta' is not a key of this result map. Keys: 'alpha'.")))
    (testing "indexing a result positionally is answered as a lookup mistake, not a KeyError repr"
      ;; `r[0]` is the guess a model makes when it read the result as a list of
      ;; rows; the message says a dict is not positional AND lists the keys.
      (let [sliced (ran session (str "r = await tool_result()\n"
                                     "try:\n    r[0]\nexcept KeyError as e:\n    print(e)"))]
        (is (str/includes? sliced "cannot index 'shell' result with 0"))
        (is (str/includes? sliced "Keys: 'op', 'stdout', 'exit', 'nested'"))))))

(harness/defbuilt-test uniform-get-probe-test
  ;; A capability return can be a LIST (one row per hit) or a bare STRING, not
  ;; only a dict. The TOP-LEVEL settle normalizes each so a uniform
  ;; `res.get('op')` sweep never trips — while the value keeps its native
  ;; list/str behaviour (index / iterate / len / concat).
  (let [session (harness/tool-session
                 {"rows"   (fn [_] [{"path" "a" "op" "update"}])
                  "text"   (fn [_] "plain text")
                  "report" (fn [_] (array-map "op" "rg" "hit_count" 2))})
        out     (ran session
                     (str "lst = await rows()\n"
                          "s = await text()\n"
                          "d = await report()\n"
                          "ops = [res.get('op') for res in (lst, s, d)]\n"
                          "print(['lst_get', lst.get('op'), 'lst0', lst[0]['op'], 'lst_len', len(lst)])\n"
                          "print(['str_get', s.get('op'), 'str_cat', s + '!'])\n"
                          "print(['dct_get', d.get('op')])\n"
                          "print(['sweep_ok', all(o is None or isinstance(o, str) for o in ops), "
                          "'rg_in', 'rg' in ops])\n"
                          "print(['native', isinstance(lst, list), isinstance(s, str), isinstance(d, dict)])"))]
    (testing "a LIST result answers `.get` with the default while every row stays reachable"
      (is (str/includes? out "'lst_get', None"))
      (is (str/includes? out "'lst0', 'update'"))
      (is (str/includes? out "'lst_len', 1")))
    (testing "a STRING result answers `.get` and still concatenates as a string"
      (is (str/includes? out "'str_get', None"))
      (is (str/includes? out "'str_cat', 'plain text!'")))
    (testing "a dict result answers `.get` with the value it holds"
      (is (str/includes? out "'dct_get', 'rg'")))
    (testing "one probe sweeps every shape without a type guard"
      (is (str/includes? out "'sweep_ok', True"))
      (is (str/includes? out "'rg_in', True")))
    (testing "the probe costs the value none of its native type"
      (is (str/includes? out "'native', True, True, True")))))
