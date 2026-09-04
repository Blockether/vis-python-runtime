(ns com.blockether.vis-python-runtime.protected-names-test
  "What a block may and may not do to a name the HOST owns.

   A bound tool is a global in the session's namespace, so nothing in Python
   stops a block writing `patch = 'x'` straight over it — and the block after
   that one would greet a string where its tool used to be. The runtime draws
   the line in `__vis_run_async__`: a protected name assigned by a block is left
   OUT of the `global` list, so the assignment is a plain local of the wrapped
   `__vis_main__` and dies with the block. Reads still see the tool, because
   each shadowed name is pre-seeded from globals first.

   That leaves two holes a local cannot close, and the runtime closes each by
   hand: a top-level `def patch(...)` is a helper the session could never keep
   (it would not persist and the next block gets the tool back), so it is
   REFUSED where it is written; and a snapshot restored into a process that now
   HAS the tool would exec `def patch(...)` over the real one for good, so
   `__vis_restore_defs__` drops every statement binding a protected name.

   These cases were pinned only in the consumer, which meant the runtime could
   ship a session whose tools a block could silently destroy and still be
   green."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]
            [com.blockether.vis-python-runtime :as runtime]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- patched
  "The stub host tool every case here reaches for: it names its argument back,
   so an assertion can tell the TOOL's answer from a shadow's."
  [args]
  (str "patched:" (first args)))

(defn- ran
  "What a block PRINTED, trimmed — a block's one success channel."
  [session code]
  (str/trim (str (:stdout (block session code)))))

(harness/defbuilt-test shadowing-a-tool-is-block-local-test
                       (let [session
                             (harness/tool-session {"patch" patched})

                             shadow
                             (block session "patch = 'not callable'\nprint(patch)")]

                         (testing
                           "the assignment itself is allowed and the block reads its own string"
                           (is (nil? (:error shadow)))
                           (is (= "not callable" (str/trim (str (:stdout shadow))))))
                         (testing "the NEXT block gets the tool back, not the string"
                           (is (= "patched:x" (ran session "print(await patch('x'))"))))))

(harness/defbuilt-test
  read-before-the-shadow-sees-the-tool-test
  ;; Without the pre-seed this is UnboundLocalError: the wrapper made `patch` a
  ;; local of `__vis_main__`, so the READ on line one referred to a local the
  ;; assignment on line two had not made yet.
  (let [session (harness/tool-session {"patch" patched})]
    (testing "a read that precedes the shadowing assignment still reaches the tool"
      (is (= "patched:x shadow"
             (ran
               session
               (str "before = await patch('x')\n" "patch = 'shadow'\n" "print(before, patch)")))))))

(harness/defbuilt-test
  ordinary-variables-alongside-a-tool-test
  (let [session (harness/tool-session {"patch" patched})]
    (testing "a variable that is not a bound name is an ordinary global"
      (is (= "patched:app.css" (ran session (str "css = 'app.css'\n" "print(await patch(css))")))))
    (testing "it persists into the next block, the way module scope does"
      (is (= "app.css" (ran session "print(css)"))))))

(harness/defbuilt-test only-bound-names-are-protected-test
                       ;; The protected set is the SESSION's namespace, not Python's builtins: a model
                       ;; writing `test = 'promise_pool.test.ts'` or `format = 'csv'` is naming
                       ;; variables, and those must persist like any other global.
                       (let [session (harness/tool-session {"patch" patched})]
                         (testing "a builtin's name is not a bound tool name"
                           (is (harness/truthy session "'format' not in __vis_protected_names__"))
                           (is (harness/truthy session "'test' not in __vis_protected_names__")))
                         (testing
                           "so binding them is an ordinary assignment that survives the block"
                           (is (= "patched:promise_pool.test.ts"
                                  (ran session
                                       (str "test = 'promise_pool.test.ts'\n"
                                            "format = 'csv'\n"
                                            "print(await patch(test))"))))
                           (is (= "csv" (ran session "print(format)"))))))

(harness/defbuilt-test print-survives-a-block-that-shadows-it-test
                       ;; `print` is the sandbox's own (`__vis_print__`, the block's one success
                       ;; channel), so it is protected exactly like a tool: a block may spell it as a
                       ;; variable, and the next block still has something to print with.
                       (let [session
                             (harness/tool-session {"patch" patched})

                             shadow
                             (block session "print = 'not callable'")]

                         (testing "shadowing the output callable is not an error"
                           (is (nil? (:error shadow))))
                         (testing "the next block can still print"
                           (is (= "still printing" (ran session "print('still printing')"))))))

(harness/defbuilt-test loop-target-shadowing-a-tool-test
                       ;; A `for` / `with` target is transient scratch. It binds in module scope like
                       ;; any other name, so it must NOT be mistaken for a durable rebind: the loop
                       ;; runs, and the callable is whole afterwards.
                       (let [session
                             (harness/tool-session {"patch" patched})

                             looped
                             (block session "for patch in ['a', 'b']:\n    pass")]

                         (testing "the loop is allowed to use the name" (is (nil? (:error looped))))
                         (testing "and the tool is back in the next block"
                           (is (= "patched:x" (ran session "print(await patch('x'))"))))))

(harness/defbuilt-test deleting-a-tool-is-block-local-test
                       ;; `del` binds the name too — that is why the wrapper declares deleted globals
                       ;; `global` — so a protected name has to survive it the same way an assignment
                       ;; is survived: the block deletes its own seeded local, never the tool.
                       (let [session
                             (harness/tool-session {"patch" patched})

                             deleted
                             (block session "del patch")]

                         (testing "the block may delete the name it was handed"
                           (is (nil? (:error deleted))))
                         (testing "the tool is still bound for the next block"
                           (is (= "patched:x" (ran session "print(await patch('x'))")))
                           (is (harness/truthy session "'patch' in globals()")))))

(harness/defbuilt-test tool-bound-after-the-session-test
                       ;; A host binds tools when it has them, not only at install time. A tool bound
                       ;; into a live session has to join the protected set right then, or the first
                       ;; block after it can clobber it.
                       (let [session (harness/block-session)]
                         (harness/bind-tools! {"later_patch" patched})
                         (runtime/install-tool! session "later_patch")
                         (testing "a late tool is protected the moment it is bound"
                           (is (harness/truthy session "'later_patch' in __vis_protected_names__")))
                         (testing "shadowing it is still block-local"
                           (is (nil? (:error (block session "later_patch = 'oops'"))))
                           (is (= "patched:x" (ran session "print(await later_patch('x'))"))))))

(harness/defbuilt-test top-level-def-over-a-tool-is-refused-test
                       ;; A top-level `def patch(...)` used to be accepted in silence and then quietly
                       ;; dropped: left out of the `global` list, it lived and died inside its own
                       ;; block, was never snapshotted, and the next block silently got the tool back.
                       ;; A helper the session cannot keep is refused where it is written.
                       (let [session
                             (harness/tool-session {"patch" patched})

                             refused
                             (block session "def patch(a):\n    return a\n")

                             klass
                             (block session "class defs:\n    pass\n")]

                         (testing "the refusal names the tool and the fix"
                           (is (str/includes? (str (:error refused)) "`patch` is a bound tool"))
                           (is (str/includes? (str (:error refused)) "patch_mine")))
                         (testing "a class head is the same trap, including over a sandbox name"
                           (is (str/includes? (str (:error klass)) "`defs` is a bound tool")))
                         (testing
                           "a def NESTED in another function is an ordinary local, never refused"
                           (is (= "7"
                                  (ran session
                                       (str "def outer():\n"
                                            "    def defs(x):\n" "        return x\n"
                                            "    return defs(7)\n" "print(outer())\n")))))
                         (testing "a plain assignment is still a block-local shadow, not a refusal"
                           (is (= "a string" (ran session "patch = 'a string'\nprint(patch)"))))
                         (testing "the tool came through all of it"
                           (is (= "patched:x" (ran session "print(await patch('x'))"))))))

(harness/defbuilt-test
  restore-never-recreates-a-tool-name-test
  ;; The same trap across processes: a snapshot written BEFORE a tool existed
  ;; carried `def patch(...)` as an ordinary session helper. Replayed into a
  ;; process that HAS the tool, it wrote straight over it for the whole process —
  ;; and the restored count never noticed, because it skips protected names.
  ;; Statements are dropped by the names they BIND, so a constant over `defs`
  ;; goes the same way and the next snapshot no longer carries either.
  (let [session
        (harness/tool-session {"patch" patched})

        snapshot
        (str "def patch(*a, **k):\n    return \"HIJACKED\"\n\n"
             "defs = \"clobbered\"\n\n"
             "def kept(n):\n    return n * 3\n")

        restored
        (harness/ev session (str "__vis_restore_defs__(" (json/write-str snapshot) ")"))]

    (testing "only the helper that is not a bound name is restored"
      (is (= 1 restored))
      (is (= "6" (ran session "print(kept(2))"))))
    (testing "the tool is untouched and the sandbox name is still callable"
      (is (= "patched:x" (ran session "print(await patch('x'))")))
      (is (harness/truthy session "callable(defs)")))
    (testing "the restore says which names it dropped, so the file can heal"
      (is (= ["defs" "patch"] (harness/ev session "__vis_restore_dropped__"))))))
