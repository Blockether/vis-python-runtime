#!/usr/bin/env bash
# Build libvispython for THIS machine into resources/prebuilds/<platform>/.
#
# Phase 1 links against the local CPython that `python3-config` reports, which
# makes the shared library depend on that install — fine for development, not
# for a release. A shippable artifact links a static libpython (Phase 3 of
# PLAN.md); the C source and the JVM binding do not change when it does.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
pycfg="${PYTHON_CONFIG:-python3-config}"

case "$(uname -s)" in
  Darwin) os=darwin; lib=libvispython.dylib ;;
  Linux)  os=linux;  lib=libvispython.so ;;
  *) echo "unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

out="$repo/resources/prebuilds/$os-$arch"
mkdir -p "$out"

# shellcheck disable=SC2046
cc -O2 -fPIC -shared -Wall -Wextra \
   $($pycfg --includes) \
   -o "$out/$lib" \
   "$here/vispython.c" \
   $($pycfg --ldflags --embed)

echo "$out/$lib"
