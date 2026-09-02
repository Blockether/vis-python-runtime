(ns com.blockether.vis-python-runtime.block-sources-test
  "The block source registry in `resources/vis-python/async_runtime.py`:
   `__vis_register_source__`, the per-block `co_filename`, and
   `__vis_evict_sources__`.

   A block's `def` lands in a code object whose file does not exist, so without
   a `linecache` entry the sandbox could RUN code it could not SHOW —
   `inspect.getsource` on a function the model had just written died with
   `could not get source code`, and with it went `traceback`'s source echo and
   the pytest shim's assert introspection.

   Two rules make that entry trustworthy. Each block registers under its OWN
   `<prog:N>` name, because two blocks share no line numbering and one shared
   name would hand a LATER block's text back for an EARLIER block's function —
   wrong source, silently, which is worse than the error. And eviction is
   oldest-first with a LIVENESS exception: a `def` outlives its block for the
   whole session, so the cap bounds DEAD blocks only. Getting that condition
   wrong made a helper defined a few hundred blocks back callable but
   unreadable, and the model re-pasted it instead of refining it.

   Ported from Vis' `env-python-test` block-source cases. The cap is driven here
   the way the Vis tests drive it — `__vis_blocks_kept__ = 1` from a block —
   because the alternative is running 128 blocks per case."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; A session is a module the interpreter holds until it is dropped,
           ;; and every case here opens its own.
           (harness/close-sessions!)))))

(defn- ran
  "Run `code` as a block in `session`, expecting it not to raise, and answer what
   it printed, trimmed."
  [session code]
  (let [answer (block session code)]
    (is (nil? (:error answer)) code)
    (str/trim (str (:stdout answer)))))

(defn- narrow-session
  "A block session whose source cap is ONE block, so eviction can be observed in
   four blocks instead of a hundred and thirty.

   The block that lowers the cap is itself registered first, under the cap still
   in force — it binds only an engine name, so it is dead the moment the next
   block registers and is the first thing dropped."
  []
  (let [s (harness/block-session)]
    (is (nil? (:error (block s "__vis_blocks_kept__ = 1"))))
    s))

(harness/defbuilt-test block-source-introspection-test
  (let [s (harness/block-session)]
    (testing "getsource reads back a function defined in an EARLIER block"
      (block s "def source_probe(a, b=2):\n    return a + b\n")
      (block s "source_probe_unrelated = 1")
      (let [out (ran s "import inspect\nprint(inspect.getsource(source_probe))")]
        (is (str/includes? out "def source_probe(a, b=2):"))
        (is (str/includes? out "return a + b"))))
    (testing "every block gets its own co_filename, so an older source is never overwritten"
      (block s "def source_probe_first():\n    return 1\n")
      (is (= "True\nTrue"
             (ran s (str "import inspect\n"
                         "def source_probe_second():\n    return 2\n"
                         "print(source_probe_first.__code__.co_filename"
                         " != source_probe_second.__code__.co_filename)\n"
                         "print(inspect.getsource(source_probe_first).strip()"
                         ".endswith('return 1'))\n")))))
    (testing "the per-block name is the registry's own <prog:N>, tracked in order"
      (is (= "True"
             (ran s (str "print(source_probe.__code__.co_filename.startswith('<prog:')"
                         " and source_probe.__code__.co_filename in __vis_block_names__)")))))))

(harness/defbuilt-test live-source-eviction-test
  (testing "the source of an old block that still backs a live definition survives"
    (let [s (narrow-session)]
      (block s "def kept_helper(a):\n    return a + 1\n")
      ;; Two blocks that bind nothing worth keeping: under a cap of one they are
      ;; what the pass has to drop, and the helper's block is what it must not.
      (block s "dead_one = 1")
      (block s "dead_two = 2")
      (let [out (ran s "import inspect\nprint(inspect.getsource(kept_helper).strip())")]
        (is (str/includes? out "def kept_helper(a):"))
        (is (str/includes? out "return a + 1")))))
  (testing "a block still evicts once nothing live comes from it"
    (let [s (narrow-session)]
      (block s "def churn():\n    return 1\n")
      ;; The redefinition unbinds the first `churn`, so its block stops being
      ;; live and the next pass drops it — the pin cannot grow without a referent.
      (block s "def churn():\n    return 2\n")
      (block s "pass")
      (is (= "2\nTrue"
             (ran s (str "import inspect\n"
                         "print(len(__vis_block_names__))\n"
                         "print(inspect.getsource(churn).strip().endswith('return 2'))\n"))))))
  (testing "the source of the block that is about to run is never dropped"
    (let [s (narrow-session)]
      (block s "def pin_one():\n    return 1\n")
      (block s "def pin_two():\n    return 2\n")
      ;; Nothing is bound out of the newest entry yet, so a liveness test would
      ;; call it dead — the pass has to spare it anyway or a block loses the
      ;; source its own traceback is about to read.
      (is (= "True"
             (ran s (str "import inspect\n"
                         "def defined_here():\n    return 3\n"
                         "print(inspect.getsource(defined_here).strip()"
                         ".endswith('return 3'))\n")))))))
