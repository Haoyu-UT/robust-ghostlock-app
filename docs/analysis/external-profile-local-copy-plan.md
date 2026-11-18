# External profile import copy — design plan (2026-11-19)

Status: plan final for implementation approval. No code has been written.

## 0. Scope rule

Implement exactly the steps in this plan. Anything not listed here is outside this change; if
something not listed turns out to be a real blocker, stop and raise it before implementing.

The change is app-side only: `ExternalProfileStore`, `AndroidProfileConfigController`, the
profile-source UI/state, their strings and tests. The profile parser, merge, validators, native
document, wire and attack routes are untouched.

## 1. The model

1. **Generation/export writes the profile document to the SD card** through SAF. The SD card is
   the durable, user-owned store and the interchange format.
2. **Import copies the document once** into one app-private file:

   ```
   filesDir/profiles/imported_profile.conf
   ```

   Every import overwrites that file.
3. **The imported copy is the external-profile selection.** The copy's own `release` field
   decides applicability; its optional `# ghostlock-profile …` lineage header stays in the
   document. The import reference (URI, name, SHA-256, parent, release) is transient: keep it
   only for the duration of the import.
4. **Every load reads the copy**, never the SAF URI. The picker URI is an argument to the
   import, not state.
5. **The source card shows only the kind**: 外部配置 (the imported copy exists), 内置配置
   (builtin/bundled), or 未加载配置.

## 2. Why: the MIUI provider

On MIUI/HyperOS the system document picker returns URIs from Xiaomi's system File Manager
provider (`content://com.mi.android.globalFileexplorer.myprovider/...`), not the AOSP
`com.android.externalstorage.documents`. That provider does not support persistable URI grants:
the selection-time read works from a transient grant, the picker activity finishes, and the
next `loadSource()` read fails. The reporter's GIF shows exactly that: the success toast
**已加载配置** and the later **无法读取所选文档** banner together.

The diting control (app build 756, Google DocumentsUI) picked the same profile from
`/sdcard/root` through the system picker and got
`content://com.android.externalstorage.documents/...`, `persistable=0x3`, `persisted=0x1`; the
app loaded it with no banner and it survived a restart. The CN MIUI provider is the
differentiator. Evidence and sources:
`external/port/external-profile-picker-2026-11-19/README.md` in the port repository.

Therefore: read the document once at import, write the copy, and load from the copy on every
resolution. This is the web-recommended workaround for providers that refuse persistence.

## 3. Import flow

1. Read the selected document (the one SAF read; the picker URI is a parameter).
2. Run `ProfileDocumentParser.entryForRelease(text, deviceRelease)`; refuse a document without
   the running release.
3. Write `imported_profile.conf` atomically: a same-directory temp file, `fsync`, `rename`.
4. Load from the copy.

Failure handling:

- If the copy write fails, fail the import and leave the previous copy and state in place.
- If the app dies during the write, the temp file is removed at startup or overwritten by the
  next import.
- If the app dies after the import, the copy is the selection; the next import overwrites it.

## 4. Load, migrate, clear

1. **Load** reads `imported_profile.conf` and its `release` field.
2. **One-time migration from the current app.** On the first run after the update, if the old
   `external_profile_uri/_name/_release/_sha256/_parent` keys exist and no copy does:
   read the old URI once; if it yields the running release, write the copy; then delete all
   `external_profile_*` keys either way. If the URI is unreadable, apply the ordinary
   no-profile state and let the user re-import. This migration is the only use of the old
   fields.
3. **No copy** means no external profile: apply the builtin/bundled precedence as today, and
   show 需要加载配置 when neither is available.
4. **Clear external / select builtin** deletes the copy. The builtin selection keeps its
   `builtin_release` pref.
5. **New import** overwrites the copy.
6. **Uninstall** removes app data.

## 5. UI

1. **Remove the external-error UI and state**: `external_profile_missing`,
   `kernel_external_unreadable(_summary)`, `run_blocked_external_unreadable`,
   `layer_external_not_applied`, the reference detail strings
   (`external_profile_label/_sha/_parent/_other_release`), and the
   `externalReadFailure`/`externalProfileUnreadable` fields.
2. **Show only the kind** in the source card: 外部配置 / 内置配置 / 未加载配置.
3. **Keep the existing clear action** (取消外部配置) visible on the 加载配置 screen whenever
   `imported_profile.conf` exists, applied or not.
4. **Use the existing status surfaces** for failures: 配置需要修正 / unsupported schema / the
   disabled run gate. Add no new message and no new button.
5. **Apply the states**:

   | condition | state |
   |---|---|
   | copy exists, release matches | external applies; source card 外部配置 |
   | no copy | builtin/bundled; if none, 需要加载配置 |
   | copy exists, schema/validation/merge fails | the existing config-error state (配置需要修正 / unsupported schema) |
   | copy's release differs from the device | the copy is the live selection and fails verification for this kernel; the existing invalid-profile state blocks the run; the source card shows 外部配置; the existing cancel action clears the copy; after clearing, builtin/bundled applies |

6. **Keep the SD-card source as the user's document.** The app shows only the kind; the user
   re-imports after editing the source.

## 6. Upgrade: added keys and schema versions

1. **Added optional key with a safe default** → the merge/tuning defaults apply; old copies
   load unchanged.
2. **Added required key with a safe default** → the merge fills it; document and test the
   default against an old copy.
3. **Added required key without a safe default, or a key that changes behaviour** → bump
   `schema_version` and ship a converter that sets it. A copy that has no converter reports
   配置需要修正.
4. **Copy schema older than the app** → convert to the current schema and **rewrite
   `imported_profile.conf` in place** on the first load: set `schema_version` to the app's
   current, preserve the lineage header, write atomically, and use the upgraded copy. Keep the
   SD-card source unchanged. If the rewrite fails, use the in-memory conversion and retry on the
   next load; if the conversion fails, report 配置需要修正 and keep the old copy.
5. **Copy schema newer than the app** → refuse with an unsupported-schema error and keep the
   copy; the app has to be updated.
6. **Validation failure after conversion** → keep the copy, report 配置需要修正; the user
   re-exports a current-schema document to the SD card and re-imports.
7. **Load every schema the app has emitted** (and the legacy forms the importer accepts), or
   ship a converter with the schema bump. The in-place rewrite is idempotent: the second load
   sees the current schema and does nothing.

## 7. Storage-model amendment

`measured-db-plan.md` §1 says app data keeps no profile documents and only the selection
reference. Amend it:

> The app keeps exactly one **imported copy** of the selected external document
> (`filesDir/profiles/imported_profile.conf`). It is the active profile, overwritten on every
> import, schema-upgraded in place when the app's profile schema advances, deleted when the
> selection is cleared or replaced, and never a precedence layer or a document library. The SD
> card remains the document store. The copy is the whole external-profile state: its `release`
> field decides applicability, and the import reference (URI/name/SHA-256/parent) stays
> transient. The old reference keys are migrated once and deleted.

`external-profile-unreadable-reporting.md` records that the CN MIUI failure is the MIUI
provider's transient grant and that import-copy removes the URI read from the steady-state
path; the reporting it added is retired.

## 8. Touch points

| file | change |
|---|---|
| `app/.../data/ExternalProfileStore.kt` | `read(uri)` for the one import read; `writeImportedProfile(text)` (atomic), `readImportedProfile()`, `clearImportedProfile()` under `filesDir/profiles/`; remove `persistReadPermission` |
| `app/.../data/AndroidProfileConfigController.kt` | import flow in `selectExternalProfile`; `loadSource` reads the copy; release from the copy; the one-time migration; clear in `clearExternalProfile`/`selectBuiltin`; remove the `storedExternalSelection` metadata and the `ProfileSelection.External` fields |
| `app/.../ui/AdvancedUI.kt` | remove the external-error banner and its trigger; the source card shows only the kind; keep the existing cancel action visible whenever the copy exists |
| `app/.../ui/GhostlockUI.kt`, `GhostlockModels.kt`, `GhostlockViewModel.kt`, `AndroidGhostlockRepository.kt` | remove the URI-read states (`externalReadFailure`, `externalProfileUnreadable`, the run-block reason) and the `ExternalProfileInfo` provenance fields; a missing copy is the ordinary no-profile state |
| `app/src/main/res/values{,-zh}/strings.xml` | remove the strings listed in §5 |
| `app/src/test/...` | the tests in §9 |
| `docs/analysis/measured-db-plan.md` | the §7 amendment |
| `docs/analysis/external-profile-unreadable-reporting.md` | mark it superseded by this model |

No native or wire files.

## 9. Tests and gates

Host unit tests:

1. Import copies the document; a later `load()` after the SAF URI becomes unreadable still
   succeeds (the CN MIUI sequence).
2. A new controller instance (restart) loads the copy.
3. `clearExternalProfile()` / `selectBuiltin()` delete the copy; the next load has no profile.
4. A new import overwrites the old copy; one file exists.
5. Migration: with the old keys and no copy, a readable URI writes the copy and deletes the
   keys; an unreadable URI deletes the keys and leaves 需要加载配置.
6. After import, no reference keys are written and the source card renders 外部配置 only. A
   schema/validation failure of the copy surfaces through the existing config-error state.
7. §6: an old schema converts and is rewritten in place on first load (lineage kept,
   `schema_version` current, second load a no-op); added keys default; a missing required key is
   reported and the old copy kept; a newer schema is refused; a rewrite failure uses the
   in-memory conversion and retries.
8. A release-mismatched copy blocks the run and keeps the existing cancel action; clearing it
   applies the builtin/bundled profile.
9. The existing external-selection, layer-policy and merge tests stay green with the retired
   assertions updated.

Device gate:

- CN MIUI `star` with the system picker: import the profile (success toast, no unreadable
  banner); navigate away and back; restart the app; the home reads 受支持 / 配置已就绪.
- Upgrade: install the new build over the current one with the selection present; the imported
  copy loads (or the one-time migration runs once).
- Diting regression: the existing selection migrates once or the user re-imports; a fresh
  import copies. The reporter's phone is not available; the diting control in
  `external/port/external-profile-picker-2026-11-19/` covers the AOSP path.

Gates: `:app:testDebugUnitTest`, `:profile-core:test`, `:app:assembleDebug`. No native gates.

## 10. Operational rules

1. Cover every copy deletion path: clear, builtin selection, new import, uninstall.
2. Keep the imported copy app-private; the run log may carry the copy as the reproducible
   artifact.
3. Keep the schema contract: every shipped schema has a parser or a converter; added keys have
   safe defaults or a schema bump.
4. The user re-imports after editing the SD-card source; the source card shows only the kind.
5. The migration deletes the old reference keys; after it, the imported copy is the only
   external-profile state.

## 11. Decisions (resolved 2026-11-19)

1. **Migration:** read the old URI once; import if it yields the running release; delete all old
   keys either way.
2. **File name:** `imported_profile.conf`, one fixed name.
3. **Missing copy:** the ordinary 需要加载配置 state.
4. **Old UI/state:** remove the external-error strings and URI-read states; a log line may
   remain.
5. **Grant:** remove `takePersistableUriPermission` and the URI prefs.
6. **Dormant copy:** keep the existing cancel action visible whenever the copy exists; show only
   the kind; use the existing status surfaces. No extra message, no extra button, no automatic
   deletion. A release-mismatched copy stays the live selection, fails verification and blocks
   the run until the user clears it; clearing applies the builtin/bundled profile.
