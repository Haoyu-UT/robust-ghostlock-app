#!/bin/sh
# Build sgpatch.ko for one kernel Image.
#
#   sh build.sh <ack-tree> <image-config> <localversion> <outdir>
#
# <ack-tree>       the ACK/common tree at the Image's commit (one per line)
# <image-config>   the Image's own .config (the device's /proc/config.gz)
# <localversion>   the full release suffix, e.g.
#                  -android15-8-g29d86c5fc9dd-abogki428889875-4k
# <outdir>         receives sgpatch.ko (and the build tree under build/)
#
# The config's own CONFIG_LOCALVERSION is emptied (the environment wins) and
# LOCALVERSION_AUTO is turned off, so the vermagic is exactly <localversion>.
# MODULE_SIG stays on (its fields are part of struct module) but SIGN_ALL is
# off, because the loader here enforces neither signatures nor vermagic and
# we have no signing key.
set -eu

[ $# -eq 4 ] || { echo "usage: $0 <ack-tree> <image-config> <localversion> <outdir>" >&2; exit 2; }
TREE=$1
CONFIG=$2
LOCALVERSION_VALUE=$3
OUT=$4

[ -d "$TREE" ] || { echo "not a tree: $TREE" >&2; exit 2; }
[ -f "$CONFIG" ] || { echo "no config: $CONFIG" >&2; exit 2; }
TREE=$(cd "$TREE" && pwd)
CONFIG=$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SRC=$(cd "$(dirname "$0")" && pwd)
BUILD="$OUT/build"
mkdir -p "$BUILD"
cp "$CONFIG" "$BUILD/.config"

sed -i \
    -e 's/^CONFIG_LOCALVERSION=.*/CONFIG_LOCALVERSION=""/' \
    -e 's/^CONFIG_LOCALVERSION_AUTO=.*/# CONFIG_LOCALVERSION_AUTO is not set/' \
    -e 's/^CONFIG_MODULE_SIG_ALL=.*/# CONFIG_MODULE_SIG_ALL is not set/' \
    -e 's/^CONFIG_WERROR=.*/# CONFIG_WERROR is not set/' \
    "$BUILD/.config"

export LOCALVERSION="$LOCALVERSION_VALUE"
export PATH="$HOME/Android/ondk/r30.1/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

# The Makefile assigns PAHOLE unconditionally, so it must come on the command
# line. Its version gates CONFIG_DEBUG_INFO_BTF through Kconfig; the option is
# part of struct module's layout (btf_data_size/btf_data) and must match the
# target kernel. No real BTF is generated for an out-of-tree module anyway
# (the step skips without vmlinux).
PAHOLE_BIN="${PAHOLE:-pahole}"

make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 PAHOLE="$PAHOLE_BIN" olddefconfig
make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 PAHOLE="$PAHOLE_BIN" modules_prepare
# No Module.symvers (the tree is only prepared, never linked): modpost cannot
# check the imports by name, so let it warn. Every import is EXPORT_SYMBOL and
# resolves at load; the empty __versions makes the loader warn about CRCs.
# The target's struct module has the BTF fields (btf_data_size/btf_data), but
# enabling CONFIG_DEBUG_INFO_BTF in Kconfig drags in tools/bpf/resolve_btfids
# (libelf headers) and real BTF generation that an out-of-tree module does not
# need. Define the guard for the compile instead: KCFLAGS reaches the generated
# .mod.c, which is what lays out `.gnu.linkonce.this_module`; ccflags-y does
# not. Without it the layout is 16 bytes short and the loader writes past our
# struct (mod_sysfs_setup oops).
KBUILD_MODPOST_WARN=1 KCFLAGS="-DCONFIG_DEBUG_INFO_BTF_MODULES" \
make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 PAHOLE="$PAHOLE_BIN" M="$SRC" modules

cp "$SRC/sgpatch.ko" "$OUT/sgpatch.ko"
llvm-strip --strip-debug -o "$OUT/sgpatch-stripped.ko" "$OUT/sgpatch.ko" 2>/dev/null || true
modinfo "$OUT/sgpatch.ko" | grep -E '^(filename|vermagic|name):'
