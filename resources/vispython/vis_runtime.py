"""The sandbox runtime, the module CPython IMPORTS.

The host does not interpolate the runtime into a string and execute it. It puts
this directory on ``sys.path`` and imports, which is what an interpreter with a
real filesystem and a real import system is for: compilation is cached in
``__pycache__``, a traceback names a file and a line, and a session that only
needs part of the runtime pays for that part.

``install`` is the per-SESSION step. Starting the interpreter happens once per
process; a session is a namespace, and every session calls ``install`` on its
own globals to receive the runtime's public names.

The runtime body is ``resources/vis-python/async_runtime.py`` in this package
and reaches the interpreter as a source root; Vis' copy is hashed against it
(``sandbox-parity-test``) until Vis pins this library and deletes its own.
"""

import ast
import builtins
import contextlib
import gc
import importlib
import importlib.machinery
import importlib.util
import io
import json
import os
import sys

#: Module that carries the sandbox runtime. Overridable so a port can point at
#: the module in its new home without a code change.
SANDBOX_MODULE = os.environ.get("VIS_PYTHON_SANDBOX_MODULE", "async_runtime")

#: Module that staples the stdlib conveniences a block may use without importing
#: them (`json.dumps`, `Path`, `re`). It installs itself onto builtins when
#: executed, so importing it once per PROCESS equips every session.
AUTO_IMPORTS_MODULE = "auto_imports"

#: Compiled sandbox sources, kept once per process — see `module_code`.
_MODULE_CODE = {}


def _preinit_mimetypes():
    """Give `mimetypes` its built-in table, so nothing goes looking for /etc.

    `guess_type` initializes lazily by READING `mimetypes.knownfiles` -
    `/etc/mime.types`, `/etc/apache2/mime.types` and their siblings - and those
    lie outside every session's roots: a block asking for the type of a name it
    chose itself would be refused for a path it never mentioned. Emptying
    `knownfiles` first is what makes the initialization file-free, because
    `init(files)` READS the known files as well as the ones it is handed. The
    built-in table costs one dict, touches the filesystem not at all, and
    answers the same on every machine.
    """
    import mimetypes

    mimetypes.knownfiles = []
    if not mimetypes.inited:
        mimetypes.init([])


_preinit_mimetypes()

def module_code(module):
    """The compiled source of one sandbox module, located through the import system.

    The host never carries this source as a string: the import machinery finds
    the file on the source roots, and the compiled result is kept because every
    session pays for it. The module is NOT imported — a sandbox module is meant
    to be executed INTO a session's globals, not to become a module of its own.
    """
    code = _MODULE_CODE.get(module)
    if code is None:
        spec = importlib.util.find_spec(module)
        origin = None if spec is None else spec.origin
        if not origin or not os.path.isfile(origin):
            raise ImportError(
                f"sandbox module {module!r} not on {sys_path_snapshot()!r}"
            )
        with open(origin, encoding="utf-8") as handle:
            code = compile(handle.read(), origin, "exec")
        _MODULE_CODE[module] = code
    return code


def sandbox_code():
    """The compiled sandbox runtime — `module_code` for the runtime module."""
    return module_code(SANDBOX_MODULE)


def install_module(namespace, module):
    """Execute one further sandbox module INTO an already equipped session.

    This is how the host adds a part of the sandbox that is CONFIGURED by the
    session: `network_guard` reads `__vis_allowed_domains__` and
    `__vis_denied_domains__` out of the namespace as it runs. Answers the file
    the code came from, so a caller can prove WHICH source ran.
    """
    code = module_code(module)
    exec(code, namespace)
    return code.co_filename


def par(thunks):
    """Run `thunks` at the same time, answering their values IN ORDER.

    `gather` hands the runtime a list of zero-argument thunks and expects a list
    back; how they RUN is the embedder's, and the embedder is the C boundary.
    `_vis_host.par` dispatches on ONE pool of worker threads owned by the
    PROCESS, whose size, per-call quota and the hard cap on live threads are
    policy the host sets and no block can raise. A pool here would be a module
    global a session could resize or walk past, and a pool per session would
    multiply threads by sessions for nothing, because one interpreter has one
    GIL however many sessions share it.

    Threads are still the whole mechanism: a thunk waiting on a socket, a
    subprocess or a host call has released the GIL, which is what a sandbox
    block's concurrency is made of. A gather INSIDE a gather child runs
    sequentially, because the outer children are holding the pool.
    """
    import _vis_host

    return _vis_host.par(list(thunks))


def install(namespace, session=None):
    """Equip `namespace` with the sandbox runtime, IN its own globals.

    The runtime is EXECUTED here rather than copied in, because that is what it
    is written for: `__vis_run_async__` and every reaper read `globals()`, so a
    block must run against the SESSION's names, not against some module's. The
    state that has to outlive an install — pending writes, the descriptor table,
    the handle registry — is adopted from builtins by the runtime itself
    (`__vis_survivor__`), so a second session shares those process-wide tables
    instead of quietly resetting them.

    Also imports the auto-imports module, because a block writes
    `json.dumps(...)` without importing json.
    Two names the runtime READS but does not define arrive here, and only when
    the host has not bound its own: `__vis_par__`, the pool `gather` dispatches
    on, and `__vis_protected_names__`, the surface a block may not silently
    shadow — a `from asyncio import gather` rebinds nothing, and a top-level
    `def cat(...)` is refused, because those names are the sandbox's.

    `session` is the name the host knows this namespace by, kept as
    `__vis_session__` so every host call made from it can say WHOSE it is: one
    interpreter holds many sessions, and a tool bound in two of them is two
    different host functions.

    Returns how many names the session ended up with, so the host can assert it
    is equipped instead of guessing.
    """
    importlib.import_module(AUTO_IMPORTS_MODULE)
    exec(sandbox_code(), namespace)
    namespace["__vis_session__"] = session
    namespace.setdefault("__vis_par__", par)
    namespace.setdefault(
        "__vis_protected_names__",
        sorted(name for name in namespace if not name.startswith("_")),
    )
    return len(namespace)


class VisToolError(RuntimeError):
    """What a HOST tool raised, as the guest sees it.

    The failure belongs to the host, not to the block: the message is the
    host's own, verbatim, and `vis_data` carries whatever the host attached to
    it. A host that reads its data back off this exception maps the failure to
    its own error shape instead of parsing a string it printed itself.
    """

    __slots__ = ("vis_data",)

    def __init__(self, message, data=None):
        super().__init__(message)
        self.vis_data = data


def host_call(name, payload):
    """Call the host callable `name` with `payload`, answering its reply text.

    The one door back out of the sandbox. Text crosses in both directions
    because the C ABI carries bytes and nothing else; what the text MEANS is
    agreed between this module and the host, never by the boundary. A host that
    failed arrives as `RuntimeError` carrying the host's own message.
    """
    import _vis_host

    return _vis_host.call(name, payload)


def _tool_arg(value):
    """One argument JSON cannot carry, as text the host can use.

    A path the model spelled as an OBJECT is still a path: every `os.PathLike`
    crosses as its FILESYSTEM string, not as a repr — `cat(root / "q.clj")` used
    to refuse with `File not found: PosixPath('/…/q.clj')`. `pathlib.Path` is
    only the common case; the duck-type is `__fspath__`. Anything else — and a
    path-like whose `__fspath__` raises, which is not a path — crosses as its
    `str`, the honest limit of a text boundary.
    """
    if isinstance(value, (bytes, bytearray)):
        return str(value)
    try:
        text = os.fspath(value)
    except Exception:
        return str(value)
    return text if isinstance(text, str) else str(value)

def _host_tool(name, session=None):
    """The guest half of the host tool `name`: JSON out, JSON back.

    Arguments travel as `{"args": [...]}` and the reply is `{"value": ...}` or
    `{"error": "..."}`. Keyword arguments fold into a TRAILING DICT the way the
    sandbox's own call path folds them (`__vis_Call__`), because a vis tool takes
    a trailing options map — `find("x", paths=[...])` is one convention on both
    sides of the boundary, not two. A value JSON cannot carry reaches the host as
    its `str`, which is the honest limit of a text boundary: a tool takes data,
    and an open socket was never data.

    The envelope also carries `session`, because the host binds a tool per
    session — the same name in two sessions is two functions, and the boundary
    is one.
    """

    def call(*args, **kwargs):
        params = list(args) + ([dict(kwargs)] if kwargs else [])
        payload = json.dumps({"session": session, "args": params}, default=_tool_arg)
        reply = json.loads(host_call(name, payload))
        if "error" in reply:
            raise VisToolError(reply["error"], reply.get("error_data"))
        return reply.get("value")

    call.__name__ = name
    return call


def install_tool(namespace, name):
    """Bind the host tool `name` into `namespace`, answering the name bound.

    A tool is not an ordinary function: `__vis_deferred__` wraps it, so calling
    one hands back a thunk — the seam `await`, `gather` and top-level
    auto-settle are built on. The name joins `__vis_protected_names__` too,
    because a bound tool is precisely a name a block may not shadow with an
    import or a top-level `def`.
    """
    deferred = namespace.get("__vis_deferred__")
    if deferred is None:
        raise RuntimeError("install(namespace) has to run before a tool is bound")
    namespace[name] = deferred(_host_tool(name, namespace.get("__vis_session__")), name)
    namespace["__vis_protected_names__"] = sorted(
        set(namespace.get("__vis_protected_names__") or ()) | {name}
    )
    return name


def install_sync_tool(namespace, name):
    """Bind the host tool `name` into `namespace` as an ORDINARY function.

    The sandbox's tools are deferred thunks: a block awaits them, gathers them
    and settles whatever it left lying around. Trusted host-side Python has no
    such runner — it calls a tool the way it calls any function and expects the
    ANSWER, not a thunk — so this is the same boundary without the deferral.
    """
    if "__vis_session__" not in namespace:
        raise RuntimeError("install(namespace) has to run before a tool is bound")
    namespace[name] = _host_tool(name, namespace.get("__vis_session__"))
    return name
def set_stdin(text):
    """Point the interpreter's ``sys.stdin`` at `text`, or restore the real one.

    A guest that calls ``input()`` reads descriptor 0, and in an embedded
    interpreter that descriptor belongs to the HOST — a terminal nobody is
    typing into, or a pipe nobody writes. The read never returns, and because
    every session's Python runs on the one runtime thread, one such block
    freezes the whole process. Measured, not theorised.

    So the host states what the guest's stdin IS. `text` is what it reads
    before EOF: ``""`` is a stream that is simply empty, which turns a stray
    ``input()`` into ``EOFError`` instead of a hang. ``None`` restores the
    process's own stdin, for the one caller that genuinely owns it — the
    human running the CLI. The stream is a real ``TextIOWrapper``, so
    ``sys.stdin.buffer`` reads bytes exactly as it does in ``python3``.
    """
    if text is None:
        sys.stdin = sys.__stdin__
    else:
        sys.stdin = io.TextIOWrapper(
            io.BytesIO(text.encode("utf-8")), encoding="utf-8"
        )
    return True

def run(source, namespace):
    """Execute `source` in `namespace`, answering the trailing expression's value.

    This is the sandbox's execution semantics, and it belongs in Python because
    it is `ast` work: statements execute, and if the last statement is an
    expression its value comes back instead of `None`. The host hands the source
    over as a string object, so nothing is interpolated into code here.

    The answer is SETTLED when the namespace carries `__vis_settle__`: a tool
    bound as a deferred thunk answers a thunk, a block's runner settles whatever
    the block left lying around, and one expression evaluated here is that same
    shape — so the caller gets the tool's answer, never its deferral.
    """
    settle = namespace.get("__vis_settle__")
    tree = ast.parse(source)
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        tail = ast.Expression(tree.body.pop().value)
        if tree.body:
            exec(compile(tree, "<vis>", "exec"), namespace)
        value = eval(compile(tail, "<vis>", "eval"), namespace)
        return settle(value) if settle is not None else value
    exec(compile(tree, "<vis>", "exec"), namespace)
    return None


def _json_default(value):
    """What JSON has no shape for: a sequence is an array, everything else text."""
    if isinstance(value, (set, frozenset, tuple)):
        return list(value)
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)


def to_json(value):
    """Render a Python value as JSON — the one data wire back to the host.

    The C ABI carries strings and the boundary already speaks JSON the other
    way (`host_call`), so ONE dialect crosses in both directions and the host
    reads it with the JSON reader it already has. `None`/booleans/numbers/
    strings, arrays for sequences and sets, objects for dicts. Anything JSON
    cannot carry — an object, a function, a raster handle, a NaN — crosses as
    its `str`, because a test asserting on one asserts on what a human sees.
    """
    try:
        return json.dumps(value, default=_json_default, allow_nan=False)
    except (TypeError, ValueError):
        return json.dumps(str(value))


def reset_handles():
    """Empty the handle registry, which no session owns.

    The registries survive an install by design (`__vis_survivor__` keeps them
    on builtins), so in ONE interpreter they outlive a session too: a test that
    wants to assert on what a block freed must start from an empty table, or it
    inherits whatever an earlier block left pinned.
    """
    import builtins

    for name in ("__vis_handles__", "__vis_handle_freers__"):
        table = getattr(builtins, name, None)
        if table is not None:
            table.clear()
    state = getattr(builtins, "__vis_handle_state__", None)
    if state is not None:
        for key in ("live_bytes", "new_bytes", "new_owners"):
            if key in state:
                state[key] = 0
        for key in ("sweeping", "owned_since_sweep"):
            if key in state:
                state[key] = False


def rewrite_imports(source, namespace):
    """Rewrite the block's imports the way the sandbox needs them.

    `import asyncio` binds the runtime's own shim, and `from asyncio import
    gather` binds nothing, because the sandbox's `gather` is the one that knows
    about the pool. The rewrite is the runtime's own
    `__vis_strip_protected_imports__`, and it happens HERE rather than in the
    host: running a block is what needs it, so a host that calls `run_block`
    gets it without owning a second copy of the rule.

    A source the rewrite cannot parse comes back untouched — the block is about
    to raise that same SyntaxError with its own line numbers, which is the
    message worth showing.
    """
    stripper = namespace.get("__vis_strip_protected_imports__")
    if stripper is None:
        return source
    try:
        return stripper(source)
    except BaseException:
        return source


def run_block(source, namespace):
    """Run `source` the way `python_execution` runs a BLOCK.

    A block has ONE success channel — what it PRINTED — so what comes back is
    the captured stdout, plus the error text when it raised. The boundary is
    real: the reapers run after it, which is where the handle registry earns its
    keep, and a block that leaves a handle unreachable pays for it HERE and not
    in whichever unrelated block allocates next.
    """
    runner = namespace.get("__vis_run_async__")
    if runner is None:
        # `globals().clear()` is legal Python and a block is allowed to run it,
        # so a session can arrive here stripped of the runtime it was equipped
        # with. Re-install rather than refuse: the state that matters — pending
        # writes, the descriptor table, the handle registry — is adopted back
        # off builtins, so the session keeps what it was still holding.
        install(namespace)
        runner = namespace.get("__vis_run_async__")
    if runner is None:
        raise RuntimeError("session is not equipped: install(namespace) first")
    source = rewrite_imports(source, namespace)
    stream = io.StringIO()
    error = None
    with contextlib.redirect_stdout(stream):
        try:
            runner(source)
        except BaseException as exc:
            error = f"{type(exc).__name__}: {exc}" if str(exc) else type(exc).__name__
    reapers = namespace.get("__vis_run_reapers__")
    if reapers is not None:
        reapers()
    return {"stdout": stream.getvalue(), "error": error}


def close_session(name):
    """Drop the session `name`, freeing what only it still held.

    A session is a module in `sys.modules` — that is where it has to be while
    the host is using it, and where it must NOT stay afterwards. CPython frees a
    file, a socket or a host handle when the last reference to it dies, and the
    last reference is usually the session's own globals: a process that never
    drops a finished session holds every descriptor every block ever leaked.

    The namespace is NOT cleared. Every function a block defined holds it as
    `__globals__`, so a finished session is a reference CYCLE — which is what the
    collector is for, and CPython's runs here. Clearing was the answer to an
    interpreter that did not refcount, and it is WRONG here: a session installs
    doors the whole PROCESS uses (`builtins.open`, the socket guard), and a
    function whose globals were cleared answers `NameError` for every session
    after it — measured. What is still referenced stays alive because something is
    still using it; the rest is freed. Answers whether there was such a session.
    """
    module = sys.modules.pop(name, None)
    if module is None:
        return False
    del module
    gc.collect()
    reclaim = getattr(builtins, "__vis_reclaim_fds__", None)
    if reclaim is not None:
        reclaim(True)
    return True


def run_block_json(source, namespace):
    """`run_block` rendered as JSON. What the C ABI calls."""
    return to_json(run_block(source, namespace))


def run_json(source, namespace):
    """`run` the source and render the value as JSON. What the C ABI calls."""
    return to_json(run(source, namespace))


def sys_path_snapshot():
    """The interpreter's current import roots, for error messages."""
    import sys

    return list(sys.path)
