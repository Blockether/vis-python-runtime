(ns com.blockether.vis-python-runtime.native-reachability-test
  "The registrations a native image cannot infer, and the jar carries.

   An FFM downcall is LINKED, not reflected: the image generates a stub only for
   a descriptor somebody declared, and an undeclared one aborts in the consuming
   binary with `MissingForeignRegistrationError` — measured, in Vis' binary,
   while this suite stayed green. Same for the host upcall stub and for the
   Python this library reads at run time: without the `SOURCES` manifest the
   embedding binary died with `ModuleNotFoundError: vis_runtime`.

   So the metadata travels inside the jar, and this test keeps it equal to
   `Interpreter`'s own boundary: a downcall with a NEW shape fails here."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [com.blockether.vispython Interpreter Jail Sources]
           [java.lang.foreign FunctionDescriptor MemoryLayout]))

(def ^:private metadata-file
  (io/file "resources" "META-INF" "native-image" "com.blockether" "vis-python-runtime"
           "reachability-metadata.json"))

(defn- metadata [] (json/read-str (slurp metadata-file)))

(defn- token
  "The reachability-metadata spelling of one FFM layout."
  [^MemoryLayout layout]
  (condp = layout
    java.lang.foreign.ValueLayout/JAVA_INT "jint"
    java.lang.foreign.ValueLayout/ADDRESS "void*"
    (throw (ex-info "no metadata spelling for this layout" {:layout (str layout)}))))

(defn- shape
  "One descriptor as the metadata declares it."
  [^FunctionDescriptor descriptor]
  {"returnType" (token (.orElseThrow (.returnLayout descriptor)))
   "parameterTypes" (mapv token (.argumentLayouts descriptor))})

(defn- boundary
  "A private static boundary field, read rather than restated by this test."
  [^Class owner field-name]
  (let [^java.lang.reflect.Field field (.getDeclaredField owner field-name)]
    (.setAccessible field true)
    (.get field nil)))

(defn- declared
  "The shapes the shipped metadata declares in `foreign/<section>`."
  [section]
  (->> (get-in (metadata) ["foreign" section])
       (map #(select-keys % ["returnType" "parameterTypes"]))
       set))

(deftest ffm-registrations-cover-the-whole-boundary-test
  (testing "the metadata ships and parses"
    (is (.isFile ^java.io.File metadata-file))
    (is (seq (declared "downcalls"))))

  (testing "every downcall shape in SIGNATURES is declared"
    (let [shapes (into (sorted-set) (map (comp pr-str shape))
                       (concat (vals (boundary Interpreter "SIGNATURES"))
                               (vals (boundary Jail "SIGNATURES"))))
          have (into (sorted-set) (map pr-str) (declared "downcalls"))]
      (is (empty? (remove have shapes))
          (str "undeclared downcall shapes: " (pr-str (remove have shapes))))))

  (testing "the host upcall stub is declared"
    (is (contains? (declared "upcalls") (shape (boundary Interpreter "HOST_DESCRIPTOR"))))))

(deftest the-python-this-library-reads-is-declared-as-a-resource-test
  (testing "the source manifest is a declared resource"
    (let [globs (set (map #(get % "glob") (get (metadata) "resources")))]
      (is (contains? globs Sources/MANIFEST)
          (str "without " Sources/MANIFEST " the embedding binary cannot find any "
               "of this library's Python"))
      (is (some #(re-find #"vispython/.*\.py$" %) globs))
      (is (some #(re-find #"vis-python/.*\.py$" %) globs)))))
