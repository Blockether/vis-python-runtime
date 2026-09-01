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

import ast
import importlib
import importlib.machinery
import importlib.util
import json
import os
import sys

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

    Also puts the shim finder on `sys.meta_path`, because an equipped session
    is one where `import bs4` finds the sandbox's bs4. Returns how many names
    were installed, so the host can assert a session is equipped instead of
    guessing. Raises `ImportError` naming the search path when the runtime
    module is not on it — a silent half-installed session is the one failure
    mode worth being loud about.
    """
    install_finder()
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


def run(source, namespace):
    """Execute `source` in `namespace`, answering the trailing expression's value.

    This is the sandbox's execution semantics, and it belongs in Python because
    it is `ast` work: statements execute, and if the last statement is an
    expression its value comes back instead of `None`. The host hands the source
    over as a string object, so nothing is interpolated into code here.
    """
    tree = ast.parse(source)
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        tail = ast.Expression(tree.body.pop().value)
        if tree.body:
            exec(compile(tree, "<vis>", "exec"), namespace)
        return eval(compile(tail, "<vis>", "eval"), namespace)
    exec(compile(tree, "<vis>", "exec"), namespace)
    return None


def to_edn(value):
    """Render a Python value as EDN — the one data wire back to the host.

    The C ABI carries strings, so a result crosses as EDN text and the host
    reads it with `clojure.edn`: `nil`/booleans/numbers/strings, vectors for
    sequences, maps for dicts, sets for sets. Anything without an EDN shape
    (an object, a function, a raster handle) crosses as its `str`, because a
    test asserting on one asserts on what a human would see.
    """
    if value is None:
        return "nil"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return repr(value)
    if isinstance(value, float):
        if value != value:
            return "##NaN"
        if value == float("inf"):
            return "##Inf"
        if value == float("-inf"):
            return "##-Inf"
        return repr(value)
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bytes):
        return json.dumps(value.decode("utf-8", "replace"))
    if isinstance(value, (list, tuple)):
        return "[" + " ".join(to_edn(item) for item in value) + "]"
    if isinstance(value, (set, frozenset)):
        return "#{" + " ".join(to_edn(item) for item in value) + "}"
    if isinstance(value, dict):
        return "{" + " ".join(to_edn(k) + " " + to_edn(v) for k, v in value.items()) + "}"
    return json.dumps(str(value))


def run_edn(source, namespace):
    """`run` the source and render the value as EDN. What the C ABI calls."""
    return to_edn(run(source, namespace))


def shim_root():
    """Directory holding the sandbox shim sources."""
    override = os.environ.get("VIS_PYTHON_SHIMS_PATH")
    if override:
        return override
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(os.path.dirname(here)), "resources", "vis-shims")


_installed_shims = set()


class ShimLoader(importlib.machinery.SourceFileLoader):
    """Load a shim source and blame the SHIM when the source itself fails.

    Without this, a shim that dies on its own missing dependency surfaces as a
    bare `ModuleNotFoundError` for the dependency — or worse, as the import
    machinery reporting the SHIM as missing — and the reader cannot tell which
    of the two is broken. The wrapper names both.
    """

    def exec_module(self, module):
        try:
            super().exec_module(module)
        except Exception as exc:
            raise ImportError(
                "vis shim %r failed to load: %s" % (module.__name__, exc)
            ) from exc


class ShimFinder:
    """Resolve a bare `import <name>` to the sandbox shim of that name.

    The sandbox ships no wheels, so a shim IS the package as far as user code
    is concerned, and code that imports `tabulate` from a `pandas` snippet must
    find it. This finder is APPENDED to `sys.meta_path`, never prepended: a
    real stdlib module always wins, and only a name Python could not import
    otherwise falls through to `resources/vis-shims/<name>.py`.
    """

    def find_spec(self, name, path=None, target=None):
        if path is not None or "." in name:
            return None
        source = os.path.join(shim_root(), name + ".py")
        if not os.path.isfile(source):
            return None
        _installed_shims.add(name)
        return importlib.util.spec_from_file_location(
            name, source, loader=ShimLoader(name, source)
        )


def install_finder():
    """Put the shim finder on `sys.meta_path` once."""
    for finder in sys.meta_path:
        if isinstance(finder, ShimFinder):
            return False
    sys.meta_path.append(ShimFinder())
    return True


def forget_shims():
    """Drop every loaded shim, so the next import gets a pristine one.

    One interpreter means one module table: a caller that monkeypatches a shim
    would otherwise hand that patch to whoever imports it next. Removing the
    modules — and the names the shims staple onto builtins — puts the table
    back the way a fresh interpreter has it.
    """
    import builtins

    for name in sorted(_installed_shims):
        module = sys.modules.get(name)
        for loaded in [n for n in sys.modules if n == name or n.startswith(name + ".")]:
            del sys.modules[loaded]
        if module is not None and getattr(builtins, name, None) is module:
            delattr(builtins, name)
    _installed_shims.clear()


def install_shim(name):
    """Load the shim `name` so `import <name>` works in this interpreter.

    A shim source installs ITSELF: it publishes a module into `sys.modules` and
    staples it onto builtins when executed. So the work here is loading the file
    through the import machinery — under a private module name, because a shim
    like `sqlite3` or `pytest` must not shadow the stdlib module it wraps — and
    letting the file do the rest. Returns the loaded source path.
    """
    install_finder()
    _installed_shims.add(name)
    path = os.path.join(shim_root(), name + ".py")
    if not os.path.isfile(path):
        raise ImportError("no shim source at %r" % (path,))
    spec = importlib.util.spec_from_file_location("vis_shim_" + name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return path


def sys_path_snapshot():
    """The interpreter's current import roots, for error messages."""
    import sys

    return list(sys.path)
