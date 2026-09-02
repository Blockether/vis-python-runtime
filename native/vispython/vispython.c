/*
 * vis-python: the whole native boundary between the JVM and the embedded
 * CPython interpreter. Everything the JVM may call is declared here, is
 * `extern "C"`, and speaks only integers and NUL-terminated UTF-8 bytes, never a PyObject.
 *
 * Two reasons for the narrow surface. Every function the JVM downcalls has to
 * be registered for GraalVM native-image, so a bridge that exposed CPython's
 * several-thousand-symbol C-API would be unshippable. And a PyObject crossing
 * the boundary would put reference counting on the JVM side, which is exactly
 * the bookkeeping this project exists to delete.
 *
 * Strings out are UTF-8, NUL-terminated, copied into a caller-owned buffer that
 * the caller sized: no allocation crosses the boundary, so nothing here can
 * leak into a long-lived agent session. Every entry point returns the number of
 * bytes written, or a negative VIS_PY_ERR_*; on error the buffer holds the
 * human-readable reason, so one call yields both the verdict and the message.
 */
#include <Python.h>
#include <errno.h>
#include <fcntl.h>
#if defined(__linux__)
#include <dlfcn.h>
#endif
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define VIS_PY_ERR_BUFFER (-1) /* caller passed no room for a result */
#define VIS_PY_ERR_INIT   (-2) /* interpreter is not running */
#define VIS_PY_ERR_PYTHON (-3) /* Python raised; buffer holds str(exception) */

#if defined(__linux__)
#define VIS_PY_STRINGIFY_INNER(value) #value
#define VIS_PY_STRINGIFY(value) VIS_PY_STRINGIFY_INNER(value)
#define VIS_PY_LIBPYTHON "libpython" VIS_PY_STRINGIFY(PY_MAJOR_VERSION) "."                          VIS_PY_STRINGIFY(PY_MINOR_VERSION) ".so.1.0"
static void *vis_py_global_libpython = NULL;
#endif
static int vis_py_started = 0;
static int vis_py_inittab = 0;

/* When an answer does not fit, the WHOLE text is kept here for one follow-up
   fetch, per thread. Asking the caller to retry with a bigger buffer is what a
   snprintf-shaped API would do, but a retry would run the block a SECOND time,
   and a block is not idempotent - so the text waits instead of the work. */
static pthread_key_t vis_py_stash_key;
static pthread_once_t vis_py_stash_once = PTHREAD_ONCE_INIT;

static void vis_py_stash_free(void *text)
{
    free(text);
}

static void vis_py_stash_key_make(void)
{
    pthread_key_create(&vis_py_stash_key, vis_py_stash_free);
}

/* Hand the stash a copy of `s`, or clear it when `s` is NULL. A stash that
   outlived its fetch would answer some later, unrelated call. */
static void vis_py_stash(const char *s)
{
    char *kept;
    pthread_once(&vis_py_stash_once, vis_py_stash_key_make);
    kept = pthread_getspecific(vis_py_stash_key);
    free(kept);
    pthread_setspecific(vis_py_stash_key, s == NULL ? NULL : strdup(s));
}

/* Copy a NUL-terminated UTF-8 string into the caller's buffer. Returns the
   FULL byte count of the string, not counting the terminator - so a return
   value of `cap` or more says the buffer holds a truncated prefix and the
   whole text is waiting in `vispython_take_result`. */
static int vis_py_copy_out(const char *s, char *out, int cap)
{
    int n;
    if (out == NULL || cap <= 0) {
        return VIS_PY_ERR_BUFFER;
    }
    n = (int)strlen(s);
    if (n > cap - 1) {
        vis_py_stash(s);
        memcpy(out, s, (size_t)(cap - 1));
        out[cap - 1] = '\0';
        return n;
    }
    vis_py_stash(NULL);
    memcpy(out, s, (size_t)n);
    out[n] = '\0';
    return n;
}

/* Drain the raised exception into the caller's buffer. Always clears it: a
   pending exception left behind poisons the next unrelated call.

   The text is `TYPE: message`, the shape a traceback's last line has, because
   the TYPE is what a host classifies on — a `SyntaxError` has to be tellable
   from a `ValueError` once the message is a plain string. */
static int vis_py_take_error(char *out, int cap)
{
    PyObject *exc = PyErr_GetRaisedException();
    PyObject *text = NULL;
    PyObject *named = NULL;
    const char *utf8 = NULL;
    int written;

    if (exc == NULL) {
        return vis_py_copy_out("Python failed without raising", out, cap);
    }
    text = PyObject_Str(exc);
    if (text != NULL && PyUnicode_GET_LENGTH(text) == 0) {
        /* `assert x` and friends raise with no message at all; the TYPE is
           then the only thing the caller can act on, so send that alone. */
        Py_DECREF(text);
        text = PyUnicode_FromString(Py_TYPE(exc)->tp_name);
    } else if (text != NULL) {
        named = PyUnicode_FromFormat("%s: %U", Py_TYPE(exc)->tp_name, text);
        if (named != NULL) {
            Py_DECREF(text);
            text = named;
        }
    }
    utf8 = (text == NULL) ? NULL : PyUnicode_AsUTF8(text);
    written = vis_py_copy_out(utf8 == NULL ? "unprintable Python exception" : utf8, out, cap);
    Py_XDECREF(text);
    Py_DECREF(exc);
    PyErr_Clear();
    return written;
}

/* --------------------------------------------------------------------------
 * Diagnostics.
 *
 * The runtime RECORDS events; it never writes a log. It is linked into a host
 * that already has a file, a rotation and a format for lines like these, so a
 * library opening its own would be deciding all three for somebody else. The
 * host PULLS with `vispython_drain_log` and files what it gets wherever its
 * own diagnostics already go.
 *
 * Pulled, never pushed, for the reason the "Guest threads" section gives: an
 * event is recorded from a pool worker, and calling out to the host from there
 * - through one pinned JVM thread, possibly under a pool lock - is the very
 * inversion that section exists to avoid. Recording costs one small mutex of
 * its own, a `vsnprintf` into a fixed slot, and nothing else: no allocation,
 * no GIL, no upcall. That mutex is a LEAF, nothing is taken while it is held,
 * so recording under any other lock stays safe.
 *
 * The ring is bounded and overwrites its OLDEST record when nobody drains,
 * because a diagnostic buffer that grows without a reader is a leak with a good
 * excuse. 1024 records of 256 bytes is a quarter of a megabyte that never grows,
 * and enough that a host draining on its own schedule loses nothing; the host
 * that owns this one drains continuously (`Interpreter.drainTo`), off the
 * interpreter's thread, since a block may hold that thread for minutes and its
 * records are exactly the ones worth having. Drops are counted and reported at
 * the head of the next drain, so a gap in the log names itself instead of lying
 * by omission.
 *
 * NEVER a guest value. An event carries counts, durations and names the HOST
 * chose; a payload, a thunk's argument, a path a block asked for and the text
 * of an exception belong to the block. This log ends up in a file people paste
 * into bug reports.
 * ------------------------------------------------------------------------ */

#define VIS_PY_LOG_SLOTS 1024
#define VIS_PY_LOG_LINE 256

#define VIS_PY_LOG_OFF 0
#define VIS_PY_LOG_WARN 1
#define VIS_PY_LOG_INFO 2
#define VIS_PY_LOG_DEBUG 3

static const char *const vis_py_log_levels[] = {"off", "warn", "info", "debug"};

static struct {
    pthread_mutex_t lock;
    char slot[VIS_PY_LOG_SLOTS][VIS_PY_LOG_LINE];
    int head;     /* the oldest record waiting */
    int count;    /* records waiting to be drained */
    int level;    /* the quietest level still recorded; OFF records nothing */
    int mirror;   /* write each record to stderr as well */
    long dropped; /* records overwritten since a drain last said so */
} vis_py_log = {PTHREAD_MUTEX_INITIALIZER, {{0}}, 0, 0, VIS_PY_LOG_OFF, 0, 0};

/* Milliseconds on a clock that only moves forward: for MEASURING, never for
   stamping, because the wall clock may step under a long call. */
static long long vis_py_now_ms(void)
{
    struct timespec now;

    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* The name a session is recorded under. Host-chosen, never guest text. */
static const char *vis_py_session_name(const char *module_name)
{
    return (module_name == NULL || module_name[0] == '\0') ? "__main__" : module_name;
}

/* Record one event as a JSON object; `fields` renders the pairs after the
   event name and may be NULL. The level is read without the lock on purpose: a
   racing policy change costs one record, and paying a mutex for every event
   the level would discard is the wrong trade. */
static void vis_py_record(int level, const char *event, const char *fields, ...)
{
    char body[VIS_PY_LOG_LINE];
    struct timespec now;
    va_list args;
    int slot;

    if (level > vis_py_log.level) {
        return;
    }
    body[0] = '\0';
    if (fields != NULL) {
        va_start(args, fields);
        vsnprintf(body, sizeof body, fields, args);
        va_end(args);
    }
    clock_gettime(CLOCK_REALTIME, &now);
    pthread_mutex_lock(&vis_py_log.lock);
    slot = (vis_py_log.head + vis_py_log.count) % VIS_PY_LOG_SLOTS;
    if (vis_py_log.count == VIS_PY_LOG_SLOTS) {
        vis_py_log.head = (vis_py_log.head + 1) % VIS_PY_LOG_SLOTS;
        vis_py_log.dropped++;
    } else {
        vis_py_log.count++;
    }
    snprintf(vis_py_log.slot[slot], VIS_PY_LOG_LINE,
             "{\"ts\":%lld,\"level\":\"%s\",\"event\":\"%s\"%s%s}",
             (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000, vis_py_log_levels[level],
             event, body[0] == '\0' ? "" : ",", body);
    if (vis_py_log.mirror) {
        fprintf(stderr, "%s\n", vis_py_log.slot[slot]);
    }
    pthread_mutex_unlock(&vis_py_log.lock);
}
/* --------------------------------------------------------------------------
 * Guest threads.
 *
 * Concurrency in the sandbox is threads, not an event loop: `gather` hands the
 * runtime zero-argument thunks, and the ones that OVERLAP are the ones that
 * release the GIL - a host call, a socket, a subprocess, an extension doing
 * its work in C. Bytecode never overlaps, and no pool anywhere changes that.
 *
 * The pool is HERE, in C, for the one thing Python cannot do to itself: a
 * bound the guest is unable to raise. A pool in `vis_runtime` is a module
 * global a block can resize, rebind or walk straight past with
 * `threading.Thread`, while a budget checked from the audit hook counts EVERY
 * thread the interpreter has, whoever started it, and refuses the one that
 * would cross the line.
 *
 * Three numbers, all the host's through `vispython_threads`:
 *   cap     - live threads the process may have at once, guest and pool alike,
 *             shared by every session, because sessions share the interpreter.
 *             -1 is NO cap, for the process the EXTENSIONS run in: that one is
 *             trusted and unconfined, so a budget it must never reach would
 *             only cost a walk of the thread list on every start.
 *   workers - threads the pool runs. The default is how a pool for BLOCKING
 *             work is sized: 32, four wide gathers at once. These threads
 *             spend their lives waiting on the host rather than competing
 *             for a core, so counting cores would bound the wrong thing.
 *   quota   - tasks ONE `par` call may have running at once, so one session's
 *             wide `gather` cannot take the pool away from the others.
 * ------------------------------------------------------------------------ */

#define VIS_PY_WORKERS_MAX 64
#define VIS_PY_WORKERS 32
#define VIS_PY_CALLER_RUNS_MS 50
#define VIS_PY_THREAD_CAP 100
#define VIS_PY_PAR_QUOTA 8

/* The events CPython raises when a thread starts: `threading.Thread.start()`
   and a bare `_thread.start_new_thread` alike. */
static const char *const vis_py_thread_events[] = {
    "_thread.start_new_thread", "_thread.start_joinable_thread", NULL};

static int vis_py_thread_cap = VIS_PY_THREAD_CAP;
static int vis_py_worker_setting = 0; /* 0 = size from the machine */
static int vis_py_par_quota = VIS_PY_PAR_QUOTA;

/* One `par` call's work. A failing thunk makes `par` raise AT ONCE, while its
   siblings are still running - that is what the sandbox's `gather` promises,
   and what lets it cancel them - so a batch OUTLIVES the call that made it and
   whoever finishes last frees it. */
typedef struct vis_py_batch vis_py_batch;

typedef struct vis_py_task {
    PyObject *thunk;
    PyObject *value;
    PyObject *error;
    vis_py_batch *batch;
    int finished;
    struct vis_py_task *next;
} vis_py_task;

struct vis_py_batch {
    PyObject *thunks; /* the sequence every thunk is borrowed from */
    vis_py_task *task;
    int count;
    int done;        /* tasks finished */
    int outstanding; /* tasks queued or running, still touching this batch */
    int abandoned;   /* `par` has returned; the last one out frees */
};

/* Drop everything a batch still owns. Needs the GIL: what a thunk answered
   after nobody was left to want it is a Python object like any other. */
static void vis_py_batch_free(vis_py_batch *batch)
{
    int i;

    for (i = 0; i < batch->count; i++) {
        Py_XDECREF(batch->task[i].value);
        Py_XDECREF(batch->task[i].error);
    }
    Py_XDECREF(batch->thunks);
    free(batch->task);
    free(batch);
}

static struct {
    pthread_mutex_t lock;
    pthread_cond_t work;     /* a task was queued */
    pthread_cond_t finished; /* a task finished */
    pthread_t worker[VIS_PY_WORKERS_MAX];
    vis_py_task *head;
    vis_py_task *tail;
    int running; /* workers created */
    int idle;    /* workers waiting for a task, holding no thread state */
    int started;
    int queued; /* tasks waiting for a worker to claim them */
} vis_py_pool = {PTHREAD_MUTEX_INITIALIZER,
                 PTHREAD_COND_INITIALIZER,
                 PTHREAD_COND_INITIALIZER,
                 {0},
                 NULL,
                 NULL,
                 0,
                 0,
                 0,
                 0};

static pthread_key_t vis_py_worker_key;
static pthread_once_t vis_py_worker_once = PTHREAD_ONCE_INIT;

static void vis_py_worker_key_make(void)
{
    pthread_key_create(&vis_py_worker_key, NULL);
}

/* Whether THIS thread is a pool worker. A `gather` inside a `gather` child runs
   sequentially: the outer children hold the pool, so submitting to it from one
   of them is exactly how a bounded pool deadlocks. */
static int vis_py_in_worker(void)
{
    pthread_once(&vis_py_worker_once, vis_py_worker_key_make);
    return pthread_getspecific(vis_py_worker_key) != NULL;
}

/* How many workers the pool should run, never more than the cap allows. */
static int vis_py_worker_target(void)
{
    int workers = vis_py_worker_setting;

    if (workers <= 0) {
        workers = VIS_PY_WORKERS;
    }
    if (workers > VIS_PY_WORKERS_MAX) {
        workers = VIS_PY_WORKERS_MAX;
    }
    if (vis_py_thread_cap > 0 && workers > vis_py_thread_cap) {
        workers = vis_py_thread_cap;
    }
    return workers < 1 ? 1 : workers;
}

/* Threads alive in this interpreter. A thread the guest started holds a thread
   state for its whole life and so does a worker RUNNING a task; a worker
   waiting for work holds none, so it is counted by hand. Needs the GIL. */
static int vis_py_live_threads(void)
{
    PyThreadState *state;
    int live = 0;
    int idle;

    for (state = PyInterpreterState_ThreadHead(PyInterpreterState_Get()); state != NULL;
         state = PyThreadState_Next(state)) {
        live++;
    }
    pthread_mutex_lock(&vis_py_pool.lock);
    idle = vis_py_pool.idle;
    pthread_mutex_unlock(&vis_py_pool.lock);
    return live + idle;
}

/* The verdict the audit hook needs: -1, with the reason raised, when one more
   thread would cross the cap. An uncapped process pays nothing here, not even
   the walk. */
static int vis_py_thread_refused(void)
{
    char refusal[128];

    if (vis_py_thread_cap < 0 || vis_py_live_threads() < vis_py_thread_cap) {
        return 0;
    }
    snprintf(refusal, sizeof refusal,
             "the sandbox may run %d threads at once and they are all taken",
             vis_py_thread_cap);
    PyErr_SetString(PyExc_RuntimeError, refusal);
    vis_py_record(VIS_PY_LOG_WARN, "thread_refused", "\"cap\":%d", vis_py_thread_cap);
    return -1;
}

static void *vis_py_worker_main(void *arg)
{
    vis_py_task *task;
    vis_py_batch *batch;
    PyObject *value;
    PyObject *error;
    PyGILState_STATE gil;
    int last;

    (void)arg;
    pthread_once(&vis_py_worker_once, vis_py_worker_key_make);
    pthread_setspecific(vis_py_worker_key, &vis_py_pool);
#if defined(__APPLE__)
    pthread_setname_np("vis-par");
#endif
    for (;;) {
        pthread_mutex_lock(&vis_py_pool.lock);
        while (vis_py_pool.head == NULL) {
            vis_py_pool.idle++;
            pthread_cond_wait(&vis_py_pool.work, &vis_py_pool.lock);
            vis_py_pool.idle--;
        }
        task = vis_py_pool.head;
        vis_py_pool.head = task->next;
        if (vis_py_pool.head == NULL) {
            vis_py_pool.tail = NULL;
        }
        vis_py_pool.queued--;
        pthread_mutex_unlock(&vis_py_pool.lock);

        /* The pool lock is never taken while WAITING for the GIL, only while
           already holding it, so a worker and a `par` cannot hold one and want
           the other. */
        gil = PyGILState_Ensure();
        value = PyObject_CallNoArgs(task->thunk);
        error = (value == NULL) ? PyErr_GetRaisedException() : NULL;
        batch = task->batch;

        pthread_mutex_lock(&vis_py_pool.lock);
        task->value = value;
        task->error = error;
        task->finished = 1;
        batch->done++;
        batch->outstanding--;
        last = batch->abandoned && batch->outstanding == 0;
        pthread_cond_broadcast(&vis_py_pool.finished);
        pthread_mutex_unlock(&vis_py_pool.lock);

        if (last) {
            vis_py_batch_free(batch);
        }
        PyGILState_Release(gil);
    }
}

/* Start the workers, once, when the first `par` needs them: a session that
   never gathers pays for no thread at all. Needs the GIL; answers -1 with the
   reason raised when the machine gave none. */
static int vis_py_pool_start(void)
{
    int wanted;
    int made = 0;

    pthread_mutex_lock(&vis_py_pool.lock);
    if (vis_py_pool.started) {
        pthread_mutex_unlock(&vis_py_pool.lock);
        return 0;
    }
    wanted = vis_py_worker_target();
    while (made < wanted &&
           pthread_create(&vis_py_pool.worker[made], NULL, vis_py_worker_main, NULL) == 0) {
        made++;
    }
    vis_py_pool.running = made;
    vis_py_pool.started = made > 0;
    pthread_mutex_unlock(&vis_py_pool.lock);
    vis_py_record(VIS_PY_LOG_INFO, "pool_start", "\"workers\":%d", made);
    if (made == 0) {
        PyErr_SetString(PyExc_RuntimeError, "no worker thread could be started");
        return -1;
    }
    return 0;
}

/* --------------------------------------------------------------------------
 * Confinement.
 *
 * GraalPy confined the guest by handing Truffle its own FileSystem; CPython has
 * no such seam and opens files with the whole process's credentials, so the
 * guard here is an AUDIT HOOK (PEP 578), installed before the interpreter
 * starts. A hook cannot be removed once added and guest code cannot see it, so
 * a block that rebinds `open`, reaches through `os`, or imports its way to a
 * descriptor still arrives here.
 *
 * The policy is C state the HOST sets over the ABI and the guest cannot reach:
 * two lists of canonical roots, one readable and one writable — a writable root
 * is readable too. No roots means unconfined, which is what a checkout's own
 * suite runs as until a test asks to be confined.
 * ------------------------------------------------------------------------ */

#define VIS_PY_MAX_ROOTS 64

typedef struct {
    char *item[VIS_PY_MAX_ROOTS];
    int count;
} vis_py_roots;

static vis_py_roots vis_py_read_roots;
static vis_py_roots vis_py_write_roots;
static int vis_py_confined = 0;

/* What the policy STORES - the two root lists and the two refusal sentences -
   is read and written under this lock. The policy is set by the HOST, on
   whatever thread the host runs on, and read by the audit hook, on whatever
   thread the guest runs on: without the lock a host replacing a policy frees the
   very strings a hook is comparing, and a `strcmp` against a freed root is
   exactly the crash that got reported (macOS arm64, two sessions confining at
   once).

   The two FLAGS - confined and network - are read unlocked on the hook's first
   line, which runs for every audited event in the process and is meant to cost a
   load and a branch. A word-sized flag cannot tear, so an unlocked read answers
   either the policy before a swap or the policy after it, and the hook holds the
   lock for the comparison that actually reads memory the swap frees.

   The lock is never held across a call into Python. A hook already holds the GIL
   when it takes this lock, so a host that took the lock and then reached for the
   GIL would close a cycle; `vispython_confine` therefore builds the whole next
   policy - interpreter roots and all - into locals FIRST and only then takes the
   lock to swap it in. Lock order is GIL, then this. */
static pthread_mutex_t vis_py_policy_lock = PTHREAD_MUTEX_INITIALIZER;

/* Whether the guest may reach the network AT ALL. This is a CAPABILITY, not part
   of confinement: a session whose host granted no egress must not resolve a name
   or dial an address, and that is a different question from which directories it
   may read. WHICH hosts a session with egress may reach is decided by the host's
   proxy, which sees the request; a guard written in Python is advice the block it
   guards can rebind. */
static int vis_py_net_allowed = 1;

/* Where CPython writes the bytecode it compiles, set at startup and remembered
   here so confinement can keep it writable. The artifact ships no `__pycache__`
   - bytecode is per-machine cache, not artifact weight - so the interpreter
   compiles what it imports and puts it under this prefix instead of next to a
   source file it must not touch. */
static char vis_py_pycache_prefix[PATH_MAX];

/* The sentence a confined guest reads when it reaches for a process. A host
   that already words this its own way sets it over `vispython_confine` and so
   keeps wording it once; the library answers with its own when none is given. */
static char vis_py_process_refusal[512];

#define VIS_PY_PROCESS_REFUSAL \
    "vis sandbox: starting a process is refused here - every process this " \
    "product runs is started by the host"

#define VIS_PY_NATIVE_REFUSAL \
    "vis sandbox: reaching a native symbol through ctypes is refused here - an " \
    "extension module the interpreter imports is native code the host chose, " \
    "and this is not"

#define VIS_PY_NETWORK_REFUSAL \
    "vis sandbox: the network is off for this session - no address can be " \
    "resolved and no connection opened from here"

/* The sentence a guest reads when it reaches for a network it was not given,
   worded by the host for the same reason the process refusal is. */
static char vis_py_network_refusal[512];

static void vis_py_roots_clear(vis_py_roots *roots)
{
    int i;
    for (i = 0; i < roots->count; i++) {
        free(roots->item[i]);
        roots->item[i] = NULL;
    }
    roots->count = 0;
}

/* Resolve `path` to an absolute, symlink-free path even when it does not exist
   yet: `realpath` answers only for what is already there, so the deepest
   existing ancestor is resolved and the missing tail appended. Lexical
   normalization alone would let a symlink INSIDE a root point anywhere. */
static int vis_py_canonical(const char *path, char *out, size_t cap)
{
    char work[PATH_MAX];
    char tail[PATH_MAX];
    char resolved[PATH_MAX];
    size_t tail_len = 0;
    int written;

    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    if (path[0] == '/') {
        if (strlen(path) >= sizeof work) {
            return 0;
        }
        strcpy(work, path);
    } else {
        char cwd[PATH_MAX];
        if (getcwd(cwd, sizeof cwd) == NULL) {
            return 0;
        }
        if (snprintf(work, sizeof work, "%s/%s", cwd, path) >= (int)sizeof work) {
            return 0;
        }
    }
    tail[0] = '\0';
    for (;;) {
        char *slash;
        size_t piece_len;

        if (realpath(work, resolved) != NULL) {
            break;
        }
        slash = strrchr(work, '/');
        if (slash == NULL) {
            return 0;
        }
        piece_len = strlen(slash + 1);
        if (piece_len + tail_len + 2 >= sizeof tail) {
            return 0;
        }
        if (tail_len == 0) {
            memmove(tail, slash + 1, piece_len + 1);
        } else {
            memmove(tail + piece_len + 1, tail, tail_len + 1);
            memcpy(tail, slash + 1, piece_len);
            tail[piece_len] = '/';
        }
        tail_len = strlen(tail);
        if (slash == work) {
            /* the parent is the filesystem root, which always resolves */
            if (realpath("/", resolved) == NULL) {
                return 0;
            }
            break;
        }
        *slash = '\0';
    }
    if (tail_len == 0) {
        if (strlen(resolved) >= cap) {
            return 0;
        }
        strcpy(out, resolved);
        return 1;
    }
    written = snprintf(out, cap, "%s%s%s", resolved, resolved[1] == '\0' ? "" : "/", tail);
    return (written > 0 && (size_t)written < cap) ? 1 : 0;
}

/* Whether `path`, already canonical, is one of `roots` or lives under one. */
static int vis_py_under(const char *path, const vis_py_roots *roots)
{
    int i;
    for (i = 0; i < roots->count; i++) {
        const char *root = roots->item[i];
        size_t n = strlen(root);
        if (strncmp(path, root, n) != 0) {
            continue;
        }
        if (path[n] == '\0' || path[n] == '/' || (n == 1 && root[0] == '/')) {
            return 1;
        }
    }
    return 0;
}

/* Add one root, canonicalized. A path that will not resolve is DROPPED rather
   than trusted: a root nobody can resolve would otherwise widen the policy by
   accident. */
static void vis_py_roots_add(vis_py_roots *roots, const char *path)
{
    char canon[PATH_MAX];
    char *kept;
    int i;

    if (path == NULL || path[0] == '\0' || roots->count >= VIS_PY_MAX_ROOTS) {
        return;
    }
    if (!vis_py_canonical(path, canon, sizeof canon)) {
        return;
    }
    for (i = 0; i < roots->count; i++) {
        if (strcmp(roots->item[i], canon) == 0) {
            return;
        }
    }
    kept = strdup(canon);
    if (kept != NULL) {
        roots->item[roots->count++] = kept;
    }
}

/* Replace `roots` with the newline-separated `spec`. */
static void vis_py_roots_set(vis_py_roots *roots, const char *spec)
{
    vis_py_roots_clear(roots);
    while (spec != NULL && *spec != '\0' && roots->count < VIS_PY_MAX_ROOTS) {
        const char *end = strchr(spec, '\n');
        size_t n = (end == NULL) ? strlen(spec) : (size_t)(end - spec);
        if (n > 0 && n < PATH_MAX) {
            char one[PATH_MAX];
            memcpy(one, spec, n);
            one[n] = '\0';
            vis_py_roots_add(roots, one);
        }
        if (end == NULL) {
            break;
        }
        spec = end + 1;
    }
}

/* The path an audit argument names, or 0 when it names none: a descriptor is an
   int, a mode is an int, and `os.utime`'s times are a tuple. */
static int vis_py_arg_path(PyObject *arg, char *out, size_t cap)
{
    PyObject *fspath;
    const char *utf8;

    if (arg == NULL || arg == Py_None || PyLong_Check(arg) || PyBool_Check(arg)) {
        return 0;
    }
    fspath = PyOS_FSPath(arg);
    if (fspath == NULL) {
        PyErr_Clear();
        return 0;
    }
    utf8 = PyBytes_Check(fspath) ? PyBytes_AsString(fspath) : PyUnicode_AsUTF8(fspath);
    if (utf8 == NULL || strlen(utf8) >= cap) {
        PyErr_Clear();
        Py_DECREF(fspath);
        return 0;
    }
    strcpy(out, utf8);
    Py_DECREF(fspath);
    return 1;
}

static int vis_py_refuse(const char *event, const char *path, int writing)
{
    PyErr_Format(PyExc_PermissionError,
                 "vis sandbox: %s of %s is outside the %s roots",
                 event, path, writing ? "writable" : "readable");
    vis_py_record(VIS_PY_LOG_WARN, "confine_refused", "\"op\":\"%s\",\"writing\":%d", event,
                  writing);
    return -1;
}

/* Check ONE argument. A path that cannot be canonicalized is refused, because
   an unresolvable path is exactly what an escape attempt looks like. */
static int vis_py_check(const char *event, PyObject *arg, int writing)
{
    char raw[PATH_MAX];
    char canon[PATH_MAX];
    int allowed;

    if (!vis_py_arg_path(arg, raw, sizeof raw)) {
        return 0;
    }
    if (!vis_py_canonical(raw, canon, sizeof canon)) {
        return vis_py_refuse(event, raw, writing);
    }
    /* Both lists are read in ONE hold: a host swapping the policy between the two
       questions could otherwise answer them from different policies. Refusing
       happens after the unlock, because `vis_py_refuse` raises in Python. */
    pthread_mutex_lock(&vis_py_policy_lock);
    allowed = vis_py_under(canon, &vis_py_write_roots)
              || (!writing && vis_py_under(canon, &vis_py_read_roots));
    pthread_mutex_unlock(&vis_py_policy_lock);
    if (!allowed) {
        return vis_py_refuse(event, canon, writing);
    }
    return 0;
}

/* Whether an `open` event is opening for WRITING. `builtins.open` reports its
   mode string and `os.open` reports None plus the raw flags, so both are read:
   the flags are the truth, the mode is what a reader recognizes. */
static int vis_py_open_writes(PyObject *mode, PyObject *flags)
{
    const char *text;

    if (mode != NULL && PyUnicode_Check(mode)) {
        text = PyUnicode_AsUTF8(mode);
        if (text == NULL) {
            PyErr_Clear();
        } else if (strpbrk(text, "wax+") != NULL) {
            return 1;
        }
    }
    if (flags != NULL && PyLong_Check(flags)) {
        long bits = PyLong_AsLong(flags);
        if (bits == -1 && PyErr_Occurred()) {
            PyErr_Clear();
            return 1;
        }
        if ((bits & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND)) != 0) {
            return 1;
        }
    }
    return 0;
}

static const char *const vis_py_write_events[] = {
    "os.chmod",  "os.chown",  "os.link",           "os.mkdir",         "os.remove",
    "os.removexattr", "os.rename", "os.rmdir",     "os.setxattr",      "os.symlink",
    "os.truncate", "os.utime", "shutil.copyfile",  "shutil.copymode",  "shutil.copystat",
    "shutil.move", "shutil.rmtree", "shutil.unpack_archive", NULL};

static const char *const vis_py_read_events[] = {
    "os.chdir", "os.getxattr", "os.listdir", "os.scandir",
    "glob.glob", "pathlib.Path.glob", NULL};

/* The process surface. CPython raises these events itself, so refusing them
   here needs no Python module replaced and survives a block that rebinds one:
   `subprocess.run`, `subprocess.check_output` and `os.popen` all reach
   `subprocess.Popen`, and `os.system` is its own event. */
static const char *const vis_py_process_events[] = {
    "os.system", "os.exec",     "os.fork",         "os.forkpty", "os.posix_spawn",
    "os.spawn",  "os.startfile", "subprocess.Popen", "pty.spawn",  NULL};

/* Native code the host did NOT choose. Opening a library is left alone,
   because `import ctypes` opens one itself and a package that merely imports
   ctypes has to keep working; what is refused is reaching a SYMBOL, which is
   the step that turns a handle into a call into libc. An extension module the
   interpreter imports from its own tree raises none of these, so a real wheel
   is unaffected. */
static const char *const vis_py_native_events[] = {
    "ctypes.dlsym", "ctypes.dlsym/handle", "ctypes.call_function", NULL};

/* Every audited step from "make a socket" to "send a datagram". Refusing the
   lookups as well as the dial is deliberate: a session with no egress should not
   learn an address either, and `socket.__new__` is what makes a raw socket
   refusable before it has an address at all. */
static const char *const vis_py_network_events[] = {
    "socket.__new__",       "socket.bind",         "socket.connect",
    "socket.getaddrinfo",   "socket.gethostbyname", "socket.gethostbyaddr",
    "socket.sendto",        NULL};

/* The refusal sentence to raise: the host's wording when it set one, else the
   library's. Copied out under the policy lock, because the host may be rewording
   it on another thread while this hook reads it. */
static void vis_py_refusal(const char *chosen, const char *fallback, char *out, size_t cap)
{
    pthread_mutex_lock(&vis_py_policy_lock);
    snprintf(out, cap, "%s", chosen[0] != '\0' ? chosen : fallback);
    pthread_mutex_unlock(&vis_py_policy_lock);
}

static int vis_py_event_in(const char *event, const char *const *events)
{
    int i;
    for (i = 0; events[i] != NULL; i++) {
        if (strcmp(event, events[i]) == 0) {
            return 1;
        }
    }
    return 0;
}

/* The hook itself. Runs for EVERY audited event in the process, so the
   unconfined answer is the first line and costs a load and a branch. */
static int vis_py_audit(const char *event, PyObject *args, void *userdata)
{
    Py_ssize_t count;
    Py_ssize_t i;
    int writing;
    char refusal[512];

    (void)userdata;
    /* The thread budget is not part of confinement - it bounds the PROCESS - so
       it answers whether a filesystem policy is in force or not. */
    if (event != NULL && vis_py_event_in(event, vis_py_thread_events)) {
        return vis_py_thread_refused();
    }
    /* Network is the process's capability too, so like the thread budget it is
       answered whether a filesystem policy is in force or not. */
    if (event != NULL && !vis_py_net_allowed && vis_py_event_in(event, vis_py_network_events)) {
        vis_py_refusal(vis_py_network_refusal, VIS_PY_NETWORK_REFUSAL, refusal, sizeof refusal);
        PyErr_SetString(PyExc_PermissionError, refusal);
        return -1;
    }
    if (!vis_py_confined || event == NULL || args == NULL || !PyTuple_Check(args)) {
        return 0;
    }
    if (vis_py_event_in(event, vis_py_process_events)) {
        vis_py_refusal(vis_py_process_refusal, VIS_PY_PROCESS_REFUSAL, refusal, sizeof refusal);
        PyErr_SetString(PyExc_RuntimeError, refusal);
        return -1;
    }
    if (vis_py_event_in(event, vis_py_native_events)) {
        PyErr_SetString(PyExc_RuntimeError, VIS_PY_NATIVE_REFUSAL);
        return -1;
    }
    count = PyTuple_GET_SIZE(args);
    if (count < 1) {
        return 0;
    }
    if (strcmp(event, "open") == 0) {
        writing = vis_py_open_writes(count > 1 ? PyTuple_GET_ITEM(args, 1) : NULL,
                                     count > 2 ? PyTuple_GET_ITEM(args, 2) : NULL);
        return vis_py_check(event, PyTuple_GET_ITEM(args, 0), writing);
    }
    writing = vis_py_event_in(event, vis_py_write_events);
    if (!writing && !vis_py_event_in(event, vis_py_read_events)) {
        return 0;
    }
    for (i = 0; i < count; i++) {
        if (vis_py_check(event, PyTuple_GET_ITEM(args, i), writing) != 0) {
            return -1;
        }
    }
    return 0;
}

/* --------------------------------------------------------------------------
 * Host callables.
 *
 * A sandbox tool is HOST code the guest calls: `grep(...)` reads as Python and
 * runs as Clojure. GraalPy handed the guest a foreign proxy for that; CPython
 * has no such object, so the door here is one function pointer the host
 * registers and one builtin module the guest reaches it through.
 *
 * The pointer is invoked as
 * `int (*)(const char *name, const char *payload, char *out, int cap)` and
 * answers the byte length its reply NEEDS: it writes the reply NUL-terminated
 * when that fits and writes nothing when it does not, so an answer larger than
 * the buffer costs one retry instead of an allocation crossing the boundary.
 * A negative return is a failure whose reason the host left in the buffer, and
 * that text becomes the Python exception.
 *
 * Only text crosses. What the payload and the reply MEAN is the runtime's
 * (`vis_runtime.install_tool` encodes JSON), never this file's: a boundary that
 * knows a dialect has to be changed whenever the dialect grows a case.
 *
 * The GIL is RELEASED around a host call. It is blocking work in another
 * language, and holding the lock would stop every other guest thread for as
 * long as the host takes.
 * ------------------------------------------------------------------------ */

#define VIS_PY_HOST_BUFFER 65536

typedef int (*vis_py_host_fn)(const char *session, const char *name, const char *payload,
                              char *out, int cap);

static vis_py_host_fn vis_py_host = NULL;

/* --------------------------------------------------------------------------
 * WHO IS CALLING.
 *
 * The host binds a tool PER SESSION, so every upcall has to say whose it is -
 * and the guest must not be the one saying it. `vis_runtime.host_call` is an
 * ordinary module function and the session in its envelope is JSON the GUEST
 * writes, so a block that names a neighbouring session reaches that session's
 * tools. Measured, in three lines, from a block confined to a temp directory:
 * it read a file the policy had refused it one statement earlier, through a
 * tool bound in another namespace.
 *
 * The one thing a block cannot forge is the IDENTITY of a session's globals.
 * It can copy any value out of another namespace, rebind its own `__name__`,
 * even write `sys.modules` - but it cannot make its own globals BE another
 * session's dict. So the namespaces this file created are remembered here, and
 * the caller is the nearest frame whose globals is one of them.
 *
 * The table holds a WEAK reference to each session module, because the host
 * drops a finished session by taking it out of `sys.modules` and a strong one
 * here would keep every session that ever ran alive. A dead entry frees its own
 * slot on the next walk. All of it runs under the GIL, like every other entry
 * point in this file, so the table needs no lock of its own.
 * ------------------------------------------------------------------------ */

#define VIS_PY_SESSIONS_MAX 512
#define VIS_PY_SESSION_NAME 128

typedef struct {
    PyObject *weak; /* weakref to the session MODULE, or NULL for a free slot */
    char name[VIS_PY_SESSION_NAME];
} vis_py_session;

static vis_py_session vis_py_sessions[VIS_PY_SESSIONS_MAX];

/* The module a slot still names, or NULL when the session is gone (and the slot
   is freed on the way out, which is the only reaping this table needs). */
static PyObject *vis_py_session_module(vis_py_session *slot)
{
    PyObject *module = NULL;

    if (slot->weak == NULL) {
        return NULL;
    }
    if (PyWeakref_GetRef(slot->weak, &module) < 0) {
        PyErr_Clear();
        module = NULL;
    }
    if (module == NULL) {
        Py_CLEAR(slot->weak);
        slot->name[0] = '\0';
        return NULL;
    }
    Py_DECREF(module); /* borrowed from here on: the caller holds the GIL */
    return module;
}

/* Remember `module` as the session called `name`. Idempotent, and silent when
   the table is full: a host that ran out of slots loses the ABILITY TO NAME a
   caller, which the call path answers as "unknown", never as somebody else. */
static void vis_py_session_remember(const char *name, PyObject *module)
{
    int free_slot = -1;
    int i;

    if (name == NULL || name[0] == '\0' || strlen(name) >= VIS_PY_SESSION_NAME) {
        return;
    }
    for (i = 0; i < VIS_PY_SESSIONS_MAX; i++) {
        PyObject *live = vis_py_session_module(&vis_py_sessions[i]);
        if (live == NULL) {
            if (free_slot < 0) {
                free_slot = i;
            }
            continue;
        }
        if (live == module) {
            return; /* already known, under whatever name it was created with */
        }
    }
    if (free_slot < 0) {
        return;
    }
    vis_py_sessions[free_slot].weak = PyWeakref_NewRef(module, NULL);
    if (vis_py_sessions[free_slot].weak == NULL) {
        PyErr_Clear();
        return;
    }
    snprintf(vis_py_sessions[free_slot].name, VIS_PY_SESSION_NAME, "%s", name);
}

/* The session whose globals dict is `globals`, or NULL for a frame that belongs
   to no session (`vis_runtime`'s own module, the standard library, an import). */
static const char *vis_py_session_named(PyObject *globals)
{
    int i;

    for (i = 0; i < VIS_PY_SESSIONS_MAX; i++) {
        PyObject *module = vis_py_session_module(&vis_py_sessions[i]);
        if (module != NULL && PyModule_GetDict(module) == globals) {
            return vis_py_sessions[i].name;
        }
    }
    return NULL;
}

/* The session the current call is being made FROM: the nearest frame whose
   globals belongs to one. `host_call` itself is defined in `vis_runtime`, whose
   frame belongs to no session, so the walk skips it and lands on the block. */
static const char *vis_py_calling_session(void)
{
    PyFrameObject *frame = PyEval_GetFrame();
    const char *found = NULL;
    int depth;

    Py_XINCREF(frame);
    for (depth = 0; frame != NULL && depth < 256; depth++) {
        PyObject *globals = PyFrame_GetGlobals(frame);
        PyFrameObject *back;

        if (globals != NULL) {
            found = vis_py_session_named(globals);
            Py_DECREF(globals);
        }
        if (found != NULL) {
            break;
        }
        back = PyFrame_GetBack(frame);
        Py_DECREF(frame);
        frame = back;
    }
    Py_XDECREF(frame);
    return found;
}

/* `_vis_host.call(name, payload)` -> the host's reply as `str`. */
static PyObject *vis_py_host_call(PyObject *self, PyObject *args)
{
    const char *name = NULL;
    const char *payload = NULL;
    const char *session = NULL;
    vis_py_host_fn host = vis_py_host;
    PyThreadState *save;
    char *buffer;
    char *grown;
    PyObject *answer;
    int cap = VIS_PY_HOST_BUFFER;
    int needed;

    (void)self;
    if (!PyArg_ParseTuple(args, "ss", &name, &payload)) {
        return NULL;
    }
    if (host == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "no host is bound to this interpreter");
        return NULL;
    }
    buffer = (char *)malloc((size_t)cap);
    if (buffer == NULL) {
        return PyErr_NoMemory();
    }
    /* Read BEFORE the GIL is released: it walks Python frames. */
    session = vis_py_calling_session();
    if (session == NULL) {
        session = "";
    }
    save = PyEval_SaveThread();
    needed = host(session, name, payload, buffer, cap);
    PyEval_RestoreThread(save);
    if (needed >= cap) {
        grown = (char *)realloc(buffer, (size_t)needed + 1);
        if (grown == NULL) {
            free(buffer);
            return PyErr_NoMemory();
        }
        buffer = grown;
        cap = needed + 1;
        save = PyEval_SaveThread();
        needed = host(session, name, payload, buffer, cap);
        PyEval_RestoreThread(save);
        if (needed >= cap) {
            free(buffer);
            PyErr_SetString(PyExc_RuntimeError, "host reply grew between calls");
            return NULL;
        }
    }
    if (needed < 0) {
        buffer[cap - 1] = '\0';
        PyErr_SetString(PyExc_RuntimeError, buffer[0] == '\0' ? "host call failed" : buffer);
        free(buffer);
        return NULL;
    }
    answer = PyUnicode_FromStringAndSize(buffer, (Py_ssize_t)needed);
    free(buffer);
    return answer;
}

/* Call every thunk on THIS thread, in order: the answer when there are fewer
   than two of them, or when the caller is itself a worker. */
static PyObject *vis_py_par_inline(PyObject *thunks, Py_ssize_t count)
{
    PyObject *values = PyList_New(count);
    PyObject *value;
    Py_ssize_t i;

    if (values == NULL) {
        return NULL;
    }
    for (i = 0; i < count; i++) {
        value = PyObject_CallNoArgs(PySequence_Fast_GET_ITEM(thunks, i));
        if (value == NULL) {
            Py_DECREF(values);
            return NULL;
        }
        PyList_SET_ITEM(values, i, value);
    }
    return values;
}

/* Take a task back OFF the queue, if no worker has claimed it yet. The pool
   lock must be held. */
static int vis_py_unqueue(vis_py_task *task)
{
    vis_py_task *node = vis_py_pool.head;
    vis_py_task *prev = NULL;

    while (node != NULL && node != task) {
        prev = node;
        node = node->next;
    }
    if (node == NULL) {
        return 0;
    }
    if (prev == NULL) {
        vis_py_pool.head = node->next;
    } else {
        prev->next = node->next;
    }
    if (vis_py_pool.tail == node) {
        vis_py_pool.tail = prev;
    }
    node->next = NULL;
    vis_py_pool.queued--;
    return 1;
}

/* `milliseconds` from now, the way pthread_cond_timedwait wants it. */
static void vis_py_deadline(struct timespec *when, long milliseconds)
{
    clock_gettime(CLOCK_REALTIME, when);
    when->tv_nsec += milliseconds * 1000000L;
    when->tv_sec += when->tv_nsec / 1000000000L;
    when->tv_nsec %= 1000000000L;
}

/* `_vis_host.par(thunks)` -> their values IN ORDER, raising the FIRST failure
   the moment that thunk fails. Waiting for the siblings first would be tidier
   to write and wrong to use: the runtime's `gather` cancels what is still
   running when a child fails, and it cannot cancel what it has not been told
   about yet. The siblings keep running into a batch nobody reads. */
static PyObject *vis_py_par(PyObject *self, PyObject *args)
{
    PyObject *sequence = NULL;
    PyObject *thunks;
    PyObject *values = NULL;
    PyObject *failure = NULL;
    vis_py_batch *batch;
    PyThreadState *save;
    Py_ssize_t count;
    Py_ssize_t i;
    PyObject *value;
    PyObject *error;
    struct timespec deadline;
    int submitted = 0;
    int saturated = 0;
    int outstanding;
    int claimed;
    int waited;
    int queued;
    int quota;

    (void)self;
    if (!PyArg_ParseTuple(args, "O", &sequence)) {
        return NULL;
    }
    thunks = PySequence_Fast(sequence, "par() wants a sequence of thunks");
    if (thunks == NULL) {
        return NULL;
    }
    count = PySequence_Fast_GET_SIZE(thunks);
    if (count < 2 || vis_py_in_worker()) {
        values = vis_py_par_inline(thunks, count);
        Py_DECREF(thunks);
        return values;
    }
    if (vis_py_pool_start() != 0) {
        Py_DECREF(thunks);
        return NULL;
    }
    batch = (vis_py_batch *)calloc(1, sizeof *batch);
    if (batch != NULL) {
        batch->task = (vis_py_task *)calloc((size_t)count, sizeof *batch->task);
    }
    if (batch == NULL || batch->task == NULL) {
        free(batch);
        Py_DECREF(thunks);
        return PyErr_NoMemory();
    }
    batch->thunks = thunks; /* the batch owns the sequence from here on */
    batch->count = (int)count;
    for (i = 0; i < count; i++) {
        batch->task[i].thunk = PySequence_Fast_GET_ITEM(thunks, i);
        batch->task[i].batch = batch;
    }
    quota = vis_py_par_quota < 1 ? 1 : vis_py_par_quota;
    for (i = 0; i < count; i++) {
        pthread_mutex_lock(&vis_py_pool.lock);
        while (submitted < (int)count && submitted - batch->done < quota) {
            if (vis_py_pool.tail == NULL) {
                vis_py_pool.head = &batch->task[submitted];
            } else {
                vis_py_pool.tail->next = &batch->task[submitted];
            }
            vis_py_pool.tail = &batch->task[submitted];
            batch->outstanding++;
            vis_py_pool.queued++;
            submitted++;
            pthread_cond_signal(&vis_py_pool.work);
        }
        pthread_mutex_unlock(&vis_py_pool.lock);

        /* The GIL goes back while this call waits, or no worker could run.
           And the wait has a floor: a task nobody has claimed after a moment is
           one the CALLER runs itself. The pool is bounded and shared, so a
           gather that arrives when every worker is busy has to make progress on
           the thread it already owns instead of waiting for one to come free -
           slower than overlapping, which is the honest answer, and never a
           stall. */
        claimed = 0;
        waited = 0;
        save = PyEval_SaveThread();
        pthread_mutex_lock(&vis_py_pool.lock);
        /* That floor is paid ONCE per gather, not once per thunk. A pool that
           left the first task sitting and still has nobody idle will do the
           same to the rest, so the caller takes them straight away; an idle
           worker means the pool came back, and then the wait is worth it again
           for the overlap. */
        if (saturated && vis_py_pool.idle == 0 && vis_py_unqueue(&batch->task[i])) {
            batch->outstanding--;
            claimed = 1;
        }
        while (!claimed && !batch->task[i].finished) {
            vis_py_deadline(&deadline, VIS_PY_CALLER_RUNS_MS);
            if (pthread_cond_timedwait(&vis_py_pool.finished, &vis_py_pool.lock, &deadline) ==
                    ETIMEDOUT &&
                vis_py_unqueue(&batch->task[i])) {
                batch->outstanding--;
                claimed = 1;
                saturated = 1;
                waited = 1;
            }
        }
        queued = vis_py_pool.queued;
        pthread_mutex_unlock(&vis_py_pool.lock);
        PyEval_RestoreThread(save);

        if (claimed) {
            vis_py_record(VIS_PY_LOG_INFO, "caller_runs",
                          "\"task\":%d,\"of\":%d,\"queued\":%d,\"waited_ms\":%d", (int)i,
                          (int)count, queued, waited ? VIS_PY_CALLER_RUNS_MS : 0);
            value = PyObject_CallNoArgs(batch->task[i].thunk);
            error = (value == NULL) ? PyErr_GetRaisedException() : NULL;
            pthread_mutex_lock(&vis_py_pool.lock);
            batch->task[i].value = value;
            batch->task[i].error = error;
            batch->task[i].finished = 1;
            batch->done++;
            pthread_mutex_unlock(&vis_py_pool.lock);
        }

        if (batch->task[i].error != NULL) {
            failure = batch->task[i].error;
            batch->task[i].error = NULL;
            break;
        }
    }
    if (failure == NULL) {
        values = PyList_New(count);
        if (values != NULL) {
            for (i = 0; i < count; i++) {
                PyList_SET_ITEM(values, i, batch->task[i].value);
                batch->task[i].value = NULL; /* the list owns it now */
            }
        }
    }
    pthread_mutex_lock(&vis_py_pool.lock);
    batch->abandoned = 1;
    outstanding = batch->outstanding;
    pthread_mutex_unlock(&vis_py_pool.lock);
    if (outstanding == 0) {
        vis_py_batch_free(batch);
    }
    if (failure != NULL) {
        PyErr_SetRaisedException(failure);
        return NULL;
    }
    return values;
}

/* `_vis_host.threads()` -> the policy in force and what is alive under it. */
static PyObject *vis_py_threads(PyObject *self, PyObject *args)
{
    (void)self;
    (void)args;
    return Py_BuildValue("{s:i,s:i,s:i,s:i,s:i,s:i}", "cap", vis_py_thread_cap, "workers",
                         vis_py_worker_target(), "quota", vis_py_par_quota, "live",
                         vis_py_live_threads(), "idle", vis_py_pool.idle, "queued",
                         vis_py_pool.queued);
}
static PyMethodDef vis_py_host_methods[] = {
    {"call", vis_py_host_call, METH_VARARGS,
     "call(name, payload) -> str: run the host callable `name` over a text payload."},
    {"par", vis_py_par, METH_VARARGS,
     "par(thunks) -> list: run zero-argument thunks on the host's worker pool."},
    {"threads", vis_py_threads, METH_NOARGS,
     "threads() -> dict: the thread policy in force and what is alive under it."},
    {NULL, NULL, 0, NULL}
};

static struct PyModuleDef vis_py_host_module = {
    PyModuleDef_HEAD_INIT,
    "_vis_host",
    "The one door from the sandbox back to the host that started it.",
    -1,
    vis_py_host_methods,
    NULL,
    NULL,
    NULL,
    NULL
};

static PyObject *vis_py_host_init(void)
{
    return PyModule_Create(&vis_py_host_module);
}

/* Bind the callable every `_vis_host.call` reaches; NULL unbinds, after which a
   guest calling a tool is told there is no host rather than crashing. The host
   may rebind at will: the pointer is read per call. Returns 0. */
int vispython_host(void *fn)
{
    vis_py_host_fn host;

    /* A cast from `void *` to a function pointer is not ISO C; copying the
       bytes is, and the JVM has no other way to hand over an upcall stub. */
    memcpy(&host, &fn, sizeof host);
    vis_py_host = host;
    return 0;
}

/* The interpreter's own installation, added to the READABLE roots whenever a
   policy comes into force: `sys.prefix`, `sys.base_prefix`, `sys.exec_prefix`
   and every absolute `sys.path` entry - the vendored stdlib, the shipped
   Python and whatever directory the host appended for installed packages.

   GraalPy wrapped its confined FileSystem with `allowLanguageHomeAccess`, so a
   confined context could still read its own stdlib; here the import machinery
   opens source through the same audited event as the guest, and a policy that
   names only the session's directories refuses the next cold import. That is
   not a sandbox, it is a broken interpreter - and deriving these paths is the
   runtime's job, since only the runtime knows where it was installed. Read at
   CONFINE time, because a host appends its package directory to `sys.path`
   after startup. */
static void vis_py_add_interpreter_roots(vis_py_roots *roots)
{
    static const char *const names[] = {"prefix", "base_prefix", "exec_prefix", NULL};
    PyGILState_STATE gil;
    PyObject *sys;
    PyObject *value;
    PyObject *entry;
    Py_ssize_t count;
    Py_ssize_t i;
    int n;

    if (!Py_IsInitialized()) {
        return;
    }
    gil = PyGILState_Ensure();
    sys = PyImport_ImportModule("sys");
    if (sys == NULL) {
        PyErr_Clear();
        PyGILState_Release(gil);
        return;
    }
    for (n = 0; names[n] != NULL; n++) {
        value = PyObject_GetAttrString(sys, names[n]);
        if (value == NULL) {
            PyErr_Clear();
            continue;
        }
        if (PyUnicode_Check(value)) {
            vis_py_roots_add(roots, PyUnicode_AsUTF8(value));
        }
        Py_DECREF(value);
    }
    value = PyObject_GetAttrString(sys, "path");
    if (value == NULL) {
        PyErr_Clear();
    } else {
        if (PyList_Check(value)) {
            count = PyList_GET_SIZE(value);
            for (i = 0; i < count; i++) {
                entry = PyList_GET_ITEM(value, i);
                /* An entry that is not absolute is the process's working
                   directory, which belongs to the host and not to the
                   interpreter. */
                if (entry != NULL && PyUnicode_Check(entry)) {
                    const char *text = PyUnicode_AsUTF8(entry);
                    if (text != NULL && text[0] == '/') {
                        vis_py_roots_add(roots, text);
                    }
                }
            }
        }
        Py_DECREF(value);
    }
    Py_DECREF(sys);
    PyGILState_Release(gil);
}
/* The character devices a confined guest still reaches. They carry no
   information about the machine and hold no state a guest could read back:
   `/dev/null` is the bit bucket the standard library opens on its own (pytest's
   capture, `subprocess.DEVNULL`, `os.devnull`), `/dev/zero` answers zeroes, and
   the random devices answer entropy the guest can already get from
   `os.urandom`. Refusing them does not narrow the sandbox, it only breaks
   ordinary Python - measured: `pytest` cannot start global capture without
   `/dev/null` and dies in its own plugin manager. Null is writable as well,
   because discarding output is what it is for. */
static void vis_py_add_device_roots(vis_py_roots *read_roots, vis_py_roots *write_roots)
{
    static const char *const readable[] = {"/dev/null", "/dev/zero", "/dev/urandom", "/dev/random",
                                           NULL};
    int i;

    for (i = 0; readable[i] != NULL; i++) {
        vis_py_roots_add(read_roots, readable[i]);
    }
    vis_py_roots_add(write_roots, "/dev/null");
}

/* Confine the interpreter to `read_roots` and `write_roots`, each a
   newline-separated list of paths, and shut the process surface and `ctypes`
   for as long as that policy is in force. Replaces whatever was in force; two
   empty lists LIFT the confinement, which only the host can ask for, because
   only the host can call this. `refusal` is the sentence the guest reads when
   it reaches for a process; empty keeps the library's own. Answers the policy
   in force, so a caller can log what it actually got after unresolvable roots
   were dropped. Two things the INTERPRETER owns are added for as long as a
   policy is in force, because a host cannot be expected to know them and a
   policy without them refuses the interpreter's own next import: the bytecode
   cache prefix given at startup, writable because it is cache and not guest
   data, and the installation itself - `sys.prefix`, `sys.base_prefix`,
   `sys.exec_prefix` and every absolute `sys.path` entry - readable. Both are
   counted in the answer. */
int vispython_confine(const char *read_roots, const char *write_roots, const char *refusal,
                       char *out, int cap)
{
    /* The next policy, assembled entirely off to the side: nothing here is
       reachable by a hook until the swap below, so the interpreter's own roots
       can be read under the GIL without this thread holding the policy lock. */
    vis_py_roots next_read = {{0}, 0};
    vis_py_roots next_write = {{0}, 0};
    vis_py_roots old_read;
    vis_py_roots old_write;
    char summary[64];
    int confined;

    vis_py_roots_set(&next_read, read_roots);
    vis_py_roots_set(&next_write, write_roots);
    confined = (next_read.count + next_write.count) > 0;
    if (confined) {
        vis_py_roots_add(&next_write, vis_py_pycache_prefix);
        vis_py_add_interpreter_roots(&next_read);
        vis_py_add_device_roots(&next_read, &next_write);
    }
    snprintf(summary, sizeof summary, "%d %d", next_read.count, next_write.count);

    pthread_mutex_lock(&vis_py_policy_lock);
    old_read = vis_py_read_roots;
    old_write = vis_py_write_roots;
    vis_py_read_roots = next_read;
    vis_py_write_roots = next_write;
    vis_py_confined = confined;
    snprintf(vis_py_process_refusal, sizeof vis_py_process_refusal, "%s",
             refusal != NULL ? refusal : "");
    pthread_mutex_unlock(&vis_py_policy_lock);

    /* The strings the swap displaced are unreachable now, so freeing them cannot
       race a hook that was mid-comparison when the swap happened. */
    vis_py_roots_clear(&old_read);
    vis_py_roots_clear(&old_write);
    return vis_py_copy_out(summary, out, cap);
}

/* Set whether the guest may reach the network: `policy` is 1 to allow and 0 to
   refuse every socket, name lookup and connection from the same audit hook that
   answers confinement. `refusal` is the sentence the guest reads; empty keeps the
   library's own. Answers the flag in force.

   What this decides is the CAPABILITY, never a domain policy: a host that lets a
   session out decides WHERE at its proxy, which sees the request and can say no
   to one URL. Here there is nothing to see yet - only whether a socket may exist. */
int vispython_network(const char *policy, const char *refusal, char *out, int cap)
{
    int want = 1;
    char summary[8];

    pthread_mutex_lock(&vis_py_policy_lock);
    if (policy != NULL && sscanf(policy, "%d", &want) == 1) {
        vis_py_net_allowed = (want != 0);
    }
    if (refusal != NULL) {
        snprintf(vis_py_network_refusal, sizeof vis_py_network_refusal, "%s", refusal);
    }
    pthread_mutex_unlock(&vis_py_policy_lock);
    snprintf(summary, sizeof summary, "%d", vis_py_net_allowed);
    return vis_py_copy_out(summary, out, cap);
}

/* Set the thread policy: `policy` is up to three integers - cap, workers,
   quota - separated by spaces, where 0 or a missing number keeps what is in
   force. Answers the three in force, so a caller can log what it got.

   `cap` is the hard one: it counts every thread the interpreter has, so a guest
   that starts its own is refused by the same budget the pool spends from, and
   every session shares it because every session shares the interpreter. A cap
   of -1 lifts it entirely - the one configuration for a process that is not the
   sandbox's, where the code is the host's own and confinement is off.
   `workers` sizes the pool when it FIRST runs; a later change is for the next
   process, because resizing a pool with work in it is a way to lose a task. */
int vispython_threads(const char *policy, char *out, int cap)
{
    int want_cap = 0;
    int want_workers = 0;
    int want_quota = 0;
    int matched;
    char summary[64];

    if (policy != NULL) {
        matched = sscanf(policy, "%d %d %d", &want_cap, &want_workers, &want_quota);
        (void)matched;
    }
    if (want_cap != 0) {
        vis_py_thread_cap = want_cap;
    }
    if (want_workers > 0) {
        vis_py_worker_setting = want_workers;
    }
    if (want_quota > 0) {
        vis_py_par_quota = want_quota;
    }
    snprintf(summary, sizeof summary, "%d %d %d", vis_py_thread_cap, vis_py_worker_target(),
             vis_py_par_quota);
    return vis_py_copy_out(summary, out, cap);
}

/* Set what is recorded: `policy` is a level name - off, warn, info, debug -
   and optionally 1 to MIRROR every record to stderr as it happens. Mirroring
   is for running this library with no host to drain it, which is how its own
   suite and `pip` use it; a host that drains leaves it at 0 and keeps its
   diagnostics in the one file it already has. The policy is total: a level
   given without the flag turns mirroring off. Answers the policy in force. */
int vispython_logging(const char *policy, char *out, int cap)
{
    char want[16];
    char summary[32];
    int mirror = 0;
    int i;

    want[0] = '\0';
    if (policy != NULL && sscanf(policy, "%15s %d", want, &mirror) >= 1) {
        for (i = 0; i <= VIS_PY_LOG_DEBUG; i++) {
            if (strcmp(want, vis_py_log_levels[i]) == 0) {
                vis_py_log.level = i;
            }
        }
        vis_py_log.mirror = (mirror != 0);
    }
    snprintf(summary, sizeof summary, "%s %d", vis_py_log_levels[vis_py_log.level],
             vis_py_log.mirror);
    return vis_py_copy_out(summary, out, cap);
}

/* Take the answer a previous call on THIS thread could not fit. The call
   reports the length it needed, so the host allocates that much and asks once;
   the text is handed over and dropped. Answers 0 with an empty buffer when
   nothing is waiting, which is the normal case. */
int vispython_take_result(char *out, int cap)
{
    char *kept;
    int n;

    if (out == NULL || cap <= 0) {
        return VIS_PY_ERR_BUFFER;
    }
    pthread_once(&vis_py_stash_once, vis_py_stash_key_make);
    kept = pthread_getspecific(vis_py_stash_key);
    if (kept == NULL) {
        out[0] = '\0';
        return 0;
    }
    n = (int)strlen(kept);
    if (n > cap - 1) {
        return VIS_PY_ERR_BUFFER;
    }
    memcpy(out, kept, (size_t)n);
    out[n] = '\0';
    pthread_setspecific(vis_py_stash_key, NULL);
    free(kept);
    return n;
}
/* Take what has been recorded since the last call: NDJSON, one object per
   line, oldest first. Answers what FITS the buffer and leaves the rest, so a
   host drains in a loop until the answer is empty. Records lost to a full ring
   are reported first, as an event of their own, because the gap matters more
   than the lines around it. */
int vispython_drain_log(char *out, int cap)
{
    const char *line;
    int written = 0;
    int n;

    if (out == NULL || cap <= 0) {
        return VIS_PY_ERR_BUFFER;
    }
    pthread_mutex_lock(&vis_py_log.lock);
    if (vis_py_log.dropped > 0 && cap > VIS_PY_LOG_LINE) {
        written = snprintf(out, (size_t)cap,
                           "{\"level\":\"warn\",\"event\":\"log_dropped\",\"records\":%ld}\n",
                           vis_py_log.dropped);
        vis_py_log.dropped = 0;
    }
    while (vis_py_log.count > 0) {
        line = vis_py_log.slot[vis_py_log.head];
        n = (int)strlen(line);
        if (written + n + 2 > cap) {
            break;
        }
        memcpy(out + written, line, (size_t)n);
        written += n;
        out[written++] = '\n';
        vis_py_log.head = (vis_py_log.head + 1) % VIS_PY_LOG_SLOTS;
        vis_py_log.count--;
    }
    pthread_mutex_unlock(&vis_py_log.lock);
    out[written] = '\0';
    return written;
}
/* Start the interpreter, rooted at `home` when one is given.

   `home` is a VENDORED CPython tree — the directory holding `lib/python3.14/`.
   Passing one is what makes an installation self-contained: without it CPython
   resolves its standard library from whatever interpreter the machine happens
   to have, so a laptop with no Python, or with the wrong one, decides whether
   the sandbox runs at all. NULL or empty keeps CPython's own search, which is
   what a source checkout built against a system interpreter wants.
   `pycache_prefix` is where compiled bytecode goes: NULL or empty leaves
   CPython's default, which writes a `__pycache__` beside every source file it
   imports - wrong for a shipped tree that is read-only and shared, and the
   reason the artifact carries no bytecode at all.

   Idempotent, so a caller that cannot cheaply know whether a sibling already
   started it does not have to. Returns 0, or VIS_PY_ERR_INIT with the reason in
   `out`. */
int vispython_initialize(const char *home, const char *pycache_prefix, char *out, int cap)
{
    PyConfig config;
    PyStatus status;

    if (vis_py_started) {
        return 0;
    }
#if defined(__linux__)
    /* FFM loads this cdylib RTLD_LOCAL. Binary extension modules intentionally leave
       CPython C-API references unresolved and expect the embedding process to export
       them globally, so promote the already-loaded adjacent libpython before import. */
    if (vis_py_global_libpython == NULL) {
        vis_py_global_libpython = dlopen(VIS_PY_LIBPYTHON, RTLD_NOW | RTLD_GLOBAL);
        if (vis_py_global_libpython == NULL) {
            vis_py_copy_out(dlerror(), out, cap);
            return VIS_PY_ERR_INIT;
        }
    }
#endif
    if (!vis_py_inittab && PyImport_AppendInittab("_vis_host", vis_py_host_init) != 0) {
        vis_py_copy_out("PyImport_AppendInittab refused _vis_host", out, cap);
        return VIS_PY_ERR_INIT;
    }
    vis_py_inittab = 1;
    if (PySys_AddAuditHook(vis_py_audit, NULL) != 0) {
        vis_py_copy_out("PySys_AddAuditHook was refused", out, cap);
        return VIS_PY_ERR_INIT;
    }
    snprintf(vis_py_pycache_prefix, sizeof vis_py_pycache_prefix, "%s",
             pycache_prefix != NULL ? pycache_prefix : "");
    if ((home == NULL || home[0] == '\0') && vis_py_pycache_prefix[0] == '\0') {
        Py_InitializeEx(0);
    } else {
        PyConfig_InitPythonConfig(&config);
        /* Match Py_InitializeEx(0): an embedded interpreter must not take the
           host process's signal handlers away from the JVM. */
        config.install_signal_handlers = 0;
        status = PyStatus_Ok();
        if (home != NULL && home[0] != '\0') {
            status = PyConfig_SetBytesString(&config, &config.home, home);
        }
        if (!PyStatus_Exception(status) && vis_py_pycache_prefix[0] != '\0') {
            status = PyConfig_SetBytesString(&config, &config.pycache_prefix,
                                             vis_py_pycache_prefix);
        }
        if (!PyStatus_Exception(status)) {
            status = Py_InitializeFromConfig(&config);
        }
        PyConfig_Clear(&config);
        if (PyStatus_Exception(status)) {
            vis_py_copy_out(status.err_msg == NULL ? "Py_InitializeFromConfig failed" : status.err_msg,
                            out, cap);
            return VIS_PY_ERR_INIT;
        }
    }
    if (!Py_IsInitialized()) {
        vis_py_copy_out("the interpreter did not start", out, cap);
        return VIS_PY_ERR_INIT;
    }
    vis_py_started = 1;
    vis_py_record(VIS_PY_LOG_INFO, "init", "\"cap\":%d,\"workers\":%d", vis_py_thread_cap,
                  vis_py_worker_target());
    /* Hand the GIL back. `Py_InitializeEx` leaves it HELD by the thread that
       started the interpreter; keeping it that way would force every entry point
       onto that one thread, and a host upcall parking it would stall every other
       session. So initialization ends here and each entry point takes the GIL
       for itself - the ordinary embedding shape. */
    (void)PyEval_SaveThread();
    return 0;
}

/* The running interpreter's version string, e.g. "3.14.6 (main, ...)". */
int vispython_version(char *out, int cap)
{
    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    return vis_py_copy_out(Py_GetVersion(), out, cap);
}

/* Resolve the module a call runs in. An empty or NULL name means `__main__`;
   any other name is created on first use, which is how the host gets one
   isolated namespace per sandbox SESSION out of a single interpreter. Sessions
   are module namespaces, not sub-interpreters: they share imported modules (so
   the second session pays nothing to import json) while keeping their own
   globals. Returns a borrowed dict, or NULL with the error taken. */
static PyObject *vis_py_namespace(const char *module_name, char *out, int cap)
{
    PyObject *module, *globals;
    const char *name = (module_name == NULL || module_name[0] == '\0') ? "__main__" : module_name;

    module = PyImport_AddModule(name);
    if (module == NULL) {
        vis_py_take_error(out, cap);
        return NULL;
    }
    globals = PyModule_GetDict(module);
    vis_py_session_remember(name, module);
    if (PyDict_GetItemString(globals, "__builtins__") == NULL) {
        if (PyDict_SetItemString(globals, "__builtins__", PyEval_GetBuiltins()) != 0) {
            vis_py_take_error(out, cap);
            return NULL;
        }
    }
    return globals;
}

/* Evaluate `code` as an EXPRESSION in `module_name` and write str(result).
   Statements belong in vispython_exec; keeping the two apart is what lets the
   JVM side stay free of Py_eval_input / Py_file_input constants. */
static int vis_py_eval_locked(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *result, *text;
    const char *utf8;
    int written;
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    result = PyRun_String(code, Py_eval_input, globals, globals);
    if (result == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    text = PyObject_Str(result);
    utf8 = (text == NULL) ? NULL : PyUnicode_AsUTF8(text);
    written = vis_py_copy_out(utf8 == NULL ? "" : utf8, out, cap);
    Py_XDECREF(text);
    Py_DECREF(result);
    return written;
}

/* Run `code` as a module body in `module_name`, for its side effects. */
static int vis_py_exec_locked(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *result;
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    result = PyRun_String(code, Py_file_input, globals, globals);
    if (result == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    Py_DECREF(result);
    return 0;
}

/* The interpreter thread ident of whatever the HOST is running right now, and 0
   between calls. A block that spins holds the interpreter thread, so the only
   way to reach it is from another thread - which needs to know WHICH thread to
   raise in. Written under the GIL by the runner, read by `vispython_interrupt`
   with the GIL held, so the value a caller acts on is a thread that was running
   when it looked. */
static volatile unsigned long vis_py_running_thread = 0;

/* Raise `KeyboardInterrupt` in the thread running guest code, answering "1" when
   a thread state took it and "0" when there was nothing to interrupt.

   The one way OUT of a runaway block. `Thread.interrupt` cannot reach guest
   code, and the host's own worker future only cancels the JAVA side: the block
   keeps burning a core until the process dies. CPython delivers an async
   exception at a bytecode boundary, so a spinning `while True:` unwinds, its
   `finally` blocks run and the session stays usable - while a thread blocked in
   a host call or inside C sees it only when it returns, which is why the caller
   treats "0", and a block that keeps running, as the interpreter it can no
   longer reach.

   Called from ANY thread, and never from the one it interrupts: it takes the GIL
   the running block keeps dropping at its switch interval. */
int vispython_interrupt(char *out, int cap)
{
    PyGILState_STATE gil;
    unsigned long target;
    int landed;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    target = vis_py_running_thread;
    if (target == 0) {
        return vis_py_copy_out("0", out, cap);
    }
    gil = PyGILState_Ensure();
    landed = PyThreadState_SetAsyncExc(target, PyExc_KeyboardInterrupt);
    PyGILState_Release(gil);
    vis_py_record(VIS_PY_LOG_WARN, "interrupt", "\"threads\":%d", landed);
    return vis_py_copy_out(landed > 0 ? "1" : "0", out, cap);
}

/* Run `code` the way the sandbox does: statements execute, and the value of a
   trailing EXPRESSION is what comes back. The split is Python's own `ast` work,
   so it lives in `vis_runtime.run`. The value comes back as JSON text, because
   the ABI carries strings and the host reads data, not a repr; this function
   only hands the source over as a Python string, never as interpolated text. */
static int vis_py_run_locked(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *runtime, *result, *text;
    const char *utf8;
    long long began;
    int written;
    began = vis_py_now_ms();
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    runtime = PyImport_ImportModule("vis_runtime");
    if (runtime == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    vis_py_running_thread = PyThread_get_thread_ident();
    result = PyObject_CallMethod(runtime, "run_json", "sO", code, globals);
    vis_py_running_thread = 0;
    Py_DECREF(runtime);
    if (result == NULL) {
        vis_py_take_error(out, cap);
        vis_py_record(VIS_PY_LOG_WARN, "run_failed", "\"session\":\"%s\",\"ms\":%lld",
                      vis_py_session_name(module_name), vis_py_now_ms() - began);
        return VIS_PY_ERR_PYTHON;
    }
    text = PyObject_Str(result);
    utf8 = (text == NULL) ? NULL : PyUnicode_AsUTF8(text);
    written = vis_py_copy_out(utf8 == NULL ? "" : utf8, out, cap);
    Py_XDECREF(text);
    Py_DECREF(result);
    vis_py_record(VIS_PY_LOG_DEBUG, "run", "\"session\":\"%s\",\"ms\":%lld",
                  vis_py_session_name(module_name), vis_py_now_ms() - began);
    return written;
}

/* Run `code` as a sandbox BLOCK: the runtime's own `__vis_run_async__` under
   captured stdout, with the reapers at the boundary. Policy is Python's
   (`vis_runtime.run_block`); this only carries the source over and brings the
   JSON object — stdout and error — back. */
static int vis_py_run_block_locked(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *runtime, *result, *text;
    const char *utf8;
    long long began;
    int written;
    began = vis_py_now_ms();
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    runtime = PyImport_ImportModule("vis_runtime");
    if (runtime == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    vis_py_running_thread = PyThread_get_thread_ident();
    result = PyObject_CallMethod(runtime, "run_block_json", "sO", code, globals);
    vis_py_running_thread = 0;
    Py_DECREF(runtime);
    if (result == NULL) {
        vis_py_take_error(out, cap);
        vis_py_record(VIS_PY_LOG_WARN, "block_failed", "\"session\":\"%s\",\"ms\":%lld",
                      vis_py_session_name(module_name), vis_py_now_ms() - began);
        return VIS_PY_ERR_PYTHON;
    }
    text = PyObject_Str(result);
    utf8 = (text == NULL) ? NULL : PyUnicode_AsUTF8(text);
    written = vis_py_copy_out(utf8 == NULL ? "" : utf8, out, cap);
    Py_XDECREF(text);
    Py_DECREF(result);
    vis_py_record(VIS_PY_LOG_INFO, "block", "\"session\":\"%s\",\"ms\":%lld,\"bytes\":%d",
                  vis_py_session_name(module_name), vis_py_now_ms() - began,
                  code == NULL ? 0 : (int)strlen(code));
    return written;
}

/* The four entry points that RUN Python code. Each takes the GIL for its own
   caller and gives it back, so ANY host thread may enter and no session waits
   behind another's upcall. `PyGILState_Ensure` is per thread and re-entrant: a
   thread that already holds the GIL pays nothing, which is what makes a host
   tool calling back INTO the interpreter - while the block that called it sits
   parked in `vis_py_host_call` - a nested acquire instead of a deadlock. The
   `_locked` bodies above assume the GIL is already held. */
int vispython_eval(const char *module_name, const char *code, char *out, int cap)
{
    PyGILState_STATE gil;
    int status;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    gil = PyGILState_Ensure();
    status = vis_py_eval_locked(module_name, code, out, cap);
    PyGILState_Release(gil);
    return status;
}

int vispython_exec(const char *module_name, const char *code, char *out, int cap)
{
    PyGILState_STATE gil;
    int status;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    gil = PyGILState_Ensure();
    status = vis_py_exec_locked(module_name, code, out, cap);
    PyGILState_Release(gil);
    return status;
}

int vispython_run(const char *module_name, const char *code, char *out, int cap)
{
    PyGILState_STATE gil;
    int status;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    gil = PyGILState_Ensure();
    status = vis_py_run_locked(module_name, code, out, cap);
    PyGILState_Release(gil);
    return status;
}

int vispython_run_block(const char *module_name, const char *code, char *out, int cap)
{
    PyGILState_STATE gil;
    int status;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    gil = PyGILState_Ensure();
    status = vis_py_run_block_locked(module_name, code, out, cap);
    PyGILState_Release(gil);
    return status;
}

