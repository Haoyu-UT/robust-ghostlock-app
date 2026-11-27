#!/bin/sh
# Host harness for the shared handoff core: the attempt order, the readiness
# contract, the fallback, the single restore and the report. The manager
# bodies themselves are device-gated; here `handoff_magisk`, `ksu_ready` and
# `pidof` are stubs, while `handoff_ksu` runs for real against a stub ksud so
# the `late-load --kmi … --allow-shell` invocation and the already-loaded
# short-circuit are exercised.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
CORE="$HERE/handoff.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() {
    echo "FAIL: $*"
    FAILED=1
}

check_contains() {
    file=$1
    needle=$2
    grep -qF "$needle" "$file" || fail "expected '$needle' in $file"
}

# One case: $1 = name, $2 = target count, $3.. = wire entries. The stub state
# comes from the exported HARNESS_* variables.
run_case() {
    name=$1
    count=$2
    shift 2
    dir="$WORK/$name"
    mkdir -p "$dir"
    script="$dir/handoff.sh"
    cat "$CORE" >"$script"
    cat >>"$script" <<'EOF'
# --- host overrides -------------------------------------------------------
pidof() { [ -n "${HARNESS_MAGISKD:-}" ] && echo 4242; }
ksu_ready() { [ -f "${HARNESS_KSU_FLAG}" ]; }
handoff_magisk() { [ -n "${HARNESS_MAGISK_OK:-}" ]; }
handoff_run
EOF
    {
        echo "LOG='$dir/core.log'"
        echo "SAFE_MODE=0"
        echo "VRKO_SELINUX_BLOCK='auto'"
        echo "MODULE_PATH=''"
        echo "MODULE_KO=''"
        echo "HANDOFF_EXEC_DIR='$WORK/exec'"
        printf "TARGETS='"
        for entry in "$@"; do
            printf '%s\n' "$entry"
        done
        printf "'\n"
    } >"$script.conf"
    OUTPUT="$dir/stdout.txt"
    sh "$script" >"$OUTPUT" 2>&1
    RC=$?
}

# Stub ksud: records its arguments, prints the late-load usage the interface
# probe requires, answers boot-info with HARNESS_KSU_KMI, and marks readiness
# on the real late-load only. HARNESS_KSU_NO_INTERFACE=1 models a token-only
# candidate: --help exits 1 with no usage.
ksud_stub() {
    target=$1
    cat >"$target" <<'EOF'
#!/bin/sh
echo "$@" >>"$HARNESS_KSU_ARGS"
case "$1" in
late-load)
    case "$2" in
    --help)
        if [ "${HARNESS_KSU_NO_INTERFACE:-}" = 1 ]; then
            echo "ksud: unrecognized subcommand" >&2
            exit 1
        fi
        echo "Usage: ksud late-load --kmi <kmi> --allow-shell"
        exit 0
        ;;
    esac
    : >"$HARNESS_KSU_FLAG"
    ;;
boot-info)
    [ "${HARNESS_KSU_NO_INTERFACE:-}" = 1 ] && exit 1
    case "$2" in
    current-kmi) echo "${HARNESS_KSU_KMI:-android12-5.10}" ;;
    supported-kmis) echo "${HARNESS_KSU_KMI:-android12-5.10}" ;;
    esac
    ;;
esac
exit 0
EOF
    chmod 755 "$target"
}

ksud_stub "$WORK/ksud"
export HARNESS_KSU_ARGS="$WORK/ksud.args"
export HARNESS_KSU_FLAG="$WORK/ksud.flag"
# The overrides run in the generated script's child shell, so the stub state
# must be exported (not merely assigned for the function call).
export HARNESS_MAGISKD=""
export HARNESS_MAGISK_OK=""
export HARNESS_KSU_KMI="android12-5.10"
export HARNESS_KSU_NO_INTERFACE=""

# 1. Magisk first and ready: one attempt, no ksud invocation.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD=1 HARNESS_MAGISK_OK=1
run_case magisk_ready 1 \
    "magisk|com.topjohnwu.magisk|$WORK/magiskbin|"
[ "$RC" -eq 0 ] || fail "magisk_ready: exit $RC"
check_contains "$OUTPUT" "handoff-target=magisk idx=1"
check_contains "$OUTPUT" "HANDOFF-SUMMARY handoff=ok target=magisk idx=1"
[ -f "$HARNESS_KSU_ARGS" ] && fail "magisk_ready: ksud was invoked"

# 2. KSU first with its KMI and staged source.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case ksu_ready 1 \
    "ksu|com.resukisu.resukisu|$WORK/ksud|android12-5.10"
[ "$RC" -eq 0 ] || fail "ksu_ready: exit $RC"
check_contains "$OUTPUT" "handoff-target=ksu idx=1"
check_contains "$OUTPUT" "HANDOFF-SUMMARY handoff=ok target=ksu idx=1"
check_contains "$HARNESS_KSU_ARGS" "late-load --help"
check_contains "$HARNESS_KSU_ARGS" "boot-info current-kmi"
check_contains "$HARNESS_KSU_ARGS" "late-load --kmi android12-5.10 --allow-shell"
check_contains "$WORK/ksu_ready/core.log" "ksud exec copy: $WORK/exec/ghostlock-ksud-"
check_contains "$WORK/ksu_ready/core.log" "late-load interface confirmed (rc=0 --kmi=1 --allow-shell=1)"

# 3. Magisk attempted and not ready, then the KSU fallback carries it.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case fallback_to_ksu 2 \
    "magisk|com.topjohnwu.magisk|$WORK/magiskbin|" \
    "ksu|com.resukisu.resukisu|$WORK/ksud|android12-5.10"
[ "$RC" -eq 0 ] || fail "fallback_to_ksu: exit $RC"
check_contains "$OUTPUT" "handoff-attempt idx=1 kind=magisk"
check_contains "$OUTPUT" "handoff-attempt idx=2 kind=ksu"
check_contains "$OUTPUT" "handoff-target=ksu idx=2"

# 4. Nothing ready: every target attempted, exit 1, the failure report.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case none_ready 2 \
    "magisk|com.topjohnwu.magisk|$WORK/magiskbin|" \
    "ksu|com.resukisu.resukisu|$WORK/nowhere|android12-5.10"
[ "$RC" -eq 1 ] || fail "none_ready: exit $RC"
check_contains "$OUTPUT" "HANDOFF-SUMMARY handoff=manager-not-ready targets=2"

# 5. KSU already loaded: no late-load invocation at all.
rm -f "$HARNESS_KSU_ARGS"
: >"$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case ksu_already 1 \
    "ksu|com.resukisu.resukisu|$WORK/ksud|android12-5.10"
[ "$RC" -eq 0 ] || fail "ksu_already: exit $RC"
check_contains "$OUTPUT" "[+] KernelSU already loaded"
[ -f "$HARNESS_KSU_ARGS" ] && fail "ksu_already: late-load was invoked"
rm -f "$HARNESS_KSU_FLAG"

# 6. An empty list fails closed.
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case empty 0
[ "$RC" -eq 1 ] || fail "empty: exit $RC"
check_contains "$OUTPUT" "HANDOFF-SUMMARY handoff=manager-not-ready targets=0"

# 7. A malformed kind is rejected without attempting a manager.
HARNESS_MAGISKD= HARNESS_MAGISK_OK=
run_case bad_kind 1 "bogus|pkg|$WORK/x|"
[ "$RC" -eq 1 ] || fail "bad_kind: exit $RC"
check_contains "$WORK/bad_kind/core.log" "unknown target kind 'bogus'"

# 8. The post-root probe takes the manager's own KMI over the resolver's.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK= HARNESS_KSU_KMI="android13-5.15"
run_case kmi_override 1 \
    "ksu|com.resukisu.resukisu|$WORK/ksud|android12-5.10"
[ "$RC" -eq 0 ] || fail "kmi_override: exit $RC"
check_contains "$HARNESS_KSU_ARGS" "boot-info current-kmi"
check_contains "$HARNESS_KSU_ARGS" "late-load --kmi android13-5.15 --allow-shell"
check_contains "$WORK/kmi_override/core.log" \
    "manager answer 'android13-5.15' (resolver 'android12-5.10')"
HARNESS_KSU_KMI="android12-5.10"

# 9. A token-only candidate is refused by the post-root probe before any
#    late-load: the static verdict was the preflight's, not this binary's.
rm -f "$HARNESS_KSU_ARGS" "$HARNESS_KSU_FLAG"
HARNESS_MAGISKD= HARNESS_MAGISK_OK= HARNESS_KSU_NO_INTERFACE=1
run_case no_interface 1 \
    "ksu|com.bogus.manager|$WORK/ksud|android12-5.10"
[ "$RC" -eq 1 ] || fail "no_interface: exit $RC"
check_contains "$OUTPUT" "KernelSU late-load interface missing"
grep -q 'late-load --kmi' "$HARNESS_KSU_ARGS" && fail "no_interface: late-load --kmi was invoked"
HARNESS_KSU_NO_INTERFACE=""

if [ "$FAILED" -eq 0 ]; then
    echo "handoff_test: all cases passed"
    exit 0
fi
exit 1
