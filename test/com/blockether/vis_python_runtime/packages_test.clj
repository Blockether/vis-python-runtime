(ns com.blockether.vis-python-runtime.packages-test
  "The packages the artifact SHIPS (`packages/base.txt`).

   These are not shims, and that is the whole point of the file. `install_finder`
   APPENDS the shim finder to `sys.meta_path`, behind `PathFinder`, so a package
   present on `sys.path` is what `import` finds and the shim of that name is
   never consulted. No code changes to make the cutover happen; a wheel arriving
   in the artifact IS the cutover. These cases are the evidence: what a block
   gets is the real distribution, at its real file.

   Which package ships and which the host installs on first import is decided by
   measurement, not by taste, and both requirement files carry the numbers."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally (harness/close-sessions!)))))

(def ^:private shipped
  "Import name -> the distribution in `packages/base.txt` that answers to it.

   Only the names a shim ALSO answers to are listed: those are the promises the
   artifact now keeps with the real library instead of a reimplementation. The
   HTTP three are absent on purpose - `packages/base.txt` says why."
  {"bs4"        "beautifulsoup4"
   "dateutil"   "python-dateutil"
   "pytz"       "pytz"
   "soupsieve"  "soupsieve"
   "xlsxwriter" "xlsxwriter"
   "yaml"       "PyYAML"})

(defn- requirement-lines
  "The requirement lines of `path`, comments and blanks dropped."
  [path]
  (->> (str/split-lines (slurp path))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (vec)))

(defn- distributions
  "The normalized distribution names `path` pins."
  [path]
  (->> (requirement-lines path)
       (map #(-> % (str/split #"==") (first) (str/lower-case) (str/replace "_" "-")))
       (set)))

(deftest pinned-requirements-test
  (testing "every requirement is pinned, or what the artifact holds depends on the day it was built"
    (doseq [path  ["packages/base.txt" "packages/on-demand.txt"]
            line  (requirement-lines path)]
      (is (re-matches #"[A-Za-z0-9._-]+==[A-Za-z0-9.+!-]+" line)
          (str path " has an unpinned requirement: " line))))
  (testing "a package ships or is installed on demand, never both"
    (is (empty? (set/intersection (distributions "packages/base.txt")
                                  (distributions "packages/on-demand.txt"))))))

(deftest shipped-names-are-pinned-test
  (testing "every distribution this namespace claims is shipped is actually in base.txt"
    (let [pinned (distributions "packages/base.txt")]
      (doseq [[module distribution] (sort shipped)]
        (is (contains? pinned (str/lower-case distribution))
            (str module " claims to come from " distribution ", which base.txt does not pin"))))))

(harness/defbuilt-test shipped-packages-are-real-test
  (let [session (harness/fresh "packages")]
    (doseq [[module distribution] (sort shipped)]
      (testing (str "`import " module "` finds " distribution ", not the shim of that name")
        (let [answer (block session (str "import " module "\nprint(" module ".__file__)"))
              file   (str/trim (str (:stdout answer)))]
          (is (nil? (:error answer)) (str module " did not import: " (:error answer)))
          (is (str/includes? file "site-packages")
              (str module " resolved to " file " instead of an installed distribution")))))))
