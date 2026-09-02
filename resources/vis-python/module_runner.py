import sys as _sys


def __vis_run_module__(name):
    """Run `name` as `__main__`, recording the exit code the process owes.

    A block has ONE channel — what it PRINTED — so the code cannot come back as
    a value. It is left in `__vis_module_exit__` instead, which the host reads
    once the block has settled.
    """
    import importlib, runpy

    def done(code):
        if code is None:
            code = 0
        elif not isinstance(code, int):
            code = 1
        globals()["__vis_module_exit__"] = int(code)
        return int(code)

    mod = None
    try:
        mod = importlib.import_module(name)
    except ImportError:
        mod = None
    # A module with no `__file__` is synthesised rather than imported from a
    # file, and `runpy` cannot run one: reach for its entry point directly.
    if mod is not None and getattr(mod, "__file__", None) is None:
        entry = getattr(mod, "console_main", None) or getattr(mod, "main", None)
        if callable(entry):
            try:
                return done(entry(_sys.argv[1:]))
            except SystemExit as _e:
                return done(_e.code)
    try:
        runpy.run_module(name, run_name="__main__", alter_sys=True)
        return done(0)
    except SystemExit as _e:
        return done(_e.code)
    except ImportError:
        _sys.stdout.write("vis-agent python: No module named " + str(name) + chr(10))
        return done(1)
