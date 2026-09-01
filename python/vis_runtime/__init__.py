"""The sandbox runtime, as a package CPython IMPORTS.

The host does not interpolate the runtime into a string and execute it. It puts
this directory on ``sys.path`` and imports, which is what an interpreter with a
real filesystem and a real import system is for: compilation is cached in
``__pycache__``, a traceback names a file and a line, and a session that only
needs part of the runtime pays for that part.

``install`` is the per-SESSION step. Starting the interpreter happens once per
process; a session is a namespace, and every session calls ``install`` on its
own globals to receive the runtime's public names.

The runtime body itself still lives in Vis (``resources/vis-python/async_runtime.py``)
and reaches this interpreter as a source root, so there is no second copy to
drift. When it moves into this package the only thing that changes is the name
below.
"""

import importlib
import os

#: Module that carries the sandbox runtime. Overridable so a port can point at
#: the module in its new home without a code change.
SANDBOX_MODULE = os.environ.get("VIS_PYTHON_SANDBOX_MODULE", "async_runtime")

#: Names every module has; not the runtime's, so never copied into a session.
_MODULE_OWN = frozenset(
    ["__name__", "__doc__", "__package__", "__loader__", "__spec__", "__file__",
     "__path__", "__cached__", "__builtins__"]
)


def install(namespace):
    """Copy the sandbox runtime's public names into `namespace`.

    Returns how many names were installed, so the host can assert a session is
    equipped instead of guessing. Raises `ImportError` naming the search path
    when the runtime module is not on it — a silent half-installed session is
    the one failure mode worth being loud about.
    """
    try:
        module = importlib.import_module(SANDBOX_MODULE)
    except ImportError as exc:
        raise ImportError(
            "sandbox runtime module %r not importable from %r: %s"
            % (SANDBOX_MODULE, sys_path_snapshot(), exc)
        ) from exc
    installed = {k: v for k, v in vars(module).items() if k not in _MODULE_OWN}
    namespace.update(installed)
    return len(installed)


def sys_path_snapshot():
    """The interpreter's current import roots, for error messages."""
    import sys

    return list(sys.path)
