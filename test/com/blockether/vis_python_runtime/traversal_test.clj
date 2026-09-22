(ns com.blockether.vis-python-runtime.traversal-test
  "Directory-entry budgets at the native sandbox boundary (Vis #279)."
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

(harness/defbuilt-test
  recursive-traversal-budget-test
  ;; Vis #279: fail before the watchdog, without hiding unmatched entries.
  (doseq [confined? [false true]]
    (let [session (harness/block-session)]
      (setup session)
      (when confined? (runtime/confine! [(str @tree) (System/getProperty "user.dir")] []))
      (doseq [code ["list(root.rglob('*.missing'))" "list(root.glob('**/*.missing'))"
                    "glob.glob(str(root / '**' / '*.missing'), recursive=True)"
                    "list(os.walk(root))"]]
        (testing (str "confined=" confined? " " code)
          (let [answer (harness/block session (str "kept = 279\n" code))]
            (is (str/includes? (str (:error answer)) "10000") (pr-str answer))
            (is (str/includes? (str (:error answer)) "grep") (pr-str answer))
            (is (str/includes? (str (:error answer)) (str @tree)) (pr-str answer)))
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
                                     "    list(islice(scan, 6000))"))]
      (is (= "6000\n" (:stdout answer)))
      (is (str/includes? (str (:error answer)) "10000")))
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
    (doseq [code ["os.listdir(root)" "cached_listdir(path=root)"
                  "importlib.reload(os).listdir(root)" "list(cached_scandir(root))"
                  "it = os.scandir(root)\nfor _ in range(10001): it.__next__()"
                  "it = os.scandir(root)\nfor _ in range(10001): type(it).__next__(it)"]]
      (let [answer (harness/block session code)]
        (is (str/includes? (str (:error answer)) "10000") (pr-str answer))))))

(harness/defbuilt-test
  caught-exhaustion-does-not-reset-budget-test
  (let [session (harness/block-session)]
    (setup session)
    (is (= {:stdout "closed exhausted\n" :error nil}
           (harness/block
             session
             (str "it = os.scandir(root)\n" "try:\n    list(it)\n"
                  "except RuntimeError as error:\n" "    assert not isinstance(error, OSError)\n"
                  "    assert next(it, None) is None\n" "    print('closed', end=' ')\n"
                  "try:\n    os.listdir(root)\n"
                  "except RuntimeError:\n    print('exhausted')"))))))

(harness/defbuilt-test
  gather-shares-one-entry-budget-test
  (let [session (harness/block-session)]
    (setup session)
    (runtime/exec! session
                   (str "import vis_runtime\n" "def scan_part():\n"
                        "    count = 0\n" "    try:\n"
                        "        with os.scandir(root) as scan:\n"
                        "            for entry in scan:\n"
                        "                count += 1\n" "                if count == 6000: break\n"
                        "    except RuntimeError:\n        pass\n" "    return count\n"))
    (is (= {:stdout "10000\n" :error nil}
           (harness/block session "print(sum(vis_runtime.par([scan_part, scan_part])))")))
    (is (= {:stdout "6000\n" :error nil} (harness/block session "print(scan_part())")))))

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
      (is (str/includes? (str (:error (harness/block session
                                                     (str "for _ in range(5001):\n"
                                                          "    list(os.scandir(small))"))))
                         "10000"))
      (finally (doseq [file (reverse (file-seq root))]
                 (io/delete-file file))))))
