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

/* Replace `roots` with the newline-separated `spec`. A path that cannot be
   canonicalized is DROPPED rather than trusted: a root nobody can resolve
   would otherwise widen the policy by accident. */
static void vis_py_roots_set(vis_py_roots *roots, const char *spec)
{
    vis_py_roots_clear(roots);
    while (spec != NULL && *spec != '\0' && roots->count < VIS_PY_MAX_ROOTS) {
        const char *end = strchr(spec, '\n');
        size_t n = (end == NULL) ? strlen(spec) : (size_t)(end - spec);
        if (n > 0 && n < PATH_MAX) {
            char one[PATH_MAX];
            char canon[PATH_MAX];
            memcpy(one, spec, n);
            one[n] = '\0';
            if (vis_py_canonical(one, canon, sizeof canon)) {
                char *kept = strdup(canon);
                if (kept != NULL) {
                    roots->item[roots->count++] = kept;
                }
            }
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
    if (!vis_py_confined || event == NULL || args == NULL || !PyTuple_Check(args)) {
        return 0;
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

static PyMethodDef vis_py_host_methods[] = {
    {"call", vis_py_host_call, METH_VARARGS,
     "call(name, payload) -> str: run the host callable `name` over a text payload."},
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
int vis_python_host(void *fn)
{
    vis_py_host_fn host;

    /* A cast from `void *` to a function pointer is not ISO C; copying the
       bytes is, and the JVM has no other way to hand over an upcall stub. */
    memcpy(&host, &fn, sizeof host);
    vis_py_host = host;
    return 0;
}
/* Confine the interpreter to `read_roots` and `write_roots`, each a
   newline-separated list of paths. Replaces whatever was in force; two empty
   lists LIFT the confinement, which only the host can ask for, because only the
   host can call this. Answers the policy in force, so a caller can log what it
   actually got after unresolvable roots were dropped. */
int vis_python_confine(const char *read_roots, const char *write_roots, char *out, int cap)
{
    char summary[64];

    vis_py_roots_set(&vis_py_read_roots, read_roots);
    vis_py_roots_set(&vis_py_write_roots, write_roots);
    vis_py_confined = (vis_py_read_roots.count + vis_py_write_roots.count) > 0;
    snprintf(summary, sizeof summary, "%d %d", vis_py_read_roots.count, vis_py_write_roots.count);
    return vis_py_copy_out(summary, out, cap);
}

/* Start the interpreter, rooted at `home` when one is given.

   `home` is a VENDORED CPython tree — the directory holding `lib/python3.14/`.
   Passing one is what makes an installation self-contained: without it CPython
   resolves its standard library from whatever interpreter the machine happens
   to have, so a laptop with no Python, or with the wrong one, decides whether
   the sandbox runs at all. NULL or empty keeps CPython's own search, which is
   what a source checkout built against a system interpreter wants.

   Idempotent, so a caller that cannot cheaply know whether a sibling already
   started it does not have to. Returns 0, or VIS_PY_ERR_INIT with the reason in
   `out`. */
int vis_python_initialize(const char *home, char *out, int cap)
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
    if (home == NULL || home[0] == '\0') {
        Py_InitializeEx(0);
    } else {
        PyConfig_InitPythonConfig(&config);
        /* Match Py_InitializeEx(0): an embedded interpreter must not take the
           host process's signal handlers away from the JVM. */
        config.install_signal_handlers = 0;
        status = PyConfig_SetBytesString(&config, &config.home, home);
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
int vis_python_version(char *out, int cap)
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
   Statements belong in vis_python_exec; keeping the two apart is what lets the
   JVM side stay free of Py_eval_input / Py_file_input constants. */
int vis_python_eval(const char *module_name, const char *code, char *out, int cap)
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
int vis_python_exec(const char *module_name, const char *code, char *out, int cap)
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
   so it lives in `vis_runtime.run`. The value comes back as EDN text, because
   the ABI carries strings and the host reads data, not a repr; this function
   only hands the source over as a Python string, never as interpolated text. */
int vis_python_run(const char *module_name, const char *code, char *out, int cap)
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
    result = PyObject_CallMethod(runtime, "run_edn", "sO", code, globals);
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
   EDN map — stdout and error — back. */
int vis_python_run_block(const char *module_name, const char *code, char *out, int cap)
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
    result = PyObject_CallMethod(runtime, "run_block_edn", "sO", code, globals);
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
int vis_python_finalize(void)
{
    int status;
    if (!vis_py_started) {
        return 0;
    }
    status = Py_FinalizeEx();
    vis_py_started = 0;
    return (status == 0) ? 0 : VIS_PY_ERR_INIT;
}
