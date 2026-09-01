(ns com.blockether.vis-python-runtime.fd-test
  "Descriptor discipline for the sandbox `open` (`resources/vis-python/async_runtime.py`).

   The interpreter the sandbox embeds does not free a handle the block DROPS
   before the block ends, and GraalPy — where this was written — never freed one
   at all: the process file descriptor stayed open forever. A loop like
   `open(p).read()` over a big tree therefore walks the whole JVM into EMFILE,
   and the first casualty is not Python: `ProcessBuilder` can no longer fork, so
   every later `shell` call dies with the JDK's misleading \"spawn helper / JDK
   version mismatch\" text and the session is wedged for good.

   So the sandbox does the reclamation by hand: every handle is registered under
   its descriptor with a WEAK ref, and once that ref is dead the descriptor is
   closed. `__vis_fd_limits__` is [ceiling, sweep-at] — PROCESS state, because
   one interpreter serves every session — and reaching the ceiling with handles
   genuinely held open raises an ordinary `OSError(EMFILE)` naming the fix, in
   the block that caused it.

   Ported from Vis. The sqlite3 case moves with the sqlite3 shim (Wave 3) and
   the socket cases with the shims that open connections."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime :as runtime]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block printed]])
  (:import [com.sun.management UnixOperatingSystemMXBean]
           [java.lang.management ManagementFactory OperatingSystemMXBean]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- open-fd-count
  "Descriptors THIS process holds, straight from the JVM. A leak through a door
   that BYPASSES the shim is invisible to the registry by definition, so the
   process's own count is the only honest measure of such a door."
  ^long []
  (let [^OperatingSystemMXBean bean (ManagementFactory/getOperatingSystemMXBean)]
    (if (instance? UnixOperatingSystemMXBean bean)
      (.getOpenFileDescriptorCount ^UnixOperatingSystemMXBean bean)
      -1)))

(defn- temp-root
  ^String []
  (str (.toAbsolutePath (Files/createTempDirectory "vis-fd" (make-array FileAttribute 0)))))

(defn- sandbox
  "A session over a temp directory holding `F` (a file of `probe\n`) and `W` (a
   path to write), with the ceiling lowered to 8 so the contract is provable in
   tens of opens instead of thousands."
  []
  (let [root (temp-root)
        f (str root "/probe.txt")
        s (harness/block-session)]
    (spit f "probe\n")
    (block s (str "F = " (pr-str f) "\n"
                  "W = " (pr-str (str root "/written.txt")) "\n"
                  "__vis_fd_limits__[:] = [8, 4]\n"))
    s))

(defn- restore-limits!
  "Put the ceiling back. It is PROCESS state, so a test that lowers it to prove
   the ceiling must not hand that number to whatever runs next."
  []
  (when harness/built?
    (block (harness/block-session) "__vis_fd_limits__[:] = __vis_fd_default_limits__()")))

(use-fixtures :each
  (fn [run]
    (try (run)
         (finally
           ;; Descriptor state is the PROCESS's here, not a context's: the
           ;; sessions this test opened still hold every handle their blocks
           ;; left, and the ceiling it lowered is the one the next test would
           ;; run under.
           (harness/close-sessions!)
           (restore-limits!)
           (harness/close-sessions!)))))

(harness/defbuilt-test fd-reclamation-test
  (testing "reclaims the descriptors a block dropped, so a leaking loop cannot exhaust the process"
    ;; The exact shape that wedged a live session: a bare `open(...)` per
    ;; iteration, handle never closed. 40 leaked opens against a ceiling of 8
    ;; only survive if dropped descriptors are actually being reclaimed.
    (let [r (block (sandbox)
                   (str "for _ in range(40):\n" "    h = open(F)\n"
                        "    del h\n" "print(json.dumps(len(__vis_fd_registry__)))"))]
      (is (nil? (:error r)))
      (is (>= 8 (printed r)))))
  (testing "reclaims across blocks, so one leaking block cannot poison the next"
    (let [s (sandbox)]
      (dotimes [_ 6] (block s "h = open(F)\ndel h"))
      (let [r (block s "print(json.dumps(len(__vis_fd_registry__)))")]
        (is (nil? (:error r)))
        (is (>= 8 (printed r))))))
  (testing "never refuses honest code that closes what it opens"
    ;; `with` returns every descriptor immediately: 64 opens against a ceiling
    ;; of 8 must be entirely unremarkable.
    (let [r (block (sandbox)
                   (str "n = 0\n"
                        "for _ in range(64):\n" "    with open(F) as fh:\n"
                        "        n += len(fh.read())\n" "print(json.dumps(n))"))]
      (is (nil? (:error r)))
      (is (= 384 (printed r)))))
  (testing "still flushes a dropped writable handle, so what a block wrote is on disk"
    ;; Reclamation must not cost the write-flush guarantee: the block-end flush
    ;; runs BEFORE the sweep.
    (let [s (sandbox)]
      (block s "open(W, 'w').write('hello')")
      (let [r (block s "print(json.dumps(open(W).read()))")]
        (is (nil? (:error r)))
        (is (= "hello" (printed r)))))))

(harness/defbuilt-test fd-ceiling-test
  (testing "refuses to cross the ceiling when the handles are genuinely held open"
    ;; Nothing is reclaimable here — the list holds every handle — so this is
    ;; the one case that must fail, and it must fail HERE rather than by
    ;; breaking process spawning somewhere else later.
    (let [r (block (sandbox) "kept = [open(F) for _ in range(64)]")
          m (str (:error r))]
      (is (some? (:error r)))
      ;; The message has to name the cause, the fix, and the escape hatch: the
      ;; JDK's spawn-helper text taught us what a misdiagnosis costs.
      (is (str/includes? m "too many open files"))
      (is (str/includes? m "with open("))
      (is (str/includes? m "VIS_PY_MAX_OPEN_FILES"))))
  (testing "keeps the ceiling reachable from the block that hit it"
    ;; A refused `open` is an ordinary catchable OSError, not a killed block.
    (let [r (block (sandbox)
                   (str "import errno\n"
                        "kept = []\n" "code = None\n"
                        "try:\n" "    for _ in range(64):\n"
                        "        kept.append(open(F))\n" "except OSError as e:\n"
                        "    code = e.errno\n"
                        "print(json.dumps(code == errno.EMFILE))"))]
      (is (nil? (:error r)))
      (is (true? (printed r)))))
  (testing "ships a default ceiling well under any process limit, sweeping at half"
    (let [s (harness/block-session)
          r (block s (str "__vis_fd_limits__[:] = __vis_fd_default_limits__()\n"
                          "print(json.dumps(__vis_fd_limits__))"))]
      (is (nil? (:error r)))
      (is (= [512 256] (printed r))))))

(harness/defbuilt-test fd-hardening-test
  (testing "reclaims descriptors opened through every door onto the filesystem"
    ;; Shimming only this module's global `open` left three doors wide open:
    ;; `io.open` (a DIFFERENT object from `builtins.open` here), `pathlib.Path.open`
    ;; / `tempfile` (both call `io.open`), and `builtins.open` reached through any
    ;; other module's globals. Each leaked one descriptor per call, and none of it
    ;; showed up in the registry — an untracked handle is invisible there by
    ;; definition — so the process's own count is what has to be watched. The
    ;; block returning at all also proves no door leads back INTO the shim; a
    ;; self-call would be a RecursionError on the very first `open`.
    (let [s (sandbox)
          before (open-fd-count)
          r (block s (str "import builtins, io, pathlib\n"
                          "for _ in range(12):\n" "    h = pathlib.Path(F).open()\n"
                          "    h = io.open(F)\n" "    h = builtins.open(F)\n"
                          "    del h\n" "print(json.dumps(len(__vis_fd_registry__)))"))
          grown (- (open-fd-count) before)]
      (is (nil? (:error r)))
      (is (>= 8 (printed r)))
      (is (> 12 grown))))
  (testing "tracks the layer that owns the descriptor, not the wrapper around it"
    ;; `open()` hands back a STACK (TextIOWrapper -> BufferedReader -> FileIO) and
    ;; the descriptor belongs to the BOTTOM of it: `os.close` on that fd through
    ;; the top layer is a hard EBADF (measured). An entry pointing at the top
    ;; layer therefore has the sandbox closing a descriptor whose real owner is
    ;; still open — on CPython, one the interpreter itself is about to reclaim.
    ;; The identity check IS the contract; the read is the consequence.
    (let [r (block (sandbox)
                   (str "h = open(F, 'rb')\n" "raw = h.raw\n"
                        "fd = h.fileno()\n"
                        "owned = __vis_fd_registry__[fd][0]() is raw\n"
                        "text = raw.read().decode()\n" "h.close()\n"
                        "print(json.dumps([owned, text]))"))]
      (is (nil? (:error r)))
      (is (= [true "probe\n"] (printed r)))))
  (testing "leaves a borrowed descriptor to its owner"
    ;; `closefd=False` means the wrapper only BORROWED an fd the block opened
    ;; itself. Tracking it closes a descriptor the block still owns and still
    ;; reads through — EBADF from code that did nothing wrong.
    (let [r (block (sandbox)
                   (str "import gc, os\n" "fd = os.open(F, os.O_RDONLY)\n"
                        "h = open(fd, 'rb', closefd=False)\n"
                        "tracked = fd in __vis_fd_registry__\n"
                        "del h\n" "gc.collect()\n"
                        "__vis_reclaim_fds__(True)\n" "out = os.read(fd, 5).decode()\n"
                        "os.close(fd)\n" "print(json.dumps([tracked, out]))"))]
      (is (nil? (:error r)))
      (is (= [false "probe"] (printed r)))))
  (testing "keeps its state and its real opener across a runtime reinstall"
    ;; `globals().clear()` is legal Python, and the session that ran it arrives
    ;; at the next block with no runtime at all. Reinstalling must not hand it a
    ;; brand-new registry — every descriptor it is still holding would be
    ;; forgotten — and must not re-capture the opener now that `builtins.open`
    ;; IS the shim: that is a RecursionError on the very next `open`.
    (let [s (sandbox)
          f (printed (block s "print(json.dumps(F))"))
          _ (block s "for _ in range(6):\n    h = open(F)\n    del h")
          before (block s "print(json.dumps(id(__vis_fd_registry__)))")
          _ (block s "globals().clear()")
          r (block s (str "print(json.dumps(open(" (pr-str f) ").read()))"))
          after (block s "print(json.dumps(id(__vis_fd_registry__)))")]
      (is (nil? (:error r)))
      (is (= "probe\n" (printed r)))
      (is (nil? (:error after)))
      (is (= (printed before) (printed after)))))
  (testing "reclaims the raw doors, which never pass through any `open`"
    ;; `io.FileIO(p)` IS the descriptor-owning object and `io.open_code(p)` hands
    ;; one back: neither goes through `open`, so both leaked one descriptor per
    ;; call while only the `open` doors were shimmed (measured, 25 per 25).
    ;; `io.FileIO` is an immutable type, so the shim is a subclass — this is what
    ;; proves the subclass is really the one being constructed.
    (let [s (sandbox)
          before (open-fd-count)
          r (block s (str "import io\n"
                          "h = io.FileIO(F)\n" "c = io.open_code(F)\n"
                          "seen = [h.fileno() in __vis_fd_registry__,\n"
                          "        c.fileno() in __vis_fd_registry__]\n"
                          "h.close()\n" "c.close()\n"
                          "for _ in range(12):\n" "    g = io.FileIO(F)\n"
                          "    del g\n"
                          "print(json.dumps(seen + [len(__vis_fd_registry__) <= 8]))"))
          grown (- (open-fd-count) before)]
      (is (nil? (:error r)))
      (is (= [true true true] (printed r)))
      (is (> 12 grown))))
  (testing "keeps `isinstance` honest after taking over `io.FileIO`"
    ;; The shim is a SUBCLASS, so the raw built INSIDE `open` is not one of its
    ;; instances. Its metaclass forwards the question to the real class, or every
    ;; library asking `isinstance(f.raw, io.FileIO)` would start answering False
    ;; the moment the sandbox loaded.
    (let [r (block (sandbox)
                   (str "import io, pathlib\n" "raw = open(F, 'rb', buffering=0)\n"
                        "out = [isinstance(raw, io.FileIO),\n"
                        "       issubclass(io.FileIO, io.RawIOBase),\n"
                        "       isinstance(io.FileIO(F), io.FileIO),\n"
                        "       pathlib.Path(F).read_text()]\n"
                        "raw.close()\n" "print(json.dumps(out))"))]
      (is (nil? (:error r)))
      (is (= [true true true "probe\n"] (printed r)))))
  (testing "leaves a descriptor borrowed through `io.FileIO` to its owner"
    ;; Same contract as `open(fd, closefd=False)`, at the raw door: the block
    ;; opened that fd itself and still reads through it after the wrapper dies.
    (let [r (block (sandbox)
                   (str "import gc, io, os\n"
                        "fd = os.open(F, os.O_RDONLY)\n" "__vis_fd_registry__.pop(fd, None)\n"
                        "h = io.FileIO(fd, 'r', False)\n" "tracked = fd in __vis_fd_registry__\n"
                        "del h\n" "gc.collect()\n"
                        "__vis_reclaim_fds__(True)\n" "out = os.read(fd, 5).decode()\n"
                        "os.close(fd)\n" "print(json.dumps([tracked, out]))"))]
      (is (nil? (:error r)))
      (is (= [false "probe"] (printed r))))))

;; Regression: `close_session` used to CLEAR the closing session's namespace, and
;; a session installs doors the whole PROCESS then uses — `builtins.open`, `io.open`,
;; the socket guard. The door left installed was a function whose globals had been
;; emptied, so the next block in ANY other session died with
;; `NameError: name '__vis_open_writes__' is not defined`.
(harness/defbuilt-test doors-outlive-a-closed-session-test
  (let [earlier (sandbox)
        later (sandbox)]
    (is (nil? (:error (block later "print(1)")))
        "the later session installed the doors this process now holds")
    (runtime/close-session! later)
    (let [r (block earlier (str "import socket\n"
                                "s = socket.socket()\n"
                                "s.close()\n"
                                "with open(F) as h:\n"
                                "    text = h.read()\n"
                                "print(json.dumps(text))"))]
      (is (nil? (:error r)))
      (is (= "probe\n" (printed r))))))
