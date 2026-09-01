#!/usr/bin/env bash
# Build libvispython for THIS machine into resources/prebuilds/<platform>/.
#
# The prebuild directory is the shipping unit: the cdylib AND the vendored
# CPython tree it was linked against, side by side. `resolve-python-home` finds
# the interpreter by that adjacency, so an installation carries its own standard
# library instead of resolving one from the machine — which is the whole reason
# a laptop with no Python, or with the wrong Python, still runs the sandbox.
#
# The tree comes from the pin in `.cpython-version` (astral-sh/python-build-standalone).
# Set VIS_PYTHON_SYSTEM=1 to link against whatever `python3-config` reports
# instead: a fast dev loop, and NOT shippable, because the artifact then depends
# on that installation.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

case "$(uname -s)" in
  Darwin) os=darwin; lib=libvispython.dylib; rpath='@loader_path/python/lib' ;;
  Linux)  os=linux;  lib=libvispython.so;    rpath='$ORIGIN/python/lib' ;;
  *) echo "unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

out="$repo/resources/prebuilds/$os-$arch"
mkdir -p "$out"

if [ "${VIS_PYTHON_SYSTEM:-0}" = "1" ]; then
  pycfg="${PYTHON_CONFIG:-python3-config}"
  # shellcheck disable=SC2046
  cc -O2 -fPIC -shared -Wall -Wextra \
     $($pycfg --includes) \
     -o "$out/$lib" \
     "$here/vispython.c" \
     $($pycfg --ldflags --embed)
  echo "$out/$lib"
  exit 0
fi

# shellcheck disable=SC1091
source "$repo/.cpython-version"
: "${CPYTHON_RELEASE:?missing from .cpython-version}"
: "${CPYTHON_VERSION:?missing from .cpython-version}"

case "$os-$arch" in
  darwin-arm64) triple=aarch64-apple-darwin ;;
  darwin-x64)   triple=x86_64-apple-darwin ;;
  linux-arm64)  triple=aarch64-unknown-linux-gnu ;;
  linux-x64)    triple=x86_64-unknown-linux-gnu ;;
  *) echo "no upstream CPython build for $os-$arch" >&2; exit 1 ;;
esac

home="$out/python"
if [ ! -d "$home" ]; then
  tarball="cpython-$CPYTHON_VERSION%2B$CPYTHON_RELEASE-$triple-install_only_stripped.tar.gz"
  url="https://github.com/astral-sh/python-build-standalone/releases/download/$CPYTHON_RELEASE/$tarball"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "vendoring CPython $CPYTHON_VERSION ($CPYTHON_RELEASE) for $triple" >&2
  curl -fsSL -o "$tmp/python.tar.gz" "$url"
  tar -xzf "$tmp/python.tar.gz" -C "$tmp"
  mv "$tmp/python" "$home"
fi

minor="${CPYTHON_VERSION%.*}"

# The shipped packages. `packages/base.txt` pins transitive dependencies too, so
# what the artifact contains does not depend on the day it was built, and
# `--only-binary` keeps a build from compiling a wheel it should have downloaded.
# `tests/` inside a redistributed package is dead weight the sandbox never runs
# (measured on darwin-arm64: 21 MB of site-packages with them, 15 MB without).
"$home/bin/python3" -m pip install \
  --no-cache-dir --only-binary=:all: --disable-pip-version-check --quiet \
  -r "$repo/packages/base.txt"
find "$home/lib/python$minor/site-packages" -type d -name tests -prune -exec rm -rf {} +
cc -O2 -fPIC -shared -Wall -Wextra \
   -I"$home/include/python$minor" \
   -o "$out/$lib" \
   "$here/vispython.c" \
   -L"$home/lib" -lpython"$minor" \
   -Wl,-rpath,"$rpath"

echo "$out/$lib"
