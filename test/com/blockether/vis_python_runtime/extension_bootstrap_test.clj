(ns com.blockether.vis-python-runtime.extension-bootstrap-test
  "Namespace ownership and slotted records at the extension injection boundary."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [is use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(defn- bootstrap-code
  [body]
  (str
    "g = {'_vis_body': "
    (json/write-str body :escape-slash false)
    "}\n"
    "exec("
    (json/write-str (slurp (io/resource "vis-python/extension_bootstrap.py")) :escape-slash false)
    ", g)\n"))

(harness/defbuilt-test
  namespace-preservation-test
  (let [session (harness/block-session)]
    (is
      (true?
        (harness/ev-guarded
          session
          (str
            "import sys, types\n" "parent = types.ModuleType('blockether')\nparent.__path__ = []\n"
            "sibling = types.ModuleType('blockether.other')\nparent.other = sibling\n"
            "unrelated = types.ModuleType('vis')\n"
            "sys.modules.update({'blockether': parent, 'blockether.other': sibling, 'vis': unrelated})\n"
            (bootstrap-code "_registration = {'spec': None}")
            "from blockether import vis\n"
            "vis is g['_vis_mod'] and sys.modules['vis'] is unrelated and "
            "sys.modules['blockether'] is parent and parent.other is sibling and "
            "sys.modules['blockether.other'] is sibling and vis.__package__ == 'blockether.vis'"))))))

(harness/defbuilt-test
  slotted-dataclass-marshalling-test
  (let [session (harness/block-session)]
    (is
      (=
        {"__vis_object__" "Result"
         "__vis_attrs__" {"child" {"__vis_object__" "Child" "__vis_attrs__" {"value" 7}}}}
        (harness/ev-guarded
          session
          (str
            (bootstrap-code
              (str
                "from dataclasses import dataclass\n"
                "@dataclass(frozen=True, slots=True)\nclass Child:\n    value: int\n"
                "@dataclass(frozen=True, slots=True)\nclass Result:\n    child: Child\n    _private: str = 'hidden'\n"
                "result = Result(Child(7))\n_registration = {'spec': None}"))
            "g['__vis_seal__'](g['_vis_mod'].result)"))))))
