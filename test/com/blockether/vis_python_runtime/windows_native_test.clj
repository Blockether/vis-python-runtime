(ns com.blockether.vis-python-runtime.windows-native-test
  "Exercise the native ABI and Windows filesystem boundary through the public bridge."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime])
  (:import [com.blockether.vispython VisPythonException]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private windows? (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- temporary-directory
  ^Path [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- python-string [value] (json/write-str (str value) :escape-slash false))

(defn- read-expression [path] (str "open(" (python-string path) ", encoding='utf-8').read()"))

(use-fixtures :each
              (fn [run]
                (runtime/initialize!)
                (try (run)
                     (finally (runtime/confine! [] [])
                              (runtime/network! true)
                              (runtime/threads! 100 0 8)
                              (runtime/logging! :off)
                              (runtime/drain-log!)))))

(deftest invalid-policy-fails-closed-test
  ;; Windows rejects device/UNC roots; dropping every root must not unconfine the process.
  (let [outside (.resolve (temporary-directory "vis-invalid-policy") "private.txt")]
    (spit (str outside) "outside")
    (is (= "outside" (runtime/eval-str "invalid-policy" (read-expression outside))))
    (runtime/confine! [(apply str (repeat 40000 "x"))] [])
    (is (thrown-with-msg? VisPythonException
                          #"vis sandbox"
                          (runtime/eval-str "invalid-policy" (read-expression outside))))))

(deftest overlong-audit-path-fails-closed-test
  (runtime/confine! [(str (temporary-directory "vis-long-path"))] [])
  (is (thrown-with-msg? VisPythonException
                        #"vis sandbox"
                        (runtime/eval-str "long-path" "open('x' * 40000)"))))

(deftest windows-native-strings-and-thread-local-results-test
  (when windows?
    (is (= "zażółć 🐍" (runtime/eval-str "windows-unicode" "'zażółć 🐍'")))
    (testing "oversized results are recovered once on the calling thread"
      (let [answers (doall (for [n (range 8)]
                             (future (runtime/eval-str (str "windows-result-" n)
                                                       (str "'" n "' * 90000")))))]
        (doseq [[n answer] (map-indexed vector answers)]
          (is (= (apply str (repeat 90000 (str n))) @answer)))))
    (is (= "42" (runtime/eval-str "windows-unicode" "sum([19, 23])")))))

(deftest windows-native-confinement-test
  (when windows?
    (let [inside
          (temporary-directory "vis-zażółć-")

          outside
          (temporary-directory "vis-outside-")

          readable
          (.resolve inside "żółw.txt")

          private
          (.resolve outside "secret.txt")

          created
          (.resolve inside "新しい.txt")]

      (spit (str readable) "inside" :encoding "UTF-8")
      (spit (str private) "outside" :encoding "UTF-8")
      (runtime/confine! [] [(str inside)])
      (is (= "inside" (runtime/eval-str "windows-files" (read-expression readable))))
      (is (= "2"
             (runtime/eval-str
               "windows-files"
               (str "open(" (python-string created) ", 'w', encoding='utf-8').write('ok')"))))
      (is (= "ok" (slurp (str created) :encoding "UTF-8")))
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-files" (read-expression private))))
      (doseq [path [(str readable ":stream") (str inside "\\NUL") (str inside "\\CON.txt")
                    "\\\\.\\NUL" "\\\\?\\C:\\Windows\\win.ini" "\\\\127.0.0.1\\share\\secret"]]
        (is (thrown-with-msg? VisPythonException
                              #"vis sandbox"
                              (runtime/eval-str "windows-files" (read-expression path)))
            path)))))

(deftest windows-native-junction-escape-test
  (when windows?
    (let [inside
          (temporary-directory "vis-junction-root-")

          outside
          (temporary-directory "vis-junction-outside-")

          junction
          (.resolve inside "escape")

          secret
          (.resolve outside "secret.txt")

          process
          (-> (ProcessBuilder. ^"[Ljava.lang.String;"
                               (into-array String
                                           ["cmd.exe" "/d" "/c" "mklink" "/J" (str junction)
                                            (str outside)]))
              (.redirectErrorStream true)
              (.start))

          output
          (slurp (.getInputStream process))]

      (is (= 0 (.waitFor process)) output)
      (spit (str secret) "private")
      (runtime/confine! [] [(str inside)])
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-junction"
                                              (read-expression (.resolve junction "secret.txt")))))
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-junction"
                                              (str "open("
                                                   (python-string (.resolve junction "new.txt"))
                                                   ", 'w').write('escape')"))))
      (is (not (.exists (.toFile (.resolve outside "new.txt")))))
      (runtime/confine! [] [])
      (Files/delete junction))))

(deftest windows-native-policy-and-diagnostics-test
  (when windows?
    (runtime/confine! [(str (temporary-directory "vis-windows-policy-"))] [])
    (doseq [code
            ["__import__('os').system('echo forbidden')"
             "__import__('winreg').OpenKey(__import__('winreg').HKEY_CURRENT_USER, 'Software')"]]
      (is (thrown-with-msg? VisPythonException
                            #"vis sandbox"
                            (runtime/eval-str "windows-policy" code))))
    (runtime/network! false)
    (is (thrown-with-msg? VisPythonException
                          #"vis sandbox"
                          (runtime/eval-str "windows-policy" "__import__('socket').socket()")))
    (runtime/logging! :debug)
    (is (= "42" (runtime/eval-str "windows-policy" "6 * 7")))
    (let [records (map json/read-str (str/split-lines (runtime/drain-log!)))]
      (is (seq records))
      (is (every? #(number? (get % "ts")) records)))
    (is (= "" (runtime/drain-log!)))))

(deftest windows-native-filesystem-errors-test
  ;; The Windows CI DLL link must not depend on the CRT's private error mapper.
  (let [directory
        (temporary-directory "vis-windows-errors-")

        missing
        (.resolve directory "missing")

        target
        (.resolve directory "target.txt")

        session
        "windows-filesystem-errors"]

    (runtime/trust! session)
    (try (is (= "[]"
                (runtime/run session
                             (str "__import__('_vis_fs').list(" (python-string directory) ")"))))
         (doseq [expression [(str "__import__('_vis_fs').list(" (python-string missing) ")")
                             (str "__import__('_vis_fs').move("
                                  (python-string missing)
                                  ", "
                                  (python-string target)
                                  ")")]]
           (is (thrown-with-msg? VisPythonException
                                 #"FileNotFoundError"
                                 (runtime/run session expression))))
         (is (not (.exists (.toFile target))))
         (finally (runtime/trust! session false) (Files/delete directory)))))

(deftest windows-native-trusted-files-test
  (when windows?
    (let [outside
          (temporary-directory "vis-trusted-zażółć-")

          session
          "windows-trusted-files"]

      (runtime/confine! [(str (temporary-directory "vis-trusted-policy-"))] [])
      (runtime/trust! session)
      (try (is (= ["new.txt" 2 "ok" true true]
                  (json/read-str
                    (runtime/run
                      session
                      (str "import _vis_fs\n"
                           "directory = " (python-string (.resolve outside "新しい"))
                           "\n" "_vis_fs.mkdir(directory)\n"
                           "source = directory + '/żółw.txt'\n" "copy = directory + '/copy.txt'\n"
                           "target = directory + '/new.txt'\n" "_vis_fs.write(source, 'ok')\n"
                           "_vis_fs.copy(source, copy)\n" "_vis_fs.move(copy, target)\n"
                           "_vis_fs.remove(source)\n"
                           "[_vis_fs.list(directory)[0], _vis_fs.stat(target)['size'], "
                           "_vis_fs.read(target).decode(), _vis_fs.remove(target), "
                           "_vis_fs.remove(directory)]")))))
           (finally (runtime/trust! session false))))))
