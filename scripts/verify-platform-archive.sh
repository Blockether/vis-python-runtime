#!/usr/bin/env bash
# Verify one release archive after unpacking it, including native execute bits.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
platform="${1:?usage: verify-platform-archive.sh <platform>}"
version="$(tr -d '\r\n' < "$repo/VIS_PYTHON_VERSION")"
archive="$repo/target/vis-python-runtime-$platform-$version.tar.gz"
unpacked="$repo/target/archive-check-$platform"

case "$platform" in
  linux-x64|linux-arm64) python_lib=libvispython.so; jail_lib=libvisjail.so ;;
  darwin-arm64|darwin-x64) python_lib=libvispython.dylib; jail_lib=libvisjail.dylib ;;
  *) echo "unsupported platform: $platform" >&2; exit 2 ;;
esac
# The worker image ships wherever GraalVM CE builds: everywhere but darwin-x64
# (build.clj `worker-platforms`).
case "$platform" in
  darwin-x64) worker="" ;;
  *) worker=vis-python-worker ;;
esac

test -f "$archive"
rm -rf "$unpacked"
mkdir -p "$unpacked"
tar -xzf "$archive" -C "$unpacked"
test -f "$unpacked/$python_lib"
test -f "$unpacked/$jail_lib"
test -d "$unpacked/python"

if [[ "$platform" == linux-* ]]; then
  test -f "$unpacked/licenses/bubblewrap-LGPL-2.1-or-later.txt"
  test -f "$unpacked/licenses/libcap-license.txt"
  ! readelf -dW "$unpacked/$jail_lib" | grep -q 'libcap'
  nm -D "$unpacked/$jail_lib" | grep -q ' visjail_spawn$'
else
  nm -gU "$unpacked/$jail_lib" | grep -q '_visjail_spawn$'
fi

if [[ -n "$worker" ]]; then
  test -x "$unpacked/$worker"
  # A native image that starts, finds its VERSION resource and exits: the
  # cheapest proof the executable in the archive is the one this tag built.
  test "$("$unpacked/$worker" --version)" = "$version"
fi

printf '%s\n' "$archive"
