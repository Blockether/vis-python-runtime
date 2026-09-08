"""Activate installed .pth files after host policy setup; refresh editable imports."""

import importlib
import importlib.machinery
import json
import os
from pathlib import Path
import site
import sys
from urllib.parse import unquote, urlsplit

_sites = {}
_roots = set()


def _under(path, roots):
    if not path:
        return False
    path = Path(os.path.realpath(path))
    return any(path.is_relative_to(root) for root in roots)


class _SourceLoader(importlib.machinery.SourceFileLoader):
    def get_code(self, fullname):
        # Editable code must not reuse timestamp/size pyc after a rapid source edit.
        return self.source_to_code(self.get_data(self.path), self.path)


class _EditableFinder:
    def find_spec(self, fullname, path=None, target=None):
        finders = tuple(sys.meta_path)
        for finder in finders[finders.index(self) + 1 :]:
            spec = finder.find_spec(fullname, path, target)
            if spec is not None:
                if isinstance(
                    spec.loader, importlib.machinery.SourceFileLoader
                ) and _under(spec.origin, _roots):
                    spec.loader = _SourceLoader(fullname, spec.origin)
                return spec
        return None


_finder = _EditableFinder()


def refresh(packages, *, reload=False):
    """Process package-site changes; on explicit reload discard editable module caches.

    Call only after the host has applied the session policy. .pth paths are import
    locations, never additional filesystem grants. Standard site semantics also
    support editable backends that install import hooks instead of path lines.
    """
    if packages is None:
        return
    packages = Path(packages)
    try:
        names = sorted(os.listdir(packages))
    except FileNotFoundError:
        names = []
    files = [packages / name for name in names if name.endswith(".pth")]
    files += [
        packages / name / "direct_url.json"
        for name in names
        if name.endswith(".dist-info")
        and (packages / name / "direct_url.json").is_file()
    ]
    stamp = tuple((str(p), p.read_bytes()) for p in files)
    previous = _sites.get(packages)
    old_roots = set(_roots)
    if previous is None or previous[0] != stamp:
        if previous is not None:
            for sequence, added in zip(
                (sys.path, sys.meta_path, sys.path_hooks), previous[1]
            ):
                sequence[:] = [item for item in sequence if item not in added]
        before = [list(sys.path), list(sys.meta_path), list(sys.path_hooks)]
        site.addsitedir(str(packages))
        added = [
            [item for item in sequence if item not in original]
            for sequence, original in zip(
                (sys.path, sys.meta_path, sys.path_hooks), before
            )
        ]
        roots = {
            Path(os.path.realpath(p))
            for p in added[0]
            if Path(p).is_dir() and Path(p) != packages
        }
        for name, body in stamp:
            if name.endswith("/direct_url.json"):
                try:
                    data = json.loads(body)
                    url = urlsplit(data.get("url", ""))
                    if (
                        data.get("dir_info", {}).get("editable")
                        and url.scheme == "file"
                    ):
                        roots.add(Path(os.path.realpath(unquote(url.path))))
                except (ValueError, TypeError):
                    continue
        _sites[packages] = (stamp, added, roots)
        _roots.clear()
        _roots.update(root for _, _, roots in _sites.values() for root in roots)
        importlib.invalidate_caches()
    if _roots and _finder not in sys.meta_path:
        sys.meta_path.insert(0, _finder)
    if reload:
        for name, module in list(sys.modules.items()):
            paths = [
                getattr(module, "__file__", None),
                *getattr(module, "__path__", ()),
            ]
            if any(_under(path, old_roots | _roots) for path in paths):
                sys.modules.pop(name, None)
                parent, _, child = name.rpartition(".")
                owner = sys.modules.get(parent)
                if owner is not None and getattr(owner, child, None) is module:
                    delattr(owner, child)
        importlib.invalidate_caches()
