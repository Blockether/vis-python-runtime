import os as _os
import runpy as _runpy
import sys as _sys


def _record_cli_exit(code):
    """Leave the process exit code in the session for the host to read."""
    if code is None:
        code = 0
    elif not isinstance(code, int):
        code = 1
    globals()["__vis_cli_exit__"] = int(code)
    return int(code)


def __vis_run_module__(name):
    """Run `name` as `__main__`, recording the process exit code."""
    original_argv = _sys.argv
    if name == "pytest":
        # The embedded host owns fatal-signal handlers. pytest's faulthandler
        # can turn recoverable JVM signals into fatal Python faults.
        # Disable only that diagnostic plugin, never any tests.
        _sys.argv = [original_argv[0], "-p", "no:faulthandler", *original_argv[1:]]
    try:
        _runpy.run_module(name, run_name="__main__", alter_sys=True)
        return _record_cli_exit(0)
    except SystemExit as exc:
        return _record_cli_exit(exc.code)
    except ImportError:
        _sys.stdout.write("vis-agent python: No module named " + str(name) + chr(10))
        return _record_cli_exit(1)
    finally:
        _sys.argv = original_argv


def __vis_run_file__(path):
    """Run a Python script as `__main__`, with its directory first on sys.path."""
    original_path = list(_sys.path)
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(path)))
    try:
        try:
            _runpy.run_path(path, run_name="__main__")
            return _record_cli_exit(0)
        except SystemExit as exc:
            return _record_cli_exit(exc.code)
    finally:
        _sys.path[:] = original_path
