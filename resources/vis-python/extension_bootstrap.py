"""Injector — build the `vis` module for ONE extension context.

The module BODY is `packages/vis-agent/src/vis/__init__.py`, the same file PyPI
ships as `vis-agent`. `internal.python-extensions/bootstrap-python` slurps it off
the classpath and prepends it here as `_vis_body`, so there is exactly one copy of
the extension API in the repository and the sandbox runs the code an author can
`pip install` and read.

This fragment does only what the package cannot do for itself: seed `_host` with
the host callables the engine installed into this session, exec the body into a
module dict of its own (so the extension file's globals stay clean), and register
it in `sys.modules` so `import vis` works.

`_host` is an OBJECT with one attribute per op — the shape `vis_contract.Host`
declares, and the same shape `vis._outside` builds when nobody seeded one — so a
host is a thing anyone can implement, not a dict literal only this file knows how
to spell. Every attribute below is an op in
`packages/vis-contract/resources/vis-contract/python-host.edn`, and
`python_host_test` fails when this object and that document disagree.
"""

# ── The host's handle on this extension's Python callables ───────────────────
#
# A callable cannot cross to Clojure: what crosses is JSON text. So every
# callable the registration carries is SEALED here — kept in this module under
# an id, and replaced in the payload by `{"__vis_callable__": id}` — and the
# host invokes it later by that id. The seal is recursive because a spec nests
# callables inside dicts and lists (a provider's `auth_fn`, a symbol's `fn`).

import json as _vis_json

_vis_callables = {}
_vis_call_seq = [0]


def __vis_seal__(value):
    """Answer `value` with every callable replaced by its id marker."""
    if callable(value):
        _vis_call_seq[0] += 1
        cid = "c%d" % _vis_call_seq[0]
        _vis_callables[cid] = value
        return {"__vis_callable__": cid}
    if isinstance(value, dict):
        return {str(k): __vis_seal__(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [__vis_seal__(v) for v in value]
    attrs = getattr(value, "__dict__", None)
    if isinstance(attrs, dict):
        return {
            "__vis_object__": type(value).__name__,
            "__vis_attrs__": {
                str(k): __vis_seal__(v)
                for k, v in attrs.items()
                if not str(k).startswith("_")
            },
        }
    return value


def __vis_registration__():
    """The sealed registration spec, or None when the file registered nothing."""
    reg = _vis_sys.modules["vis"].__dict__.get("_registration")
    return None if reg is None else __vis_seal__(reg["spec"])


def __vis_unseal_host__(value):
    """Answer `value` with every host-callback marker replaced by the host tool.

    The mirror of `__vis_seal__`: a Clojure fn cannot cross either, so the host
    binds it as an ordinary tool in this session and sends the name it bound.
    That name is a global here, which is what makes `printer('...')` — a
    provider's `auth(printer)` — an ordinary call for the extension author.
    """
    if isinstance(value, dict):
        if len(value) == 1 and "__vis_callback__" in value:
            return globals()[value["__vis_callback__"]]
        return {k: __vis_unseal_host__(v) for k, v in value.items()}
    if isinstance(value, list):
        return [__vis_unseal_host__(v) for v in value]
    return value


def __vis_call__(cid, args_json):
    """Invoke the sealed callable `cid` on JSON-decoded args, sealing the answer."""
    args = __vis_unseal_host__(_vis_json.loads(args_json))
    return __vis_seal__(_vis_callables[cid](*args))


import sys as _vis_sys, types as _vis_types


def __vis_member__(op):
    """Answer `op` with every Python callable in its arguments SEALED.

    A callable cannot cross to Clojure — JSON text crosses — so an argument
    that is one is kept here under an id and sent as its marker, exactly as a
    registration's callables are. `vis.ask` hands the host a validator this
    way, and the host calls it back by that id while the extension is parked
    inside the very call that passed it.
    """

    def call(*args):
        return op(*[__vis_seal__(a) for a in args])

    return call


# Every `__vis_host_*__` name the host injected becomes a member of `_host`,
# under that name with the marker trimmed: `__vis_host_jailed_shell__` is
# `_host.jailed_shell`. The list is NOT written out here on purpose — it lived in
# this file once, so a door added in the host meant a release of this library
# before the host could use it, for a name this file only ever passed through.
_vis_mod = _vis_types.ModuleType("vis")
_vis_mod.__dict__["_host"] = _vis_types.SimpleNamespace(
    **{
        _vis_name[len("__vis_host_") : -len("__")]: __vis_member__(_vis_door)
        for _vis_name, _vis_door in list(globals().items())
        if _vis_name.startswith("__vis_host_")
        and _vis_name.endswith("__")
        and callable(_vis_door)
    }
)
exec(compile(_vis_body, "vis/__init__.py", "exec"), _vis_mod.__dict__)
_vis_sys.modules["vis"] = _vis_mod
