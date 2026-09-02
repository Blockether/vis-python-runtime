#!/usr/bin/env bash
# Verify one release archive after unpacking it, including native execute bits.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
platform="${1:?usage: verify-platform-archive.sh <platform>}"
version="$(tr -d '\r\n' < "$repo/VIS_PYTHON_VERSION")"
archive="$repo/target/vis-python-runtime-$platform-$version.tar.gz"
unpacked="$repo/target/archive-check-$platform"

case "$platform" in
  linux-x64|linux-arm64) lib=libvispython.so ;;
  darwin-arm64|darwin-x64) lib=libvispython.dylib ;;
  *) echo "unsupported platform: $platform" >&2; exit 2 ;;
esac

test -f "$archive"
rm -rf "$unpacked"
mkdir -p "$unpacked"
tar -xzf "$archive" -C "$unpacked"
test -f "$unpacked/$lib"
test -d "$unpacked/python"

if [[ "$platform" == linux-* ]]; then
  test -x "$unpacked/bin/bwrap"
  test -f "$unpacked/licenses/bubblewrap-LGPL-2.1-or-later.txt"
  test -f "$unpacked/licenses/libcap-license.txt"
  test -f "$unpacked/licenses/musl-copyright.txt"
  ! readelf -lW "$unpacked/bin/bwrap" | grep -q ' INTERP '
  ! readelf -dW "$unpacked/bin/bwrap" 2>/dev/null | grep -q '(NEEDED)'
  "$unpacked/bin/bwrap" --version
fi

printf '%s\n' "$archive"
