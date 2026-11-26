# Handoff resolution — one detector, one selected manager, one explicit plan

**Status:** design for operator approval, 2026-11-26. L-level (the delivery path's
selection). No code yet. Slices and gates below are the implementation contract.

**Question.** The root manager is currently chosen by an inference — the module pipeline
stages Magisk, and `handoffEnv(magiskBin)` reads a non-null directory as "hand off to
Magisk"; every other run gets the `kernelsu` default. Is that good enough, and what should
replace it before a second KernelSU path exists?

**Answer.** No, it is a v1 accident that happened to be correct. Replace it with: one
detector that enumerates **both** manager families, one resolver that turns
`(release, selected manager)` into a `HandoffPlan` with a feasibility verdict and a
**reason**, and the native reads the plan (the `GHOSTLOCK_HANDOFF` transport stays). The
pipeline stops constraining the family; the version gate for KernelSU becomes a named
feasibility fact, not an implicit failure after the exploit.

## 1. What exists today, and why it holds only by accident

- **`ManagerScanner`** (`app/.../data/ManagerScanner.kt`) enumerates KernelSU-family
  managers only: an ELF prefilter, a `late-load`/`allow_shell` token scan, and a
  `late-load --help` probe. `Candidate.suitable` is that verdict; `resolve(candidates,
  selected)` picks the stored `manager_package` or the first suitable. Two call sites:
  the run-start preflight (`AndroidGhostlockRepository.kt:823-845`) and the Shizuku
  service.
- **`MagiskStage`** (`app/.../data/MagiskStage.kt`) separately looks up
  `com.topjohnwu.magisk` / `io.github.vvb2060.magisk`, and stages
  `magisk`/`magiskinit`/`magiskpolicy` (+ four optional) from the APK's native libs.
- **The switch**: `handoffEnv(magiskBin)` (`AndroidGhostlockRepository.kt:1263-1271`)
  sets `GHOSTLOCK_HANDOFF=magisk` iff `magiskBin != null`; the two DirtyFrag stage calls
  pass `run?.magiskBin` (`:1421`, `:1483`). `run` is the `ModuleRun`, created only in the
  module-pipeline resolver block (`:1603-1647`, which today *requires*
  `MagiskStage.stage` to succeed). Otherwise the env is absent and the native's
  `RuntimeConfig` default `"kernelsu"` applies (`src/core/session/runtime_config.h:73`,
  parsed at `runtime_config.cpp:119`), baked into the script at `ops.cpp:314`/`:911`.
- **The script** keeps two branches under one `if [ "$HANDOFF" = magisk ]`
  (`ops.cpp:763`): `magisk_handoff()` (`:420-473`) and the KernelSU late-load branch
  (`:776-860`), whose KMI derivation fails closed on a tokenless 5.4 kernel.

The defects that follow:

1. **Inference, not decision.** "A Magisk directory exists" means "hand off to Magisk"
   only because the module branch is the only thing that stages one. A second capable
   manager makes the inference wrong, silently.
2. **Asymmetric feasibility.** Module ⇒ Magisk is checked before the exploit; W1 ⇒ KSU is
   a compile-time default with no resolution-time check. A device with no suitable ksud
   fails after the exploit, not before.
3. **No reason on record.** The script logs which branch ran; the app and run store do
   not say why it was chosen or why the alternative was rejected.
4. **One branch, two contracts.** The venus KMI bug (2026-11-26) was a precondition only
   one handoff needed, shared by both; the fix moved it into the KSU branch. The
   structural form of that lesson is not yet taken: each handoff should own its
   preconditions.

## 2. Design

### 2.1 One detector

A `RootManagerDetector` returns candidates from both families, read-only:

```kotlin
sealed interface RootManager {
    val packageName: String; val label: String
    data class Ksu(val ksudPath: String, val interfaceOk: Boolean?, ...) : RootManager
    data class Magisk(val nativeLibDir: String, val required: Set<String>) : RootManager
}
```

- The KSU pipeline is today's `scan()` unchanged (ELF prefilter → tokens → `late-load
  --help`); `interfaceOk == null` (probe unavailable) keeps its static-token fallback.
- The Magisk branch is `MagiskStage`'s lookup minus the staging: the package exists and
  its `nativeLibraryDir` carries `magisk`, `magiskinit`, `magiskpolicy`. **Detection must
  not stage** — `MagiskStage.stage` deletes and rewrites its destination directory, so
  staging belongs to the selected candidate only.
- No persistent cache (same rule as `ManagerScanner`: a stale "capable" verdict is the one
  failure worth avoiding).

**Package visibility (measured 2026-11-26, diting, Android 15).** The detector does not
need `QUERY_ALL_PACKAGES`: a probe app (`targetSdk` 31) with **no permissions** and only
`<queries>` entries resolved `com.topjohnwu.magisk` and `com.resukisu.resukisu` via
`PackageManager.getPackageInfo`, while an undeclared installed user app
(`com.rifsxd.ksunext`) was HIDDEN and `getInstalledPackages(0)` returned 273 of the
shell's 356 packages. (The visible-set count confirms the filter is active; system
packages such as Settings are auto-visible and are not a valid negative control.
`run-as <app> pm path <pkg>` is **not** a faithful probe — it resolves undeclared
packages; use the app-context `PackageManager` call.)

Consequences:

- The manifest must declare both Magisk packages in `<queries>`:
  `com.topjohnwu.magisk` and `io.github.vvb2060.magisk` (the six KSU packages are already
  declared). Without the entries, `getPackageInfo` throws and `MagiskStage` reads as
  "manager not installed".
- If `QUERY_ALL_PACKAGES` is removed (decision §4.6), `ManagerScanner`'s enumeration
  shrinks to the visible set: the declared builtins still scan normally, but an
  undeclared KSU fork becomes invisible — the detector is a known-list check. File access
  to the manager's APK/libs is unaffected by visibility; it was never the permission that
  allowed the staging copy.

### 2.2 One resolver

```kotlin
data class HandoffPlan(
    val kind: Kind,              // KSU | MAGISK          (NONE is an open decision, §4.1)
    val state: State,            // FEASIBLE | INFEASIBLE
    val reason: String,          // required; user-facing and written to the run store
    val manager: RootManager?,
    val payload: Payload,        // ksu: ksud path; magisk: staged bin dir
)
```

Feasibility rules, evaluated **before the exploit**:

| family | feasible when | infeasible reason (examples) |
|---|---|---|
| KSU | release `major.minor >= 5.10` **and** a suitable ksud is staged (`interfaceOk != false`) | `ksu: kernel line <5.10 has no late-load module` (the temporary gate, §2.4); `ksu: no ksud with late-load --help` |
| Magisk | the manager package exists and all required native libs are present | `magisk: manager not installed`; `magisk: native libs incomplete (missing magiskpolicy)` |

Selection: the stored package (`ManagerScanner.PREF_KEY = "manager_package"`) if it
resolves to a **feasible** candidate; otherwise a defined auto order, with the stored
choice self-healing (cleared, like the route/carrier choices, when the package is gone).

Auto order is a **product preference table**, not a feasibility fact, so it can change
without touching the rules:

| pipeline | v1 order | why |
|---|---|---|
| module (5.4 line) | `magisk → ksu` | Magisk needs no kernel module; KSU is infeasible below 5.10 anyway |
| W1 (5.10+) | `ksu → magisk` | today's default; KSU is the managed root the chain expects |

The module chain's hard `MagiskStage.stage` requirement (`:1603-1647`) is replaced by
"resolve a feasible plan, then stage that candidate". On 5.4 this is observably
identical; it becomes a real generalization only after §4.2's gate.

### 2.3 The selected plan goes down the existing wire

`handoffEnv(plan)` replaces `handoffEnv(magiskBin)` and serialises the plan:
`GHOSTLOCK_HANDOFF=…`, `GHOSTLOCK_MAGISK_BIN=…` (Magisk), and nothing new for KSU
(ksud is already `$HOME_DIR/ksud` + the six `/data/app` patterns). The native's
`RuntimeConfig` is unchanged; the script's two branches are unchanged. The `none` value
is not added in v1 (§4.1).

Every plan is logged and recorded:

```
handoff=magisk reason=selected:com.topjohnwu.magisk feasible=1
handoff=ksu state=infeasible reason=kernel-line<5.10
```

app log + run-store meta, alongside the existing `module pair` line.

### 2.4 The temporary KernelSU gate

> **KSU is infeasible on kernels < 5.10.** Late-load selects `<kmi>_kernelsu.ko` from the
> manager's embedded assets; the official builds ship `android12-5.10` and later, and a
> vendor `5.4 qgki` has no KMI token at all.

Encoded as a pure `ksuFeasibility(release)` reason in the resolver, so:

- the 5.4 module chain is unchanged (Magisk is the only feasible family);
- a 5.4 device with no Magisk is **refused before the exploit** with that reason, instead
  of failing at `ksud-missing` or at the KMI guard afterwards;
- lifting it later is a deliberate change tied to a gated compatible build (a fork with an
  embedded 5.4 module, or a user-supplied hash-pinned `.ko` via `insmod`), not deleting a
  string compare.

The script's KMI guard stays as defence in depth: the Kotlin gate makes the common case
early and named; the guard still fails closed if a handoff is ever forced.

## 3. Files and slices

**S1 — detector + plan + resolver, no behaviour change.**
Files: `ManagerScanner.kt` (unified candidates, Magisk detection, keep `resolve` for the
package choice), new `HandoffResolver.kt`, `MagiskStage.kt` (split lookup from staging),
`AndroidGhostlockRepository.kt` (`handoffEnv(plan)`, the module block, the preflight,
logs/meta), `app/src/main/AndroidManifest.xml` (add the two Magisk `<queries>` entries;
§4.6 decides `QUERY_ALL_PACKAGES`), `runtime_config` untouched.
The v1 preference table reproduces today's mapping exactly.
Gates: host tests over fake candidates and releases (`5.4.233…`, `5.10.236…`,
`6.1.138…`); app/profile-core suites; device smoke that the logged plan matches the
branch that ran (diting: ksu; star/venus: magisk).

**S2 — feasibility rules and the <5.10 gate.**
Files: `HandoffResolver.kt` (the table, `ksuFeasibility`), picker strings
(`FieldLabels` EN+ZH), the preflight refusal text, the run-store meta line.
Behaviour change: a no-Magisk 5.4 device is refused early with the named reason; a
selected-but-infeasible manager self-heals to auto.
Gates: unit tests for every rule branch; the star/venus rig (module+Magisk completes);
the diting (KSU feasible); a no-manager rig (refusal); a 5.4 rig with KSU selected
(infeasible reason, no exploit run).

**S3 — picker and stored selection.**
Files: the root-manager picker (extend the existing Advanced entry), preference
migration from the KSU-only key, `ManagerScannerTest`-style host tests for the pure parts.
Gates: app suites; a device pass selecting each family and reading the logged plan.

**S4 — the future compatible fork (not in scope).**
The extension point is one new KSU feasibility source in the resolver: an embedded KMI
listed by `boot-info supported-kmis` (matched by an explicit rule or the user's choice),
or `insmod` + a user-supplied `.ko`. Each carries its evidence; the `<5.10` rule is lifted
only for a gated build, and the outcome is "root shell, manager best-effort", never a
requirement of the chain.

## 4. Decisions to make with this plan

1. **"No manager" policy.** Today the run-start preflight refuses when no suitable ksud
   exists. With Magisk detected, a Magisk-only device becomes runnable (intended). A
   device with neither: keep the refusal (v1), or add a `NONE` plan ("root shell, no
   manager", the Samsung shape)? v1 keeps the refusal; `NONE` is a later decision that
   touches the script (a third branch) and the run verdict.
2. **Module chain + KSU on ≥5.10 is untested.** The switch module makes the device
   permissive; KSU's late-load then re-enforces from its own init (`late_load.rs`'s
   comment). The version gate does not prove that pair — it needs its own bench gate
   before the preference table offers it (v1's table prefers Magisk for the module chain
   for this reason).
3. **The Shizuku path.** `GhostlockUserService` scans managers in its own process and sets
   no handoff env today; the module pipeline is direct-only (the resolver is called only
   from the repository). In v1 the service resolves the same plan for KSU candidates and
   inherits the `<5.10` refusal; Magisk staging from the service is a separate question
   and is not claimed.
4. **Failure semantics.** The module chain currently ends `exit 1` when
   `magisk_handoff` is not ready, although the init shell existed. With a selectable
   manager this should become a distinct outcome ("root shell achieved, manager not
   ready") rather than reading as a chain failure. v1 keeps the exit code and records the
   plan + reason; the verdict change is a separate, small design.
5. **Probe honesty.** KSU's verdict is an interface probe (`late-load --help`, confirmed /
   static / missing); Magisk's is a file inventory. The picker must show them as different
   kinds of evidence ("interface confirmed" vs "binaries present"), so it never promises
   more than the resolver checks.
6. **`QUERY_ALL_PACKAGES`.** The operator's constraint is not to ask for package-listing
   visibility. v1 removes the permission and relies on the `<queries>` declarations
   (§2.1), accepting that an undeclared KSU fork is invisible. Keeping the permission
   would restore arbitrary-fork discovery; that is a product decision, not a detector
   detail.

## 5. Risks

- **Preference drift.** The v1 table reproduces today's mapping; if it silently diverges
  (e.g. W1 to Magisk without a gate), the change is a product behaviour change, not a
  refactor. The device smoke in S1 exists to catch exactly that.
- **Refusal timing.** Moving the no-manager refusal before the exploit changes user-visible
  behaviour on 5.4 devices without Magisk: they now fail fast with a reason instead of a
  post-exploit `ksud-missing`. Intended, and it is the reason S2 is its own slice.
- **A stale stored package.** A family/package pair that disappears must self-heal, or a
  user's old selection silently disables the run; the resolver clears and logs.
- **Trust.** Magisk and KSU are both third-party managers; the plan selects among what is
  installed and never downloads or ships one. A user-supplied KSU module remains out of
  scope (§3 S4) and, when it comes, is opt-in and hash-pinned.

## 6. Approval checklist

- [ ] One detector, one `HandoffPlan`, `handoffEnv(plan)` replacing the `magiskBin`
      inference.
- [ ] The v1 preference table (module: magisk→ksu; W1: ksu→magisk) as an explicit product
      choice.
- [ ] The `<5.10` KSU feasibility gate as a resolver reason (temporary, with the lift path
      named).
- [ ] The preflight refusal and the `handoff=… reason=…` log/meta line.
- [ ] S4's extension point acknowledged as out of scope.
