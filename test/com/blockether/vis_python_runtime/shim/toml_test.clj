(ns com.blockether.vis-python-runtime.shim.toml-test
  "The toml-compat shim installed into every sandbox context via the generic
   sandbox-shim mechanism (`extension/sandbox-shims`): a `toml` module published
   into `sys.modules` (so `import toml` works). Reading delegates to the stdlib
   `tomllib` for a spec-correct parse; writing is a pure-Python serializer covering
   scalars, arrays, inline/nested tables and array-of-tables. No host bridge."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [truthy]]))

(harness/defshim-test toml-module-test "toml"
  (testing "publishes toml under sys.modules"
    (is (truthy session "import toml\n__import__('sys').modules.get('toml') is not None")))
  (testing "autoloads toml onto builtins (no import needed)"
    (is (truthy session "'a = 1' in toml.dumps({'a':1})")))
  (testing "supports `import toml` with a version string"
    (is (truthy session "import toml\nisinstance(toml.__version__, str)"))))

(harness/defshim-test toml-roundtrip-test "toml"
  (testing "loads scalars, arrays and tables via the stdlib tomllib parser"
    (is (truthy session (str "import toml\n"
                             "doc = 'title = ' + chr(39) + 'vis' + chr(39) + chr(10)\n"
                             "doc += '[owner]' + chr(10) + 'ports = [80, 443]' + chr(10)\n"
                             "d = toml.loads(doc)\n"
                             "d['title'] == 'vis' and d['owner']['ports'] == [80, 443]"))))
  (testing "dumps nested tables and round-trips back to the same dict"
    (is (truthy session (str "import toml\n"
                             "obj = {'title':'vis','owner':{'name':'blk','ports':[1,2]}}\n"
                             "toml.loads(toml.dumps(obj)) == obj"))))
  (testing "serializes an array-of-tables as [[section]]"
    (is (truthy session (str "import toml\n" "obj = {'items':[{'id':1},{'id':2}]}\n"
                             "s = toml.dumps(obj)\n"
                             "s.count('[[items]]') == 2 and toml.loads(s) == obj"))))
  (testing "serializes booleans/floats/strings with correct toml syntax"
    (is (truthy session (str "import toml\n" "s = toml.dumps({'b':True,'f':1.5,'name':'ab'})\n"
                             "'b = true' in s and 'f = 1.5' in s "
                             "and ('name = ' + chr(34) + 'ab' + chr(34)) in s")))))
