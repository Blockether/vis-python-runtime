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
#include <string.h>

#define VIS_PY_ERR_BUFFER (-1) /* caller passed no room for a result */
#define VIS_PY_ERR_INIT   (-2) /* interpreter is not running */
#define VIS_PY_ERR_PYTHON (-3) /* Python raised; buffer holds str(exception) */

static int vis_py_started = 0;

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

/* Start the interpreter. Idempotent, so a caller that cannot cheaply know
   whether a sibling already started it does not have to. Returns 0, or
   VIS_PY_ERR_INIT with the reason in `out`. */
int vis_python_initialize(char *out, int cap)
{
    if (vis_py_started) {
        return 0;
    }
    Py_InitializeEx(0);
    if (!Py_IsInitialized()) {
        vis_py_copy_out("Py_InitializeEx did not start the interpreter", out, cap);
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
