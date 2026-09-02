(ns com.blockether.vis-python-runtime.trusted-fs-test
  "The filesystem a TRUSTED session reaches, and the one an untrusted session does not.

   Confinement is for the model's code. An extension is the user's own, at the
   host's trust level, and the files it owns are not the session's roots —
   `~/.config/gh`, a cache, a checkout it maintains. So a trusted session does
   its filesystem in C, where the audit hook does not stand, and an untrusted one
   is refused there and keeps the roots it was given.

   What makes it a door rather than a widening is the guard: it asks what the
   RUNTIME was asked to run, which is the one thing Python cannot forge. A block
   can take another session's globals out of `sys.modules` and `exec` into them —
   measured, and that is exactly why the frame the code runs in decides nothing
   here."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block block-session]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           (when harness/built? (runtime/confine! [] []))
           (harness/close-sessions!)))))

(defn- temp-dir
  ^String [prefix]
  (str (.toAbsolutePath (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(harness/defbuilt-test the-trusted-session-reaches-what-the-policy-refuses-test
  (let [outside (temp-dir "vis-trusted-outside")
        inside (temp-dir "vis-trusted-inside")
        note (str outside "/note.txt")
        extension (block-session)
        sandbox (block-session)]
    (spit note "from the host's side")
    (runtime/trust! extension)
    (runtime/confine! [inside] [inside] "confined to the session's roots")
    (testing "the sandbox is refused the path, as its policy says"
      (is (some? (:error (block sandbox (str "open(" (pr-str note) ").read()"))))))
    (testing "the trusted session reads it, writes it, and lists its directory"
      (let [answer (block extension
                          (str "import _vis_fs\n"
                               "print(_vis_fs.read(" (pr-str note) ").decode())\n"
                               "_vis_fs.write(" (pr-str (str outside "/made.txt")) ", 'made')\n"
                               "print(_vis_fs.read(" (pr-str (str outside "/made.txt")) ").decode())\n"
                               "print(sorted(_vis_fs.list(" (pr-str outside) ")))"))]
        (is (nil? (:error answer)))
        (is (= ["from the host's side" "made" "['made.txt', 'note.txt']"]
               (str/split-lines (str/trim (str (:stdout answer))))))))
    (testing "copy and move are the host's too, and they carry the bytes"
      (let [answer (block extension
                          (str "import _vis_fs\n"
                               "_vis_fs.copy(" (pr-str note) ", " (pr-str (str outside "/copy.txt")) ")\n"
                               "_vis_fs.move(" (pr-str (str outside "/copy.txt")) ", "
                               (pr-str (str outside "/moved.txt")) ")\n"
                               "print(_vis_fs.read(" (pr-str (str outside "/moved.txt")) ").decode())\n"
                               "print(_vis_fs.stat(" (pr-str (str outside "/copy.txt")) ") is None)\n"
                               "print(_vis_fs.remove(" (pr-str (str outside "/moved.txt")) "))"))]
        (is (nil? (:error answer)))
        (is (= ["from the host's side" "True" "True"]
               (str/split-lines (str/trim (str (:stdout answer))))))))
    (testing "the sandbox reaching for the same door is refused, module and all"
      (let [answer (block sandbox (str "import _vis_fs\n"
                                       "print(_vis_fs.read(" (pr-str note) "))"))]
        (is (str/includes? (str (:error answer)) "not trusted here"))))
    (testing "and it stays refused after exec'ing into the trusted session's globals"
      ;; The attack that defeats every identity taken from a frame: a session is a
      ;; module in `sys.modules`, so its globals dict is there for the taking, and
      ;; code exec'd into it runs in a frame that belongs to the victim.
      (let [answer (block sandbox
                          (str "import sys, _vis_fs\n"
                               "g = sys.modules[" (pr-str extension) "].__dict__\n"
                               "g['__stolen_target__'] = " (pr-str note) "\n"
                               "src = 'import _vis_fs\\nstolen = _vis_fs.read(__stolen_target__)'\n"
                               "exec(compile(src, '<x>', 'exec'), g)\n"
                               "print(g['stolen'])"))]
        (is (str/includes? (str (:error answer)) "not trusted here"))))
    (testing "trust can be taken back"
      (runtime/trust! extension false)
      (is (str/includes? (str (:error (block extension
                                             (str "import _vis_fs\n"
                                                  "_vis_fs.read(" (pr-str note) ")"))))
                         "not trusted here")))))
