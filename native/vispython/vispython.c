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
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define VIS_PY_ERR_BUFFER (-1) /* caller passed no room for a result */
#define VIS_PY_ERR_INIT   (-2) /* interpreter is not running */
#define VIS_PY_ERR_PYTHON (-3) /* Python raised; buffer holds str(exception) */

static int vis_py_started = 0;
static int vis_py_inittab = 0;

/* Copy a NUL-terminated UTF-8 string into the caller's buffer, truncating to
   fit. Returns the byte count written, not counting the terminator. */
static int vis_py_copy_out(const char *s, char *out, int cap)
{
    int n;
    if (out == NULL || cap <= 0) {
        return VIS_PY_ERR_BUFFER;
    }
    n = (int)strlen(s);
    if (n > cap - 1) {
        n = cap - 1;
    }
    memcpy(out, s, (size_t)n);
    out[n] = '\0';
    return n;
}

/* Drain the raised exception into the caller's buffer. Always clears it: a
   pending exception left behind poisons the next unrelated call. */
static int vis_py_take_error(char *out, int cap)
{
    PyObject *exc = PyErr_GetRaisedException();
    PyObject *text = NULL;
    const char *utf8 = NULL;
    int written;

    if (exc == NULL) {
        return vis_py_copy_out("Python failed without raising", out, cap);
    }
    text = PyObject_Str(exc);
    if (text != NULL && PyUnicode_GET_LENGTH(text) == 0) {
        /* `assert x` and friends raise with no message at all; the TYPE is
           then the only thing the caller can act on, so send that instead. */
        Py_DECREF(text);
        text = PyUnicode_FromString(Py_TYPE(exc)->tp_name);
    }
    utf8 = (text == NULL) ? NULL : PyUnicode_AsUTF8(text);
    written = vis_py_copy_out(utf8 == NULL ? "unprintable Python exception" : utf8, out, cap);
    Py_XDECREF(text);
    Py_DECREF(exc);
    PyErr_Clear();
    return written;
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
 *   workers - threads the pool runs. The default is how a pool for BLOCKING
 *             work is normally sized, `min(32, cpus + 4)`: these threads spend
 *             their lives waiting on the host, not competing for a core, so
 *             sizing them by core count would bound the wrong resource.
 *   quota   - tasks ONE `par` call may have running at once, so one session's
 *             wide `gather` cannot take the pool away from the others.
 * ------------------------------------------------------------------------ */

#define VIS_PY_WORKERS_MAX 32
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
    pthread_cond_t work;     /* a task was queued, or the pool is stopping */
    pthread_cond_t finished; /* a task finished */
    pthread_t worker[VIS_PY_WORKERS_MAX];
    vis_py_task *head;
    vis_py_task *tail;
    int running; /* workers created */
    int idle;    /* workers waiting for a task, holding no thread state */
    int started;
    int stopping;
} vis_py_pool = {PTHREAD_MUTEX_INITIALIZER, PTHREAD_COND_INITIALIZER, PTHREAD_COND_INITIALIZER,
                 {0},                       NULL,                    NULL,
                 0,                         0,                       0,
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
    long cpus;
    int workers = vis_py_worker_setting;

    if (workers <= 0) {
        cpus = sysconf(_SC_NPROCESSORS_ONLN);
        workers = (int)(cpus < 1 ? 1 : cpus) + 4;
    }
    if (workers > VIS_PY_WORKERS_MAX) {
        workers = VIS_PY_WORKERS_MAX;
    }
    if (workers > vis_py_thread_cap) {
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
   thread would cross the cap. */
static int vis_py_thread_refused(void)
{
    char refusal[128];

    if (vis_py_live_threads() < vis_py_thread_cap) {
        return 0;
    }
    snprintf(refusal, sizeof refusal,
             "the sandbox may run %d threads at once and they are all taken",
             vis_py_thread_cap);
    PyErr_SetString(PyExc_RuntimeError, refusal);
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
        while (vis_py_pool.head == NULL && !vis_py_pool.stopping) {
            vis_py_pool.idle++;
            pthread_cond_wait(&vis_py_pool.work, &vis_py_pool.lock);
            vis_py_pool.idle--;
        }
        task = vis_py_pool.head;
        if (task == NULL) {
            pthread_mutex_unlock(&vis_py_pool.lock);
            return NULL;
        }
        vis_py_pool.head = task->next;
        if (vis_py_pool.head == NULL) {
            vis_py_pool.tail = NULL;
        }
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
    if (made == 0) {
        PyErr_SetString(PyExc_RuntimeError, "no worker thread could be started");
        return -1;
    }
    return 0;
}

/* Stop the workers and join them. The caller must NOT hold the GIL: a worker
   finishing its task needs it. */
static void vis_py_pool_stop(void)
{
    pthread_t worker[VIS_PY_WORKERS_MAX];
    int count;
    int i;

    pthread_mutex_lock(&vis_py_pool.lock);
    if (!vis_py_pool.started) {
        pthread_mutex_unlock(&vis_py_pool.lock);
        return;
    }
    vis_py_pool.stopping = 1;
    count = vis_py_pool.running;
    memcpy(worker, vis_py_pool.worker, sizeof worker[0] * (size_t)count);
    pthread_cond_broadcast(&vis_py_pool.work);
    pthread_mutex_unlock(&vis_py_pool.lock);
    for (i = 0; i < count; i++) {
        pthread_join(worker[i], NULL);
    }
    pthread_mutex_lock(&vis_py_pool.lock);
    vis_py_pool.started = 0;
    vis_py_pool.stopping = 0;
    vis_py_pool.running = 0;
    pthread_mutex_unlock(&vis_py_pool.lock);
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

    if (path == NULL || path[0] == '\0' || roots->count >= VIS_PY_MAX_ROOTS) {
        return;
    }
    if (!vis_py_canonical(path, canon, sizeof canon)) {
        return;
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
    return -1;
}

/* Check ONE argument. A path that cannot be canonicalized is refused, because
   an unresolvable path is exactly what an escape attempt looks like. */
static int vis_py_check(const char *event, PyObject *arg, int writing)
{
    char raw[PATH_MAX];
    char canon[PATH_MAX];

    if (!vis_py_arg_path(arg, raw, sizeof raw)) {
        return 0;
    }
    if (!vis_py_canonical(raw, canon, sizeof canon)) {
        return vis_py_refuse(event, raw, writing);
    }
    if (vis_py_under(canon, &vis_py_write_roots)) {
        return 0;
    }
    if (writing || !vis_py_under(canon, &vis_py_read_roots)) {
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

    (void)userdata;
    /* The thread budget is not part of confinement - it bounds the PROCESS - so
       it answers whether a filesystem policy is in force or not. */
    if (event != NULL && vis_py_event_in(event, vis_py_thread_events)) {
        return vis_py_thread_refused();
    }
    if (!vis_py_confined || event == NULL || args == NULL || !PyTuple_Check(args)) {
        return 0;
    }
    if (vis_py_event_in(event, vis_py_process_events)) {
        PyErr_SetString(PyExc_RuntimeError, vis_py_process_refusal[0] != '\0'
                                                ? vis_py_process_refusal
                                                : VIS_PY_PROCESS_REFUSAL);
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

typedef int (*vis_py_host_fn)(const char *name, const char *payload, char *out, int cap);

static vis_py_host_fn vis_py_host = NULL;

/* `_vis_host.call(name, payload)` -> the host's reply as `str`. */
static PyObject *vis_py_host_call(PyObject *self, PyObject *args)
{
    const char *name = NULL;
    const char *payload = NULL;
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
    save = PyEval_SaveThread();
    needed = host(name, payload, buffer, cap);
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
        needed = host(name, payload, buffer, cap);
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
    int submitted = 0;
    int outstanding;
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
            submitted++;
            pthread_cond_signal(&vis_py_pool.work);
        }
        pthread_mutex_unlock(&vis_py_pool.lock);

        /* The GIL goes back while this call waits, or no worker could run. */
        save = PyEval_SaveThread();
        pthread_mutex_lock(&vis_py_pool.lock);
        while (!batch->task[i].finished) {
            pthread_cond_wait(&vis_py_pool.finished, &vis_py_pool.lock);
        }
        pthread_mutex_unlock(&vis_py_pool.lock);
        PyEval_RestoreThread(save);

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
    return Py_BuildValue("{s:i,s:i,s:i,s:i}", "cap", vis_py_thread_cap, "workers",
                         vis_py_worker_target(), "quota", vis_py_par_quota, "live",
                         vis_py_live_threads());
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
/* Confine the interpreter to `read_roots` and `write_roots`, each a
   newline-separated list of paths, and shut the process surface and `ctypes`
   for as long as that policy is in force. Replaces whatever was in force; two
   empty lists LIFT the confinement, which only the host can ask for, because
   only the host can call this. `refusal` is the sentence the guest reads when
   it reaches for a process; empty keeps the library's own. Answers the policy
   in force, so a caller can log what it actually got after unresolvable roots
   were dropped. The bytecode cache prefix given at startup is added to the
   writable roots for as long as a policy is in force: it is the interpreter's
   own cache, not guest data, and it is counted in the answer. */
int vispython_confine(const char *read_roots, const char *write_roots, const char *refusal,
                       char *out, int cap)
{
    char summary[64];

    vis_py_roots_set(&vis_py_read_roots, read_roots);
    vis_py_roots_set(&vis_py_write_roots, write_roots);
    snprintf(vis_py_process_refusal, sizeof vis_py_process_refusal, "%s",
             refusal != NULL ? refusal : "");
    vis_py_confined = (vis_py_read_roots.count + vis_py_write_roots.count) > 0;
    if (vis_py_confined) {
        vis_py_roots_add(&vis_py_write_roots, vis_py_pycache_prefix);
    }
    snprintf(summary, sizeof summary, "%d %d", vis_py_read_roots.count, vis_py_write_roots.count);
    return vis_py_copy_out(summary, out, cap);
}

/* Set the thread policy: `policy` is up to three integers - cap, workers,
   quota - separated by spaces, where 0 or a missing number keeps what is in
   force. Answers the three in force, so a caller can log what it got.

   `cap` is the hard one: it counts every thread the interpreter has, so a guest
   that starts its own is refused by the same budget the pool spends from, and
   every session shares it because every session shares the interpreter.
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
    if (want_cap > 0) {
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
int vispython_eval(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *result, *text;
    const char *utf8;
    int written;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
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
int vispython_exec(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *result;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
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

/* Run `code` the way the sandbox does: statements execute, and the value of a
   trailing EXPRESSION is what comes back. The split is Python's own `ast` work,
   so it lives in `vis_runtime.run`. The value comes back as JSON text, because
   the ABI carries strings and the host reads data, not a repr; this function
   only hands the source over as a Python string, never as interpolated text. */
int vispython_run(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *runtime, *result, *text;
    const char *utf8;
    int written;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    runtime = PyImport_ImportModule("vis_runtime");
    if (runtime == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    result = PyObject_CallMethod(runtime, "run_json", "sO", code, globals);
    Py_DECREF(runtime);
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

/* Run `code` as a sandbox BLOCK: the runtime's own `__vis_run_async__` under
   captured stdout, with the reapers at the boundary. Policy is Python's
   (`vis_runtime.run_block`); this only carries the source over and brings the
   JSON object — stdout and error — back. */
int vispython_run_block(const char *module_name, const char *code, char *out, int cap)
{
    PyObject *globals, *runtime, *result, *text;
    const char *utf8;
    int written;

    if (!vis_py_started) {
        return VIS_PY_ERR_INIT;
    }
    globals = vis_py_namespace(module_name, out, cap);
    if (globals == NULL) {
        return VIS_PY_ERR_PYTHON;
    }
    runtime = PyImport_ImportModule("vis_runtime");
    if (runtime == NULL) {
        vis_py_take_error(out, cap);
        return VIS_PY_ERR_PYTHON;
    }
    result = PyObject_CallMethod(runtime, "run_block_json", "sO", code, globals);
    Py_DECREF(runtime);
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

/* Stop the interpreter. Idempotent. Returns 0, or VIS_PY_ERR_INIT if CPython
   reported a non-zero finalization status. */
int vispython_finalize(void)
{
    PyThreadState *save;
    int status;

    if (!vis_py_started) {
        return 0;
    }
    /* Workers first, and without the GIL: one of them may be inside a task and
       needs the GIL to leave it. Finalizing under a live worker is a crash. */
    save = PyEval_SaveThread();
    vis_py_pool_stop();
    PyEval_RestoreThread(save);
    status = Py_FinalizeEx();
    vis_py_started = 0;
    return (status == 0) ? 0 : VIS_PY_ERR_INIT;
}
