(ns com.blockether.vis-python-runtime.grep-paging-test
  "A CAPPED SEARCH PAGES ITSELF (`resources/vis-python/async_runtime.py`).

   A search tool answers TEXT, and `limit` caps a page — 50 hits by default — so
   a wide sweep answers a SLICE. Line 1 says so and names the literal next call,
   but a bare string is a DEAD END: continuing means retyping the whole call with
   `offset`, and the step nobody takes by hand is the step that turns a capped
   page into \"that is all there is\".

   The runtime wraps such a page in a `__VisGrep__`: still the text (str
   operations, slicing, `print`, and the uniform `.get('op')` probe all behave,
   and iterating it still yields CHARACTERS), plus `next_offset` / `next(g)` /
   `pages()` / `all()`. The wrapper is chosen by the NAME of the call that
   produced the text (`__vis_paged_tools__`) and continued with the options map
   that call carried (`__vis_paged_spec__`), so these cases drive the real settle
   path with a HOST tool bound under the name `grep` — the host's side of the
   protocol, not any particular host's search."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- page-text
  "One canned page in the shape a search renderer really emits: a summary line
   that names the literal next call, then one anchored row. `next-offset` nil is
   an UNCAPPED page — no arrow, and so no next call."
  [offset next-offset]
  (str "grep 'q'  50 hits · 3 of 30 files"
       (when next-offset
         (str "  capped by limit → next(r) or grep({…, \"offset\": " next-offset "})"))
       "\nsrc/a.clj  (1)\n  1:aaa│ hit from offset " offset))

(defn- stub-grep
  "A host `grep` that pages: offsets 0 → 50 → 100, the last one complete. Every
   spec it is called with is recorded, so a case can assert that the NEXT page
   carried the whole original options map and not just an offset."
  [calls]
  (fn [args]
    (let [spec
          (first args)

          offset
          (long (or (get spec "offset") 0))]

      (swap! calls conj spec)
      (if (= "done" (get spec "query"))
        "grep 'done'  2 hits · 1 file\nsrc/a.clj  (1)\n  1:aaa│ complete"
        (page-text offset (when (< offset 100) (+ offset 50)))))))

(defn- paging-session
  "A block session with [[stub-grep]] bound as the host tool `grep`, and the atom
   recording the specs it was called with."
  []
  (let [calls (atom [])]
    [(harness/tool-session {"grep" (stub-grep calls)}) calls]))

(defn- ran
  "What a block PRINTED, trimmed — and a block that raised fails HERE rather than
   leaving an empty string to be compared against."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)))
    (str/trim (str (:stdout answer)))))

;; Regression: a capped page was a DEAD END. The result was a bare `str`, so the
;; only way on was to retype the whole call with `offset` — and a sweep that
;; stopped at 50 hits read exactly like a tree that holds 50.
(harness/defbuilt-test
  grep-page-continues-itself-test
  (let [[session] (paging-session)]
    (testing "a capped page is still the text, and knows where the next one starts"
      (is (= "__VisGrep__ True True 50 True"
             (ran session
                  (str "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n"
                       "print(type(g).__name__, isinstance(g, str),"
                       " g.get(\"op\") is None, g.next_offset, g.is_capped)")))))))

(harness/defbuilt-test
  grep-next-carries-the-whole-spec-test
  (let [[session calls] (paging-session)]
    (testing "next(g) walks to the end and carries the WHOLE options map, not just an offset"
      (is (= "100 None None"
             (ran session
                  (str "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n" "p2 = next(g)\n"
                       "p3 = next(p2)\n" "print(p2.next_offset, p3.next_offset, next(p3, None))"))))
      (is (= [{"query" "q" "paths" ["src"]} {"query" "q" "paths" ["src"] "offset" 50}
              {"query" "q" "paths" ["src"] "offset" 100}]
             @calls)))))

(harness/defbuilt-test
  grep-pages-and-all-test
  (let [[session] (paging-session)]
    (testing "pages() walks every page and all() joins them"
      (is (= "3 3"
             (ran session
                  (str "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n"
                       "print(len(list(g.pages())), g.all().count(\"grep 'q'\"))")))))
    (testing "a bound that stops the walk early SAYS so and names the call that continues"
      (let [out (ran session
                     (str "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n"
                          "print(g.all(max_pages=2).splitlines()[-1])"))]
        (is (str/starts-with? out "… stopped after 2 pages"))
        (is (str/includes? out "\"offset\": 100"))))))

(harness/defbuilt-test
  grep-pages-are-lazy-test
  (let [[session calls] (paging-session)]
    (testing "pages() is lazy: abandoning the walk never runs the searches behind it"
      (let [out (ran session
                     (str "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n"
                          "for page in g.pages():\n"
                          "    break\n" "print(len(g))"))]
        (is (pos? (Long/parseLong out)))
        (is (= 1 (count @calls)))))))

(harness/defbuilt-test
  grep-uncapped-page-test
  (let [[session calls] (paging-session)]
    (testing "an UNCAPPED page already is the whole answer"
      (is (= "None False None 1"
             (ran session
                  (str "g = grep({\"query\": \"done\", \"paths\": [\"src\"]})\n"
                       "print(g.next_offset, g.is_capped, next(g, None),"
                       " len(list(g.pages())))"))))
      (is (= 1 (count @calls))))
    ;; `next` without a default is the protocol: the walk ends in StopIteration,
    ;; not in a None every caller has to test for.
    (testing "the last page ends the walk the way Python ends every walk"
      (is (= "StopIteration"
             (ran session
                  (str "g = grep({\"query\": \"done\", \"paths\": [\"src\"]})\n" "try:\n"
                       "    next(g)\n" "    print(\"no stop\")\n"
                       "except StopIteration:\n" "    print(\"StopIteration\")")))))))

;; Iterating a string means CHARACTERS everywhere else in Python. A page walk
;; that quietly stole `__iter__` would break `"".join(g)` and `list(g)` for every
;; caller who never asked for paging.
(harness/defbuilt-test grep-page-iterates-as-text-test
                       (let [[session] (paging-session)]
                         (testing "iterating the page still yields characters"
                           (is (= "['g', 'r', 'e', 'p'] True grep"
                                  (ran session
                                       (str
                                         "g = grep({\"query\": \"q\", \"paths\": [\"src\"]})\n"
                                         "print(list(g)[:4], \"\".join(g) == str(g), g[:4])")))))))
