(ns com.blockether.vis-python-runtime.shim.numpy-test
  "The numpy-compat shim installed into every sandbox context via the generic
   sandbox-shim mechanism (`extension/sandbox-shims`): a `numpy` module published
   into `sys.modules` (so `import numpy` works) and implemented in PURE Python on
   the stdlib (math + random) — an ndarray with broadcasting, reductions, ufuncs,
   indexing, dot/matmul, a linalg submodule and a random submodule. No host bridge."
  (:require [clojure.test :refer [is testing]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [ev]]))

(harness/defshim-test numpy-module-test "numpy"
  (testing "publishes numpy under sys.modules"
    (is (true? (ev session
                   "import numpy\n__import__('sys').modules.get('numpy') is not None"))))
  (testing "autoloads numpy onto builtins (no import needed)"
    (is (true? (ev session "numpy.array([1,2,3]).sum() == 6"))))
  (testing "supports `import numpy as np`"
    (is (true? (ev session "import numpy as np\nnp.array([1,2]).shape == (2,)"))))
  (testing "exposes a version string + constants"
    (is (true? (ev session
                   (str
                    "import numpy as np\n"
                    "isinstance(np.__version__, str) and abs(np.pi - 3.14159265) < 1e-6 "
                    "and np.newaxis is None"))))))

(harness/defshim-test numpy-ndarray-test "numpy"
  (testing "array infers shape + dtype and round-trips via tolist"
    (is (true? (ev session
                   (str "import numpy as np\n" "a = np.array([[1,2,3],[4,5,6]])\n"
                        "a.shape == (2,3) and a.ndim == 2 and a.size == 6 "
                        "and a.tolist() == [[1,2,3],[4,5,6]]")))))
  (testing "reshape / ravel / transpose"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.arange(6).reshape(2,3)\n"
                        "a.reshape(3,2).tolist() == [[0,1],[2,3],[4,5]] "
                        "and a.T.tolist() == [[0,3],[1,4],[2,5]] "
                        "and a.ravel().tolist() == [0,1,2,3,4,5]")))))
  (testing "broadcasting: matrix + row vector, and scalar ops"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.array([[1,2,3],[4,5,6]])\n"
                        "(a + np.array([10,20,30])).tolist() == [[11,22,33],[14,25,36]] "
                        "and (a * 2).tolist() == [[2,4,6],[8,10,12]] "
                        "and (2 ** np.array([1,2,3])).tolist() == [2,4,8]")))))
  (testing "indexing: int / tuple / slice / boolean mask"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.array([[1,2,3],[4,5,6]])\n"
                        "a[1,2] == 6 and a[0].tolist() == [1,2,3] "
                        "and a[:,1].tolist() == [2,5] "
                        "and a[a > 3].tolist() == [4,5,6]")))))
  (testing "setitem: scalar into a slice and via a boolean mask"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.array([[1,2,3],[4,5,6]])\n" "a[0] = 0\n"
                        "b = np.array([1,2,3,4]); b[b > 2] = 9\n"
                        "a.tolist() == [[0,0,0],[4,5,6]] and b.tolist() == [1,2,9,9]")))))
  (testing "rejects ragged nested sequences rather than creating malformed arrays"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "try:\n" "    np.array([[1,2],[3]])\n"
                        "    ok = False\n" "except ValueError:\n"
                        "    ok = True\n" "ok"))))))

(harness/defshim-test numpy-reductions-test "numpy"
  (testing "sum / mean along axes"
    (is
     (true? (ev session
                (str "import numpy as np\n"
                     "a = np.array([[1,2,3],[4,5,6]])\n"
                     "np.sum(a) == 21 and np.sum(a, axis=0).tolist() == [5,7,9] "
                     "and np.sum(a, axis=1).tolist() == [6,15] "
                     "and np.mean(a) == 3.5 and np.mean(a, axis=1).tolist() == [2.0,5.0]")))))
  (testing "any / all reduce along axes (incl. the .any(axis=) method form)"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.array([[1,0],[0,0]])\n"
                        "np.any(a) == True and a.any() == True "
                        "and np.any(a, axis=0).tolist() == [True, False] "
                        "and a.any(axis=1).tolist() == [True, False] "
                        "and np.all(a, axis=1).tolist() == [False, False] "
                        "and np.all(a, axis=0, keepdims=True).shape == (1,2)")))))
  (testing
   "any / all: axis + keepdims + negative axis + dtype + method/module parity"
    (is
     (true?
      (ev
       session
       (str
        "import numpy as np\n"
        "out = []\n" "f = np.array([[0,0],[0,0]]); t = np.array([[1,1],[1,1]])\n"
        "out += [np.any(f) == False, np.all(t) == True, f.any() == False, t.all() == True]\n"
        "b = np.array([[1,0],[0,0]]); c = np.array([[1,1],[1,0]])\n"
        "out += [b.any(axis=0).tolist() == [True,False], b.any(axis=1).tolist() == [True,False], np.any(b, axis=0).tolist() == [True,False]]\n"
        "out += [c.all(axis=0).tolist() == [True,False], c.all(axis=1).tolist() == [True,False], np.all(c, axis=1).tolist() == [True,False]]\n"
        "out += [b.any(axis=-1).tolist() == b.any(axis=1).tolist(), c.all(axis=-1).tolist() == c.all(axis=1).tolist()]\n"
        "out += [str(b.any(axis=0).dtype) == 'bool', str(c.all(axis=0).dtype) == 'bool']\n"
        "out += [b.any(axis=0, keepdims=True).shape == (1,2), c.all(axis=1, keepdims=True).shape == (2,1)]\n"
        "v = np.array([0,0,0]); w = np.array([1,2,3])\n"
        "out += [v.any() == False, v.all() == False, w.any() == True, w.all() == True]\n"
        "all(out)")))))
  (testing "min / max / argmax / std / var"
    (is (true? (ev session
                   (str
                    "import numpy as np\n" "a = np.array([1,2,3,4])\n"
                    "np.amin(a) == 1 and np.amax(a) == 4 and int(np.argmax(a)) == 3 "
                    "and abs(np.std(a) - 1.1180339887) < 1e-6 and np.var(a) == 1.25")))))
  (testing "cumsum / clip / sort / unique / where"
    (is (true?
         (ev session
             (str
              "import numpy as np\n" "np.cumsum(np.array([1,2,3])).tolist() == [1,3,6] "
              "and np.clip(np.array([-1,5,20]), 0, 10).tolist() == [0,5,10] "
              "and np.sort(np.array([3,1,2])).tolist() == [1,2,3] "
              "and np.unique(np.array([3,1,2,1,3])).tolist() == [1,2,3] "
              "and np.where(np.array([1,0,1]) > 0, 10, 20).tolist() == [10,20,10]"))))))

(harness/defshim-test numpy-linalg-test "numpy"
  (testing "dot / matmul for 1d and 2d"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "int(np.dot(np.array([1,2,3]), np.array([4,5,6]))) == 32 "
                        "and (np.array([[1,2],[3,4]]) @ np.array([[5,6],[7,8]])).tolist() "
                        "== [[19,22],[43,50]]")))))
  (testing "linalg det / inv round-trips to the identity"
    (is (true? (ev session
                   (str "import numpy as np\n" "A = np.array([[1.,2.],[3.,4.]])\n"
                        "abs(np.linalg.det(A) - (-2.0)) < 1e-9 "
                        "and np.allclose(np.linalg.inv(A) @ A, np.eye(2))")))))
  (testing "linalg solve recovers the known solution"
    (is (true? (ev session
                   (str
                    "import numpy as np\n"
                    "x = np.linalg.solve(np.array([[3.,1.],[1.,2.]]), np.array([9.,8.]))\n"
                    "np.allclose(x, np.array([2.,3.]))")))))
  (testing "linalg norm / trace / matrix_power"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "abs(np.linalg.norm(np.array([3.,4.])) - 5.0) < 1e-9 "
                        "and int(np.trace(np.array([[1,2],[3,4]]))) == 5 "
                        "and np.linalg.matrix_power(np.array([[1,1],[0,1]]), 3).tolist() "
                        "== [[1,3],[0,1]]"))))))

(harness/defshim-test numpy-ufunc-and-random-test "numpy"
  (testing "ufuncs: sqrt / exp / abs / floor"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "np.sqrt(np.array([4,9,16])).tolist() == [2.0,3.0,4.0] "
                        "and np.abs(np.array([-1,-2,3])).tolist() == [1,2,3] "
                        "and np.floor(np.array([1.7,2.2])).tolist() == [1.0,2.0]")))))
  (testing "creation helpers: zeros / ones / eye / full / linspace"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "np.zeros((2,2)).tolist() == [[0.0,0.0],[0.0,0.0]] "
                        "and np.ones(3).tolist() == [1.0,1.0,1.0] "
                        "and np.eye(2).tolist() == [[1.0,0.0],[0.0,1.0]] "
                        "and np.full((2,), 7).tolist() == [7,7] "
                        "and np.linspace(0,1,5).tolist() == [0.0,0.25,0.5,0.75,1.0]")))))
  (testing "random is seedable + bounded"
    (is
     (true? (ev session
                (str "import numpy as np\n"
                     "np.random.seed(0)\n" "r = np.random.rand()\n"
                     "0.0 <= r <= 1.0 and 0 <= np.random.randint(0, 5) < 5 "
                     "and np.random.rand(2,3).shape == (2,3)")))))
  (testing "RandomState / default_rng are reproducible independent generators"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.random.RandomState(42).rand(3).tolist()\n"
                        "b = np.random.RandomState(42).rand(3).tolist()\n"
                        "g = np.random.default_rng(7)\n" "a == b "
                        "and np.random.default_rng(7).integers(0,10,size=4).shape == (4,) "
                        "and g.standard_normal(3).size == 3"))))))

(harness/defshim-test numpy-keepdims-axis-and-manip-test "numpy"
  "Reductions gain keepdims + tuple axis; take / split / repeat-along-axis /
   histogram and in-place operators round-trip like real numpy."
  (testing "keepdims preserves reduced dims (incl. the softmax idiom)"
    (is
     (true? (ev session
                (str
                 "import numpy as np\n"
                 "a = np.array([[1,2],[3,4]])\n" "x = np.array([[1.0,2.0,3.0]])\n"
                 "e = np.exp(x - x.max(axis=1, keepdims=True))\n"
                 "a.sum(axis=1, keepdims=True).tolist() == [[3],[7]] "
                 "and a.sum(keepdims=True).shape == (1,1) "
                 "and abs(float((e / e.sum(axis=1, keepdims=True)).sum()) - 1.0) < 1e-9")))))
  (testing "tuple axis reduces over several axes"
    (is (true?
         (ev session
             (str
              "import numpy as np\n"
              "np.ones((2,3,4)).sum(axis=(0,1)).tolist() == [6,6,6,6] "
              "and np.ones((2,3,4)).sum(axis=(0,2), keepdims=True).shape == (1,3,1)")))))
  (testing "mean / std / var honor axis + keepdims"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "a = np.array([[1,2],[3,4]])\n"
                        "a.mean(axis=1, keepdims=True).tolist() == [[1.5],[3.5]] "
                        "and abs(a.std(axis=0)[0] - 1.0) < 1e-9 "
                        "and a.var(axis=1, keepdims=True).shape == (2,1)")))))
  (testing "take selects along a flat index or an axis"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "m = np.arange(6).reshape(2,3)\n"
                        "np.take(np.array([10,20,30]), [0,2]).tolist() == [10,30] "
                        "and np.take(m, [1,0], axis=0).tolist() == [[3,4,5],[0,1,2]] "
                        "and np.take(m, [2,0], axis=1).tolist() == [[2,0],[5,3]]")))))
  (testing
   "split / array_split partition along an axis"
    (is
     (true?
      (ev
       session
       (str
        "import numpy as np\n"
        "[x.tolist() for x in np.split(np.array([1,2,3,4]), 2)] == [[1,2],[3,4]] "
        "and [x.tolist() for x in np.split(np.array([1,2,3,4,5]), [2,4])] == [[1,2],[3,4],[5]] "
        "and [x.size for x in np.array_split(np.array([1,2,3,4,5]), 3)] == [2,2,1] "
        "and [x.tolist() for x in np.split(np.arange(6).reshape(2,3), 3, axis=1)]"
        " == [[[0],[3]],[[1],[4]],[[2],[5]]]")))))
  (testing
   "repeat works along an axis"
    (is
     (true?
      (ev
       session
       (str
        "import numpy as np\n"
        "np.repeat(np.array([[1,2]]), 2, axis=0).tolist() == [[1,2],[1,2]] "
        "and np.repeat(np.array([[1,2],[3,4]]), 2, axis=1).tolist() == [[1,1,2,2],[3,3,4,4]]")))))
  (testing "histogram bins counts and edges"
    (is
     (true? (ev session
                (str "import numpy as np\n"
                     "c, edg = np.histogram(np.array([1,2,1,3,3,3]), bins=3)\n"
                     "c2, _ = np.histogram(np.array([0.5,1.5,2.5]), bins=3, range=(0,3))\n"
                     "c.tolist() == [2,1,3] and len(edg) == 4 and c2.tolist() == [1,1,1]")))))
  (testing "in-place operators rebind element-wise"
    (is (true? (ev session
                   (str "import numpy as np\n" "a = np.array([1,2,3]); a += 10\n"
                        "b = np.array([1,2,3]); b *= np.array([2,2,2])\n"
                        "a.tolist() == [11,12,13] and b.tolist() == [2,4,6]"))))))

(harness/defshim-test numpy-dstack-test "numpy"
  "dstack / atleast_3d stack along the depth axis like numpy: 1-D inputs become
   (1, N, k), 2-D inputs (M, N, k) and 3-D inputs concatenate on axis 2."
  (testing "stacks 1-D arrays into a (1, N, k) block"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "b = np.dstack([np.array([1,2,3]), np.array([4,5,6])])\n"
                        "b.shape == (1,3,2) and b.tolist() == [[[1,4],[2,5],[3,6]]]")))))
  (testing "stacks 2-D arrays into channels"
    (is (true?
         (ev session
             (str "import numpy as np\n"
                  "a = np.dstack([np.zeros((2,3)), np.ones((2,3)), np.full((2,3),2.0)])\n"
                  "a.shape == (2,3,3) and a[1,2].tolist() == [0.0,1.0,2.0]")))))
  (testing "concatenates 3-D inputs on the depth axis and lifts scalars"
    (is (true? (ev session
                   (str
                    "import numpy as np\n"
                    "a = np.dstack([np.zeros((2,3)), np.ones((2,3))])\n"
                    "np.dstack([a, a]).shape == (2,3,4) "
                    "and np.dstack([1.0, 2.0]).shape == (1,1,2)")))))
  (testing "atleast_3d promotes to three dimensions"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "np.atleast_3d(np.array([1,2])).shape == (1,2,1) "
                        "and np.atleast_3d(np.zeros((2,3))).shape == (2,3,1)"))))))

(harness/defshim-test numpy-package-submodule-test "numpy"
  "The compatibility package keeps common NumPy import paths usable."
  (testing "imports and executes fft/polynomial/masked/testing/typing subsets"
    (is (= [[0.0 0.25 -0.5 -0.25] 7 [1 9] true]
           (ev session
               (str "from numpy.fft import fftfreq\n"
                    "from numpy.polynomial import Polynomial\n"
                    "from numpy.ma import array as masked_array\n"
                    "from numpy.testing import assert_allclose\n"
                    "from numpy.typing import NDArray\n"
                    "assert_allclose([1.0], [1.0 + 1e-8])\n"
                    "[fftfreq(4).tolist(), Polynomial([1,2])(3), "
                    "masked_array([1,2], mask=[False,True]).filled(9).tolist(), "
                    "NDArray is not None]"))))))

(harness/defshim-test numpy-ma-alias-regression-test "numpy"
  (testing
   "keeps standard numpy.ma aliases and mask inspection usable"
    (is
     (=
      [[1 9] [false true] [1 2] true]
      (ev
       session
       (str
        "from numpy.ma import masked_array, getmaskarray, getdata, isMaskedArray\n"
        "a = masked_array([1,2], mask=[False,True])\n"
        "[a.filled(9).tolist(), getmaskarray(a).tolist(), getdata(a).tolist(), isMaskedArray(a)]"))))))

;; Regression, report 49f413b1 (the dark-theme logo session): working on an
;; (h,w,3) image, `arr[mask]` with an (h,w) mask returned one channel per pixel
;; instead of whole pixels (`.tolist()` then raised "'int' object is not
;; iterable"), `np.roll(a, 1, 0)` raised "roll() takes 2 positional arguments"
;; and `np.broadcast_to` did not exist at all.
(harness/defshim-test numpy-image-indexing-regression-test "numpy"
  (testing "a mask over the leading axes selects whole sub-arrays, not single values"
      ;; The Clojure bridge narrows whole floats to integers, so the float64
        ;; dtype is asserted inside Python and the values compare as integers.
    (is (= [[[0 1 2] [9 10 11]] [[[0.0 0.0 0.0] [3.0 4.0 5.0]] [[6.0 7.0 8.0] [0.0 0.0 0.0]]]
            [[[1.0 2.0 3.0] [3.0 4.0 5.0]] [[6.0 7.0 8.0] [1.0 2.0 3.0]]] [4 5 6] "float64"]
           (ev session
               (str "import numpy as np\n" "a = np.arange(12).reshape(2,2,3)\n"
                    "m = np.array([[True,False],[False,True]])\n"
                    "b = a.astype('float64'); b[m] = 0.0\n"
                    "c = a.astype('float64'); c[m] = np.array([1.0,2.0,3.0])\n"
                    "f = np.array([[1,2,3],[4,5,6]])\n"
                    "[a[m].tolist(), b.tolist(), c.tolist(), f[f > 3].tolist(), "
                    "str(c.dtype)]")))))
  (testing "a mask that does not match the leading axes is an IndexError"
    (is (true? (ev session
                   (str "import numpy as np\n"
                        "try:\n"
                        "    np.arange(12).reshape(2,2,3)[np.array([True,False,True])]\n"
                        "    ok = False\n" "except IndexError:\n"
                        "    ok = True\n" "ok")))))
  (testing "roll takes an axis (and a tuple of axes), flat roll unchanged"
    (is (= [[[4 5 6] [1 2 3]] [[2 3 1] [5 6 4]] [[6 4 5] [3 1 2]] [[6 1 2] [3 4 5]]]
           (ev session
               (str "import numpy as np\n" "a = np.array([[1,2,3],[4,5,6]])\n"
                    "[np.roll(a,1,0).tolist(), np.roll(a,-1,1).tolist(), "
                    "np.roll(a,(1,1),(0,1)).tolist(), np.roll(a,1).tolist()]")))))
  (testing "broadcast_to stretches size-1 axes and rejects impossible shapes"
    (is (= [[[1.0 2.0 3.0] [1.0 2.0 3.0]] [[1 1 1] [2 2 2]] [[5 5] [5 5]] true]
           (ev session
               (str "import numpy as np\n"
                    "try:\n" "    np.broadcast_to(np.array([1.0,2.0,3.0]), (3,2))\n"
                    "    raised = False\n" "except ValueError:\n"
                    "    raised = True\n"
                    "[np.broadcast_to(np.array([1.0,2.0,3.0]), (2,3)).tolist(), "
                    "np.broadcast_to(np.array([[1],[2]]), (2,3)).tolist(), "
                    "np.broadcast_to(5, (2,2)).tolist(), raised]"))))))
