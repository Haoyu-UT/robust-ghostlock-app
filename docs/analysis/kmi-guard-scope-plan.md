# The KMI guard's scope — L-level plan

**Status 2026-11-26: designed, awaiting operator approval. No code yet.**

## The defect the venus report found

Build 917's module chain (Mi 11 `venus`, `5.4.274-qgki-g76ac0b265f44`) reaches a real root shell and
then aborts in the root script:

```
[*] policy fixup rc=0
[!] cannot parse KMI from uname -r
[*] enforcing restore: reason=kmi-parse before=0
DFRC=1
```

`src/core/attack/ops.cpp` derives the KernelSU late-load KMI from `uname -r`:

```sh
KVER=$(uname -r | cut -d. -f1-2)            # 5.4
AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)   # empty on -qgki-
if [ -z "$AVER" ] || [ -z "$KVER" ]; then
  echo '[!] cannot parse KMI from uname -r' >>"$LOG"
  restore_enforce kmi-parse
  exit 1
fi
KMI="${AVER}-${KVER}"
```

The guard sits **before** the handoff branch, and `$KMI` is read only by the two late-load
invocations (`"$KSUD" late-load --kmi "$KMI"` and the `/system/bin/lmkd` retry). This run's handoff
is **Magisk** (`HANDOFF=magisk`), and `magisk_handoff()` never reads `$KMI` — so a path that cannot
use a KMI is refused for want of one. The module+Magisk design already says why no mapping is
possible there: KernelSU late-load is a kernel-module load for an exact GKI KMI, and a vendor
`5.4 qgki` is outside that set (`module-magisk-implementation-plan.md`, decisions; P4: `module ⇒
Magisk`).

Evidence: [`external/github_error_reports/5.4.274-qgki-g76ac0b265f44/README.md`](../../../../external/github_error_reports/5.4.274-qgki-g76ac0b265f44/README.md)
report 4 (run `20261126-005530`); the same guard is reached by the star `5.4.233` device once it
gets past the switch.

## The change

Move the refusal into the KernelSU branch of the script (the `else` of `if [ "$HANDOFF" = magisk ]`),
as its first statement; delete the unconditional guard. `KVER`/`AVER`/`KMI` stay where they are
(harmless on the Magisk path, and the assignment keeps one definition).

```sh
# before
KMI="${AVER}-${KVER}"
# safe mode: ...
...
if [ "$FIXUP_RC" -eq 0 ]; then
if [ "$HANDOFF" = magisk ]; then
  magisk_handoff
else
  if grep -q kernelsu /proc/modules 2>/dev/null; then

# after
KMI="${AVER}-${KVER}"
# safe mode: ...
...
if [ "$FIXUP_RC" -eq 0 ]; then
if [ "$HANDOFF" = magisk ]; then
  magisk_handoff
else
  if [ -z "$AVER" ] || [ -z "$KVER" ]; then
    echo '[!] cannot parse KMI from uname -r' >>"$LOG"
    restore_enforce kmi-parse
    exit 1
  fi
  if grep -q kernelsu /proc/modules 2>/dev/null; then
```

Deliberately **not** changed:

- **No `-qgki-` KMI mapping** (`5.4 → android12-5.4`). Late-load has no 5.4 kernel module, so a
  mapping could only feed ksud a KMI it cannot serve; the branch keeps failing closed, with the same
  reason and the same enforcing restore.
- **No change to the GKI parse.** `5.10.236-android12-9-…` → `android12-5.10`,
  `6.1.138-android14-11-…` → `android14-6.1`, `6.6.66-android15-8-…` → `android15-6.6`, byte for
  byte as today.
- **No change to the Magisk path** other than not being refused: same policy patch, same `magiskd`
  wait, same `MAGISK-SUMMARY`, same enforcing restore.
- **No change to the failure exits.** The guard keeps `restore_enforce kmi-parse` and no
  `dump_debug` (as today); the dumps it would want are the same ones later failure exits already
  collect.
- **No wire/profile change.** The script is embedded text; the schema and the runtime env are
  untouched.

## Gates

- Native: `make -C src ghostlock` zero warnings, `make -C src lint-tidy`, the full
  `native-host-tests` (the root script is embedded text; no unit test asserts its bytes today).
- `sh -n` on the generated script (the existing convention for the harness scripts).
- App: the usual suite (this change is native-only, so only a rebuild is strictly required) and a
  build number bump.
- Attack-path record: a script-text diff quoted in the plan/commit, since no `cmp_disasm` target
  covers the root script (it is data, not code).

## Device gates

- **venus (5.4.274)** — the reporter's device or an equivalent run: the module chain should reach
  `shell=uid=0(root)`, then `MAGISK-HANDOFF ok` (or a named failure inside the handoff:
  `magiskpolicy`, `magiskd`, the boot stages), and a `RESULT=` line. The box must end Enforcing.
- **star (5.4.233)** — the same image family with the same module; its 2026-11-25 report never
  passed the switch, so its downstream is unmeasured.
- **diting (5.10.236)** — regression: the KernelSU handoff must still parse `android12-5.10` and
  late-load. This is the one path the guard was written for, so it is the mandatory control.

## Risks

- Low. The changed line only decides **where** an existing refusal runs; the refusal itself, and
  every string it prints, is unchanged. The Magisk path gains a step it already assumed (a handoff
  that runs), and the KernelSU path is gated exactly as before.
- Rollback: revert the one hunk; no state, profile or wire implications.
