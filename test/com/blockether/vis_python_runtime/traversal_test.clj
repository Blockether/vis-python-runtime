(ns com.blockether.vis-python-runtime.traversal-test
  "Directory-entry budgets at the native sandbox boundary (Vis #279): a spent budget
   returns partial results with one note."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private tree (atom nil))

(use-fixtures :once
              (fn [run]
                (let [root (io/file (harness/temp-dir "vis-traversal"))]
                  (reset! tree root)
                  (try
                    ;; A single wide directory catches counters that only count scandir calls.
                    (doseq [i (range 10001)]
                      (Files/createFile (.toPath (io/file root (str i ".txt")))
                                        (make-array FileAttribute 0)))
                    (run)
                    (finally (doseq [file (reverse (file-seq root))]
                               (io/delete-file file))
                             (reset! tree nil))))))

(use-fixtures
  :each
  (fn [run]
    (try (run) (finally (when harness/built? (runtime/confine! [] [])) (harness/close-sessions!)))))

(defn- setup
  [session]
  (runtime/exec!
    session
    (str "import os, pathlib, glob\n" "root = pathlib.Path(" (pr-str (str @tree)) ")\n")))

(defn- partial?
  "True when `answer` has no error and its output says once that its walks are partial."
  [answer]
  (let [out (str (:stdout answer))]
    (and (nil? (:error answer))
         (= 1 (count (re-seq #"\[vis\] Directory walks stopped after 10000 entries" out)))
         (str/includes? out "grep"))))

(harness/defbuilt-test
  recursive-traversal-budget-test
  ;; Vis #279: stop before the watchdog and return what the walk found.
  (doseq [confined? [false true]]
    (let [session (harness/block-session)]
      (setup session)
      (when confined? (runtime/confine! [(str @tree) (System/getProperty "user.dir")] []))
      (doseq [code ["print(len(list(root.rglob('*.missing'))))"
                    "print(len(list(root.glob('**/*.missing'))))"
                    "print(len(glob.glob(str(root / '**' / '*.missing'), recursive=True)))"
                    "print(len(list(os.walk(root))))"]]
        (testing (str "confined=" confined? " " code)
          (let [answer (harness/block session (str "kept = 279\n" code))]
            (is (partial? answer) (pr-str answer))
            (is (str/includes? (str (:stdout answer)) (str @tree)) (pr-str answer)))
          (is (= {:stdout "279\n" :error nil} (harness/block session "print(kept)"))))))))

(harness/defbuilt-test
  shared-budget-and-recovery-test
  (let [session (harness/block-session)]
    (setup session)
    (is (= {:stdout "6000\n" :error nil}
           (harness/block session
                          (str "from itertools import islice\n"
                               "with os.scandir(root) as scan:\n"
                               "    print(len(list(islice(scan, 6000))))"))))
    (let [answer (harness/block session
                                (str "with os.scandir(root) as scan:\n"
                                     "    print(len(list(islice(scan, 6000))))\n"
                                     "with os.scandir(root) as scan:\n"
                                     "    print(len(list(islice(scan, 6000))))"))]
      (is (partial? answer) (pr-str answer))
      (is (str/starts-with? (str (:stdout answer)) "6000\n") (pr-str answer))
      (is (str/ends-with? (str (:stdout answer)) "\n4000\n") (pr-str answer)))
    (is (= {:stdout "10000\n" :error nil}
           (harness/block session
                          (str "with os.scandir(root) as scan:\n"
                               "    print(len(list(islice(scan, 10000))))"))))))

(harness/defbuilt-test
  native-enumeration-entrypoints-test
  (let [session (harness/block-session)]
    (setup session)
    (runtime/exec! session
                   "import importlib\ncached_listdir = os.listdir\ncached_scandir = os.scandir")
    (doseq [code ["print(len(os.listdir(root)))" "print(len(cached_listdir(path=root)))"
                  "print(len(importlib.reload(os).listdir(root)))"
                  "print(len(list(cached_scandir(root))))"
                  (str "it = os.scandir(root)\nn = 0\ntry:\n"
                       "    while True:\n        it.__next__()\n        n += 1\n"
                       "except StopIteration:\n    print(n)")
                  (str "it = os.scandir(root)\nn = 0\ntry:\n"
                       "    while True:\n        type(it).__next__(it)\n        n += 1\n"
                       "except StopIteration:\n    print(n)")]]
      (let [answer (harness/block session code)]
        (is (partial? answer) (pr-str answer))
        (is (str/ends-with? (str (:stdout answer)) "\n10000\n") (pr-str answer))))))

(harness/defbuilt-test
  spent-budget-stays-spent-test
  (let [session (harness/block-session)]
    (setup session)
    (let [answer (harness/block session
                                (str "it = os.scandir(root)\n" "first = len(list(it))\n"
                                     "assert next(it, None) is None\n"
                                     "print(first, len(os.listdir(root)))"))]
      (is (partial? answer) (pr-str answer))
      (is (str/ends-with? (str (:stdout answer)) "\n10000 0\n") (pr-str answer)))))

(harness/defbuilt-test
  gather-shares-one-entry-budget-test
  (let [session (harness/block-session)]
    (setup session)
    (runtime/exec! session
                   (str "import vis_runtime\n" "def scan_part():\n"
                        "    count = 0\n" "    with os.scandir(root) as scan:\n"
                        "        for entry in scan:\n" "            count += 1\n"
                        "            if count == 6000: break\n" "    return count\n"))
    (let [answer (harness/block session "print(sum(vis_runtime.par([scan_part, scan_part])))")]
      (is (partial? answer) (pr-str answer))
      (is (str/ends-with? (str (:stdout answer)) "\n10000\n") (pr-str answer)))
    (is (= {:stdout "6000\n" :error nil} (harness/block session "print(scan_part())")))))

(harness/defbuilt-test
  bulk-file-operations-do-not-spend-budget-test
  ;; Vis #362: rmtree, copytree and pytest are not searches; reads stay capped.
  (let [session
        (harness/block-session)

        copy
        (str (io/file (harness/temp-dir "vis-bulk") "copy"))]

    (setup session)
    (is (= {:stdout "False 9000\n" :error nil}
           (harness/block session
                          (str "import shutil\nfrom itertools import islice\n"
                               "shutil.copytree(root, "
                               (pr-str copy)
                               ")\n"
                               "shutil.rmtree("
                               (pr-str copy)
                               ")\n"
                               "with os.scandir(root) as scan:\n"
                               "    kept = len(list(islice(scan, 9000)))\n"
                               "print(os.path.exists("
                               (pr-str copy)
                               "), kept)"))))
    (doseq [[module exempt?] [["_pytest.pathlib" true] ["pytest" true] ["shutilx" false]]]
      (testing module
        (let [answer (harness/block
                       session
                       (str "probe = {'__name__': "
                            (pr-str module)
                            ", 'os': os}\n"
                            "exec('def visit(p):\\n    return len(os.listdir(p))', probe)\n"
                            "print(probe['visit'](root))"))]
          (if exempt?
            (is (= {:stdout "10001\n" :error nil} answer))
            (do (is (partial? answer) (pr-str answer))
                (is (str/ends-with? (str (:stdout answer)) "\n10000\n") (pr-str answer)))))))))

(harness/defbuilt-test
  small-scans-and-exact-limit-test
  (let [session
        (harness/block-session)

        root
        (io/file (harness/temp-dir "vis-small-scan"))]

    (try
      (.mkdir (io/file root "empty"))
      (spit (io/file root ".hidden") "")
      (runtime/exec! session (str "import os, pathlib\nsmall = " (pr-str (str root))))
      (let [answer (harness/block
                     session
                     (str "expected = {'.hidden', 'empty'}\n"
                          "assert set(os.listdir(path=pathlib.Path(small))) == expected\n"
                          "assert set(os.listdir(os.fsencode(small))) == {b'.hidden', b'empty'}\n"
                          "with os.scandir(small) as scan:\n"
                          "    assert {e.name for e in scan} == expected\n"
                          "assert os.listdir(small + '/empty') == []\n" "if os.name != 'nt':\n"
                          "    fd = os.open(small, os.O_RDONLY)\n"
                          "    try:\n        assert set(os.listdir(fd)) == expected\n"
                          "    finally:\n        os.close(fd)\n"
                          "try:\n    os.listdir(small + '/absent')\n"
                          "except FileNotFoundError:\n    pass\nelse:\n    assert False\n"
                          "try:\n    os.listdir(unknown=small)\n"
                          "except TypeError:\n    pass\nelse:\n    assert False\n"
                          "print('ordinary')"))]
        (is (= {:stdout "ordinary\n" :error nil} answer)))
      (is (= {:stdout "exact empty\n" :error nil}
             (harness/block session
                            (str "for _ in range(5000):\n    assert len(os.listdir(small)) == 2\n"
                                 "print('exact', end=' ')\n"
                                 "assert os.listdir(small + '/empty') == []\n" "print('empty')"))))
      (is (partial? (harness/block session
                                   (str "for _ in range(5001):\n" "    list(os.scandir(small))"))))
      (finally (doseq [file (reverse (file-seq root))]
                 (io/delete-file file))))))
