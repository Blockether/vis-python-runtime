"""Process-start TLS compatibility policy for embedded Python.

VIS_PYTHON_TLS_STRICT defaults to true. False removes only VERIFY_X509_STRICT
from assignments to ssl.SSLContext.verify_flags, including stdlib and library
factories. Certificate trust, hostname checking and every other flag are unchanged.
This is not a security boundary: callers still own their SSL contexts. Libraries
using another TLS implementation and external processes are unaffected.
"""

import os
import ssl


def _configure():
    strict = os.environ.get("VIS_PYTHON_TLS_STRICT", "true")
    if strict == "true":
        return
    if strict != "false":
        raise ValueError("VIS_PYTHON_TLS_STRICT must be true or false")

    flags = ssl.SSLContext.verify_flags

    def set_flags(context, value):
        flags.fset(context, value & ~int(ssl.VERIFY_X509_STRICT))

    ssl.SSLContext.verify_flags = flags.setter(set_flags)


_configure()
