(ns com.blockether.vis-python-runtime.shim.tabulate-test
  "The tabulate-compat shim installed into every sandbox context via the generic
   sandbox-shim mechanism (`extension/sandbox-shims`): a `tabulate` module published
   into `sys.modules` (so `from tabulate import tabulate` works) and implemented in
   PURE Python on the stdlib. Renders list-of-lists / list-of-dicts / dict-of-lists
   / DataFrame across plain/simple/github/grid/rst/html tablefmts. No host bridge."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [ev]]))

(harness/defshim-test tabulate-module-test "tabulate"
  (testing "publishes tabulate under sys.modules"
    (is (true?
         (ev session
             "import tabulate\n__import__('sys').modules.get('tabulate') is not None"))))
  (testing "supports `from tabulate import tabulate`"
    (is (true? (ev session
                   (str "from tabulate import tabulate\n"
                        "isinstance(tabulate([[1,2]], tablefmt='plain'), str)"))))))

(harness/defshim-test tabulate-format-test "tabulate"
  (testing "simple format aligns numbers right, strings left, under a rule"
    (is (true? (ev session
                   (str "from tabulate import tabulate\n"
                        "t = tabulate([['Alice',30]], headers=['name','age'])\n"
                        "lines = t.split(chr(10))\n"
                        "lines[0].startswith('name') and set(lines[1]) <= set('- ') "
                        "and lines[2].rstrip().endswith('30')")))))
  (testing "github format emits a pipe header + alignment separator row"
    (is (true? (ev session
                   (str "from tabulate import tabulate\n"
                        "t = tabulate([['a',1]], headers=['s','n'], tablefmt='github')\n"
                        "lines = t.split(chr(10))\n"
                        "lines[0].startswith('|') and '--:' in lines[1]")))))
  (testing "grid format draws box borders with + corners"
    (is (true? (ev session
                   (str "from tabulate import tabulate\n"
                        "t = tabulate([['a',1]], headers=['s','n'], tablefmt='grid')\n"
                        "t.startswith('+') and t.count('+') >= 8 and '|' in t")))))
  (testing "headers='keys' reads column names from list-of-dicts"
    (is
     (true?
      (ev session
          (str
           "from tabulate import tabulate\n"
           "t = tabulate([{'a':1,'b':2},{'a':3,'b':4}], headers='keys', tablefmt='plain')\n"
           "t.split(chr(10))[0].split() == ['a','b']")))))
  (testing "renders a pandas-shim DataFrame directly"
    (is (true? (ev session
                   (str "from tabulate import tabulate\n"
                        "import pandas as pd\n"
                        "df = pd.DataFrame({'x':[1,2],'y':['p','q']})\n"
                        "t = tabulate(df, headers='keys', tablefmt='github')\n"
                        "'x' in t and 'y' in t and 'p' in t and 'q' in t"))))))

(defn- render
  "Renders one tabulate call inside the sandbox context and returns the string."
  [c expr]
  (ev c (str "from tabulate import tabulate\n" expr)))

;; Byte-for-byte fidelity against upstream python-tabulate 0.9.0 output.
(harness/defshim-test tabulate-fidelity-test "tabulate"
  (testing "plain padding, decimal alignment and float trimming"
    (is (= "item        cost\n------  --------\nspam     41.9999\neggs    451"
           (render
            session
            "tabulate([['spam',41.9999],['eggs',451.0]], headers=['item','cost'])"))))
  (testing "simple format pads headers to MIN_PADDING and rules the columns"
    (is (= "name      age\n------  -----\nAlice      30\nBob         9"
           (render session
                   "tabulate([['Alice',30],['Bob',9]], headers=['name','age'])"))))
  (testing "headerless simple keeps a top AND bottom rule"
    (is (= "-----  --\nAlice  30\nBob     9\n-----  --"
           (render session
                   "tabulate([['Alice',30],['Bob',9]])"))))
  (testing "numeric strings are parsed and aligned on the decimal point"
    (is (= "   v\n----\n 1.5\n10"
           (render session
                   "tabulate([['1.5'],['10']], headers=['v'])"))))
  (testing "github separator carries per-column alignment colons"
    (is (= "| s   |   n |\n|:----|----:|\n| a   |   1 |"
           (render session
                   "tabulate([['a',1]], headers=['s','n'], tablefmt='github')"))))
  (testing "pipe format matches github's colon separator"
    (is (= "| s   |   n |\n|:----|----:|\n| a   |   1 |"
           (render session
                   "tabulate([['a',1]], headers=['s','n'], tablefmt='pipe')"))))
  (testing "orgtbl uses + at the header separator crossings"
    (is (= "| s   |   n |\n|-----+-----|\n| a   |   1 |"
           (render session
                   "tabulate([['a',1]], headers=['s','n'], tablefmt='orgtbl')"))))
  (testing "rst rules with = and no pipes"
    (is (= "===  ===\ns      n\n===  ===\na      1\n===  ==="
           (render session
                   "tabulate([['a',1]], headers=['s','n'], tablefmt='rst')"))))
  (testing "tsv keeps the padded cells"
    (is (= "s  \t  n\na  \t  1"
           (render session
                   "tabulate([['a',1]], headers=['s','n'], tablefmt='tsv')"))))
  (testing
   "html carries per-column text-align styles"
    (is
     (=
      "<table>\n<thead>\n<tr><th style=\"text-align: left;\">s  </th><th style=\"text-align: right;\">  n</th></tr>\n</thead>\n<tbody>\n<tr><td style=\"text-align: left;\">a  </td><td style=\"text-align: right;\">  1</td></tr>\n</tbody>\n</table>"
      (render session "tabulate([['a',1]], headers=['s','n'], tablefmt='html')"))))
  (testing
   "multiline cells are split across grid rows"
    (is
     (=
      "+-------+-----+\n| a     |   b |\n+=======+=====+\n| two   |   1 |\n| lines |     |\n+-------+-----+"
      (render session
              "tabulate([['two\\nlines',1]], headers=['a','b'], tablefmt='grid')"))))
  (testing
   "maxcolwidths wraps a long cell"
    (is
     (=
      "+----------+-----+\n| s        |   n |\n+==========+=====+\n| a long   |   1 |\n| sentence |     |\n| here     |     |\n+----------+-----+"
      (render
       session
       "tabulate([['a long sentence here',1]], headers=['s','n'], maxcolwidths=[8,None], tablefmt='grid')"))))
  (testing "colalign overrides the inferred alignment"
    (is (= "  s  n\n---  ---\n  a  1"
           (render session
                   "tabulate([['a',1]], headers=['s','n'], colalign=('right','left'))"))))
  (testing "floatfmt applies to every float"
    (is (= "   a     b\n----  ----\n1.23  2.00"
           (render session
                   "tabulate([[1.23456,2.0]], headers=['a','b'], floatfmt='.2f')"))))
  (testing "showindex prepends a right-aligned index column"
    (is (= "    s      n\n--  ---  ---\n 0  a      1\n 1  b      2"
           (render session
                   "tabulate([['a',1],['b',2]], headers=['s','n'], showindex=True)"))))
  (testing "a generator of rows renders like a list of rows"
    (is (= "s      n\n---  ---\na      1\nb      2"
           (render session
                   "tabulate((r for r in [['a',1],['b',2]]), headers=['s','n'])"))))
  (testing "missing values render as the empty string"
    (is (= "s    n\n---  ---\na"
           (render session
                   "tabulate([['a',None]], headers=['s','n'])")))))

(harness/defshim-test tabulate-surface-test "tabulate"
  (testing "exposes tabulate_formats, simple_separated_format and TableFormat"
    (is (= [true true true]
           (ev session
               (str "import tabulate as tb\n"
                    "['github' in tb.tabulate_formats,\n"
                    " tb.simple_separated_format(',').padding == 0,\n"
                    " isinstance(tb.TableFormat, type)]")))))
  (testing
   "renders SEPARATING_LINE as a rule between body rows"
    (is
     (=
      "s      n\n---  ---\na      1\n---  ---\nb      2"
      (render
       session
       "tabulate([['a',1], __import__('tabulate').SEPARATING_LINE, ['b',2]], headers=['s','n'])")))))
