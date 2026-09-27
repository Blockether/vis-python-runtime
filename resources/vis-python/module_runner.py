import linecache as _linecache
import os as _os
import runpy as _runpy
import sys as _sys
import textwrap as _textwrap
import types as _types

# The runner's and runpy's frames lead every program traceback; `python` hides them.
_RUNNER_GLOBALS = globals()
_RUNPY_FILE = _runpy.run_module.__code__.co_filename


def _record_cli_exit(code):
    """Leave the process exit code in the session for the host to read."""
    globals()["__vis_cli_exit__"] = int(code)
    return int(code)


def _exit_status(code):
    """CPython's SystemExit rule: None succeeds, an int is the status, and anything
    else is printed to stderr and fails."""
    if code is None:
        return 0
    if isinstance(code, int):
        return code
    try:
        print(code, file=_sys.stderr)
    except Exception:
        pass
    return 1


def _flush_streams():
    """Flush what the program left buffered; a stream it closed is left alone."""
    streams = (_sys.stdout, _sys.stderr, _sys.__stdout__, _sys.__stderr__)
    for stream in {
        id(stream): stream for stream in streams if stream is not None
    }.values():
        try:
            if not stream.closed:
                stream.flush()
        except Exception:
            pass


def _program_traceback(tb):
    """The traceback from the program's first frame, as `python` reports it."""
    while tb is not None and (
        tb.tb_frame.f_globals is _RUNNER_GLOBALS
        or tb.tb_frame.f_code.co_filename == _RUNPY_FILE
    ):
        tb = tb.tb_next
    return tb


def _report(exc):
    """Print an uncaught exception through sys.excepthook, like the interpreter."""
    exc.__traceback__ = _program_traceback(exc.__traceback__)
    try:
        _sys.excepthook(type(exc), exc, exc.__traceback__)
    except BaseException:
        import traceback

        traceback.print_exception(exc)


def _run_as_main(program):
    """Run `program` with CPython's exit rules and answer its exit status.

    A block's stdout is an in-memory capture; a program owns the process stdout,
    so `sys.stdout.buffer`, `fileno()` and `isatty()` work and closing it leaves
    the capture intact. Errors go to stderr, never into the program's output.
    The calling thread is not Python's main thread, so `threading` sees a daemon
    dummy that new threads would inherit; like CPython's main thread it is
    non-daemon while the program runs, so `__vis_shutdown__` waits for them."""
    import threading

    capture = _sys.stdout
    current = threading.current_thread()
    daemonic = current._daemonic
    _sys.stdout = _sys.__stdout__ or capture
    current._daemonic = False
    try:
        try:
            program()
            status = 0
        except SystemExit as exc:
            _flush_streams()
            status = _exit_status(exc.code)
        except BaseException as exc:
            _flush_streams()
            _report(exc)
            status = 130 if isinstance(exc, KeyboardInterrupt) else 1
    finally:
        _flush_streams()
        current._daemonic = daemonic
        _sys.stdout = capture
    return _record_cli_exit(status)


def _raised_by_lookup(exc):
    """Whether runpy raised `exc` while finding the module, before running it."""
    tb = exc.__traceback__
    while tb is not None and tb.tb_next is not None:
        tb = tb.tb_next
    return tb is not None and tb.tb_frame.f_code.co_filename == _RUNPY_FILE


def __vis_run_code__(source, filename="<string>", dedent=False):
    """Run source text as `__main__`, as `python -c` (dedented) and `python -` do."""
    if dedent:
        source = _textwrap.dedent(source)

    def program():
        main = _types.ModuleType("__main__")
        _linecache.cache[filename] = (
            len(source),
            None,
            source.splitlines(True),
            filename,
        )
        previous = _sys.modules.get("__main__")
        _sys.modules["__main__"] = main
        try:
            exec(compile(source, filename, "exec", dont_inherit=True), main.__dict__)
        finally:
            if previous is None:
                _sys.modules.pop("__main__", None)
            else:
                _sys.modules["__main__"] = previous

    return _run_as_main(program)


def __vis_run_module__(name):
    """Run `name` as `__main__`, recording the process exit code."""
    original_argv = _sys.argv
    if name == "pytest":
        # The embedded host owns fatal-signal handlers. pytest's faulthandler
        # can turn recoverable JVM signals into fatal Python faults.
        # Disable only that diagnostic plugin, never any tests.
        _sys.argv = [original_argv[0], "-p", "no:faulthandler", *original_argv[1:]]

    def program():
        try:
            _runpy.run_module(name, run_name="__main__", alter_sys=True)
        except ImportError as exc:
            if not _raised_by_lookup(exc):
                raise
            raise SystemExit("vis-agent python: " + str(exc)) from None

    try:
        return _run_as_main(program)
    finally:
        _sys.argv = original_argv


def __vis_run_file__(path):
    """Run a Python script as `__main__`, with its directory first on sys.path."""
    original_path = list(_sys.path)
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(path)))
    try:
        return _run_as_main(lambda: _runpy.run_path(path, run_name="__main__"))
    finally:
        _sys.path[:] = original_path


def __vis_shutdown__():
    """Finish like the interpreter at exit: join non-daemon threads, then run atexit.

    Only a process about to exit calls this; afterwards thread pools refuse work."""
    import atexit
    import threading

    capture = _sys.stdout
    _sys.stdout = _sys.__stdout__ or capture
    try:
        try:
            threading._shutdown()
        except BaseException as exc:
            _report(exc)
        atexit._run_exitfuncs()
    finally:
        _flush_streams()
        _sys.stdout = capture
