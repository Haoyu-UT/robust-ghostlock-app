# Handoff resolution — one detector, one rule set, one precondition per run

**Status:** design approved; **S1–S4 implemented and device-gated 2026-11-27** (all six
route×manager combinations ran, plus the picker and the no-Magisk paths). All §4 points are
settled. L-level (the delivery path's selection).

**Question.** The root manager is currently chosen by an inference — the module pipeline
stages Magisk, and `handoffEnv(magiskBin)` reads a non-null directory as "hand off to
Magisk"; every other run gets the `kernelsu` default. Is that good enough, and what should
replace it before a second KernelSU path exists?

**Answer.** No, it is an accident that happened to be correct. Replace it with **one
precondition, implemented once in the app process and called by every route before it does
anything**: a detector that enumerates both manager families, a rule set that turns
`(release, user choice, candidates)` into a `HandoffResolution` — a verdict per discovered
manager, a derived per-family summary, and an ordered target list — and a transport that stages
those targets. The native reads the plan (the `GHOSTLOCK_HANDOFF*` env transport stays; it now
carries the ordered list). KernelSU's kernel-line limit becomes a named feasibility fact
evaluated before the exploit, and every route's handoff script attempts the same two families in
the same order.

**Decisions taken in the review (operator, 2026-11-26/27):**

- Magisk is a handoff target on **every** route; there is no per-route manager special-casing.
  Magisk's handoff is device-proven (the module chain, several runs), so it is not treated as a
  risky option.
- The timer route is not an exception. Its `fake` endgame writes `selinux_state` and verifies
  `enforce == 0` (`src/timer/timer.c`, the `stage_write(SELINUX_STATE_STATIC + slide - 7, …)`
  block) before the handoff script runs, so it has the permissive window `magisk_handoff()`
  needs. What it lacks is the script branch and the staging (S3).
- **The feasibility check is a precondition, implemented the same way for every route** —
  direct, Shizuku, timer — in the app process. A route does not get its own verdict, its own
  visibility, or a later decision. If the check says no, the run does not start.
- **No preference order beyond a no-choice default.** The stored choice wins when it is
  feasible; with nothing stored the detector's order runs — KSU candidates first, then Magisk
  (today's W1 default). Nothing is written when the user has not chosen (Auto = no key).
- **`QUERY_ALL_PACKAGES` stays.** A normal permission: granted at install, no prompt, no user
  action. It keeps the detector a whole-device scan instead of a known list; the two Magisk
  `<queries>` are added anyway as the explicit support list.
- **No manager ⇒ refuse.** No feasible manager blocks the run at the precondition, with a reason
  naming both families. `NONE` ("root shell, no manager") is deferred to the day the app owns a
  root mechanism to hand to.
- **Fail-closed on a detector error.** A detector that throws blocks with its error as the
  reason, exactly like a detector that found nothing (§2.5).
- **`module + KSU` is not a special case.** The rules are route-free; there is no ≥5.10 module to
  exercise the pair with today, so nothing needs a gate. The first ≥5.10 module profile is
  device-gated by the standing new-profile rule like any other profile.

## 1. What exists today

Four decision points and two scripts:

1. **Run-start preflight** (`AndroidGhostlockRepository.preflight`) scans KernelSU-family
   managers only and blocks with `NO_ROOT_MANAGER` when none is suitable. The manager scan does
   **not** look at the profile (only the anchor check does), so it applies to every direct and
   Shizuku run.
2. **`PipelineResolver`'s module entry** (the module lambda in `AndroidGhostlockRepository`)
   calls `MagiskStage.stage` and rejects the entry when it returns null.
3. **`stageKsud`** scans again for every non-module run and stages the chosen KSU binary.
4. **The Shizuku service** scans and stages ksud again, in the service process (the W1 path and
   the timer path), and sets **no** handoff env, so the native's `kernelsu` default applies.
   The picker (`managerCandidates`) is a fifth scan.

The switch: `handoffEnv(magiskBin)` sets `GHOSTLOCK_HANDOFF=magisk` iff the module chain staged
a Magisk directory; otherwise the env is absent and `RuntimeConfig`'s default `"kernelsu"`
applies (`src/core/session/runtime_config.h`), baked into the root script by `ops.cpp`.

The scripts: `src/core/attack/ops.cpp` serves the module and W1 pipelines (`magisk_handoff()`
and the KSU late-load branch under one `if [ "$HANDOFF" = magisk ]`); `src/timer/timer.c`'s
`write_ksu_script()` serves the timer route, KernelSU only.

### The defects

1. **Inference, not decision.** "A Magisk directory exists" means "hand off to Magisk" only
   because the module branch is the only thing that stages one.
2. **Asymmetric feasibility — and inverted on 5.4.** Module ⇒ Magisk is checked before the
   exploit; W1 ⇒ KSU is a compile-time default with no resolution-time check. Worse, because the
   preflight does not look at the profile, a **module-only 5.4 device needs a KSU manager to
   start at all** and Magisk to stage; a device with only Magisk is refused at run start with
   "No usable ksud found", and a device with only KSU fails the module entry. Only a device with
   neither gets an honest refusal.
3. **No reason on record.** The script logs which branch ran; the app and run store do not say
   why it was chosen or why the alternative was rejected.
4. **One branch, two contracts.** The venus KMI bug (2026-11-26) was a precondition only one
   handoff needed, shared by both; the fix moved it into the KSU branch. Each handoff should own
   its preconditions.
5. **Per-process, per-route verdicts.** The app and the service each scan, with different
   visibility and different code paths; the timer does not go through the app's check at all.
   Nothing owns "which manager this run uses".

## 2. Design

### 2.1 One detector

A `RootManagerDetector` returns candidates from both families, read-only, **in the app
process**. The services stop scanning: they receive the chosen plan and stage it (§2.3). The
model is defined once in §2.2.

- The KSU **candidate discovery** is today's `ManagerScanner.scan()` (ELF prefilter → tokens →
  `late-load --help`); the KMI probes (§2.4) are a separate feasibility step over the suitable
  candidates, added in S2. A candidate is usable exactly when `Candidate.suitable` is true — that
  is `interfaceOk == true`, or probe-unavailable with both static tokens (`lateLoadToken &&
  allowShellToken`). `suitable` is the candidacy gate; §2.4 adds feasibility on top of it, never a
  second candidacy predicate.
- The Magisk branch is `MagiskStage`'s lookup minus the staging, over both packages in `PACKAGES`
  order (`com.topjohnwu.magisk`, then `io.github.vvb2060.magisk`): each installed package whose
  `nativeLibraryDir` (or APK members) carries `magisk`, `magiskinit`, `magiskpolicy` is a row.
  Today's `managerInfo` returns only the first; the detector enumerates both. **Detection must
  not stage** — `MagiskStage.stage` deletes and rewrites its destination directory, so staging
  belongs to the selected candidate only.
- No persistent cache (same rule as `ManagerScanner`: a stale "capable" verdict is the one
  failure worth avoiding). The returned plan is the only reuse: `stageKsud` and the service
  consume it instead of scanning again.

**Package visibility.** `QUERY_ALL_PACKAGES` is a **normal** permission: the system grants it
at install, with no prompt and no user action. It is **kept** (decision, 2026-11-26): it is what
makes the detector a whole-device scan rather than a known list — `ManagerScanner` walks every
installed app's native lib dir, and `getPackageInfo` works for a manager whose package name this
app has never shipped. The only cost is disclosure (the manifest states the app can read the
installed-app list), plus a Play justification requirement if the app ever ships there; the
distribution is GitHub/NAS.

Measured 2026-11-26 on the diting (Android 15), for the case where the permission is ever
dropped: a probe app (`targetSdk` 31) with **no permissions** and only `<queries>` entries
resolved `com.topjohnwu.magisk` and `com.resukisu.resukisu` via `PackageManager.getPackageInfo`,
while an undeclared installed user app (`com.rifsxd.ksunext`) was HIDDEN and
`getInstalledPackages(0)` returned 273 of the shell's 356 packages. (`run-as <app> pm path
<pkg>` is **not** a faithful probe — it resolves undeclared packages; use the app-context
`PackageManager` call.)

Consequences:

- The manifest declares both Magisk packages in `<queries>` (`com.topjohnwu.magisk`,
  `io.github.vvb2060.magisk`); the six KSU packages are already declared. Today's code path does
  not need them (the permission covers it), but they make the support list explicit and keep
  Magisk detection working if the permission is ever removed.
- With the permission, the app process and the shell uid see the same package set, so the single
  detector has no visibility gap against the service.

### 2.2 One rule set, no product ranking

```kotlin
enum class Kind { KSU, MAGISK }
enum class State { FEASIBLE, INFEASIBLE }

/** A discovered manager. Identity: package + payload path — the scanner emits one row per
 *  executable-shaped ELF, so the same package can appear more than once. */
sealed interface RootManager {
    val kind: Kind
    val packageName: String
    val label: String
    data class Ksu(val ksudPath: String, val interfaceOk: Boolean?) : RootManager
    data class Magisk(val nativeLibDir: String, val required: Set<String>) : RootManager
}

/** One row per discovered manager. Key: `manager` — it is never null. */
data class HandoffVerdict(
    val manager: RootManager,
    val state: State,
    val reason: String,          // required; doubles as the evidence line
    /** The KMI the resolver used for this row (§2.4); KSU only. The transport
     *  copies it into the target — the script must not re-derive it. */
    val kmi: String? = null,
)

/** The precondition's result: every candidate judged, nothing staged yet. */
data class HandoffResolution(
    /** One row per discovered candidate, detector order. */
    val verdicts: List<HandoffVerdict>,
    /** Feasible rows in attempt order: the stored choice first, then detector order. */
    val feasible: List<HandoffVerdict>,
)

/** The transport's result: a feasible manager, the staged payload, and (KSU) the KMI to pass. */
data class HandoffTarget(val manager: RootManager, val source: String, val kmi: String?)
```

One table and one transport list:

- **`verdicts`** — one row per discovered candidate, in detector order, keyed by the **manager**
  (package + path).
  A rejected candidate keeps its row and reason; `manager` is never null. A reason can come from
  the manager itself (`Failed to get KMI from boot/modules`) or from the fallback rule
  (`kernel line <5.10`); either way it is attached to each candidate row it rejects, so nothing is
  lost by not storing a family row.
- **`targets`** — built by the transport from the feasible rows, in order, each with the staged
  source the script will read. The list's head is the manager the run uses if nothing fails.

The **family lines** (app log, refusal, picker headers) are a projection computed where they are
printed, never stored:

- a feasible row of that kind exists → `feasible via <package>`;
- rows exist but none is feasible → the first row's reason;
- no rows at all → a fixed per-kind line **in the projection** (`KSU: no ksud with late-load
  --help`, `Magisk: not installed`) — a constant, not resolver data.

Feasibility rules, evaluated **before the exploit**:

| family | feasible when | infeasible reason (examples) |
|---|---|---|
| KSU | the candidate is `suitable`, `boot-info supported-kmis` succeeds, and `boot-info current-kmi` exits 0 with output in that list (the release rule is the fallback when the manager cannot answer) | `Failed to get KMI from boot/modules`; `supported KMI list does not contain android11-5.10`; `no ksud with late-load --help` |
| MAGISK | the candidate's package resolved and all required native libs are present | `manager not installed`; `native libs incomplete (missing magiskpolicy)` |

**Route conditions: none.** The signature has no route or pipeline parameter; a route cannot
narrow a family, and the timer's `fake` endgame opens the same permissive window (§2.6).

Selection produces `feasible` in this order; the transport then turns it into targets:

1. the stored `manager_package`'s row first, if it is feasible;
2. the rest in the detector's order (below);
3. no feasible row ⇒ `feasible.isEmpty()` ⇒ the run is refused with the family lines' reasons.

A stored choice that is infeasible but installed is **kept** and logged (`selected: … infeasible
— using …`); only a choice whose package no longer exists is cleared. That is deliberately
stronger than the route/carrier self-healing in `AndroidProfileConfigController`, which clears
any name outside the viable set: a manager is a user choice, not a derived one.

The detector's order — **KSU candidates first (today's ranking: `suitable`, `interfaceOk`,
`builtin`, label), then Magisk** — is the no-choice default, decided 2026-11-26. The picker's
"Auto" stores nothing (the preference key is absent), so the order applies only when the user
has never chosen; it preserves today's W1 default. A stored choice is a **preference, not an
exclusion**: the chosen manager's row goes first, and every other feasible candidate remains a
fallback.

**`module + KSU` is allowed** like any other pair: the rules are route-independent, so there is
no per-route exception. On 5.4 the manager's own `current-kmi` fails (or the fallback line rule
refuses), so the module route keeps handing off to Magisk; on ≥5.10 both families can be feasible
and the order applies like any other pair. No bundled module profile is ≥5.10 yet, so nothing
exercises that pair today; the first one will be device-gated by the standing new-profile rule
like any other profile.

### 2.3 One transport rule

`env(targets)` — the transport's list, not the resolution — carries the order in one variable:
`GHOSTLOCK_HANDOFF_TARGETS` = newline-separated wire entries, first = the selection, in the same
`kind|package|source|kmi` form the AIDL carries (one codec, `HandoffWire`; the package field is
the log name). A Magisk entry's `kmi` is empty; a KSU entry carries the staged ksud and the KMI
to pass. It replaces `GHOSTLOCK_HANDOFF`/`GHOSTLOCK_MAGISK_BIN`; `RuntimeConfig` parses the
list, and the binary that writes the script materializes it — with the script's other
parameters — into a sidecar beside the script (§2.6). The process environment cannot carry them: the DirtyFrag init
shell does not inherit it, and the timer child has a fixed `envp`.

- **Direct routes**: the app stages each target's payload into `filesDir` (a ksud copy per KSU
  target, the Magisk libs once) and passes `env(targets)` to `runStage` — the two DirtyFrag stage
  calls, module and W1.
- **Shizuku routes**: the app passes the ordered **managers** and their KMI over the existing
  AIDL (`managerPackage` becomes the ordered list), and the service stages each into
  `/data/local/tmp/ghostlock-app` **without re-deciding**. The app's filesDir is not readable by
  the shell uid, so the service copies from each manager's own origin path.
- **Timer**: the same as Shizuku, plus the targets passed to `libtimer`, which writes the sidecar
  (S3).

The `source` in a `HandoffTarget` is process-local: each staging site produces its own copy (the
app's `filesDir`, the service's working dir, the timer workdir); what travels is the manager's
origin path and the KMI, never a path from another uid's sandbox.

A target whose payload cannot be staged is **dropped before the exploit** and logged; if none
remains the run fails before the native (past the precondition, so it is a run-start failure,
not a dialog). That is a transport refinement, not a re-decision by the script: the script only
attempts the list it was given (§2.6).

The app log prints the derived **family** lines and the effective target; the run-store meta
keeps every **candidate** verdict, alongside `module pair`:

```
handoff=magisk state=feasible reason=selected:com.topjohnwu.magisk
handoff=ksu state=feasible reason=available (2 candidates)
handoff-target=ksu effective=2/2 (1-based; target 1 magisk: magiskd did not start)
```

### 2.4 Feasibility is early and named: the manager's own KMI interface

> **Amended 2026-11-27 (later):** the exec probes below are superseded by §8. The primary rule
> becomes a *static* read of the same data (the `_kernelsu.ko` asset names in the manager's own
> `ksud`) plus KSU's own KMI parse of `uname -r`, so preflight emits no exec. `boot-info` remains
> only in the post-root re-detection (§7), where it runs as root from the exempt
> `/data/local/tmp`. The text below is kept as the record of the exec-based design and its
> measurements.

The primary rule is the **manager's own interface**, not a derivation of ours. `ksud boot-info`
(verified 2026-11-26 on three rigs: ReSukiSU 4.2.0-rc3 and KernelSU-Next 3.4.2) offers:

- `boot-info current-kmi` → one KMI line — `android12-5.10` on the diting, `android14-6.1` on the
  duchamp, `android15-6.6` on the OnePlus 13s — exit 0;
- `boot-info supported-kmis` → the embedded `*_kernelsu.ko` asset names, one per line; the same
  eight on both managers, with **no 5.4 line and no `android11-5.10`**.

A KSU candidate is feasible when it is `suitable` **and** `supported-kmis` succeeds **and**
`current-kmi` exits 0 **and** its output is a member of that list. `supported-kmis` doubles as the
capability probe: if it fails, the manager cannot answer and the fallback rule applies. The reason
is the manager's own stderr (`Failed to get KMI from boot/modules`) or a named `supported KMI list
does not contain <kmi>`; nothing is parsed by us. Two extra execs per suitable candidate, in the
rule step.

**Why not validate the KMI against `uname -r`.** It is tempting, but the manager's source
(`boot_patch::get_current_kmi`) parses `uname -r` first and — when that fails — falls back to a
kernel module's `vermagic` under `/vendor/lib/modules`. On exactly the kernels where uname parsing
fails, the KMI can legitimately not appear in uname at all, so "split the KMI and search uname"
would refuse a handoff that works. When the KMI did come from uname, the check is tautological.
The two parts may feed a **diagnostic log line** only (`KMI from vermagic, not uname`), never the
verdict.

**Fallback: the release mirror.** A manager that cannot answer — `boot-info` absent, or
`supported-kmis` failing — falls back to the release rule. Its parse and token rows are
`ops.cpp`'s old derivation (`:793-819`); the `major.minor < 5.10` row is **new** — the script
never compared versions and refused a 5.4 qgki kernel only because its uname carried no
`android<NN>` token:

| condition (in this order) | verdict | reason |
|---|---|---|
| `<major>.<minor>` does not parse | infeasible | `uname -r has no numeric <major>.<minor>` |
| `major.minor < 5.10` | infeasible | `kernel line <5.10 has no late-load module` |
| no `android<NN>` token | infeasible | `uname -r carries no android<NN> token` |
| otherwise | feasible | — |

The table is pinned by a Kotlin unit test. There is no shell counterpart to mirror once S2
deletes the script's derivation; it is not the primary path.

**The KMI travels with the target.** The resolver passes the KMI it used — the manager's
`current-kmi` answer, or the value the fallback mirror derived — inside the KSU target, and the
script passes it verbatim (`late-load --kmi "$kmi" --allow-shell`). The script must **not** re-derive it: its
old derivation reads only `uname -r`, so it would refuse a KMI the manager legitimately took from
vermagic. Defence in depth becomes the readiness check (`kernelsu` in `/proc/modules`) and the
ordered fallback, not a second derivation.

### 2.5 The precondition, and the run

**One entry point, one process, every route.** The precondition **is** `preflight()`; it calls
`resolveHandoff(release, storedChoice)` internally and returns the plan. Every route calls the
same function, before anything else:

| route | call site | on no feasible row |
|---|---|---|
| direct (module, ghostlock, dirtyfrag) | `preflight(useShizuku = false)` in `runExploit` | `PreflightFailure`, no run store, no native |
| Shizuku W1 | `preflight(useShizuku = true)` in `runExploit`, before dispatch | same refusal |
| timer (`fake`/`zero`) | `preflight(useShizuku = true)` from `onRunTimer`, before the service call | same refusal, logged |
| timer `probe` | not a handoff run — no check | — |

The refusal names **both** families and appends their reasons — `no feasible manager:
KSU: Failed to get KMI from boot/modules; Magisk: not installed` — as a new string replacing
`manager_picker_empty` for the run refusal (the picker's empty toast keeps the old one).

The timer passes the Shizuku flag because that flag already selects the anchor rule that
applies to it: no anchor check (the timer uses no lock anchor, and the direct chain's anchor
state file is not the service's to judge). A route enum would state that more directly, but it
is not required for this change.

The plan is **returned** (`PreflightResult.Passed(plan)`), replacing today's `preflightManager`
stash and its TTL, and passed explicitly rather than stored: `runExploit(config, plan)`,
`runExploitWithShizuku(config, plan)` and `runTimerRoute(plan, onLog)` — through
`RunExploitUseCase` and the ViewModel — so the direct run, the Shizuku dispatch and the timer
dispatch all consume the same object and a plan cannot outlive its preflight.

Properties it must keep:

- **Detection and rules only; no staging.** Staging belongs to the transport (§2.3).
- **Fail-closed on both a detector error and an empty result.** A scan that throws blocks with
  the exception as the refusal's reason — the same dialog path as a genuinely missing manager.
  Today's fail-open existed because `stageKsud` re-scanned independently ("let stageKsud report
  it"); with one detector there is no second scan, so an errored scan can only yield an empty
  plan the transport cannot stage, and the run would die later with a worse message.
- **No pipeline parameter.** The manager is decided before the pipeline walk; the walk no longer
  changes it. (`PipelineResolver` still chooses the route; it consumes the manager the
  precondition chose, and the family follows from it.)
- **One verdict for the picker and the run.** The picker calls `resolveHandoff` (detection and
  rules only, no anchor), so what it shows is what the run enforces.

Staging order inside a run is unchanged: the module entry builds the targets (stages the
feasible rows) to decide the entry — a row that cannot be staged is dropped, none left rejects
the entry and falls through, as today; a non-module run stages before the native. Nothing
re-decides the manager or reorders the list.

### 2.6 The handoff scripts

| route | wrapper | handoff core |
|---|---|---|
| module, W1 (ghostlock, dirtyfrag) | `ops.cpp` — fixup/vr.ko/dumps prologue, sidecar, report | shared |
| timer | `timer.c` — write the core + sidecar, run it as the fake-cred child | shared (Magisk with S3) |

**One core, two wrappers.** The handoff is implemented once: `src/core/handoff/` carries a real
`.sh` file, embedded at build time into a C-compatible header and compiled into both binaries
(`src/Makefile` gains the generated header and a `sh -n` check). Writing bytes instead of a
printf format removes the `%`-escaping layer the venus KMI bug lived in. The wrappers may only
add route-specific prologue/epilogue; they may not grow their own handoff steps.

The core owns the ordered attempt loop:

```
for t in targets:                     # TARGETS from the sidecar, never the environment
    magisk -> magisk_handoff "<dir>"
    ksu    -> "<src>" late-load --kmi "<kmi>" --allow-shell
    readiness: magisk -> magiskd alive; ksu -> `kernelsu` in /proc/modules
    log handoff-attempt idx=N kind=… rc=… ready=…
    if ready: handoff-target=<kind> idx=N ; break
handoff_before_restore                # wrapper hook: ops dumps + module unload; timer nop
restore enforce (once, after the last attempt)
report <handoff ok | manager-not-ready>
```

The KSU invocation is exactly what the probe guarantees (`--allow-shell` and `--kmi`).
`--package-name` and the auto-detected KMI both go: the ops invocation, which passes neither, has
carried the handoff on ReSukiSU (diting, `20261109-002934`) and KernelSU-Next 3.4.2 (the OnePlus
DirtyFrag gate), both from a copied ksud. If a future manager needs the flag, that is a probe
contract change, not a per-route one.

**Parameters travel in a sidecar.** The script cannot read the process environment: the
DirtyFrag init shell does not inherit ours, and the timer's child has a fixed `envp[]`
(`timer.c`). So the binary that writes the script also writes its parameters into a file beside
it (`$0.conf`) — the `TARGETS` list (one wire entry per line), plus `LOG`, `SAFE_MODE`, the vr.ko
mode and `MODULE_PATH`/`MODULE_KO`. The core derives the path from `$0`, which is the script
path in all three executors (`execl`, the DirtyFrag init shell, the timer child). Two
portability facts the device found here: the phone's `sh` reparses an `eval` string, so the
list is read with a here-document, and it treats a bare `|` inside `${v%%|*}` as an empty
alternative, so the wire entry is split with `cut`.

Readiness is strict (a pid / a `/proc/modules` line), so a half-started Magisk never coexists
with a KSU load. The restore and the single report sit **outside** the loop — today ops calls
`finish_run`/exit inside a branch and the timer script exits before its restore; moving them out
is the cost of the fallback, and it fixes the timer's leak in passing.

**The script's own discovery goes.** With the targets carrying every source, `ops.cpp`'s `KSUD`
search — `$HOME_DIR/ksud`, the six `/data/app` `find` patterns, `/data/local/tmp/ksud`,
`/data/adb/ksu/bin/ksud` — and the timer script's `pm path` loop are **deleted**, not kept as a
fallback. Two things stay, and neither is discovery: the Samsung DEFEX retry, which bind-mounts
the *target's* source over `/system/bin/lmkd` and re-runs it, and the readiness check (`kernelsu`
in `/proc/modules`). A missing or empty target list fails closed with a named reason — the app
always supplies one, so that would be a bug, not a case to guess at. The timer script's other
divergences — `--package-name`, the absent `--kmi`, its own readiness check — go with the same
change; the shared core is what stops them coming back.

The timer's `fake` endgame is its permissive-window route: it writes `selinux_state` (with
retries and a read-back of `enforce == 0`) and `panic_on_warn`, then runs the script as the
fake-cred child (full caps, `u:r:shell:s0`). `magisk_handoff()` needs exactly that state, so S3
is a **second consumer and a staging path, not a mechanism**: the tool writes the shared core
plus the sidecar and executes it, and the core's single restore covers the failure exits the
current timer script misses (a missing ksud exits before its `echo 1 > enforce`).

## 3. Slices

**S1 — detector + resolver + one precondition, a strict refactor.**
Files: `ManagerScanner.kt` (family + Magisk detection; `resolve` unchanged), `MagiskStage.kt`
(split detection from staging), new `HandoffResolver.kt`, `AndroidGhostlockRepository.kt`
(`preflight` calls `resolveHandoff` and **returns** the plan; `runExploit` /
`runExploitWithShizuku` take it; `stageKsud` consumes it — the `preflightManager` stash and its
TTL go; one log/meta line), `GhostlockRepository.kt` (interface), `GhostlockUseCases.kt`,
`GhostlockViewModel.kt` (`Passed(plan)` → the run call), `ShizukuExploitRunner.kt` (the ordered
list replaces `managerPackage`), `GhostlockUserService.kt` (consume the plan; drop the
`runExploit` scan only — the timer path keeps its scan and staging until S3),
`IGhostlockUserService.aidl` (`runExploit`'s `managerPackage` → the plan; the timer call is
unchanged until S3), `AndroidManifest.xml` (add the two Magisk `<queries>`; `QUERY_ALL_PACKAGES`
stays).
Selection in S1 is **KSU only** (today's rule; Magisk is detected but not yet selectable), so
no outcome moves and the preflight still blocks a Magisk-only device. The module block keeps its
explicit Magisk staging; the timer is untouched. The env stays today's
`GHOSTLOCK_HANDOFF`/`GHOSTLOCK_MAGISK_BIN` — S1 changes no native code; the ordered list and the
attempt loop arrive with S2.
Gates: host tests over the pure resolver; app/profile-core suites; a diting W1 smoke whose
logged plan matches the branch that ran; a manifest assertion (both queries present,
`QUERY_ALL_PACKAGES` still declared).

**S1 landed 2026-11-27** (builds 936/937, commit `0bacde94`). `Handoff.kt` (model), `HandoffResolver.kt` (rules and the
family-line projection), `HandoffWire.kt` (the `kind|package|origin|kmi` entry), Magisk detection
split out as `MagiskStage.detect` (§2.1's "the lookup minus the staging"), `Candidate.toManager()`
in the scanner, `Passed(plan)` through the repository / use case / ViewModel, and the AIDL's
`managerPackage` replaced by `String[] targets`. Three implementation notes:

- **The KMI rides on the verdict.** `HandoffVerdict.kmi` (above) is how the resolver's answer
  reaches the transport, since the transport builds `HandoffTarget` from the feasible rows.
- **The run-store meta spells the family key `handoff.<kind>`.** `RunLogStore.appendMeta` is
  replace-by-key, so two `handoff=` lines collapsed to the last one (found by the gate's first
  run); the run log keeps the `handoff=<kind>` form. The first S1 run is the evidence.
- **Magisk detection lives in `MagiskStage`, not `ManagerScanner`** — it owns the package list
  and the lib inventory, and §2.1 calls the branch "`MagiskStage`'s lookup minus the staging".

Gates read: app **293/0** (1 skip; 15 new: resolver 9, wire 4, manifest 2), profile-core 62/0
(1 skip), and three direct runs on the diting (builds 936/937, 5.10.236): `handoff=ksu
state=feasible reason=available (1 candidate)` → `ksud selected: com.resukisu.resukisu
[late-load interface confirmed]`, the native's `handoff=kernelsu`, `dirtyfrag: RESULT=OK
script=0 enforce=1`, store `completed`; the meta carries both family lines and all four
candidate rows (the rig has Magisk installed, reported infeasible as designed).

**S2 — the rules: KSU via `boot-info`, Magisk selectable, W1 Magisk, the attempt loop.**
Files: `ManagerScanner.kt` (probe `boot-info supported-kmis` as the capability check, then
`current-kmi`, over the suitable candidates; cache the answers for the run), `HandoffResolver.kt`
(the KSU rule and its release fallback, the verdict list and the feasible order, the stored-choice
rule),
`src/core/handoff/` (the one core script, its build embed and the `sh -n` check),
`src/core/attack/ops.cpp` (the wrapper: sidecar write, `KSUD` search and KMI derivation deleted,
one restore and one report), `src/Makefile`, a host harness with stub `ksud`/`magisk`,
`AndroidGhostlockRepository.kt` (the module entry consumes the plan, refusal text, meta),
`GhostlockUserService.kt` (W1 staging through the one staging helper), `PreflightFailure` and the
strings (EN+ZH), `runtime_config` (parse the list), tests.
Behaviour change: a Magisk-only 5.4 device becomes runnable; a kernel with no matching KMI (5.4,
or an unparseable/unlisted one) is refused **at run start** with the manager's own reason; W1
gains the Magisk fallback; the core attempts the targets in order and records the effective one;
a selected-but-infeasible manager is logged and skipped, not cleared.
Gates: unit tests for every rule branch — including the release fallback and the probe's
absent/failed cases, fed as fake outputs to the pure resolver; the two real managers on the
diting (ReSukiSU) and the OnePlus (KernelSU-Next) reading `current-kmi` inside their supported
list; a Magisk-only module run (none in hand — the star reporter is the out-of-band confirmation);
and the 5.4 failure mode of `current-kmi`, which still needs a 5.4 unit with a ksud (the star, if
its preflight's passing manager is a KSU one). The W1 Magisk branch is the same `magisk_handoff()`
body under the same window as the module route; with no Magisk-only W1 unit in hand it is recorded
as body-identical, not claimed as a separate device pass. Forcing a family while both are
installed needs the picker (S4) or a debug hook — recommend a `--ez handoff_manager <pkg>` hook
following the `fallback_test` precedent, retired with S4.

**S3 — the timer route: the same precondition and the Magisk handoff.**
Files: `src/timer/timer.c` (`write_ksu_script` becomes "write the shared core + sidecar and run
it"; the `pm path` loop, `--package-name`, the auto-KMI and its own readiness are deleted, not
ported), `GhostlockUserService.kt` (stage the plan's targets into the timer workdir through the
same staging helper), `GhostlockViewModel.kt` (`onRunTimer` calls `preflight(useShizuku = true)`
and passes the plan to the service), `GhostlockRepository.kt` (`runTimerRoute(plan, onLog)`),
`ShizukuExploitRunner.kt` (the timer call carries the targets), strings/logs.
Gates: `sh -n` on the generated script; a host test for the env; a diting timer `fake` run with
Magisk forced (KSU control beside it): `MAGISK-PHASE`, magiskd, `enforce=1` restored, run-store
verdict.

**S2/S3 landed 2026-11-27** (build 938, commit `4ff4c213`). The core, both wrappers, the
wire transport and the rules are in: `src/core/handoff/handoff.sh` (embedded as bytes into both
binaries by `src/Makefile`, `sh -n`-gated, with the `handoff_test.sh` loop harness in
`native-host-tests`), `ops.cpp` (prologue, `dump_debug`/unload hooks, sidecar; the KSUD search
and the KMI derivation deleted), `timer.c` (writes the core + sidecar, `--handoff-targets`;
`write_ksu_script`'s `pm path` loop, `--package-name` and auto-KMI deleted), `RuntimeConfig`
(parses `GHOSTLOCK_HANDOFF_TARGETS`), `ManagerScanner.probeKmi`, the resolver rules and the
Magisk feasibility, staging of both families in the repository and the UserService, the timer's
precondition and plan, and the family-reason refusal dialog. `handoff_probe` also accepts the
core's `HANDOFF-SUMMARY handoff=ok` (a Magisk handoff has no module line), and the native prints
`root manager ready` when that summary is the only readiness fact.

Device matrix — every pair shows `handoff-target=… idx=1`, the enforcing restore, and a
`completed` run store:

| route | × ksud | × Magisk |
|---|---|---|
| ghostlock, dirtyfrag W1 (diting) | `005502` | `005617` |
| ghostlock, full chain (diting) | `005834` | `005733` |
| module (diting, 5.10.236 bench profile) | `005125` | `005024` |
| timer `fake` (diting, after the prime-core hold) | `014443` | `014719` |
| timer `fake` (POCO 6.1.138) | `010310` | `010505` |

The module pair ran on a temporary module-only 5.10.236 profile (the 2026-11-22 diting
`dfswitch` module, its real CRCs, so the delivery patch is a no-op); the profile and asset were
reverted after the runs. Timer runs used the POCO's bundled 6.1.138 profile first (Magisk was
installed there for the pair; Shizuku started with its own adb `start.sh`) and then the diting's
own 5.10.236 timer block — the diting's tool pins its victim to CPU 7, which the vendor's prime
core parks, so the run needs `min_cpus=1` on `cpu7/core_ctl` first (an environment fact of the
tool, not the handoff). The KSU handoff in the matrix mostly takes the "already loaded" readiness path
(both rigs carry root from boot), so the real `late-load` was proven separately: the diting was
RAM-booted on the **stock** `external/boot.img` (no KernelSU, no `su`; `fastboot boot`, nothing
written) and run `20261127-022303` shows the shared core's own invocation —
`late-load kmi=android12-5.10 source=/data/user/0/com.sesame.app/files/ksu-0`, `late-load exit=0`,
`[+] KernelSU module loaded`, `handoff-target=ksu idx=1`, `RESULT=OK script=0 enforce=1`, store
`completed` — with `su` returning `u:r:ksu:s0` afterwards; a normal reboot returned the rig to
`boot_a`. The OnePlus 6.6.66 KernelSU-Next probe is in the S4 record below.

The stored choice was exercised through the debug hook (`--es handoff_manager <pkg>`, retired
with S4); with it the family order flips and the other family stays a fallback, as specified.

**S4 landed 2026-11-27** (build 940). The picker is the run's own resolution over both
families: `HandoffResolver.choices` turns the verdicts into rows with `feasible` and the
evidence (`supported-kmis lists …`, `binaries present (…)`) or the refusal reason; only a
feasible row can be stored, an infeasible-but-installed row stays visible and a tap on it
toasts its reason. Auto still stores nothing. The KMI answers are cached per boot (in memory,
keyed by the ksud path), shared by the picker and the pre-flight. The stored-choice self-heal
is in the pre-flight: a choice whose package vanished is cleared; an installed-but-infeasible
one is kept and logged (`selected:<pkg> infeasible — using <head>` in the family line). The
`--es handoff_manager` bench hook is retired — the picker is the bench path.

Two plan-conformance fixes landed with it: the module entry accepts any staged manager (the
old code still demanded Magisk, so a KSU-only ≥5.10 module profile would have been rejected
despite §2.2), and the home badge/strings are manager-agnostic (`moduleManager`,
`ModuleBlocker.ManagerMissing`).

Device:
- diting: the picker lists `ReSukiSU — supported-kmis lists android12-5.10` and
  `Magisk — binaries present (magisk, magiskinit, magiskpolicy)`; selecting Magisk (`013617`)
  and ReSukiSU (`013813`) flips the plan head and the effective target.
- diting, module bench with Magisk **uninstalled** (`015044`): `handoff.magisk=infeasible
  reason=not installed`, `module pair … handoff=ksu:com.resukisu.resukisu`, target=ksu,
  `RESULT=OK` — the module-entry fix and the vanished-pin self-heal (the stored
  `com.topjohnwu.magisk` was cleared) in one run.
- diting timer: KSU `014443`, Magisk `014719` (via the picker), both `handoff-target=… idx=1`,
  timer verdict 0.
- OnePlus 6.6.66: the picker reads `KernelSU-Next — supported-kmis lists android15-6.6`, the
  second real manager's `boot-info` answer.

**S4 — picker and stored selection.**
Files: the Advanced picker over both families with the evidence kinds (§4.3), the stored choice
and its self-heal, host tests. Retires the S2 debug hook.
Gates: a device pass selecting each family and reading the logged plan.

**S5 — the future compatible fork (not in scope).**
The `supported-kmis` membership test already covers the common case: a fork that ships a 5.4
`<kmi>_kernelsu.ko` becomes feasible with no code change, because the rule asks the manager rather
than the release string — provided its `current-kmi` can answer on that kernel; if it cannot, the
fallback's `<5.10` row refuses, as today. What remains is `insmod` + a user-supplied `.ko` (hash-pinned, opt-in) —
its evidence is the file's hash and the kernel's module-load verdict — and the outcome is "root
shell, manager best-effort", never a requirement of the chain.

## 4. Decisions and open points

1. **module + KSU on ≥5.10 — settled: not a special case.** The rules are route-free, so the
   pair is permitted like any other. No bundled module profile is ≥5.10 (the two are 5.4, where
   KSU is infeasible and Magisk is used as now), so nothing exercises it yet; the first ≥5.10
   module profile is device-gated by the standing "a new profile needs a device run" rule, with
   no separate bench record.
2. **Failure semantics — settled 2026-11-27: the log is the record.** The module chain still
   ends `exit 1` when no target became ready although the root shell existed, and that outcome is
   documented from what the run wrote, not re-encoded: the run log carries
   `handoff-attempt idx=N kind=… package=… rc=… ready=…`, the manager's own output,
   `HANDOFF-SUMMARY handoff=manager-not-ready targets=N` and `HANDOFF-DIAG`; the run-store meta
   carries the family lines and every candidate verdict, including the transport's dropped
   targets. A distinct outcome code was considered and dropped: the case is rare by construction
   (the precondition refuses the structural failures before the exploit, and a multi-target list
   falls back), and a reader of the log can already tell "root achieved, manager not ready" from
   "never rooted". Revisit only if a device class turns out to hit it regularly.
3. **Probe honesty — implemented (S4).** KSU's verdict is the manager's own interface
   (`supported-kmis`/`current-kmi`); Magisk's is a file inventory. The picker shows the
   resolver's evidence line per row, and an infeasible row is not selectable.

## 5. Risks

- **Refusal timing.** Moving the no-manager refusal before the exploit changes user-visible
  behaviour on 5.4 devices without Magisk (they fail fast with a reason). Intended; it is why
  S2 is its own slice.
- **A stale stored package.** A family/package pair that disappears must self-heal, or an old
  selection silently disables the run; a choice that is merely infeasible is kept and logged.
- **Wrapper drift.** One core implements the handoff and the two wrappers may only add
  prologue/epilogue. A wrapper that grows its own handoff step is a bug, not a rule.
- **A third-party text interface.** `boot-info` is ksud's CLI; a fork can rename or drop it. Any
  `supported-kmis` failure means "cannot answer" and falls back to the release mirror; the two
  managers in hand (ReSukiSU 4.2.0-rc3, KernelSU-Next 3.4.2) are gated, and the probe answers are
  cached per run (two extra execs per candidate).
- **A failed attempt leaves state.** A Magisk attempt that never reached magiskd leaves
  `/data/adb/magisk`, a `/debug_ramdisk` tmpfs mount and the live policy patch before a KSU
  fallback runs. Boot-scoped except the files; the attempt and the fallback are both logged, and
  the strict readiness check (§2.6) is what stops a half-started Magisk from coexisting with a
  KSU load in one boot.
- **Trust.** Magisk and KSU are both third-party managers; the plan selects among what is
  installed and never downloads or ships one. A user-supplied KSU module stays out of scope
  (S5) and, when it comes, is opt-in and hash-pinned.

## 6. Approval checklist

- [ ] One detector, one `HandoffResolution` with a verdict per candidate and an ordered target
      list, `env(targets)` replacing the `magiskBin` inference.
- [ ] One precondition — `preflight()` — called in the app process by every route (direct,
      Shizuku, timer), returning the plan.
- [ ] Magisk selectable on module, W1 and timer; no per-route rule, no product ranking — only
      the detector's order when nothing is stored.
- [ ] The manager's own `boot-info supported-kmis`/`current-kmi` as the KSU rule, with the
      release mirror as the fallback and the uname cross-check as a diagnostic only.
- [ ] The shared core attempts the targets in order, restores enforcing once, and records the
      effective target; the `handoff=…` / `handoff-target=…` log and meta lines. The wrappers
      stay thin.
- [ ] The no-manager refusal at the precondition, with a message naming both families and their
      reasons.
- [ ] S3's timer consumer (the shared core + sidecar) and the S2 debug hook (or S4 first) as the
      bench path.
- [ ] S5 acknowledged as out of scope.

## 7. Amendment 2026-11-27: the exec path and the post-root probe

The guard-generation measurements (the incident's `oplus_security_guard`, the OnePlus's
`oplus_secure_guard_new`) put the app's root-manager handling on the guard's own input path:

- Both generations act on an `execve` path, not on the caller: the old module reports a non-root
  `/data` exec and blocks a root one; the new module blocks the non-root exec outright; only
  `/data/local/tmp` is exempt in both.
- The app is `untrusted_app`: it cannot write or exec in `/data/local/tmp` (measured), and its
  in-place target is the manager's library under `/data/app`. A preflight probe therefore either
  cannot run (staging refused) or, on a guarded ROM, would be a guard event. A probe killed by a
  signal is **no verdict** about the interface, not a missing one — it falls back to the static
  tokens (`ManagerScanner.Probe.killed`).
- The staged `source` the app hands off is in its sandbox (`filesDir/ksu-N`, under `/data`), so
  the core's late-load was itself a root `/data` exec — a block event on the old guard, a report
  on the new one. `handoff_ksu` now copies the source into `HANDOFF_EXEC_DIR` (default
  `/data/local/tmp`) and execs the copy, falling back to the source only when the copy cannot be
  made.
- The re-detection after root is authoritative and lives in the core, before the late-load:
  `late-load --help` must exit 0 and name `--kmi` and `--allow-shell` (the app's own contract);
  `boot-info current-kmi` replaces the resolver's KMI when its first line is KMI-shaped. A target
  that fails the interface check is skipped and the next target attempted; readiness remains the
  verdict.
- Harness: `handoff_test.sh` case 2 asserts the probe and the exec copy, case 8 the KMI override,
  case 9 the token-only refusal. The Gradle tasks declare `handoff.sh` as an input so a core
  change rebuilds both embedded binaries (the timer tool had been silently stale).

Device gate: **passed end to end on the diting** (2026-11-27, run `20261127-102625`) on a stock
`external/boot.img` RAM boot (no `kernelsu`, no `su`). The handoff log: `ksud exec copy:
/data/local/tmp/ghostlock-ksud-11509`; `late-load interface confirmed (rc=0 --kmi=1
--allow-shell=1)`; `kmi: manager answer 'android12-5.10' (resolver 'android12-5.10')`;
`late-load kmi=android12-5.10 source=/data/local/tmp/ghostlock-ksud-11509`; `late-load exit=0`;
`handoff-target=ksu idx=1`; KSU live (`su` as `u:r:ksu:s0`), run store `completed`, enforcing
restored, and the copy removed. The OnePlus 13s cannot exercise a real load — KernelSU-Next is
resident and `rmmod` returns EAGAIN — so there the same core ran as root with readiness stubbed:
the copy, the `late-load --help` contract and the manager's own KMI (`android15-6.6`) all held.
Remaining: a locked new-guard ColorOS unit for the app-side preflight claim.

## 8. Amendment 2026-11-27 (later): preflight feasibility without exec — the static KMI rule

§7 put the app's manager handling on the guard's input path: every preflight exec of a manager
binary is a guard event (old guard: non-root `/data` exec → report; new guard: caller-based, the
app's own exe is under `/data`, → report), and a probe killed by a signal is **no verdict**. The
data the probe was fetching is not behaviour, though — it is a string table inside the manager's
own `ksud`. So the primary rule becomes static, and preflight emits no exec at all.

### The exact KSU semantics (verified against the sources)

Checked in `tiann/KernelSU` and `rifsxd/KernelSU-Next` (identical code), and the same pattern
literal is present in both managers' shipped `libksud.so` on our rigs (ReSukiSU 4.2.0-rc3 and
KernelSU-Next 3.4.2) — a fork cannot silently change it without changing the asset list it prints:

| what | where | semantics |
|---|---|---|
| supported list | `userspace/ksud/src/assets.rs::list_supported_kmi()` | every embedded asset name stripped of the literal suffix `_kernelsu.ko`. `boot-info supported-kmis` prints exactly this list. |
| lookup | `userspace/ksud/src/late_load.rs::run()` | `format!("{kmi}_kernelsu.ko")` → asset table; absent → `asset not found: …`. Membership **is** the check KSU makes before loading. |
| current KMI | `userspace/ksud/src/boot_patch.rs::get_current_kmi()` | `parse_kmi_from_uname()` first, else `parse_kmi_from_modules()`: first `*.ko` in `/vendor/lib/modules` → `modinfo` → the line starting `vermagic` → same parser. |
| parser | `boot_patch.rs::parse_kmi()` | regex `(.* )?(\d+\.\d+)(\S+)?(android\d+)(.*)`, **leftmost** match, result `{group 4}-{group 2}` (android token + `major.minor`). |
| image-buffer parser | `boot_patch.rs::parse_kmi(buffer)` | a *different* regex, `(\d+\.\d+)(?:\S+)?(android\d+)`, for kernel images/boot images; not part of `current-kmi`. |
| asset suffix filter | same as supported list | `_susfs_guard_lkm.ko` names (KSU-Next) are **not** KMIs and must not be matched. |

Two consequences the implementation must honour:

- The KMI **must** be derived with KSU's parser, not our release mirror: a kernel whose uname has
  no `android<NN>` token may still yield a KMI from a vendor module's vermagic, and KSU accepts
  that. Both branches are mirrored, so the two agree by construction.
- The list must be read with the exact suffix strip (`_kernelsu.ko`), or the susfs names and the
  bare parsing tokens (the same regexes appear in the binary) would inflate it.

### The rule

1. **List per candidate, static.** The token scan already has the candidate's bytes
   (`ManagerScanner`); collect every `…_kernelsu.ko` occurrence, strip the suffix — the same set
   `boot-info supported-kmis` prints. The names sit in one blob and are **concatenated with no
   separator** in the shipped binaries, so the KMI is taken as the rightmost KMI-shaped tail
   before the suffix, never by a whole-word walk (measured 2026-11-27: a word walk found 0–1 of
   the 8).
2. **Current KMI, static.** `System.getProperty("os.version")` (= `uname -r`) → KSU's `parse_kmi`;
   if it fails, read the first `*.ko` under `/vendor/lib/modules` and take its `.modinfo`
   `vermagic=` value (the static equivalent of `modinfo`; world-readable, no exec) → the same
   parser.
3. **Verdict.** A KSU candidate is feasible iff it is `suitable` **and** the current KMI is a
   member of its list. Suitability is the static interface tokens (`late-load`, `allow-shell`)
   **plus one manager-owned fact** — a KMI asset list or the `supported-kmis` interface token —
   because the exploit app's own native embeds the handoff script and otherwise matches the
   tokens (measured 2026-11-27: it was ranked as a candidate; after the rule it is a row, never a
   choice). The reason names
   both: `provides android12-5.10, android14-6.1; kernel is android15-6.6`. No interface exec.
4. **The KMI still travels with the target** (`late-load --kmi … --allow-shell`), and the
   post-root re-detection of §7 still replaces it when the core gets a KMI-shaped answer.
5. **Fallback unchanged in role, demoted:** a candidate with no `_kernelsu.ko` tokens falls back to
   the release mirror (§2.4 table). A parser failure with no vermagic fallback is infeasible, as it
   would be for KSU.

### What this does not prove (the permissiveness the user flagged)

The static list is the manager's **embedded asset set**, i.e. KSU's necessary condition — it says
"this build ships a module for this KMI line", not "late-load will succeed". Load can still fail:
the driver-install syscall blocked by seccomp (ksud's own string), a vendor module blacklist or
guard (`oplus_secure_guard`'s `block_modules`), a vermagic/signature refusal, SELinux. So the
preflight verdict is an **upper bound**; the ordered attempt loop and the readiness check remain
the authority, exactly as §7 established. The gain is that no preflight exec reaches the guard,
and no probe verdict depends on a vendor kill.

### Tests to pin

- The parser against the three release strings on our rigs — `5.10.236-android12-9-00003-g…`
  → `android12-5.10`; `6.1.138-android14-11-g…` → `android14-6.1`; `6.6.66-android15-8-g…`
  → `android15-6.6` — plus a `vermagic` line, a 5.4 qgki release (no `android` token → parse
  failure) and a no-token string.
- The candidate gate: our own native's script tokens (`late-load`, `--allow-shell`,
  `kernelsu.ko`, no assets, no `supported-kmis`) are not a manager; a payload with
  `supported-kmis` and no assets still is.
- The asset names concatenated with no separator (the shipped shape), behind a preceding
  string that breaks a whole-word walk.
- The two managers' observed lists as fixtures (the same eight: `android12-5.10`, `android13-5.10`,
  `android13-5.15`, `android14-5.15`, `android14-6.1`, `android15-6.6`, `android16-6.12`,
  `android17-6.18`; no 5.4, no `android11-5.10`), and that `_susfs_guard_lkm.ko` names and the
  bare regex tokens do not enter the list.
- A one-off agreement measurement: on each rig, the static list equals `boot-info supported-kmis`
  and the static parse equals `boot-info current-kmi`, recorded in the session evidence.
- The guard property: the whole preflight path contains no `exec`.
