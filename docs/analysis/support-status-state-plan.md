# Support status: state-management plan (2026-11-20)

Status: **proposed — awaiting operator approval.** This is an L-level change (public data
structures, cross-file contracts); per AGENTS.md no code lands before approval. The audit that
motivates it is in this repo's history review and in
`external/github_error_reports/5.4.233-qgki-gbb70cde46897/README.md` (port repository).

**Sequencing (2026-11-21):** this plan is slice **N3** of
`ui-architecture-refactor-plan.md`; §11 there records the amendments (the session holder and the
editor holder). The decisions in §5 and the gate in §6 S5 are unchanged.

## 0. Scope rule

Implement exactly the slices in §6. Anything not listed is outside this change; if something not
listed turns out to be a real blocker, stop and raise it.

**In scope (app only):** one authoritative support verdict, its state ownership and refresh
model, the editor-session split, the run path consuming the verdict, deletion of the dead
layer-notice machinery, the legacy-store coverage decision, the new strings, and their tests.

**Out of scope:** the resolver's semantics (what makes a profile resolvable), the field
validators, the badge's visual redesign (colors/layout beyond the state mapping), the external
import copy, the measured database, the native document, the wire, and every attack route. No
native or `cmp_disasm` gate is required.

## 1. The problem

The home verdict is a **derived cache with several writers and no provenance**. Today:

- `state.kernelSupported` comes from `AndroidGhostlockRepository.snapshot()`, which performs its
  own resolution (`AndroidGhostlockRepository.kt:242-257`).
- `state.profileLayers`, `executionHasProfile`, `profileInvalidPaths` come from a **second**
  resolution in `GhostlockViewModel.refreshSnapshot()` (`GhostlockViewModel.kt:2102-2137`), plus
  a third writer, `applyExecutionConfig`/`applyAdvancedConfig` (`:312-331`, `:1339-1361`), which
  **the editor session also calls** with its own controller (`:1126-1131`, `:1159-1160`).
- `externalBlocked` is recomputed in the UI from two fields written at different times
  (`GhostlockUI.kt:1530`), and the run-readiness conjunction exists four times with different
  subsets (`GhostlockUI.kt:1493-1497`, `:1633`, `GhostlockViewModel.kt:1477`, `:1446-1458`).
- The run itself re-resolves (`AndroidGhostlockRepository.kt:1383`; `nativeDocument()` at
  `AndroidProfileConfigController.kt:746-750`) instead of consuming the verdict it was gated on.

Measured failure modes this produces (each becomes a test in §7):

| id | mode | where |
|---|---|---|
| H1 | **Editor-close leak.** The session's config is applied into the live state; closing the editor only ends the session (`ViewModel:1310-1319`), so the Home badge can show the session's `invalidPaths`/`layers`/`hasProfile` while the live profile is fine, until the next resume/Parameters open | `:1126-1131`, `:1310-1319` |
| H2 | Two resolutions per refresh, merged by hand (`externalProfileUnreadable` picks one with `?:`) | `ViewModel:2125-2127` |
| H3 | Split provenance: some fields only from the refresh load, some only from `apply*`, `activeBuiltinProfile` never refreshed; no resolution identity ties any two fields together | `:312-331`, `:2102-2137` |
| H4 | No serialization: every action launches its own IO coroutine; last completion wins field by field (import vs toggle vs refresh) | `:303-309`, `:540-570`, `:2099` |
| H5 | Two truths for one fact: `kernelSnapshot` var vs `state.*` | `:1477`, `:1357` |
| H6 | Reads with side effects (`load()` migrates and rewrites; `snapshot()` migrates) and the run re-reads | controller `:69`, `:873`; repo `:229`, `:1383` |
| A1 | MTK pair-missing is rendered as 不支持 / 未找到匹配的内核配置; the real advice exists only in an unreachable run-log branch | `GhostlockModels.kt:47-48`, `GhostlockUI.kt:1665-1669`, `ViewModel:1487` |
| A3 | `externalError` is set for any selection's failure (e.g. a builtin for another release refused by `requireIdentity`), but the card's blocked branch requires a copy — the failure is invisible and `describe()` mislabels it "external" | controller `:907-927`, `ProfileMerger.kt:132-137`, `GhostlockModels.kt:299-308` |
| A5 | The blocked run tap toasts 未找到匹配本机内核的配置, contradicting the card | `ViewModel:1446-1458`, `strings.xml:111` |
| R1 | The blocked reason (five causes, measured in the star report) is computed and dropped — no surface shows it | `GhostlockModels.kt:274-275` |

## 2. Target model

### 2.1 `SupportStatus` — one sealed verdict

```kotlin
enum class SourceKind { External, Builtin, Bundled, None }

sealed interface SupportStatus {
    val canRun: Boolean get() = this is Ready
    val source: SourceKind

    data object Ready : SupportStatus                       // profile resolved, fields valid, access ready
    data class AccessPending(val status: ShizukuStatus)     // everything green but Shizuku
    data object CopyUnreadable                              // the copy cannot be read/parsed
    data class ProfileBlocked(val reason: String)           // readable, resolution refused (5 causes)
    data class FieldsInvalid(val paths: Set<String>)        // field validation
    data object PhysicalPairMissing                         // MTK, no bootloader-derived pair
    data object KernelUnsupported                           // release covered by no live source
    data object ProfileMissing                              // covered, but no profile (defensive)
    data class ResolutionFailed(val detail: String?)        // load() threw (new; today swallowed)
}
```

Precedence (first match wins), preserving today's card order where it matters:

1. `ResolutionFailed` — the resolution threw.
2. `CopyUnreadable` — external selection and `externalReadFailure`.
3. `ProfileBlocked` — a readable selection's `layers.externalError` (external **or** builtin; the
   reason is carried verbatim, e.g. `the profile's kernel_phys_* map does not name diting`).
4. `KernelUnsupported` — the release is covered by no live source (see D2).
5. `PhysicalPairMissing` — covered, but MTK and no `physLoad`/`physOffset`.
6. `FieldsInvalid(paths)` — a profile resolved and `invalidPaths` is non-empty.
7. `ProfileMissing` — covered, `hasProfile == false`, no error (defensive; a test pins that the
   live paths cannot reach it, and it stays as the fail-closed landing spot).
8. `AccessPending(status)` — the verdict is otherwise ready, Shizuku not READY.
9. `Ready`.

`ProfileBlocked` replaces the copy-only assumption: a builtin selection refused by
`requireIdentity` becomes visible with its reason (A3) instead of falling to 需要加载配置.

### 2.2 The resolver contract

One function, one resolution:

```kotlin
// AndroidGhostlockRepository
data class SupportOutcome(
    val status: SupportStatus,
    val config: ProfileConfig?,      // non-null iff status is Ready/AccessPending/FieldsInvalid
    val release: String,
    val source: SourceKind,
    val selectionLabel: String?,     // builtin/bundled release, for the source cards
)

suspend fun supportOutcome(release: String, pair: CpuPair): SupportOutcome
```

- It performs **exactly one** `profileController.load(release, pair)`; the pair-known,
  coverage, invalid-paths, layers and has-profile facts all come from that one result.
- It is the **only** caller of `load()` for the support path; `runExploitBinary` and the UI stop
  loading independently (§2.6).
- It catches a thrown resolution and maps it to `ResolutionFailed(detail)`, never to
  "no profile" (R1/A4).
- Side effects: `migrateLegacyExternalSelection` moves **out** of `load()`/`snapshot()` into an
  explicit one-shot `repository.migrateExternalSelectionOnce()` called from `initialize()` before
  the first resolution. The `ensureSchema` copy write-back inside `load()` stays (idempotent,
  content-normalizing) and is documented as the only remaining write.

### 2.3 Ownership

```kotlin
// SessionViewModel (activity-scoped) — ui-architecture-refactor-plan.md §3.5
private val session: StateFlow<SessionState>  // device, access, settings, run, support
private var resolutionGeneration = 0L
private val resolutionMutex = Mutex()

// EditorStateHolder (screen-scoped) — the editor session ONLY
val editor: StateFlow<EditorState>
```

`SessionState.support` replaces this plan's `HomeState`; Home and Parameters both read it, so the
single-source requirement is unchanged. The editor is a separate holder, so no editor action can
write session state. `EditorState` owns `editTargetName`, the session's config projections
(`editorFields`, `editorInvalidPaths`, `editorRoute`, `editorFallback`, `editorRoots`,
`editorEditing`). `onCloseProfileOverrides()` clears `EditorState` only; the Home verdict cannot
change because the session closed.

### 2.4 Serialization and generations

```kotlin
fun refetchSupport() {
    val request = ++resolutionGeneration
    viewModelScope.launch(Dispatchers.IO) {
        resolutionMutex.withLock {
            if (request != resolutionGeneration) return@withLock   // superseded while queued
            val outcome = repository.supportOutcome(release(), pair())
            withContext(Dispatchers.Main) {
                if (request == resolutionGeneration) {
                    home.update { it.copy(support = outcome) }
                }
            }
        }
    }
}
```

Every trigger in §2.5 calls `refetchSupport()`; nothing else writes `HomeState.support`. A result
whose generation is stale is dropped, never applied. The mutex serializes resolutions, so the
"two resolutions per refresh" class disappears rather than being ordered by luck.

### 2.5 Freshness — the definitive trigger list

`refetchSupport()` is called from, and only from:

1. `initialize()` (after `migrateExternalSelectionOnce()`);
2. `MainActivity.onResume()` (`refreshAccessStatus()`);
3. the Shizuku status listener (`setShizukuStatusListener`);
4. external import / clear external / select builtin;
5. the Shizuku toggle;
6. CPU-pair selection (always — the pair is a merge input);
7. the parse flow finishing;
8. the legacy-cleanup completing (D2 may change coverage).
9. the run flow after it terminates **only if** it can change a verdict input (today: it cannot;
   measured facts merge only on export — keep this explicit so a future change is noticed).

No file observer in this change (the copy is app-private and only the app writes it); a
`FileObserver` on `filesDir/profiles/` is listed as a follow-up if a second writer ever appears.

### 2.6 The run consumes the verdict

`runExploit()` resolves once through `supportOutcome()` (or reuses `home.support` when its
generation is current) and:

- refuses with `status`'s own message when `!canRun` (no second message ladder);
- passes the resolved `ProfileConfig` down: `runExploitUseCase(config, …)` →
  `runExploitBinary(config, …)`, deleting the internal `profileController.load()` at
  `AndroidGhostlockRepository.kt:1383`;
- `nativeDocument(config, pipeline)` keeps its internal re-resolution (same deterministic
  resolver, same files, same call chain), documented as such.

### 2.7 Consumers

| consumer | change |
|---|---|
| `ActivationStatusCard` | takes `SupportStatus`; branch table in §4; no derived booleans at the call site |
| `RunButton` | `enabled = support.canRun && !running`; overlay when `!canRun` calls `onBlocked(status)` |
| `ViewModel.runExploit()` | one `when (support)` refusal + the resolved config (no `run_blocked_*` ladder) |
| `onProfileInvalid()` | replaced by the same `when (support)` mapping (A5) |
| Parameters/Advanced source banners | `outcome.source` + `selectionLabel` (both banners use the same value; A6) |
| AdvancedUI gates (`executionHasProfile`) | `support.config != null` |

## 3. `GhostlockUiState` field migration

| current field | disposition |
|---|---|
| `kernelSupported` | dies → `SupportStatus.KernelUnsupported` |
| `externalProfileUnreadable` | dies → `CopyUnreadable` |
| `executionHasProfile` | dies → `support.config != null` |
| `importedProfilePresent` | dies → `outcome.source == External` |
| `profileInvalidPaths` | moves into `FieldsInvalid(paths)` (live); editor paths move to `EditorState` |
| `profileLayers` | moves into `support.config.layers`; no separate writer |
| `activeBuiltinProfile` | moves into `outcome.selectionLabel`/`source` |
| `executionRelease` | moves into `outcome.release` |
| `profileRoute`, `profileFallback` | move into `support.config` |
| `executionFields` | derived from `support.config.general` |
| `executionEditing` | stays (a UI draft) |
| `customCpuPair` | derived function of `support.config` + `cpuPairs` |
| `devicePairMissing` | dies → `PhysicalPairMissing` |
| `editTargetName`, `profileOverrideRelease`, `profileOverrideRoots`, `profileOverrideEditing` | move to `EditorState` |
| `dialogLayerNotice`, `layerNoticeChecked` | deleted with the notice machinery (D3) |
| `ProfileLayers.imported`, `importedApplied`, `overridesPresent`, `overridesApplied`, `overridesBuild`, `hasInactiveLayer`, `noticeDue` | deleted (D3; only the tests construct them today) |
| `ProfileLayers.builtinRelease`, `bundledAuthoritative`, `builtinSelected`, `build`, `uncarriedKeys`, `externalApplied`, `externalError`, `externalReadFailure` | stay; the stale `bundledAuthoritative` doc comment is corrected |
| `legacyPresent`, `legacyProfiles`, `legacyOverridePresent` | stay (separate flow); their effect on coverage per D2 |

## 4. Card mapping

| status | title | summary | action | toast (blocked tap) |
|---|---|---|---|---|
| `Ready` | 受支持 | 配置已就绪 (or the Shizuku-ready variant) | run | — |
| `AccessPending` | 通过 Shizuku 执行 | the Shizuku status line | Shizuku | same |
| `CopyUnreadable` | 配置无法读取 | 请在配置参数中重新导入配置 | Parameters | same |
| `ProfileBlocked` | 配置需要修正 | 所选配置不适用于当前内核**：<reason>** (new detail; A3/R1) | Parameters | same |
| `FieldsInvalid` | 配置需要修正 | 请修正标记的配置项 | Parameters | same |
| `PhysicalPairMissing` | **需要物理内存参数** (new) | 请提供 vendor_boot/dtbo 并重新解析（联发科） | Parameters/parse | same |
| `KernelUnsupported` | 不支持 | 未找到匹配的内核配置 | Parameters | same |
| `ProfileMissing` | 需要加载配置 | 加载配置后可继续 | Parameters | same |
| `ResolutionFailed` | **配置解析失败** (new) | <detail> | Parameters | same |

All strings land in both `values/` and `values-zh/`; the English reason from the resolver is
shown verbatim after the localized summary (diagnostic, like the run log).

## 5. Decisions (operator sign-off)

- **D1 — `describe()` log format.** Drop the two dead clauses (`imported=…`, `overrides=…`) and
  keep `external=…; builtin=…; build=…`. Evidence documents quote the old line; new exports
  carry the new form. *Recommend: drop.*
- **D2 — the retired `UserProfileStore`.** It still counts toward coverage
  (`releaseCoveredByStoredSource`, repo `:1914-1924`) and seeds `recommendShizuku`
  (`:234-236`), but the resolver never reads it. **Option A (recommend):** remove both
  influences; a legacy-only kernel becomes `KernelUnsupported` and the existing cleanup entry
  still offers the export. **Option B:** keep coverage and add a distinct
  `LegacyOnly` state prompting the cleanup — more states, honest wording. *Recommend A.*
- **D3 — delete the layer-notice machinery.** `imported*`/`overrides*`, `hasInactiveLayer`,
  `noticeDue`, `ProfileLayerNotice`, `maybeRaiseLayerNotice`, `dialogLayerNotice`,
  `layerNoticeAcknowledged/acknowledge`. Nothing sets those fields in production since
  `23c00ece`/`9a21257f`. *Recommend delete; `ProfileLayersTest` goes with it.*
- **D4 — MTK pair wording.** New state + new strings (title/summary in §4). *Recommend add.*
- **D5 — surface the blocked reason.** Show `reason` in the card summary and the Parameters
  source card. *Recommend add* (this is the star report's ask).
- **D6 — `ResolutionFailed`.** New state instead of today's silent degradation. *Recommend add.*
- **D7 — run plumbing.** Pass the resolved `ProfileConfig` into `runExploitUseCase` and delete
  the load at repo `:1383`. *Recommend do; it is the TOCTOU close.*
- **D8 — `snapshot()` slims down.** It stops resolving; it returns device facts and settings
  only. *Recommend do* (H2/H5).

## 6. Slices

**S0 — tests first (expected red).** Add the seams (a counting `ProfileConfigController` fake;
a gated dispatcher for race tests) and the tests in §7 for H1, H4, H2, D7, R1, A1, A3. They fail
on today's tree; each later slice flips its group green. Gate: tests compile and the red set is
exactly the expected list.

**S1 — model introduction, no behavior change.** `SupportStatus`/`SupportOutcome` +
`repository.supportOutcome()`; map the current resolution into the new type; the card consumes it
through a pure mapping function with an equivalence test against today's predicates. Gate:
`:app:testDebugUnitTest`, `:profile-core:test`, `assembleDebug`.

**S2 — lifecycle.** `refreshSnapshot()` becomes `refetchSupport()` with the generation guard and
mutex; `snapshot()` stops resolving; the editor session moves to `EditorState` behind an
`EditorStateHolder` (the N4 pattern applied to one screen); migration moves to `initialize()`.
Gate: S0's H1/H4/H2 groups green.

**S3 — consumers and run.** Card/Button/toast/Parameters consume `SupportStatus` through their
per-screen actions (`ui-architecture-refactor-plan.md` N4); the duplicated predicates and message
ladders are deleted; the run receives the resolved config. Gate: S0's D7/R1/A5 groups green; diting
smoke of Ready/Blocked (see S5).

**S4 — cleanups.** D1/D3 deletion, D2 coverage change, D4/D6 states and strings. Gate: the log
golden and the string-pair test; `ProfileLayersTest` removal recorded.

**S5 — device gate (diting).** The star document on the diting yields
`ProfileBlocked(the profile's kernel_phys_* map does not name diting)` with the reason visible;
the 759-style crafted states (unreadable copy, release-edited copy, schema 3, invalid field)
render their own status; a valid profile reaches `Ready` and a full run completes. Evidence in
`external/port/` with the usual kit.

## 7. Test matrix

| test | pins |
|---|---|
| `SupportStatusMappingTest` | each resolver output → status; precedence; reason propagation (R1) |
| `SupportResolutionTest` | exactly one `load()` per `supportOutcome()`; a thrown load → `ResolutionFailed`, not "no profile" |
| `SupportRefetchRaceTest` | a stale generation is dropped; the mutex serializes; out-of-order completions cannot overwrite (H4) |
| `EditorIsolationTest` | the H1 leak: open the editor, set `offset.init_task = 0`, close → Home `support` is unchanged; the session's verdict stays in `EditorState` |
| `RunConsumesVerdictTest` | the run refuses with the same status message and passes the same `ProfileConfig` (D7) |
| `BlockedReasonTest` | the star document resolves to `ProfileBlocked` with the map-reason on a non-`star` identity; the five causes from the port report map from the resolver's strings |
| `LegacyCoverageTest` | D2: a legacy-only release is not `KernelUnsupported`'s "supported"; cleanup does not change the verdict otherwise |
| `PhysicalPairStateTest` | MTK without a pair → `PhysicalPairMissing`, not `KernelUnsupported` (A1) |
| `DescribeFormatTest` | the new `describe()` golden; the two dead clauses are gone (D1) |
| `ProfileLayersTest` | removed with the notice machinery (D3), or rewritten to the trimmed type |

## 8. Risks and rollback

- **User-visible wording changes** (D4/D5/D6, A1/A5): zh+en strings land together; the S5 device
  screenshots are the gate.
- **Log format change** (D1): evidence readers must not regex the removed clauses; this plan is
  the record of the change.
- **`ProfileConfig` now travels in `HomeState`**: immutable and shallow (maps/lists already
  built), no extra resolution.
- **Behavior semantics** (D2): a legacy-only device changes from 需要加载配置 to 不支持; the
  cleanup entry remains. Operator sign-off required.
- **Rollback:** app-only, no data migration; revert the commit series. The copy file format and
  the wire are untouched.

## 9. Status

Plan written 2026-11-20. Sequenced as N3 of `ui-architecture-refactor-plan.md` on 2026-11-21. No
code before operator approval of §5's decisions. On approval: S0 lands first and is expected red;
each subsequent slice carries its own gate; the device gate (S5) closes the change.
