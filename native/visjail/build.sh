#!/usr/bin/env bash
# Build the process-jail cdylib for this machine into its platform runtime.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

case "$(uname -s)" in
  Darwin) os=darwin; lib=libvisjail.dylib ;;
  Linux) os=linux; lib=libvisjail.so ;;
  *) echo "visjail does not support $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64) arch=x64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

out="$repo/resources/prebuilds/$os-$arch"
mkdir -p "$out/licenses"

if [ "$os" = darwin ]; then
  [ "$arch" = x64 ] && cc_arch="-arch x86_64" || cc_arch="-arch arm64"
  cc $cc_arch -O2 -fPIC -dynamiclib -Wall -Wextra -Werror -Wno-deprecated-declarations \
     -o "$out/$lib" "$here/visjail.c" -lsandbox -lutil
  echo "$out/$lib"
  exit 0
fi

for command in cc curl make meson ninja pkg-config python3 readelf sha256sum tar; do
  command -v "$command" >/dev/null || {
    echo "missing build command: $command (install a C toolchain, Meson, Ninja, pkg-config, Linux headers and binutils)" >&2
    exit 1
  }
done

# shellcheck disable=SC1091
source "$repo/.bubblewrap-version"
: "${BUBBLEWRAP_VERSION:?missing from .bubblewrap-version}"
: "${BUBBLEWRAP_SHA256:?missing from .bubblewrap-version}"
: "${LIBCAP_VERSION:?missing from .bubblewrap-version}"
: "${LIBCAP_SHA256:?missing from .bubblewrap-version}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

cap_archive="libcap-$LIBCAP_VERSION.tar.xz"
curl -fsSLO "https://cdn.kernel.org/pub/linux/libs/security/linux-privs/libcap2/$cap_archive"
echo "$LIBCAP_SHA256  $cap_archive" | sha256sum -c -
tar -xJf "$cap_archive"
cap="$work/libcap-$LIBCAP_VERSION"
make -C "$cap/libcap" -j"${VIS_BUILD_JOBS:-2}" \
  CC=cc BUILD_CC=cc CFLAGS="-O2 -fPIC" PAM_CAP=no GOLANG=no DYNAMIC=no lib=lib

mkdir -p "$work/pkg"
cat >"$work/pkg/libcap.pc" <<EOF
prefix=$cap
libdir=\${prefix}/libcap
includedir=\${prefix}/libcap/include
Name: libcap
Description: Linux capabilities library
Version: $LIBCAP_VERSION
Libs: \${libdir}/libcap.a
Cflags: -I\${includedir}
EOF

bwrap_archive="bubblewrap-$BUBBLEWRAP_VERSION.tar.gz"
curl -fsSL -o "$bwrap_archive" \
  "https://github.com/containers/bubblewrap/archive/refs/tags/v$BUBBLEWRAP_VERSION.tar.gz"
echo "$BUBBLEWRAP_SHA256  $bwrap_archive" | sha256sum -c -
tar -xzf "$bwrap_archive"
bwrap="$work/bubblewrap-$BUBBLEWRAP_VERSION"

# Bubblewrap is an executable upstream. Compile the same sources into this
# library with its main renamed; visjail forks once, and only the child enters
# that function, so every spawn begins with a pristine copy of its globals.
python3 - "$bwrap/meson.build" "$here/visjail.c" "$here/visjail.h" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
needle = "manpages_xsl ="
target = """
shared_library(
  'visjail',
  [
    'bubblewrap.c',
    'bind-mount.c',
    'network.c',
    'utils.c',
    'chroot_realpath.c',
    'safe_openat.c',
    %r,
  ],
  c_args : ['-Dmain=vis_bwrap_main', '-include', %r],
  dependencies : [selinux_dep, libcap_dep],
  install : false,
)

""" % (sys.argv[2], sys.argv[3])
if needle not in text:
    raise SystemExit("bubblewrap meson layout changed")
path.write_text(text.replace(needle, target + needle, 1))
PY

PKG_CONFIG_LIBDIR="$work/pkg" CC=cc meson setup "$bwrap/_build" "$bwrap" \
  --buildtype=release \
  -Dtests=false \
  -Dman=disabled \
  -Dselinux=disabled \
  -Db_staticpic=true
meson compile -C "$bwrap/_build"
install -Dm755 "$bwrap/_build/libvisjail.so" "$out/$lib"
cp "$bwrap/COPYING" "$out/licenses/bubblewrap-LGPL-2.1-or-later.txt"
cp "$cap/License" "$out/licenses/libcap-license.txt"

# libcap is part of the cdylib. libc + the loader are the only host ABI; a
# deployment never needs a bubblewrap or libcap package.
if readelf -dW "$out/$lib" | grep -q 'libcap'; then
  echo "libvisjail unexpectedly depends on a system libcap" >&2
  exit 1
fi
nm -D "$out/$lib" | grep -q ' visjail_spawn$'
echo "$out/$lib"
