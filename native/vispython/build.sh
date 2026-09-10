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
  Darwin) os=darwin; lib=libvispython.dylib; rpath='@loader_path/python/lib'; dl_lib= ;;
  Linux)  os=linux;  lib=libvispython.so;    rpath="\$ORIGIN/python/lib"; dl_lib=-ldl ;;
  *) echo "unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
if [ "$os" = darwin ]; then
  [ "$arch" = x64 ] && cc_arch=(-arch x86_64) || cc_arch=(-arch arm64)
else
  cc_arch=()
fi

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
  "$repo/native/visjail/build.sh"
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

# uv is an installer, like pip, not a shared user distribution. Stage only the
# pinned executable and its licenses; never bootstrap it from the operator's PATH.
# shellcheck disable=SC1091
source "$repo/.uv-version"
: "${UV_VERSION:?missing from .uv-version}"
if command -v sha256sum >/dev/null 2>&1; then
  sha256=(sha256sum)
else
  sha256=(shasum -a 256)
fi
case "$os-$arch" in
  darwin-arm64) uv_sha="$UV_SHA256_DARWIN_ARM64" ;;
  darwin-x64) uv_sha="$UV_SHA256_DARWIN_X64" ;;
  linux-arm64) uv_sha="$UV_SHA256_LINUX_ARM64" ;;
  linux-x64) uv_sha="$UV_SHA256_LINUX_X64" ;;
esac
uv_version=""
if [ -x "$home/bin/uv" ]; then
  uv_version="$("$home/bin/uv" --version)"
fi
if [ "${uv_version%% (*}" != "uv $UV_VERSION" ] ||
   [ ! -f "$out/licenses/uv-LICENSE-MIT" ] || [ ! -f "$out/licenses/uv-LICENSE-APACHE" ]; then
  uv_tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}" "$uv_tmp"' EXIT
  uv_archive="uv-$triple.tar.gz"
  curl -fsSL -o "$uv_tmp/$uv_archive" \
    "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/$uv_archive"
  (cd "$uv_tmp" && printf '%s  %s\n' "$uv_sha" "$uv_archive" | "${sha256[@]}" -c -)
  tar -xzf "$uv_tmp/$uv_archive" -C "$uv_tmp"
  for kind in MIT APACHE; do
    curl -fsSL -o "$uv_tmp/LICENSE-$kind" \
      "https://raw.githubusercontent.com/astral-sh/uv/$UV_VERSION/LICENSE-$kind"
  done
  (cd "$uv_tmp" && printf '%s  %s\n' \
    "$UV_SHA256_LICENSE_MIT" LICENSE-MIT "$UV_SHA256_LICENSE_APACHE" LICENSE-APACHE | "${sha256[@]}" -c -)
  mkdir -p "$out/licenses"
  cp "$uv_tmp/LICENSE-APACHE" "$out/licenses/uv-LICENSE-APACHE"
  cp "$uv_tmp/LICENSE-MIT" "$out/licenses/uv-LICENSE-MIT"
  install -m 755 "$uv_tmp/uv-$triple/uv" "$home/bin/uv"
fi

minor="${CPYTHON_VERSION%.*}"

# The artifact carries CPython and its installers (pip and uv). User-selected
# distributions live outside this tree and survive runtime upgrades.
# Bytecode is per-machine CACHE, not artifact weight: it nearly doubles the tree
# (measured on darwin-arm64: 11.8 MB of .pyc against 18.4 MB of stdlib source)
# and it is invalid the moment the tree moves. The interpreter starts with a
# `pycache_prefix` under the user's own directory, so the first run compiles what
# it imports, every run after that is cached, and the shipped tree never changes.
find "$home" -type d -name __pycache__ -prune -exec rm -rf {} +
cc "${cc_arch[@]}" -O2 -fPIC -shared -pthread -Wall -Wextra \
   -I"$home/include/python$minor" \
   -o "$out/$lib" \
   "$here/vispython.c" \
   -L"$home/lib" -lpython"$minor" $dl_lib -Wl,-rpath,"$rpath"

"$repo/native/visjail/build.sh"

echo "$out/$lib"
