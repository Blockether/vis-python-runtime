#!/usr/bin/env bash
# Build the private Linux jail enforcer into this platform's release tree.
# The result is a static musl binary: it borrows no host libc or libcap and
# therefore runs on distributions older or newer than the build machine.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

[ "$(uname -s)" = Linux ] || { echo "bubblewrap is Linux-only" >&2; exit 1; }
case "$(uname -m)" in
  x86_64|amd64) arch=x64; kernel_asm=x86_64-linux-gnu ;;
  arm64|aarch64) arch=arm64; kernel_asm=aarch64-linux-gnu ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

for command in curl make meson musl-gcc ninja pkg-config readelf sha256sum strip tar; do
  command -v "$command" >/dev/null || {
    echo "missing build command: $command (install musl-tools, Meson, Ninja, pkg-config, make and binutils)" >&2
    exit 1
  }
done

# shellcheck disable=SC1091
source "$repo/.bubblewrap-version"
: "${BUBBLEWRAP_VERSION:?missing from .bubblewrap-version}"
: "${BUBBLEWRAP_SHA256:?missing from .bubblewrap-version}"
: "${LIBCAP_VERSION:?missing from .bubblewrap-version}"
: "${LIBCAP_SHA256:?missing from .bubblewrap-version}"

out="$repo/resources/prebuilds/linux-$arch"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

cap_archive="libcap-$LIBCAP_VERSION.tar.xz"
curl -fsSLO "https://cdn.kernel.org/pub/linux/libs/security/linux-privs/libcap2/$cap_archive"
echo "$LIBCAP_SHA256  $cap_archive" | sha256sum -c -
tar -xJf "$cap_archive"
cap="$work/libcap-$LIBCAP_VERSION"
make -C "$cap/libcap" -j"${VIS_BUILD_JOBS:-2}" \
  CC=musl-gcc BUILD_CC=cc PAM_CAP=no GOLANG=no DYNAMIC=no lib=lib

# musl-gcc intentionally does not search the host's glibc headers. Bubblewrap
# still needs Linux UAPI declarations, so copy only those neutral headers into
# an isolated include root rather than adding /usr/include to the search path.
headers="$work/kernel-headers"
mkdir -p "$headers/asm" "$work/pkg"
cp -R /usr/include/linux /usr/include/asm-generic "$headers/"
cp -R "/usr/include/$kernel_asm/asm/." "$headers/asm/"
cat >"$work/pkg/libcap.pc" <<EOF
prefix=$cap
libdir=\${prefix}/libcap
includedir=\${prefix}/libcap/include
Name: libcap
Description: Linux capabilities library
Version: $LIBCAP_VERSION
Libs: \${libdir}/libcap.a
Cflags: -I\${includedir} -I$headers
EOF

bwrap_archive="bubblewrap-$BUBBLEWRAP_VERSION.tar.gz"
curl -fsSL -o "$bwrap_archive" \
  "https://github.com/containers/bubblewrap/archive/refs/tags/v$BUBBLEWRAP_VERSION.tar.gz"
echo "$BUBBLEWRAP_SHA256  $bwrap_archive" | sha256sum -c -
tar -xzf "$bwrap_archive"
bwrap="$work/bubblewrap-$BUBBLEWRAP_VERSION"

PKG_CONFIG_LIBDIR="$work/pkg" \
  CFLAGS="-I$headers -include linux/limits.h" \
  CC=musl-gcc meson setup "$bwrap/_build" "$bwrap" \
  --buildtype=release \
  -Ddefault_library=static \
  -Dtests=false \
  -Dman=disabled \
  -Dselinux=disabled \
  -Db_staticpic=true \
  -Dc_link_args=-static
meson compile -C "$bwrap/_build"

install -Dm755 "$bwrap/_build/bwrap" "$out/bin/bwrap"
strip "$out/bin/bwrap"
mkdir -p "$out/licenses"
cp "$bwrap/COPYING" "$out/licenses/bubblewrap-LGPL-2.1-or-later.txt"
cp "$cap/License" "$out/licenses/libcap-license.txt"
cp /usr/share/doc/musl/copyright "$out/licenses/musl-copyright.txt"

# Presence is not enough: reject an ELF interpreter or dynamic dependency, then
# prove this kernel can execute the user and mount namespaces the jail needs.
if readelf -lW "$out/bin/bwrap" | grep -q " INTERP "; then
  echo "private bubblewrap unexpectedly has an ELF interpreter" >&2
  exit 1
fi
if readelf -dW "$out/bin/bwrap" 2>/dev/null | grep -q "(NEEDED)"; then
  echo "private bubblewrap unexpectedly has a dynamic dependency" >&2
  exit 1
fi
"$out/bin/bwrap" --unshare-all --ro-bind / / /bin/true
"$out/bin/bwrap" --version
echo "$out/bin/bwrap"
