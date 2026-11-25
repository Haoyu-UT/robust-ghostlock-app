# The KMI guard's scope — L-level plan

**Status 2026-11-26: implemented and host-gated; device gates outstanding.** Revised the same day
after the operator's objection that the draft mixed the handoffs: the Magisk handoff and the
KernelSU handoff are separate cases, and one manager's (`KernelSU-Next`) optional `--kmi` is not a
fact about the others.

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

Two things, kept separate: **which handoff** needs a KMI at all, and **how the string is parsed**
when one does.

### A. Case separation (the actual fix)

`HANDOFF=magisk` never needs a KMI — `magisk_handoff()` does not read `$KMI`. The derivation and
its refusal move into the KernelSU branch (the `else` of `if [ "$HANDOFF" = magisk ]`), after the
`kernelsu already loaded` check, so the two handoffs stop sharing a precondition only one of them
has. The 5.4 module pipeline is `module ⇒ Magisk` by design, so this is the venus failure removed
without touching any manager's CLI semantics.

```sh
if [ "$HANDOFF" = magisk ]; then
  magisk_handoff
else
  if [ -z "$KVER" ] || [ -z "$AVER" ]; then        # reason is named, see B
    echo "[!] cannot parse KMI from uname -r ($KMI_WHY)" >>"$LOG"
    restore_enforce kmi-parse
    exit 1
  fi
  KMI="${AVER}-${KVER}"
  if grep -q kernelsu /proc/modules 2>/dev/null; then
```

### B. The string, for the KernelSU branch

The current parse has a bug independent of the handoff:

```
$ printf '%s\n' 5.10.81-android-9 | grep -o 'android[0-9]*'
android                     # → KMI "android-5.10", a malformed value
```

`[0-9]*` allows zero digits. Replace the whole derivation with a strict one and carry the reason:

```sh
UNAME_R=$(uname -r)
v=${UNAME_R%%-*}                                   # trim the localversion suffix
kmaj=${v%%.*}; krest=${v#*.}; kmin=''
[ "$krest" != "$v" ] && kmin=${krest%%.*}
case "${kmaj}${kmin}" in ''|*[!0-9]*) KVER=''; KMI_WHY='no numeric <major>.<minor>' ;;
                       *) KVER="${kmaj}.${kmin}" ;; esac
AVER=$(printf '%s\n' "$UNAME_R" | grep -oE 'android[0-9]+' | head -1)
[ -z "$AVER" ] && KMI_WHY='uname -r carries no android<NN> token'
```

`5.10.236-android12-9-…` → `android12-5.10`, `6.1.138-android14-11-…` → `android14-6.1`,
`6.6.66-android15-8-…` → `android15-6.6` — the same values as today. A tokenless kernel
(`5.4.274-qgki-…`, and the store's own `5.10.246-gki-g3e27bf130358`) fails closed with a line that
says which half was missing.

### Deliberately not adopted

- **No ksud auto-detect fallback** (`omit --kmi` and let ksud work it out). KernelSU-Next's
  `--kmi` is `Option<String>` and its help reads "Specify kernel KMI version instead of
  auto-detection"; omitted, it parses the uname itself and then reads a `/vendor/lib/modules/*.ko`
  vermagic via `modinfo` (KernelSU-Next `3b18216f`, `userspace/ksud/src/cli.rs:42-54`,
  `late_load.rs:38-61`, `boot_patch.rs:67-101`). But that is **one manager's** semantics. The app
  hands over to any of six KSU-family packages, `ManagerScanner`'s only verified cross-manager fact
  is that `late-load --help` names `--kmi` and `--allow-shell`, and optionality cannot be probed
  safely — executing `late-load` to test it daemonizes and attempts a module load. The KernelSU
  branch therefore keeps passing `--kmi` and keeps failing closed when it has none. Making it work
  on a tokenless kernel needs a device test of that specific manager, not a global assumption.
- **No `-qgki-`/version→generation table.** For 5.10/5.15/6.1/6.6 the generation is ambiguous by
  version alone (`android12-5.10` and `android13-5.10` both exist), and a `-qgki-` kernel is
  outside the GKI set entirely.
- **No change to the Magisk path** other than not being refused: same policy patch, same `magiskd`
  wait, same `MAGISK-SUMMARY`, same enforcing restore.
- **No change to the KernelSU failure exits.** The guard keeps `restore_enforce kmi-parse` and no
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

## Implementation record (2026-11-26)

Implemented in `src/core/attack/ops.cpp` on `document`. The generated-shell diff is exactly the
relocation plus the strict derivation:

```diff
 if [ "$FIXUP_RC" -eq 0 ]; then rm -f "$POLICY"; fi
-KVER=$(uname -r | cut -d. -f1-2)
-AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)
-if [ -z "$AVER" ] || [ -z "$KVER" ]; then
-  echo '[!] cannot parse KMI from uname -r' >>"$LOG"
-  restore_enforce kmi-parse
-  exit 1
-fi
-KMI="${AVER}-${KVER}"
 # safe mode: disable all modules before exec ksud
...
 else
   KSU_ALREADY=0
+  KMI_WHY=''
+  UNAME_R=$(uname -r)
+  kmi_v=$(echo "$UNAME_R" | cut -d- -f1)
+  kmi_maj=$(echo "$kmi_v" | cut -d. -f1)
+  kmi_min=$(echo "$kmi_v" | cut -d. -f2)
+  KVER=''
+  if [ -n "$kmi_maj" ] && [ -n "$kmi_min" ]; then
+    case "${kmi_maj}${kmi_min}" in
+      *[!0-9]*) ;;
+      *) KVER="${kmi_maj}.${kmi_min}" ;;
+    esac
+  fi
+  AVER=$(echo "$UNAME_R" | grep -o 'android[0-9][0-9]*' | head -1)
+  if [ -z "$KVER" ]; then
+    KMI_WHY='uname -r has no numeric <major>.<minor>'
+  elif [ -z "$AVER" ]; then
+    KMI_WHY='uname -r carries no android<NN> token'
+  fi
+  if [ -n "$KMI_WHY" ]; then
+    echo "[!] cannot parse KMI from uname -r ($KMI_WHY); late-load cannot pick a module" >>"$LOG"
+    restore_enforce kmi-parse
+    exit 1
+  fi
+  KMI="${AVER}-${KVER}"
   if [ ! -x "$KSUD" ]; then
```

**Two defects were found while implementing, both from this text being an outer `snprintf` format:**
a literal `%s` in the shell (`printf '%s\n'`) consumed one of the format arguments
(`-Wformat-insufficient-args`), and a `%%` in `${VAR%%-*}` collapsed to `%` in the generated script.
The block now contains no `%` at all — `cut` replaces the parameter expansions.

Gates, all green:

- `make -C src ghostlock` — zero warnings; `make -C src lint-tidy` rc 0; `make -C src
  native-host-tests` all targets pass.
- Generated script: extracted from the built source and checked with `sh -n` and `dash -n`.
- The block exercised under `dash` with a fake `uname -r`: `5.10.236-android12-9-…` →
  `android12-5.10`, `6.1.138-android14-11-…` → `android14-6.1`, `6.6.66-android15-8-…` →
  `android15-6.6`, `6.12.23-android16-5-…` → `android16-6.12`, `5.15.194-android13-8-…` →
  `android13-5.15`; `5.10.81-android-9`, `5.10.246-gki-…`, `5.4.274-qgki-…`, `5.10`, `6` and an
  empty uname all refuse with `kmi-parse`.
- `tools/cmp_disasm.py` baseline-vs-candidate: every target's instruction count identical, five
  `LAYOUT-SHIFT` entries (annotated operands only — the rodata moved). The `RESULT: FAIL` is the
  known control: the same three symbols (`run_main_route_threads`, `multicast_owner_worker`,
  `multicast_waiter_worker`) are missing in a baseline-vs-itself run too.
- No host test covers the generated script's text; the guard's real gate is the device one below.

**Device gates outstanding:** venus (Magisk path, the report's failure), star (same image family),
diting (KernelSU path — the mandatory regression control for `android12-5.10`). Note the diting as
configured cannot exercise the rewritten parse: `kernelsu` is loaded from boot, so the script takes
`kernelsu already loaded; skipping late-load`; a real KSU parse test there needs a stock `boot_a`.
**Build 924** (`Sesame-v1.2(924)-arm64-v8a-debug.apk`, md5 `866a2c1b…`, sha256 `805c5261…`,
23,507,523 B; packaged `libghostlock.so` md5 `daf9f529…`) is on the NAS at
`ghostlock-builds/2026-11-26-kmi-guard/` with a `BUILD.txt`.

## Risks

- Low, and the two halves fail differently. **A** only decides *where* an existing refusal runs: the
  Magisk path stops meeting a precondition it never used, and the KernelSU path is gated exactly as
  before. **B** changes the values only where the old line was wrong (`android-5.10` is impossible
  now) and keeps the GKI values identical; the refusal line gains the reason. Rollback: revert the
  one hunk; no state, profile or wire implications.
- Not covered by this change, and intentionally so: a **tokenless GKI kernel with a KernelSU
  handoff** still fails closed. That is a real case in the store (`5.10.246-gki-g3e27bf130358`,
  whose profile is timer-only today) and it needs a per-manager answer with device evidence, not a
  shared assumption.
