# Profile schema cleanup — L-level plan (schema v3, pre-release)

**Status:** in progress. The transport is implemented and device-proven; the
authored-shape migration is the remaining slice. See *Implementation status*
below.

**Base:** `fix/ui-behaviour-2026-11-23` @ `18641467`; cut the work branch after that merges to
`document`.

**Branch:** `feat/profile-schema-cleanup` (work is on the base branch while it is unmerged).

## Implementation status (2026-11-24)

Landed, gated and committed on `fix/ui-behaviour-2026-11-23`:

| slice | state |
|---|---|
| Native JSON transport (decision 19) | **done**: `src/core/profile/json.{c,h}` (shared tree parser), `json_profile.cpp` (ghostlock mapper), `timer/profile_fields.c` (timer mapper), `entry.*`/`main.cpp` over JSON, the GLK1 wire deleted. Gates: native build 0 warnings, timer build, `lint-tidy` 0, host suite incl. `json_test`/`timer_profile_test`, `cmp_disasm` control (identical instruction counts). |
| App-side transport render | **done**: `ProfileTransport` renders the one document from the resolved tree; the run path, timer tool, Shizuku frame and Gradle exporter use it; `profile.json` sidecar + sha256 log line replace the wire dump/digest; the emitted document golden re-frozen. Gates: app 285 tests, profile-core 85, `assembleDebug`. All 68 exported documents decode through the native mapper on the host. |
| Migration aid | **done, and one-off**: `tools/migrate_profile_shape.py` (stdlib-only HOCON subset parser + the authored mapping) converts the bundled documents — 74 converted, 9 skipped, each verified by parsing its own output back, and `--check` compares against an existing output directory. The Kotlin tool and its Gradle task are removed. The assets are **not** applied yet — that lands with the resolver and the extractor/ship switch. |
| Credential references | **done**: the document names `user`/`user_ns`/`ucounts`/`group_info`; the layout is assigned by the derived offsets (a 5.x image without ucounts writes user/user_ns/group_info), and the native compacts the image-bearing ones into the wire's write slots. |
| Device gate | **All three rigs, fresh boot, run store `completed`** — diting on the final build 879, POCO/OnePlus on 875: diting 5.10.236 (W1-only + DirtyFrag stage `RESULT=OK script=0 enforce=1`, KernelSU loaded), POCO 6.1.138 (`compact=1` from the BTF layout, select native, `KernelSU ready`), OnePlus 6.6.66 (`compact=0` node shape, four clean route calls, `KernelSU ready`). The run's `profile.json` decodes through the native mapper, and the native's `--load-prebuilt-profile` refusals were run on the diting: unknown key (named path), truncated JSON, the old GLK1 bytes, and a release mismatch all exit 255 with one message and no crash. The gate found one real defect, now fixed: the 5.10 assets state the waiter shape through the branch's `compact_waiter`, which the transport must carry into `kernel.struct.waiter.shape`. |

Remaining (the authored-shape migration, slices 1b–3 below): convert the 74
assets and the store via the rewriter, rewrite `ProfileResolver` /
`ProfileMerger` / `PerDevicePhys` / `Timer98260` / `ProfileDocument` for the
new shape, migrate the controller/repository/UI candidate model, update the
Rust extractor's `--format conf` and the ship script, then the remaining device
arms (Shizuku transport; the authored-shape import path).


**Scope:** the authored HOCON profile (bearer of `schema_version = 3`), its two renderers (the Rust
extractor's `--format conf` and `tools/ship_golden_profiles.py`), the app's resolver / render / UI /
measured overlay, the profile transport itself (the GLK1 binary wire is **deleted**, decision 19),
the 74 bundled assets and the test-kernel store, and both native profile readers (ghostlock's, and
the timer tool's, which are merged into one). The exploit's attack code is unchanged; its profile
input becomes JSON, so the native reader is new code on the run path and the native gates below
cover it.

The version number stays **3**: the package rename (`com.sesame.app`) means this v3 was never
released, so the cleanup defines v3 rather than succeeding it. No migration for old documents; the
one exception is a clear rejection message for a pre-release v3 file (§5.4).

## 1. Why

The current v3 document is not clean in six measurable ways (audit of the 74 bundled assets):

| finding | evidence |
|---|---|
| One fact, up to four serialisations | authored HOCON; `ProfileDocument.render`'s `kernel{}`/`native{}` backend JSON; the store's options JSON; the binary wire. Each renames fields (`profile.cred.caps_count` is a string in one, a number in the other). |
| Duplicated facts with no cross-check | ≈300 timer-block fields duplicate `cred`/`task_struct` (61 profiles); 2 copies already disagree in `5.15.194` (`caps 48/3` vs BTF `40/5`). 38 route branches repeat the waiter layout; the anchor triple is repeated per branch. |
| Three address encodings | image-relative decimal (`offset.init_task`), signed decimal VA (`cred.ref0_image = -272688810528`), quoted hex VA (`timer.selinux_state`) — and the same symbol appears twice (`selinux_state` vs `selinux_enforcing`, exactly `image_base` apart). |
| Dead / incompatible keys | `routes` (never used), `fallback` (the native never executes it), `recommend_shizuku`, `safe_mode`, `Auto`, block-local `version`, 640 explicit nulls emitted inconsistently with the schema's own "omit unused" rule. |
| One key, two axes | `pipeline` mixes the chain (`dirtyfrag`/`ghostlock`), the delivery (`module`) and the backend (`timer`); the picker models the timer as a fake route named `timer_98260`. |
| Measured facts handled two ways | the DirtyFrag probe is auto-reused by the resolver; the `mm` stride needs a manual merge/export. |

## 2. Decisions (settled with the operator)

| # | decision |
|---|---|
| 1 | Thorough cleanup; the document stays **schema v3** (never released) |
| 2 | Top level: `devices{}` + `kernel{}` + one block per engine (`ghostlock`, `timer`) |
| 3 | No route fallback; every declared branch lives in the engine that runs it |
| 4 | No chain list: `kernel.dirtyfrag` is the static verdict; the app's ladder is DirtyFrag first (when allowed), then GhostLock; `kdp` forces DirtyFrag only. The module is a delivery: `ghostlock.module` replaces `ghostlock.syscalls`. |
| 5 | Quoted hex addresses; decimal struct/stack offsets, sizes, counts; booleans; word enums |
| 6 | No platform-family key; the phys rule stays on the running device's SoC |
| 7 | The `mm` stride stays inline (`kernel.heap.stride`) |
| 8 | Carriers are nested blocks inside a syscall; the blocks present are the carriers |
| 9 | Derive `kernel_major`; drop block versions, `recommend_shizuku`; `kernelsnitch` → `kernel.heap` |
| 10 | Engine blocks own their layout and selection (`syscall`, `carrier`, `syscalls`, `anchor` — which carries `scratch` —, `module`, `execution`) |
| 11 | Vocabulary: engine block / syscall / carrier; "route" leaves the document |
| 12 | No engine selector: the app's display order decides; the first option is the default; a stored choice wins |
| 13 | Measured facts are an automatic overlay (see §3.3) |
| 14 | No absent state: a key with no value is `null`, never omitted (see §3.2) |
| 15 | `w1_scratch` is a field of the anchor block: `ghostlock.anchor { image, bytes, stride, scratch }` |
| 16 | The `5.15.194` cred layout is re-derived from the image's BTF and adopted (expected `caps 40/5`); the rewriter must not choose between the copies |
| 17 | An unknown key under `ghostlock.execution` (or `execution.routes.<route>`) is an import error |
| 18 | A timer-only document is the full fixed shape with nulls (§3.2) |
| 19 | **Every backend reads the same JSON document.** The GLK1 binary wire is deleted (encoder, decoder, digest, and the planned cut); the native decodes the render directly, through the reader the timer tool already has (§4.6) |

## 3. The target document

### 3.1 Example (the real 6.1.134 Neo7 asset, converted)

```hocon
schema_version = 3
release = "6.1.134-android14-11-o-gd252f36df142"

devices {
  RE6022L1 { phys { load = "0x40000000", offset = "0x40000000" } }
}

kernel {
  image_base = "0xffffffc008000000"
  symbols {
    init_task = "0x201fbc0"
    init_cred = "0x2032028"
    root_task_group = "0x2208580"
    empty_zero_page = "0x2201000"
    selinux_state = "0x225afd8"        # was offset.selinux_enforcing
    selinux_blob_sizes = "0x1558a60"
    security_hook_heads = "0x1558350"
    nf_logger = "0x2012f88"
    loggers_0_1 = "0x2012ed8"
    boot_id = "0x237e188"
    panic_on_warn = "0x200b2c8"
    root_user = "0x2030d00"
    init_user_ns = "0x2030a70"
    init_ucounts = "0x2032588"
    init_groups = "0x2032020"
  }
  struct {
    task { prio = 132, …, cred = 2104, seccomp = 2304 }
    cred {
      copy_size = 128
      usage { offset = null, size = 4, value = 1 }
      uid = 4
      security = 120
      security_sid = 4
      caps { offset = 40, count = 5, mask = "0xffffffffffffffff" }
      refs {
        user       { offset = 128, image = null }
        user_ns    { offset = 136, image = null }
        ucounts    { offset = 144, image = null }
        group_info { offset = 152, image = null }
      }
    }
    waiter { shape = compact, pi_tree = 24, task = 48, lock = 56, wake_state = 64 }
    rt_mutex { size = 32 }
    selinux_state { enforcing = 0, initialized = 2 }
  }
  heap { stride = 1024, collisions = 4 }
  dirtyfrag = infeasible
  kdp = false
}

ghostlock {
  syscall = sigreturn
  carrier = null
  anchor {
    image   = "0x22568b8"
    bytes   = 4096
    stride  = 32
    scratch = "0x2256900"
  }
  syscalls {
    select_stack     = null
    sigreturn        { native { waiter_off { plain = 232, sve = 232 } }, compat = null }
    multicast_waiter { native = null, compat { waiter_off = 168, buffer_size = 260, futex_abi = time64 } }
    tcp_zerocopy     = null
  }
  module = null                     # module-only images: module { … }, syscalls = null
  execution = null
}

timer {
  trigger    = killable
  match_bits = 21
  sites {
    timer      { offset = "0x585510" }
    task_alloc { low_bits = "0x1c75f0" }
  }
  object_size     = 264
  task_alloc_size = 4800
  node_offset     = 120
  cred { request_size = 176, alloc_size = 192 }
  trace { gfp { offset = 24, size = 8 } }
}
```

Variants (reviewed in `/tmp/sesame-schema-review/shapes.conf` during the design):
module-only (`kernel` all-null except `dirtyfrag = unknown`, `ghostlock.module`, `timer = null`);
timer-only (`ghostlock = null`, `devices = null`, only the timer facts non-null); KDP
(`kdp = true`, `dirtyfrag = feasible`, the ladder is DirtyFrag-only).

### 3.2 The null principle

Every key the shape declares is present; a key with no value is `null`. A fixed vocabulary:
`kernel.symbols` names every symbol (null when the image does not export it); `ghostlock.syscalls`
lists the four known branches (null when infeasible); a branch states both carriers (null for the
one it does not declare). `null` means "no value" and the consumer's compiled default applies. The
merge already ignores incoming nulls (`deepMergeValues`), so a fully expanded document does not
shadow the presets or the builtin. The validator is strict: a missing key in a fixed block is a
schema error.

### 3.3 Measured facts (automatic overlay)

Declared measurable set: `kernel.dirtyfrag` (probe; match release + ROM fingerprint + probe
protocol) and `kernel.heap.stride` (`/proc/slabinfo`; match release). A matching measurement
replaces the document's value **in the resolved map**, never in the document; the run log and store
record the effective value and its source; the UI shows effective vs document; a static-vs-measured
disagreement is logged as an extractor defect. The merge preview + export stays, as the way to
materialize the effective facts into a self-contained document. Nothing outside the set can be
overlaid.

## 4. Implementation

### 4.1 profile-core

- `ProfileResolver.kt` — rewrite validation for the new shape:
  - block constants and fixed key sets (`devices`, `kernel`, `ghostlock`, `timer`, the symbol
    vocabulary, the four routes, the carrier pair);
  - cross-block rules: at least one engine; `ghostlock` ⇒ `syscall` names a non-null branch and
    `carrier` is null or a block of that branch; exactly one of `syscalls`/`module` is non-null;
    `kdp` ⇒ `dirtyfrag` ∈ {feasible, unknown}; the timer's fake group; multicast/sigreturn require
    `waiter.shape = compact`; an unknown key under `ghostlock.execution` (or
    `execution.routes.<route>`) is an error;
  - the anchor block's containment check (the only physical-layout rule that moves): a `scratch`
    inside `image .. image + bytes` must leave room for the 0x18-byte collateral window; a scratch
    outside the range is a second region and is allowed;
  - keep the physical-layout group checks (cred bounds, waiter ordering, `rt_mutex` vs anchor
    stride) on the new paths;
  - **delete** the global `KnownTopLevel`/`DeprecatedTopLevel` list, `pipelineOrder`,
    `requiresW1`, `W1RequiredTopLevel` (the W1 set moves inside `ghostlock`), `nativeValue`'s
    synonym table, the route/fallback search, `validateCompatCarrierGroup`, the carriers list, and
    the `compact_waiter` vs `waiter_layout.compact` comparison (the latter is the only copy now).
- `PerDevicePhys.kt` → reads `devices.<id>.phys`; a null half is the compiled default; an unknown
  device is a refusal; the map/scalar/null three-shape and key-set rules go.
- `ProfileMerger.kt` — drop `mergeRouteObjects` and the `fallback` special cases; deep merge on the
  new tree; `fillRouteExecutionDefaults` → `ghostlock.execution`; identity checks unchanged.
- `ProfileDocument.kt` — delete the `kernel{}`/`native{}` re-nesting; the backend document is the
  resolved map (one shape).
- `RouteCarriers.kt` — delete (carrier blocks are the declaration).
- `ModuleBlock.kt` — `ghostlock.module`, `sha256`; version dropped.
- `Timer98260.kt` — read `timer` plus the shared `kernel.symbols` / `kernel.struct.cred`
  (deduplicated overlay); `match_bits`; `sites`.
- `ProfileExporter.kt` — export the resolved document as HOCON; no mapping table, no wire.
- **Delete** `NativeProfile.kt`'s GLK1 encoder and its digest (§4.6); the render is the transport.
- **New** `MeasuredOverlay.kt` — pure overlay + provenance (facts in, effective map + records out).

### 4.2 App data layer

- `AndroidProfileConfigController.kt` — resolve/validate on the new shape; candidates become
  `(engine, syscall?, carrier?)` with the timer candidate without the `timer_98260` pseudo-name;
  display order and the stored per-engine choice; `completeProfileFields` emits the fixed shape;
  import dialog paths/labels; the single render (`renderTimerDocument` becomes the one transport,
  with the tool's VAs computed as `image_base + offset`); `injectPhysMaps` → `devices`; the merge
  preview writes `dirtyfrag`/stride.
- `AndroidGhostlockRepository.kt` — remove the pipeline list from the run path: the ladder
  (DirtyFrag if the effective verdict allows it, then GhostLock; KDP forces DirtyFrag; module when
  `ghostlock.module` is non-null); the measured overlay for `dirtyfrag` and the stride; provenance
  lines in the log and the run-store meta; the native input is the rendered JSON document (§4.6);
  the DirtyFrag probe recording unchanged; `meta.pipeline` still fed (1/2) from the chosen chain.
- `PipelineResolver.kt` — delete (the ladder replaces it); its unit test becomes a ladder test.
- `MeasuredProfileStore.kt` — key paths move to `dirtyfrag` / `heap.stride`; the overlay API
  (fact + ROM/protocol match + provenance). The DB is app-local and starts empty for the new
  package, so no migration.
- `TimerOnlyDocument.kt` — emit the full fixed shape with nulls (the timer's facts only).
- `TimerBlockText.kt` — delete: it exists because the old single-route cut never carried the timer
  block and the export had to append its text (`GhostlockViewModel.kt:2864`). The resolved document
  carries `timer` itself.
- `Profile.kt` — the resolved model, unchanged in role. `NativeProfile.kt` and
  `NativeProfileDigestTest` are deleted (§4.6).

### 4.3 App UI

- `RouteMatrixDialog.kt` — engine section (ghostlock/timer), syscall and carrier rows from the
  non-null branches, and the DirtyFrag row gated by the effective verdict rather than a chain list.
- `HomeScreen.kt`, `GhostlockViewModel.kt`, `SupportStatus.kt`, `AdvancedUI.kt` — source labels
  and the candidate model; `dirtyFragSelectable` reads the effective verdict.
- `FieldLabels.kt` — the new dotted paths for the import dialog.
- Measurements — effective vs document for the two measurable facts.

### 4.4 Extractor (Rust)

- `tools/extract_rs/src/report.rs` — the new `--format conf` render: the four blocks, hex
  image-relative symbols, `image_base`, the `struct` groups, `heap`, `dirtyfrag`, `kdp`, the
  branch/carrier catalogue with nulls, the timer block as offsets.
- `tools/extract_rs/src/derive.rs` — emit `image_base`; the link base the timer VAs were already
  built on; keep the send-path verdict as `dirtyfrag`.
- `tools/extract_rs/src/options.rs` — the options document's profile section to the same shape
  (the superset facts the ship script consumes).
- `tools/timer_extract/src/report.rs` — the timer block as offsets (no cred overlay, no
  `version`).
- Tests: the render assertions and the corpus (`options`, `cut`, `route_verdicts`, `codegen`,
  `families`) against the regenerated store.

### 4.5 Store and ship

- `tools/ship_golden_profiles.py` — render the new shape from the superset; `dirtyfrag` from the
  `pipelines` verdicts; `devices` from the entry's `devices[]`; the module block; nulls; the
  `--check` compares the new asset bytes.
- The store: regenerate each entry's profile facts with the updated extractor (deterministic from
  the in-hand images), then re-apply the hand-curated additions (device pairs, module manifests,
  the A16 KDP cell); review the per-entry diff.
- **`5.15.194`**: re-derive the cred layout from the image's BTF during the regeneration (the
  expected answer is `caps 40/5`; today's asset ships `48/3`) and adopt it. The regeneration must
  not pick between the two copies silently — this is the one entry where the resolution is
  recorded by hand, in `meta.txt`. Benign in value (the two skipped words are zero in
  `init_cred`), but wrong for the native's caps write.
- The 4 reference templates (`5.15/6.1/6.6/6.12-template.conf`) rewritten.

### 4.6 The transport: one JSON document for every backend

Decision 19 deletes the GLK1 v2 wire. Today the app renders canonical JSON for the timer and a
bespoke byte stream for ghostlock; from this release both are fed the same
`ProfileDocument.render(resolved)` document, and each reader takes the blocks it owns and ignores
the rest.

**Deleted:** `profile-core/src/main/kotlin/com/sesame/app/data/NativeProfile.kt` (959 lines:
encoder, digest, presence bookkeeping), `src/core/profile/binary.{cpp,h}` (690),
`NativeProfileDigestTest`, the wire halves of `NativeDocumentEquivalenceTest` /
`ProfileRoundTripTest` / `RouteCandidatesTest`, and the planned `ProfileCut.kt` is never written.

**The shared reader — two layers, because the existing timer reader already has them:**

- `src/core/profile/json.{c,h}` — the tree parser promoted from `src/timer/profile_json.c`: the
  arena, `MAX_DEPTH 32`, `MAX_VALUES 4096`, the 1 MiB cap, no duplicate keys, integers, `0x…`
  strings above 2^63, `null`/`true`/`false` (today's lines 65–527, plus `obj_get`/`value_u64`).
  The typed accessors and the strict per-block key check (`check_block_keys`) become public.
- `src/core/profile/json_profile.cpp` — ghostlock's mapper: tree → `profile::kernel_offsets`. It is
  the same name → field table the GLK1 decoder carries today, so the table moves and only the
  tokenizer changes. It derives `kernel_major` from `release` (decision 9).
- `src/timer/profile_fields.c` — the timer's mapper: the current `timer_profile_parse` body,
  rewritten to `timer` / `kernel.symbols` / `kernel.struct.cred`.
- Both binaries link `json.c`. Build: the ghostlock `Makefile` swaps `core/profile/binary.cpp`
  for `core/profile/json_profile.cpp` and adds `core/profile/json.c` to `C_SRCS` (the `.c` rule and
  `-Icore` already exist — `dfprobe.c` uses them); the timer's rule gains `-Icore`; `json.h` carries
  `extern "C"` guards. A new `src/core/tests/json_test` replaces `profile_binary_test` in
  `native-host-tests` (today's `profile_json_test.c` is built by no target at all).

**Transport mechanics.** `read_glk1_stdin` / `read_glk1_file` / `read_glk1_frame_stdin` become
`read_profile_*` over JSON; the Shizuku frame keeps its length prefix and carries JSON bytes (the
AIDL signature stays `ByteArray`, so the service's binder surface does not move);
`--load-prebuilt-profile` takes a JSON file (no profile `.bin` ships; the two `assets/*.bin` are
DirtyFrag blobs); the timer tool keeps `--profile <file>` (the service already writes
`timer-profile.json`). `pipeline_check` keeps its cross-check — the render states the
resolved chain for a run, as today.

**Sidecars and the log line.** `dumpRuntimeProfile` writes one `profile.json` — the exact bytes
handed over — instead of `profile.conf` + `profile.bin`, and the run log quotes its sha256 where it
quotes the wire digest today (`logProfileDigest`). That sidecar is the device test's evidence
channel (§6.1).

**Native strictness.** Each mapper refuses an unknown key in a block it owns — the app validator's
rule (decision 17) applied at the native edge. This is what replaces the digest's key-drift
detection, and it is testable with no run (§6.1 T3).

**Presence and the digest.** The wire's "key omitted ≠ provided 0" is now "null ≠ value": a null
key leaves the field unset in `kernel_offsets`, so the compiled default applies — the same rule the
document states (§3.2). `meta.recommend_shizuku` and `meta.fallback_route` are never written and
keep their 0 defaults. The wire's `kDigestMismatch` is replaced by `schema_version` plus strict
unknown-key rejection (decision 17); the digest only ever caught app/native skew inside one APK,
and that is the one protection this drops deliberately.

**The equivalence gate.** Freeze the **decoded `kernel_offsets`** for all 74 bundled profiles from
the current branch (a host-test dump produced through today's wire path), then require the new path
(asset → rewriter → resolver → render → `json_profile`) to reproduce it. Compare *effective* values
(the native's fallback applied), not optional presence, so "was absent, now stated as the same
value" is not a difference. The enumerated, reviewed exceptions:

1. `5.15.194`'s caps move `48/3` → `40/5` (§4.5).
2. `fallback_route` is gone (decision 3). It was never executed — only read by the
   non-compact-waiter guard (`src/core/attack/ops.cpp:106`) — and the six profiles that declared
   `fallback.to = select_stack` now carry that branch in `ghostlock.syscalls`, where it is also
   selectable (an intended UI change for those six).
3. Values the new document states unconditionally (the waiter shape, `rt_mutex.size`, the cred
   `usage`/`uid`/`security` fields, `selinux_state`) now always arrive; behaviour-neutral iff equal
   to the default the old path applied, which the gate asserts per family (uid 4 / security 120 /
   usage 4 on 5.x; the release-derived waiter and SELinux layouts elsewhere).

Every difference outside the list fails the gate.

### 4.7 Timer tool

The tool keeps reading the document the app renders; what changes is the reader's split (§4.6) and
its key names (`timer`, `kernel.symbols`, `kernel.struct.cred`). The app still computes the tool's
VAs (`image_base + offset`), so the tool's arithmetic is unchanged. `TimerBlockText.kt` and
`renderTimerDocument` follow. The loader's only gate becomes the shared `src/core/tests/json_test`
(a rendered 6.1.134 document through the timer mapper) — today's `profile_json_test.c` is built by
no target, and that gap closes here for both readers.

### 4.8 Docs and templates

`docs/kernel_profiles/PROFILE_SCHEMA.md` and `_ZH.md` rewritten for the new shape and the null
principle; `defaults.md` (the execution block path); the store entry READMEs that quote keys; the
asset header comments (`# fact_sources:` stays a comment).

## 5. Migration

1. **Freeze the decoded-profile golden** from the current branch for all bundled profiles — the
   native decoder's `kernel_offsets` per asset (§4.6).
2. Land profile-core + the shared native reader + tests against the *existing* assets through a
   one-shot `tools/migrate_profile_shape.py` that mechanically converts the old HOCON to the new
   shape: symbols hex, `image_base` derived from `timer VA − offset`, the timer overlay removed, the
   `dirtyfrag` verdict from the store's `pipeline_facts` with the old `pipeline` as fallback,
   `syscall` set to the old exporter's default route (the first viable alphabetically) so the
   decoded `route` does not move, a `fallback.route` branch moved into `ghostlock.syscalls`, and
   nulls for absent values. The rewriter is a verification aid, not the shipping path: every
   difference from the regenerated assets is listed and reviewed.
3. Regenerate the store profile facts and re-apply the hand-curated bits; `--check`; diff review.
4. Pre-release v3 rejection: a document carrying the retired top-level keys (`route`,
   `kernelsnitch`, `pipeline`, `offset`, `fallback`, …) is refused with one message
   ("pre-release v3 layout; regenerate with this build") rather than a list of unknown-key errors.
5. App data/UI, the ladder, the overlay, the timer mapper.
6. Docs, templates, device pass, release cut.

No document, preference or measured-DB migration is needed: the package rename
(`com.sesame.app`) starts all three empty, and an external file from the fork's pre-release builds
is refused with the message in step 4. The installed rigs are upgraded by install, not by data
carry-over.

## 6. Gates

- `:app:testDebugUnitTest`, `:profile-core:test`, `:app:assembleDebug` — including the new
  `execution` unknown-key rejection, the null-shape validator, and the overlay provenance tests.
- The decoded-profile equivalence golden (74 profiles, with the enumerated differences of §4.6 —
  no difference outside the list).
- Extractor `cargo test` + the store corpus (`options`, `cut`, `route_verdicts`, `codegen`,
  `families`) against the regenerated store; `ship_golden_profiles.py --check`.
- Native: `make -C src ghostlock`, `make -C src timer`, `lint-tidy`, `native-host-tests` (with the
  shared `json_test`: a rendered document through both mappers, plus a malformed-input corpus —
  truncated, deep, duplicate keys, unknown keys, oversize — each refused cleanly, never a crash).
  `cmp_disasm` is run as a control: the attack functions' instruction counts must not move (the
  profile reader is a separate call path); any change fails the gate.
- Device pass: §6.1 (one run per fresh boot; every run classified with the run-failure table
  before it is read as a verdict).
- No profile's support claim changes; only `5.15.194`'s caps value moves.

### 6.1 On-device test design

The attack code is untouched, so the device question is not "does the exploit still work" but
**does the native receive the same facts on a real device, on every family** — and does the new
parser fail cleanly. The three rigs pin three different mappings:

| rig | kernel | what it pins |
|---|---|---|
| diting (`cb7548c8`) | 5.10.236, QTI | the no-BTF 5.10 template (cred, the release-derived waiter/SELinux layout), the DirtyFrag ladder, the measured overlay, both transports (direct + Shizuku), the timer route |
| POCO X6 Pro (`FYPRFEG685EQP7LJ`) | 6.1.138, MTK | BTF-derived facts, the MTK phys rule, the four-branch catalogue, tcp/select, the **timer-only** document shape |
| OnePlus 13s (`e9b70795`) | 6.6.66 | the non-compact `rt_waiter_node` shape, the ladder selecting DirtyFrag end to end, the guarded-route refusal |

**T0 — provenance.** On every rig: versionCode, `lastUpdateTime`, and the packaged
`libghostlock.so` **and `libtimer.so`** md5s against HEAD (`libtimer.so` carries the timer mapper —
a stale tool would read new keys as unknown). Recorded before any run; the upgrade is
`adb install -r -d` with prefs and run-store listed before and after.

**T1 — the load path, one run per rig.** Fresh boot, the canonical harness
(`scripts/sigret-run-once.sh`: cpu-active hold, cpuset gate, live-bounds 执行 tap). Pass: the native
prints `resolved profile loaded: <release>` (the mapper's success line), the run store ends in a
terminal state, and the run's `profile.json` sidecar (debug export on) hashes to the sha256 the log
quotes. The stage reached is classified with the run-failure table — a `+0x188`, a W2 miss or a
phantom kill is a known class, not a schema signal. **One run proves the load path, not a rate**;
no rate claim is made.

**T2 — both transports (diting).** One direct run and one Shizuku run. Pass: the same T1 line from
each, plus the Shizuku ACK handshake succeeding (the frame now carries JSON with an unchanged
`ByteArray` signature). The OnePlus has no Shizuku and the POCO's arm is optional, so this is
diting-only.

**T3 — the decoder's refusals (device, no run).** The native takes `--load-prebuilt-profile <file>`
and refuses before any attack when the document is bad. Copy the packaged binary to
`/data/local/tmp` (it cannot be executed from the app's lib dir) and run it from `adb shell` — a
refusal only reads the file, so no root is needed — then push files and read the messages:

| input | expected |
|---|---|
| the rig's own rendered `profile.json` | **not run here** — a valid document proceeds into the attack; T1 covers it |
| the same with an unknown key in `ghostlock` / `kernel.struct.*` | refused, naming the key |
| truncated JSON | refused as malformed |
| today's GLK1 `profile.bin` | refused — no silent misparse of the old format |
| a valid document with another `release` | refused by the existing uname check |
| (OnePlus) a document selecting `multicast_waiter` with the non-compact waiter | refused by the route/waiter-shape guard (`src/core/attack/ops.cpp:106`) |

Each refusal must be a non-zero exit with one message, never a crash — and the last row is the
device half of the guard that the app validator mirrors. This is the cheapest real-device test of
the new code and the one that would catch a parser defect before a run does.

**T4 — the ladder and the overlay.** OnePlus: the ladder picks DirtyFrag from the effective verdict
and the log names the source (`static` / `probe` / `db`), ending `RESULT=OK script=0 enforce=1`.
Diting: seed the measured DB with a stride and a dirtyfrag record → the log and the merge preview
name the source and the effective value; clear the record → the document's value is used. Pass is
the provenance line, not the run outcome.

**T5 — generation → import → run.** Diting: parse `external/boot.img` (md5 `ab88fe15…`) on-device,
save; the exported document must be the new shape and its image-derived facts must equal the host
extraction (the ten offsets and the anchor), with the stride the picker reports on this rig (960);
import it → Home Ready → a T1-line run. POCO (MTK): skip the physical-pair prompt so generation
emits the **timer-only** document (§3.2) → import → the route picker offers only the timer → one
timer run through Shizuku, which exercises the timer mapper's new keys and the app's `image_base` VA
arithmetic. This is the only arm that feeds the timer block a real document.

**T6 — import rejection (diting).** Import a pre-release asset → the one-message rejection (§5.4);
import a document missing a fixed-block key → a schema error naming the path; import the same file
twice → stable. After each refusal the previous selection is intact (the existing transactional
rule).

**T7 — unreachable, recorded as gaps.** The module-only document (no 5.4 unit in hand) and the KDP
cell (no A16 unit). Neither is a pass line.

**Rig hygiene.** No flashing anywhere. The POCO is a daily device: upgrade only (`adb install -r
-d`), no clearing; every route choice, external selection and measured record a test touches is
restored from a pre-state dump. Runs are scheduled last, one per fresh boot, with the pstore
capture available (`CAPTURE=1`). Shizuku arms run only where the service is installed (diting,
POCO).

**Evidence.** This is an attack-path-adjacent change, so the records are archived, not ephemeral:
`external/port/profile-json-<date>/` with per-run `summary.txt`, the deciding log excerpt, the
run's `profile.json` sidecar (text, so it travels), and the T3 refusal outputs. Builds do not.

**Pre-registered fail rules.** Any of these fails the gate: (i) a rig whose app reports Ready but
whose native never prints the load line; (ii) a native crash or app `FATAL EXCEPTION` in T1–T6;
(iii) a T3 refusal without a message, or any T3 crash; (iv) a T5 export differing from the host
extraction outside the §4.6 exception list; (v) an import refusal that leaves the old selection
changed. A run that stops in a known class is reported as such, not as a pass.

## 7. Risks

| risk | mitigation |
|---|---|
| Store regeneration loses hand-curated fields (device pairs, module manifests, KDP) | the rewriter's diff per entry; `--check`; review each changed entry |
| The JSON mapper reads a value differently | the 74-profile decoded golden; an enumerated difference list |
| Null expansion changes merge behaviour | `deepMergeValues` already ignores incoming nulls; a merge test with a fully-expanded document |
| The ladder changes which chain runs | the ladder is the uniform policy the bundle already carried; device logs name the choice; KDP/probe gates unchanged |
| The new reader is on the run path | the strict bounded reader (fixed caps on depth, value/item counts and the string arena; duplicate and unknown keys refused), shared with an already-shipped consumer, the malformed corpus, `cmp_disasm` as a control |
| The timer tool breaks | the shared reader's host test with a rendered document; the app computes the VAs |
| An old pre-release profile imports silently | the explicit retired-key rejection (§5.4) |
| A rig is unavailable, or a run stops in a known failure class | T1's pass is the load line, not the run's outcome; T3 needs no run; an unavailable rig is recorded as a gap, never a pass |
| UI churn (engine/candidate model) | the route-matrix test and a device check of the sheet |

## 8. Decisions resolved in review

1. `w1_scratch` lives inside the anchor block: `ghostlock.anchor { image, bytes, stride,
   scratch }` (decision 15).
2. `5.15.194` is re-derived and adopted at `caps 40/5` (decision 16).
3. An unknown `execution` key is an import error (decision 17).
4. A timer-only document is the full fixed shape with nulls (decision 18).
5. Every backend reads the same JSON document; the GLK1 wire is deleted (decision 19, adopted
   after review).

## 9. Sequence and effort

| slice | work | est. |
|---|---|---|
| 1 | profile-core schema + the shared JSON reader + the native mapper + the decoded golden + the rewriter | 2–2.5 d |
| 2 | app data: resolver, ladder, overlay, controller, store | 1.5 d |
| 3 | extractor + store regeneration + ship + `5.15.194` | 1.5–2 d |
| 4 | app UI + timer mapper + docs/templates | 1 d |
| 5 | device pass (§6.1, three rigs) + release cut (build, NAS, notes) | 1–1.5 d |

Commits (one per slice, `feat(profile): …`), the branch flow per AGENTS.md. The exploit's attack
code is untouched, but its profile input is new: `cmp_disasm` is run as a control, and the decoded
golden in §4.6 is the review surface.

## Appendix A — key map (abbreviated)

| today | new |
|---|---|
| `kernel_major` | derived from `release` |
| `kernel_phys_load/offset` maps | `devices.<id>.phys.load/offset` (null = default) |
| — | `kernel.image_base` |
| `kdp` | `kernel.kdp` (boolean) |
| `pipeline` | `kernel.dirtyfrag` + the ladder (no list) |
| `route.<r>.lock_anchor_{image,bytes,stride}` + `w1_scratch.image` | `ghostlock.anchor.{image,bytes,stride,scratch}` |
| `route.<r>.carriers` / `compat_carrier` / geometry | `ghostlock.syscalls.<r>.<carrier>.*` |
| `route.<r>.compact_waiter`, `.task_offset`, `.lock_offset` | `kernel.struct.waiter.*` |
| `fallback`, `routes`, `recommend_shizuku` | gone |
| `offset.*` | `kernel.symbols.*` (hex); `selinux_enforcing` → `selinux_state` |
| `task_struct.*` | `kernel.struct.task.*` |
| `cred.*` (`ref0..3`, `ref_count`) | `kernel.struct.cred.*` + named `refs` |
| `waiter_layout.*` | `kernel.struct.waiter.*` (`compact` → `shape`) |
| `kernelsnitch.*` | `kernel.heap.*` + `kernel.struct.rt_mutex.size` |
| `selinux.*` | `kernel.struct.selinux_state.*` |
| `module` | `ghostlock.module` (`asset_sha256` → `sha256`) |
| `timer_98260.*` | `timer.*`, with `cred`/`task`/symbols deduplicated into `kernel.*` |
| `execution` | `ghostlock.execution` |
| `schema_version` / `release` | unchanged |
