"""Process-start TLS compatibility policy for embedded Python.

VIS_PYTHON_TLS_STRICT defaults to true. False removes only VERIFY_X509_STRICT
from assignments to ssl.SSLContext.verify_flags, including stdlib and library
factories. Certificate trust, hostname checking and every other flag are unchanged.
This is not a security boundary: callers still own their SSL contexts. Libraries
using another TLS implementation and external processes are unaffected.

A host that starts the interpreter in its own process cannot set the environment
first. It calls configure() after startup instead, before guest code makes contexts.
"""

import os
import ssl

_STRICT_FLAGS = ssl.SSLContext.verify_flags


def _lenient_flags(context, value):
    _STRICT_FLAGS.fset(context, value & ~int(ssl.VERIFY_X509_STRICT))


def configure(strict):
    """Apply the policy: True restores stdlib flags, False clears only STRICT.

    Existing contexts keep their flags. Contexts that receive flags later use the policy.
    """
    if not isinstance(strict, bool):
        raise TypeError("strict must be a bool")
    if strict:
        ssl.SSLContext.verify_flags = _STRICT_FLAGS
    else:
        ssl.SSLContext.verify_flags = _STRICT_FLAGS.setter(_lenient_flags)


def _configure():
    strict = os.environ.get("VIS_PYTHON_TLS_STRICT", "true")
    if strict not in ("true", "false"):
        raise ValueError("VIS_PYTHON_TLS_STRICT must be true or false")
    if strict == "false":
        configure(False)


_configure()
