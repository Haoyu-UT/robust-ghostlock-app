# Manager detection visibility — the OEM app-list permission, probe-first detection, and the startup prompt

**Status:** design approved; **slices 1–2 implemented and CN-gated 2026-11-28** (build 966,
host gates green; diting/Xiaomi.eu grant + deny, OnePlus/ColorOS and POCO/Global no-permission,
and the **decisive stock-CN gate** all device-tested). L-level: manifest, the manager detector,
the preflight failure surface and the UI. No change to the attack path, the wire, the profile
format or the native.

**Question.** Several users report that the run-start preflight fails with "no manager found"
although a KernelSU/Magisk manager is installed and working. The suspicion is that
`QUERY_ALL_PACKAGES`, declared in the manifest, is not granted by the OS. What is actually
happening, and what should detection do about it?

**Answer.** The manifest permission is the wrong lever. Chinese ROMs (MIUI 13+/HyperOS, ColorOS,
OriginOS, HarmonyOS/MagicOS) add a **second, dangerous, runtime permission** standardized in
TAF 108-2022: `com.android.permission.GET_INSTALLED_APPS`. Declaring it is not granting it; the
user must answer a runtime dialog. Until then `getInstalledPackages()` returns the calling app
alone — even `QUERY_ALL_PACKAGES` does not help. Our detector is built entirely on that API, so
it sees nothing and the preflight refuses. The fix is three layers: probe the known manager
packages directly (works under the gate, no permission needed), declare and **always ask** for
the OEM permission at startup, and make the refusal say which of the three states it is in.

**The decisive test is a CN phone.** The gate is demonstrated on a Xiaomi ROM below, but only a
stock Chinese build (HyperOS CN / ColorOS CN / OriginOS CN / MagicOS CN) settles whether it is
enforced by default for apps that do *not* declare the permission — which is the users' actual
state. See §6.

## 1. The mechanism (verified against the vendor documentation and on device)

- **Standard:** T/TAF 108-2022《移动终端应用软件列表权限实施指南》, built on YD/T 2407-2021.
- **Permission:** `com.android.permission.GET_INSTALLED_APPS`, protection level `dangerous` on
  MIUI/HyperOS (measured `protection=0x1`), defined by `com.lbe.security.miui`. It is an
  **additional** management layer on top of `android.permission.QUERY_ALL_PACKAGES`.
- **Controlled APIs** (the only four the vendor documents): `getInstalledPackages`,
  `getInstalledPackagesAsUser`, `getInstalledApplications`, `getInstalledApplicationsAsUser`.
  `getPackageInfo` is **not** on that list.
- **Without the grant:** the Xiaomi documentation says the app "cannot directly return the app
  list, only the calling app's own information". Measured: `getInstalledPackages()` returned
  exactly one package, the app itself — not even system apps.
- **Default:** Xiaomi changed the default to deny/ask from May 2023. The permission is
  grantable per app through the system dialog; the dialog exposes 拒绝 / 始终允许 /
  仅在使用中允许.
- **Declaring turns it on.** This is the part that matters most: the permission only exists for
  an app that declares it, and declaring it puts the app under the gate. A fresh install that
  declares the permission enumerated only itself *before* the user answered anything. Xiaomi
  documents the `do_not_need_get_installed_apps` meta-data as the way to switch the gate off —
  which is only necessary because declaring switches it on.
- **Support detection:** `PackageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)`
  returns a `PermissionInfo` iff the ROM defines the permission (`NameNotFoundException`
  otherwise). Xiaomi's reference code additionally checks
  `PermissionInfo.packageName == "com.lbe.security.miui"`; treat "defined" as supported and
  record the defining package (vivo's overseas builds skip the control; its doc uses
  `ro.vivo.product.overseas`).
- **`QUERY_ALL_PACKAGES`** remains a normal permission (granted at install, no prompt). It
  governs AOSP package visibility; it does not override the TAF gate.
- **The layers are independent.** Granting TAF without `QUERY_ALL_PACKAGES` leaves AOSP
  visibility filtering intact (measured: unchanged count). With `QUERY_ALL_PACKAGES` present,
  the TAF grant restores the full list.

## 2. What the code does today, and the defects

Detection runs in the app process (the Shizuku service receives a plan and never scans,
`handoff-resolution-plan.md` §2.1).

1. **KSU detection is enumeration-only.** `ManagerScanner.scan()` (`ManagerScanner.kt:108`)
   calls `getInstalledPackages(0)` (`:112`) and then looks at each app's `nativeLibraryDir`.
   Under the gate the list is the app itself, so no candidate exists. The Magisk half
   (`MagiskStage.detect()`, `MagiskStage.kt:46`) already probes the two Magisk packages
   directly with `getPackageInfo`, and both are in `<queries>` — so Magisk survives while KSU
   does not. That asymmetry is the bug.
2. **"Not found" and "installed but unusable" are conflated.** `HandoffResolver.choices()`
   (`HandoffResolver.kt:149`) drops every KSU candidate that is not `suitable`. A user with
   SukiSU Ultra (`com.sukisu.ultra`, whose bundled ksud has no `late-load`) sees the picker
   toast "No usable ksud found. Install a KernelSU-family root manager." — while one is
   installed. The run's own refusal is better (it carries the "not suitable" reason through
   `familyLines`) but the picker is not.
3. **The payload is only looked for in `nativeLibraryDir`.** An APK built with
   `extractNativeLibs=false` keeps `lib/arm64-v8a/libksud.so` inside the APK; the native dir
   can be empty, and the candidate is skipped. `MagiskStage` already has the APK-member
   fallback; `ManagerScanner` does not.
4. **No visibility audit, no permission handling, and a misleading refusal.** The preflight
   (`AndroidGhostlockRepository.preflight`, `:840`) blocks with `NO_ROOT_MANAGER`; the picker
   (`GhostlockViewModel.onOpenManagerPicker`, `:2206`) shows the generic toast; the home module
   card probes a list that omits `com.sukisu.ultra`. None of them can tell the user that the OS
   hid the list.

The 2026-11-26 visibility decision in `handoff-resolution-plan.md` §2.1 ("`QUERY_ALL_PACKAGES`
is normal, so the whole-device scan always works; `<queries>` is the fallback") is correct for
AOSP and incomplete for these ROMs — it did not know about the TAF layer.

## 3. Evidence: the probe and what it measured (2026-11-28)

A throwaway probe APK (not shipped; sources were built in `/tmp`, regenerable from Appendix A)
was installed on the three devices at hand. Two variants:

- **A (product shape):** `QUERY_ALL_PACKAGES` + `com.android.permission.GET_INSTALLED_APPS` +
  `<queries>` for the ten packages.
- **Q:** the TAF permission + `<queries>`, **without** `QUERY_ALL_PACKAGES`.

Each run logs: the permission definition and defining package, both grant bits,
`getInstalledPackages()` size, the canary (non-declared user apps visible), which declared
packages appear in enumeration, and `getPackageInfo` for every declared package.

| device / ROM | TAF defined | dialog | denied | granted |
|---|---|---|---|---|
| diting — Xiaomi.eu `OS3.0.3.0.VLFCNXM`, A15 | **yes**, `com.lbe.security.miui`, `0x1` | **yes** | **`total=1`**, `userApps=1`, `canary=0` | **`total=358`**, `userApps=17`, `canary=12` |
| diting — variant Q (no `QUERY_ALL_PACKAGES`) | yes | yes | `total=275`, `userApps=5`, `canary=0` (AOSP visibility) | `total=275` — **unchanged** |
| OnePlus CPH2723 — ColorOS A16 (IN) | **no** (`NameNotFoundException`) | skipped | — | `total=552`, `userApps=117` |
| POCO duchamp — Global A16 | **no** (static `dumpsys`) | — | — | probe install refused by Install-via-USB |

Exact lines:

```
# diting, fresh install, dialog answered 拒绝
requestResult code=42 perms=[com.android.permission.GET_INSTALLED_APPS] results=[-1] grantedNow=-1
... checkSelfPermission(TAF)=DENIED
installedPackages total=1 userApps=1 canary(non-declared user apps)=0

# diting, dialog answered 始终允许
requestResult ... results=[0] grantedNow=0
... checkSelfPermission(TAF)=GRANTED
installedPackages total=358 userApps=17 canary(non-declared user apps)=12
declared seen in enumeration=[com.resukisu.resukisu, moe.shizuku.privileged.api,
    com.sukisu.ultra, com.topjohnwu.magisk]
```

The dialog itself (MIUI's, `package=com.lbe.security.miui`):
`允许"PermTestAll"获取已安装的应用信息？` with 拒绝 / 始终允许 / 仅在使用中允许.

Findings:

1. **Declaring the permission activates the gate.** A truly fresh install that declares it read
   `total=1` at startup, before the dialog was answered. (A reinstall after a prior grant showed
   `total=358` at startup with the standard bit DENIED — MIUI's own record survived the
   uninstall — then 拒绝 flipped it to `1`. The dialog answer is authoritative; the bit alone is
   not.)
2. **Deny = self only; grant = full list.** The state follows the dialog answer.
3. **The layers are independent.** In variant Q the grant changed nothing (`275` before and
   after); AOSP visibility still filtered. The TAF grant is not a substitute for
   `QUERY_ALL_PACKAGES`.
4. **`getPackageInfo` for `<queries>` packages works in every filtered state.** In the
   TAF-denied state, every installed declared manager (ReSukiSU, Magisk, SukiSU Ultra,
   Shizuku) returned `PROBE_OK`, while enumeration held only the app itself and even declared
   packages were absent from it. Probe-first is validated on the real ROM.
5. **The canary works** — 12 non-declared user apps when unfiltered, 0 when filtered.
6. **`checkSelfPermission` alone can be stale.** After `adb shell pm revoke`, the bit read
   DENIED while enumeration stayed full until the next dialog answer. Test with the dialog, not
   `pm revoke`; the audit records both the bit and the canary.
7. **Where the permission is undefined, declaring is a no-op** — OnePlus verified (no dialog, no
   change); POCO static.
8. **"仅在使用中允许" is sufficient:** `checkSelfPermission` reports GRANTED; detection and the
   preflight run in the foreground.

**Lab limitation (recorded honestly):** no stock CN ROM was at hand. The diting is Xiaomi.eu
(CN-based, but with its security center modified); the POCO is Global and refused the probe
install. The mechanism is demonstrated on a Xiaomi ROM and the design is correct in both
filtered states, but the users' actual state — a stock CN build — is not yet measured. **A CN
phone is the decisive test (§6).**

## 4. Design

### 4.1 Probe-first detection (slice 1; no permission required)

The known packages become the primary source; enumeration becomes an additive source for
unknown forks.

- **Probe list.** `ManagerScanner.PROBE_PACKAGES = BUILTIN + "com.sukisu.ultra"` (7). The
  Magisk list stays `MagiskStage.PACKAGES` (2). The manifest `<queries>` must contain
  `PROBE_PACKAGES + MagiskStage.PACKAGES` plus the existing Shizuku entry (10 total, within
  Xiaomi's "no more than 10" guidance). A unit test parses the manifest and fails when a code
  list entry is missing.
- **Scan flow.**
  1. For every probe package: `getPackageInfo(pkg, 0)`; a hit is an installed manager. Known
     packages are **not** subject to the `FLAG_SYSTEM` skip (a preinstalled or updated system
     manager must still count).
  2. `getInstalledPackages(0)`; add packages not already probed, keeping the existing
     user-app/system rule for these extras (self excluded).
  3. For each installed package, resolve payloads: `nativeLibraryDir` first (the current
     streaming ELF/token scan); **if none is ksud-like and the package was probed**, also scan
     the APK — `sourceDir` plus `splitSourceDirs`, entries under `lib/arm64-v8a/`, the same ELF
     shape prefilter and token scan over the entry stream, bounded by `MAX_SCAN_BYTES`.
  4. A probed package that yields no ksud-like payload still becomes a `Candidate` with
     `path = null` and a verdict ("installed; no late-load-capable ksud in its libs"), so the
     picker and the refusal can say so. `path` becomes nullable; `toManager()` passes
     `path.orEmpty()`, and staging only touches feasible rows.
- **`installedManager()`** (the home module badge) stays on
  `MagiskStage.PACKAGES + BUILTIN`: a presence-only badge must not call a
  known-but-unsuitable manager (SukiSU Ultra) "ready". The picker and the refusal name it
  instead; a tri-state badge is a follow-up.
- **Cost.** Nine `getPackageInfo` calls plus one enumeration; the APK-member scan runs only for
  a probed package whose native dir produced no candidate. No new exec, no staging.

### 4.2 Visibility audit

`ManagerScanner.scan()` returns `ScanResult(candidates, visibility)`:

```kotlin
data class VisibilityAudit(
    val permissionDefined: Boolean,      // getPermissionInfo != null
    val permissionPackage: String?,      // e.g. com.lbe.security.miui
    val permissionGranted: Boolean,      // checkSelfPermission == GRANTED
    val enumerationCount: Int,
    val userAppCount: Int,
    val canary: List<String>,            // non-declared user apps visible (sample)
    val declaredSeen: List<String>,      // declared packages present in enumeration
    val probedInstalled: List<String>,   // declared packages getPackageInfo found
) {
    val provenUnfiltered: Boolean get() = canary.isNotEmpty()
    val possiblyFiltered: Boolean get() = !provenUnfiltered
    val canAsk: Boolean get() = permissionDefined && !permissionGranted
}
```

Canary predicate (one-sided proof — it can prove the filter is off, never that it is on):

```
declared  = PROBE_PACKAGES + MagiskStage.PACKAGES + Shizuku
installer = packageManager.getInstallerPackageName(self)   // may be null
canary    = enumerated.filter {
    it.packageName != self &&
    (it.applicationInfo.flags and FLAG_SYSTEM) == 0 &&
    it.packageName !in declared &&
    it.packageName != installer
}
```

No side channel is used to see hidden apps (`queryIntentActivities`,
`getPackagesHoldingPermissions`, `LauncherApps`): obtaining names the gate is meant to hide
would circumvent the user's app-list choice. The audit only observes what the controlled API
chose to return.

The audit is recorded in `scan_test`, in logcat (`handoff:`), and as the first run-log line of a
started run (`handoff: app-list permission=…`). Putting it into the run-store meta file as well is
a follow-up; the run log already travels with the run.

### 4.3 The permission: declare, and always ask (slice 2; atomic)

**Operator decision (2026-11-28): always ask for the permission as long as the OS lists it.**
The canary is diagnostics/verification, not a gate on the prompt.

**Probe-first is orthogonal to the ask.** Detection success never suppresses the prompt and
never changes its condition: the startup dialog is shown whenever the OS lists the permission
and the grant bit is off — even when a suitable manager was found by direct probe, even when the
canary proves the enumeration is already unfiltered, and even when the preflight would pass with
the current list. Probe-first is the fallback for a denied or absent grant: it keeps known
managers detectable in that state. The permission changes what the app can *see* (unknown forks,
a whole-device scan), never whether it asks, and never which manager a known package resolves to:
with a known manager installed, the run's handoff target is identical before and after the
grant. The refusal dialog (§4.4) is a second chance offered when no feasible manager exists, not
the trigger for the request.

- **Manifest:** add `<uses-permission android:name="com.android.permission.GET_INSTALLED_APPS" />`.
  Keep `QUERY_ALL_PACKAGES`. Add `com.sukisu.ultra` to `<queries>` (can ship in slice 1 — it is
  AOSP visibility, not the TAF gate). Never add `do_not_need_get_installed_apps`.
- **Support state:** `ManagerScanner.appListPermission(context): AppListPermissionState`
  (`supported`, `definingPackage`, `granted`), from `getPermissionInfo` and
  `checkSelfPermission`. `NameNotFoundException` = not supported = never ask.
- **Startup prompt.** `GhostlockViewModel.initialize()` runs `maybeAskAppListPermission()` after
  the state loads; condition: `supported && !granted`. The overlay holds one dialog at a time:
  the existing notification rationale is offered first; if a dialog is already up, the app-list
  prompt is deferred to its dismissal (a one-shot `appListPromptPending` flag). Every launch
  until granted; no permanent dismissal (a 稍后 button dismisses for this launch only).
- **Dialog:** new `AppDialog.AppListPermission` (title + message), mirroring
  `NotificationPermission`, rendered with 授予 / 稍后. The message states the consequence of
  denial: *without this, only root managers the app knows by package name can be found*.
- **Request path:** `GhostlockEffect.RequestAppListPermission` → a `MainActivity`
  `ActivityResultContracts.RequestPermission` launcher for the permission string →
  `viewModel.onAppListPermissionResult(granted)`. On grant: re-run `refreshSnapshot` (support,
  module card) and, if the dialog came from a Run refusal, re-run the preflight once and
  continue the run the user tapped. On denial with
  `shouldShowRequestPermissionRationale == false` after a previous ask: offer 打开设置
  (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) instead of re-requesting. A first-ever ask
  also reports `false`, so a stored "asked" flag distinguishes the two.
- **"仅在使用中允许"** is accepted: the bit is GRANTED and detection/preflight are foreground.
- **Why the declaration and the prompt are one unit:** declaring activates the gate on Xiaomi.
  Shipping the declaration without the prompt would leave every Xiaomi user on a fresh install
  with self-only enumeration until they find the permission in Settings. Slice 2 must be one
  commit/release; slice 1 must not declare the permission.

### 4.4 Truthful refusal and picker (slice 1/2)

- **`choices()`** includes installed-but-unsuitable KSU candidates with their verdict
  (feasible = false); the picker's existing infeasible-row behavior (toast and keep the dialog
  open) then explains instead of hiding. This covers SukiSU Ultra and any manager whose payload
  has no `late-load`.
- **Preflight refusal.** This dialog is a second chance, not the primary ask (the startup prompt
  is independent and runs regardless of detection). When `plan.isEmpty`:
  - if the audit says `canAsk` or `possiblyFiltered` → new
    `PreflightFailure.APP_LIST_RESTRICTED`; the dialog says the system may be hiding the
    installed-app list, names the defined permission package when known, and offers 授予 (when
    `canAsk`) or 打开设置. The family lines and the audit line go into `Blocked.reasons`.
  - otherwise → `NO_ROOT_MANAGER` as today.
- **Picker empty state.** Three messages instead of one: no manager installed; a manager is
  installed but unusable (the unsuitable rows already show it); the OS hides the app list
  (with the grant action). The current `manager_picker_empty` string is no longer used for the
  second and third cases.
- **Home module card.** `moduleFacts` uses the probe list; note (follow-up, not required here)
  that "installed but unsuitable" could get its own card state.
- **Strings** (EN + ZH): the startup/refusal title and message, 授予 / 稍后 / 打开设置, the
  picker hints, and the audit line labels. The existing strings stay for their current uses.

### 4.5 What this deliberately does not do

- **No root-side discovery** (operator, 2026-11-28): a manager found only after W1/W2 would
  appear from nowhere in the UI, which is more confusing than a truthful "not found". The
  deleted six-pattern search is not revived.
- **No side-channel enumeration** (see §4.2).
- **No attack-path, wire, profile or native change.** `GHOSTLOCK_HANDOFF_TARGETS`, the handoff
  core, `ops.cpp`/`timer.c` and every profile are untouched. A candidate with a null payload
  path is never feasible, so it never reaches staging.

## 5. Slices

**Slice 1 — probe-first detection and truthful UI (no declaration).**
`ManagerScanner` probe-first + APK-member fallback + nullable path + `VisibilityAudit`;
`ScanResult` through `preflight`, `managerCandidates`, `scan_test`; `choices()` unsuitable rows;
`PreflightFailure.APP_LIST_RESTRICTED` + refusal/picker strings; `<queries>` + `com.sukisu.ultra`;
manifest/code-list test; Robolectric tests with a `PackageSource` seam (filtered enumeration,
probes succeed; APK-only payload; canary states). This slice alone fixes the reported preflight
failure for every supported manager, with no prompt and no behavior change on ROMs that do not
gate.

**Slice 2 — the permission (one unit with the declaration).**
Manifest `com.android.permission.GET_INSTALLED_APPS`; `AppListPermissionState`; startup dialog +
request/result/settings flow; re-audit and one-shot re-preflight on grant; run-meta audit lines;
strings EN/ZH. Ships only with the prompt.

**Slice 3 — verification** (§6). A build is pushed to the NAS
(`ghostlock-builds/2026-11-28-manager-visibility/` + `BUILD.txt`), per the standing rule.

## 6. Gates and verification

**Expected outcomes by environment (the matrix the device gates verify):**

| environment | current build (pre-fix) | slice 1 only (probe-first) | slice 2 (declare + prompt) |
|---|---|---|---|
| **CN stock** `VLFCNXM` (the report) | `NO_ROOT_MANAGER` with a manager installed; picker toast; module card says missing | known managers found by direct probe → preflight passes; unknown forks still hidden; the state is nameable | prompt; **denied** → known managers still found by probe, `APP_LIST_RESTRICTED` when none, list-hidden explanation; **granted** → full enumeration and the run's handoff unchanged. **Improved.** |
| **Xiaomi.eu** (current rig) | undeclared; full enumeration; detection works (measured) | detection unchanged; the picker lists installed-but-unsuitable managers (SukiSU Ultra); APK-only payloads are found | prompt (the declaration activates the gate); **granted** → full enumeration restored (measured `358`/`canary 12`), **non-regressive**; **denied** → known managers only, unknown forks hidden (accepted cost) |
| **True Global / OEM without the permission** (POCO Global, ColorOS) | full enumeration; detection works | detection unchanged; same additive picker/payload improvements | permission undefined → **no prompt, no change** |

"Granted" includes 仅在使用中允许 (foreground-only): detection and the preflight run in the
foreground, and the grant bit is GRANTED.

**Host gates:** `:app:assembleDebug`; app unit tests; profile-core tests; the manifest test
(permission declared, `<queries>` contains every code-listed package, no
`do_not_need_get_installed_apps`). No native gates — no native file changes.

**Device gates (the lab set):**

- **diting (Xiaomi.eu, A15):** after slice 2, the startup prompt appears on a fresh install;
  始终允许 → the audit reads the full enumeration and the run's handoff resolution is unchanged;
  拒绝 → the audit reads self-only, the preflight still finds ReSukiSU/Magisk/SukiSU Ultra via
  `getPackageInfo`, and the picker/refusal name the list-hidden state (not "not installed").
  Slice 1 alone must be checked first: with the permission undeclared, detection is unchanged
  and full.
- **OnePlus CPH2723 (ColorOS A16, IN):** the permission is undefined → no prompt; detection and
  runs unchanged; the home module card and picker behave as today (plus SukiSU Ultra visible).
- **POCO duchamp (Global A16):** static — undefined; run if the Install-via-USB setting allows
  installing the build.

**The decisive test is a CN phone.** Install the slice-2 build on a stock Chinese ROM
(HyperOS CN / ColorOS CN / OriginOS CN / MagicOS CN) with a KernelSU-family manager installed
and the app-list permission not yet granted. Assert:

1. The startup dialog appears, and the system permission dialog is MIUI/ColorOS/vivo's own (the
   permission is defined — record `definingPackage`).
2. With the permission **denied**, the preflight reports `APP_LIST_RESTRICTED` (list hidden),
   not `NO_ROOT_MANAGER`; the picker shows the known manager via the direct probe; the audit
   reads `enumeration` near 1 and `canary=0`.
3. After **granting**, the audit's enumeration jumps to the full set (`canary > 0`), the same
   manager still resolves, and a run completes its handoff as before.
4. The same build on the same phone, before slice 2 (undeclared), reproduces the user report
   (`NO_ROOT_MANAGER` with a manager installed) — this is the regression the slice fixes.

**Executed 2026-11-28 on the diting flashed to stock CN `OS3.0.3.0.VLFCNXM`:**

1. Passed — the startup rationale led to MIUI's own dialog
   (`允许“Sesame”获取已安装的应用信息？`, `com.lbe.security.miui`).
2. Passed — denied: `enumeration=1 canary=0`, `probedInstalled=[com.resukisu.resukisu]`; the
   picker listed ReSukiSU; with the manager removed, 执行 showed the `APP_LIST_RESTRICTED` dialog
   with the audit line.
3. Passed for the enumeration/resolution half — granted: `enumeration=395 canary=38`, manager
   still found. A full exploit+handoff run was not part of this gate.
4. Passed — the pre-fix build 942's picker said `未找到可用的 ksud` with ReSukiSU installed.

The decisive gate is closed; the full record is in §10.

## 7. Risks

- **The declaration activates the gate.** On Xiaomi a user who denies the prompt loses
  unknown-manager detection (known managers still resolve by probe). This is why the prompt
  ships with the declaration and why the message states the consequence. It is the accepted
  cost of the operator's "always ask" decision.
- **Users on ROMs where the gate is defined but was previously inactive** (Xiaomi.eu, as
  measured) will now see a prompt they did not need before. Granting is harmless and restores
  the same full enumeration; denying narrows detection to the known list. Accepted.
- **OEM UI variance.** Some ROMs may suppress the dialog for sideloaded apps or provide no
  "always allow" option; the 打开设置 fallback covers it. The rationale/permanent-denial
  distinction needs the "asked" flag.
- **`checkSelfPermission` can lag the OEM layer** (measured after `pm revoke`). The audit
  records the canary too; the dialog answer is the authoritative path.
- **`getPermissionInfo` may be defined but unenforced** (Xiaomi.eu before declaration). With
  "always ask" this only costs a prompt.
- **AOSP visibility can be the filter instead** (if `QUERY_ALL_PACKAGES` is stripped). The audit
  can prove "unfiltered" or "no proof"; the refusal explains the hidden-list state even when no
  permission is available to request.
- **`<queries>` count.** Ten packages is at Xiaomi's documented guidance limit; adding further
  forks should go through the permission path, not more `<queries>` entries.
- **Private space / work profiles** install managers in another user and remain invisible; out
  of scope, noted in the audit as "not visible".
- **Play distribution** would restrict `QUERY_ALL_PACKAGES`; not applicable (GitHub/NAS).

## 8. Decisions taken

- **2026-11-26/27** (existing): one detector, one rule set, one precondition; `QUERY_ALL_PACKAGES`
  stays.
- **2026-11-28:** always ask for `com.android.permission.GET_INSTALLED_APPS` when the OS lists
  it, at startup and at a no-manager refusal; neither the canary nor a successful probe-first
  detection gates the prompt. Probe-first is the denial fallback, not a substitute for asking.
- **2026-11-28:** root-side discovery (L4) is dropped; the UI remains the single source of
  truth for which manager will be used.
- **2026-11-28:** probe-first detection is the primary detector; enumeration is additive.
- **2026-11-28:** `com.sukisu.ultra` joins the probe/`<queries>` list so "installed but
  unusable" is visible.
- **2026-11-28:** **a CN phone is the decisive test** for the permission behavior; the
  Xiaomi.eu measurement only proves the declaration-activated gate and the probe path.

## 9. Approval checklist

- [x] Slice 1 scope (probe-first, APK fallback, audit, `choices()`, refusal/picker) approved.
- [x] Slice 2 scope (declare + startup prompt + request) approved as **one** unit.
- [x] "Always ask when listed" accepted with its Xiaomi.eu cost (§7).
- [x] The ask is independent of detection: a probe-found manager, a passing preflight or an
      unfiltered canary does not suppress the startup prompt (probe-first is the denial fallback).
- [x] Auto-resume of the tapped run after a grant from the refusal dialog accepted.
- [x] A CN unit for the decisive gate: the diting was flashed to stock CN
      `OS3.0.3.0.VLFCNXM` and the gate ran (2026-11-28).
- [x] Build pushed to the NAS: `ghostlock-builds/2026-11-28-manager-visibility/` with `BUILD.txt`
      (the device evidence is recorded there).

## 10. Implementation status (2026-11-28)

**Build 966** — `Sesame-v1.2(966)-arm64-v8a-debug.apk`, md5 `7f7e6524…`, sha256 `5c258069…`,
on the NAS at `ghostlock-builds/2026-11-28-manager-visibility/`.

**Host gates:** app unit tests **316 / 0 failed / 1 skipped**; `:profile-core:test` green;
`:app:assembleDebug` clean.

**Device gates (all on build 966):**

- **diting (Xiaomi.eu `OS3.0.3.0.VLFCNXM`, A15)** — the declaration activates the gate here, as
  measured with the probe.
  - *Granted:* startup rationale → MIUI's `允许“Sesame”获取已安装的应用信息？` → 始终允许;
    `scan-test.txt`: `app-list permission=granted enumeration=356 canary=10`,
    `probedInstalled=[com.resukisu.resukisu, com.sukisu.ultra]`.
  - *Denied:* `app-list permission=defined(com.lbe.security.miui) not granted enumeration=1
    canary=0`, `declaredSeen=[]`, and **probe-first still found**
    `probedInstalled=[com.resukisu.resukisu, com.sukisu.ultra]`; ReSukiSU remained a suitable
    candidate (8 KMI assets, `android12-5.10` a member).
  - The Advanced picker under denial listed: ReSukiSU `provides android12-5.10`, SukiSU Ultra
    `not suitable`, Magisk `binaries present`. The rig was restored to **granted** after the test.
- **OnePlus CPH2723 (ColorOS A16, IN)** — permission undefined → **no app-list dialog**;
  `scan-test.txt`: `app-list permission=undefined enumeration=551 canary=113`; KernelSU-Next
  suitable, SukiSU Ultra a truthful `not suitable` row. Detection unchanged.
- **POCO/duchamp (Global HyperOS 3, A16, 6.1.138)** — permission undefined → **no app-list
  dialog**; `scan-test.txt`: `app-list permission=undefined enumeration=401 canary=33`;
  ReSukiSU suitable (8 KMI assets, `android14-6.1` a member). The ROM's Install-via-USB gate
  blocked the APK until `settings put secure adb_install_need_confirm 0`; detection unchanged.
- **diting, stock CN `OS3.0.3.0.VLFCNXM` (A15, certified 5.10.236)** — the **decisive unit**,
  flashed 2026-11-28; the TAF permission is defined (`com.lbe.security.miui`, dangerous) and the
  stock ROM gates an app that does **not** declare it:
  - *Pre-fix build 942 (no declaration):* the Advanced picker says
    `未找到可用的 ksud。请安装 KernelSU 系 root 管理器。` **with ReSukiSU installed** — the user
    report reproduced on the decisive ROM.
  - *966, denied:* `app-list permission=defined(com.lbe.security.miui) not granted
    enumeration=1 canary=0`, `declaredSeen=[]`, and **probe-first found**
    `probedInstalled=[com.resukisu.resukisu]`; the picker lists
    `ReSukiSU (com.resukisu.resukisu) — provides android12-5.10`.
  - *966, denied, no manager:* tapping 执行 shows the **`APP_LIST_RESTRICTED`** dialog with the
    audit line (`handoff: app-list permission=defined(com.lbe.security.miui) not granted
    enumeration=1 canary=0`) and 允许/暂不.
  - *966, granted:* `enumeration=395 canary=38`, manager still found. Rig restored to granted,
    with ReSukiSU reinstalled.

**Not yet device-tested:** nothing in this change. (The run-store meta copy of the audit, the
tri-state home badge and an `APP_LIST_RESTRICTED` unit seam remain follow-ups.)

**Follow-ups (not in this change):** the run-store meta copy of the audit; a tri-state home
module badge (missing / installed-but-unusable / hidden); an `APP_LIST_RESTRICTED` unit seam for
the repository's failure selection.

## Appendix A — the probe (regenerable, not retained)

Two minimal APKs (`targetSdk 35`, debuggable, no resources) built with `aapt2`/`d8`/`apksigner`
against the SDK platform jar, installed via adb, driven through the real permission dialog. The
audit is written to logcat tag `PERMTEST` and to `files/audit.txt` (read with
`run-as <pkg> cat files/audit.txt`).

Manifest essentials (variant A; variant Q drops `QUERY_ALL_PACKAGES` and changes the package
name):

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
<uses-permission android:name="com.android.permission.GET_INSTALLED_APPS" />
<queries> ... ten <package> entries ... </queries>
```

The audit per run: `getPermissionInfo` (defined? package? protection), both grant bits,
`shouldShowRequestPermissionRationale`, installer, `getInstalledPackages` size, user-app count,
canary count + sample, declared packages seen in enumeration, and `getPackageInfo` per declared
package. The request is issued from the activity with `requestPermissions`; the result is
logged with `results[]` and the post-request grant bit, then the audit is repeated.

## Appendix B — references

- Xiaomi, 《获取应用列表权限的适配说明》 (pId=1619).
- Xiaomi, 《应用软件列表信息获取说明及适配指南》 (pId=1830).
- vivo, 《"读取已安装应用列表"权限三方适配说明》 (doc/747).
- T/TAF 108-2022《移动终端应用软件列表权限实施指南》; YD/T 2407-2021.
- Android, *Package visibility filtering* (the AOSP layer; independent of the TAF layer).
- Precedent implementation: SD Maid SE PR #2311 (treats the permission as a runtime permission
  and treats `NameNotFoundException` as "not applicable").
