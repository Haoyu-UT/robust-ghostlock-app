#!/bin/sh
# Build the DirtyFrag SELinux switch module for one kernel Image.
#
#   sh build.sh <ack-tree> <image-config> <Module.symvers> <localversion> <outdir>
#
# <ack-tree>      a clean ACK/common tree at the Image's version (5.4.x)
# <image-config>  the Image's own IKCONFIG text (`scripts/extract-ikconfig Image`)
# <Module.symvers> the Image's export CRCs. Generate it from the Image itself
#                 (its __ksymtab/__kcrctab); the module imports module_layout,
#                 printk, register_kprobe, unregister_kprobe and
#                 __ubsan_handle_cfi_check_fail_abort. A wrong table is refused
#                 by the loader (`disagrees about version of symbol`).
# <localversion>  the release suffix, e.g. -qgki-gbb70cde46897
# <outdir>        receives dfswitch.ko (debug-stripped)
#
# Toolchain: ONDK r30.1's clang (21.0.0), the compiler the 5.4 Images' vendor
# modules were built with. Source env.sh or set ANDROID_NDK_HOME.
set -eu

if [ $# -ne 5 ]; then
    echo "usage: $0 <ack-tree> <image-config> <Module.symvers> <localversion> <outdir>" >&2
    exit 2
fi

TREE=$1
CONFIG=$2
SYMVERS=$3
LOCALVERSION=$4
OUT=$5

: "${ANDROID_NDK_HOME:?source env.sh or set ANDROID_NDK_HOME}"
NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
PATH=$NDK_BIN:$PATH
export PATH

HERE=$(cd "$(dirname "$0")" && pwd)
BUILD=$OUT/build
SRC=$OUT/src
mkdir -p "$BUILD" "$SRC" "$OUT"
cp "$HERE/dfswitch.c" "$HERE/Makefile" "$SRC/"

cp "$CONFIG" "$BUILD/.config"
sed -i "s|^CONFIG_LOCALVERSION=.*|CONFIG_LOCALVERSION=\"$LOCALVERSION\"|" "$BUILD/.config"
grep -q '^# CONFIG_LOCALVERSION_AUTO is not set' "$BUILD/.config" ||
    echo '# CONFIG_LOCALVERSION_AUTO is not set' >>"$BUILD/.config"

make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    CROSS_COMPILE=aarch64-linux-gnu- olddefconfig modules_prepare
make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    CROSS_COMPILE=aarch64-linux-gnu- M="$SRC" \
    KBUILD_EXTRA_SYMBOLS="$SYMVERS" modules

llvm-strip --strip-debug -o "$OUT/dfswitch.ko" "$SRC/dfswitch.ko"
# The loader does not need the build id, but ld.lld's --build-id output is
# path-dependent: a rebuild from another directory is functionally identical
# with a different hash. Update the profile's asset_sha256 when regenerating.
modinfo "$OUT/dfswitch.ko" | grep -E '^(filename|vermagic|name):'
