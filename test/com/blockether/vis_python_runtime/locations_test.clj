(ns com.blockether.vis-python-runtime.locations-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [com.blockether.vispython Locations]))

(defn- with-vis-home
  "Call `f` while the `vis.home` property is `value`, then restore it."
  [value f]
  (let [old (System/getProperty "vis.home")]
    (try (System/setProperty "vis.home" value)
         (f)
         (finally (if old (System/setProperty "vis.home" old) (System/clearProperty "vis.home"))))))

;; Blockether/vis#455: a Vis with its own VIS_HOME still kept this runtime's state in ~/.vis/python.
(deftest vis-home-state-test
  (testing "the runtime state is below the Vis home that the host sets"
    (let [home (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir") "vis-home-455"))]
      (with-vis-home home
                     #(do (is (= (str home "/python") (Locations/stateRoot)))
                          (is (= (str home "/python/sources") (Locations/sourcesDir)))
                          (is (= (str home "/python/runtime/1.0/darwin-arm64")
                                 (Locations/runtimeDir "1.0" "darwin-arm64")))))))
  (testing "a leading ~ resolves against user.home"
    (with-vis-home "~/vis-455"
                   #(is (= (str (System/getProperty "user.home") "/vis-455/python")
                           (Locations/stateRoot)))))
  (testing "without a setting, the state is in ~/.vis/python"
    (when-not (System/getenv "VIS_HOME")
      (with-vis-home ""
                     #(is (= (str (System/getProperty "user.home") "/.vis/python")
                             (Locations/stateRoot)))))))
