# One extractor — merge `timer-extract` into `ghostlock-extract`

**Status 2026-11-25 — proposed; no code yet.** Operator approved the direction ("plan doc, then
implement"). Supersedes the "two analyzers stay separate" decision in
[`timer-block-generation-plan.md`](timer-block-generation-plan.md) §3. Authority for the timer
mechanism and the profile block: `CVE-2026-98260-ABSORB-PLAN.md` (root repo) and
`external/port/diting-246-2026-11-19/TIMER-ROUTE.md`.

## 1. Problem

The split was meant to keep the timer engine's analyzer independent, but it produced three
workarounds that are now the expensive part of profile generation:

| workaround | where |
|---|---|
| `--facts-only`: continue past the CVE-2026-43499 refusal and emit an *incomplete* document that a second tool completes | `tools/extract_rs/src/main.rs:118`, `report.rs:469`, `document.rs:134` |
| a second native binary, a Gradle task, and a Kotlin deep-merge into both the document and the options envelope | `tools/timer_extract`, `build.gradle.kts:227`, `AndroidGhostlockRepository.kt:556/592`, `TimerFragmentMerge.kt` |
| a Python store sweep and `timer_analysis` preservation hacks in two Rust tests | `tools/sweep_timer_store.py`, `tests/generate_options.rs:36`, `tests/options_ondevice.rs:53` |

Two concrete defects follow from the split:

- `timer-extract --emit-document` is only a projection (no `kernel.image_base`,
  `kernel.struct.task.cred`, `kernel.symbols.selinux_state`), so the 5.10.246 asset had to be
  hand-extended (`TIMER-ROUTE.md`, shape finding 1).
- The app runs the timer analyzer only after the main extractor exits 0
  (`AndroidGhostlockRepository.kt:556`), so a route-ambiguous image (exit 7) silently loses the
  timer block.

## 2. Measured state (2026-11-25)

- `tools/timer_extract` is already half this repo: `boot`, `btf`, `disasm`, `error`, `ikconfig`,
  `kallsyms`, `kallsyms_finder` are vendored copies of `extract_rs`'s modules (their headers say
  so). `extract_rs`'s copies are supersets; `ikconfig` is the one module that was pruned hard
  (386 → 119 lines). Only `derive.rs` (2050), `facts.rs` (361) and `report.rs` (847) are
  timer-specific; `kbuilds.rs` (314) is unwired here (`--add` writes `timer/kbuilds.h`, absent).
- The document already has the seam: `document::build` emits `("timer", Node::Null)` and lists
  `panic_on_warn`, `root_user`, `init_user_ns`, `init_ucounts`, `init_groups` as `Node::Null`
  (`document.rs:174/206`) — exactly the five the timer fragment fills (`timer report.rs:360`).
- The store's `options-*.json` documents already carry the timer block for most entries (checked:
  5.10.198/226, 6.1.138 ×5, 6.6.66), and 62 of the 75 entries in the asset `index.conf` carry it
  (the 13 without are the two 5.4 module profiles, the four templates and six 6.1.1xx entries), so
  a generated profile that carries the block matches the bundled profiles. Today generated
  profiles get it only when the second analyzer happens to succeed.
- Timer unit tests: 27 (21 in `derive`, 4 in `facts`, 2 in `report`). Extractor: 166 + corpus.

## 3. Design

### 3.1 Target layout

```
tools/extract_rs/
├── src/
│   ├── timer/
│   │   ├── mod.rs        entry point `analyze(&ImageCtx, &Options) -> Result<Analysis>`
│   │   ├── derive.rs     ported verbatim (crate:: paths unchanged)
│   │   ├── facts.rs      ported verbatim
│   │   ├── report.rs     ported; the projection renderer (`render_document`) deleted
│   │   └── NOTICE        upstream Apache-2.0 notice + provenance
│   └── … (existing modules untouched)
└── tests/
```

The timer modules keep their provenance headers ("derived from YuKongA/CVE-2026-98260
`tools/extract`") so an upstream sync is a diff of one directory. `tools/timer_extract/` is
deleted, and `NOTICE` moves into `src/timer/`.

The shared parsers are **not** copied again: `timer::derive` imports `crate::{boot, btf, disasm,
error, ikconfig, kallsyms, kallsyms_finder}`. Two API deltas:

- `ikconfig`: add `embedded_config(&[u8]) -> Option<BTreeMap<String,String>>`, `parse(&str)` and
  `REQUIRED_CONFIG` (moved from the timer copy) beside the existing `KernelConfig`. No behaviour
  change to `KernelConfig`.
- `timer::derive::analyze` stops loading the image. It takes the already-parsed inputs:
  `analyze(kernel: &[u8], release: Option<String>, ks: &Kallsyms, btf: Option<&Btf>, options:
  &Options)`. `Symbols::new(&ks)` and the BTF accessors already match.

### 3.2 One pass, one document

In `main.rs`, after `resolve_kallsyms` and BTF are available and after the 43499 primitive check:

1. Run the timer analysis unless `--no-timer`. A failure is logged (`timer: <reason>`), never
   fatal; `timer = null` and the pass continues.
2. Derive the native facts exactly as today — including on a CVE-refused image, because the timer
   tool needs `kernel.image_base`, `kernel.struct.task.cred` and `kernel.symbols.selinux_state`,
   which only the native derivation produces. This is what `--facts-only` already does; the flag
   merely stops being a separate mode.
3. Render one document:
   - the `kernel` block from the native renderer, with the timer fragment merged into it using
     the **same "null means no opinion" deep merge** the store sweep uses (`sweep_timer_store.py`
     `merge()`), so values, shapes (`cred.refs`' `image` keys) and ordering are preserved;
   - the five timer-owned symbols filled from the timer analysis;
   - `timer = <block>` when the timer is vulnerable and the block completes the fake group;
     otherwise `timer = null`;
   - `ghostlock = null, devices = null` when the 43499 primitive is patched (today's facts-only
     shape), otherwise the normal blocks.
4. `--options-out` gains `timer_analysis` (the evidence object plus `document` = the cleaned timer
   fragment), written by the extractor itself.

So one invocation produces a complete, tool-runnable document for every outcome: native, native +
timer, timer-only, or nothing.

### 3.3 CLI surface

Removed (the app and the store sweep are the only callers; both are updated in this change):

- `--facts-only` — the default behaviour now covers it.
- `--emit-timer-conf`, `--emit-superset`, `--emit-document` — the document and the envelope carry
  these. `--emit-timer-conf` is kept, renamed `--timer-conf-out PATH`, for evidence capture and
  the operator's scripts; it writes the same fragment.
- `--add`, `--kbuilds` — unwired in this repo and its registry file does not exist.

Kept, prefixed (`--timer-*`) so the native flags stay unambiguous:

- `--timer-layout-template 5.10` (the labelled no-BTF template), `--timer-layout k=v`
  (repeatable), `--timer-runtime-facts FILE`, `--timer-detect-only`, `--timer-endgame
  real|fake|real,fake` (default `real,fake`: a profile's block must complete the fake group to be
  viable).

New: `--no-timer` (skip the 98260 analysis; the fast native-only path for tests and triage).

### 3.4 Exit codes

Unchanged and now independent of the engine:

- `0` — a document was written (native, native + timer, or timer-only).
- `6` — nothing derivable: the 43499 primitive is patched **and** the timer is patched/derivable
  false.
- `7` — route ambiguity; the document is still written, with the timer block if present.

The timer's own verdict (`vulnerable`/`patched`/`unknown`) is **not** a process exit code; it goes
to stderr (`timer: vulnerable, block derived`) and `timer_analysis.verdict`.

### 3.5 App changes

- `build.gradle.kts`: delete `buildTimerExtract` and `prepareTimerExtractJniLibs`; delete
  `app/build.gradle.kts:196`'s dependency.
- `AndroidGhostlockRepository`: delete `TimerExtractBinaryName`, the binary lookup,
  `deriveTimerBlock`, `timerLayoutTemplate`, and both `mergeTimerFragment` calls. `parseSource`
  stops passing `--facts-only`; `timerDerived` is read from the parsed document
  (`document["timer"] != null`). The timer lines of the parse log come from the main extractor's
  stderr.
- `TimerFragmentMerge.kt` is deleted.
- `advancePhys`'s ghostlock-null branch stays (a patched image still produces it), but the
  document already carries the timer block; `finishTimerOnly` consumes it directly.
- `TimerOnlyDocument` **stays**: the MediaTek pair-missing path still projects a native document
  to timer-only.
- `timer-block.conf` sidecar: keep writing it from the document's `timer` block if the run log's
  evidence is still wanted; otherwise drop it (decided during implementation, not a gate).

No change to `ProfileResolver`, `Timer98260`, the wire or the native.

### 3.6 Store tooling

- `tools/sweep_timer_store.py` is deleted. `generate_options` (the store regenerator) no longer
  preserves `timer_analysis` by hand; the extractor writes it.
- `tests/options_ondevice.rs` stops stripping `timer_analysis`; it asserts the timer block for
  entries whose `timer_analysis.verdict` is vulnerable.
- Everything else (`ship_golden_profiles.py`, `migrate_profile_shape.py`) is untouched.

## 4. Gates

The output format does not change, so this is a value-equivalence exercise:

1. **Store equivalence.** Regenerate the whole store with the merged tool and compare every
   `options-*.json` `document` against the current (swept) store, value-wise after canonicalising
   key order. Zero differences expected; any difference is a porting bug. `timer_analysis` must be
   present exactly where it is today.
2. **Bundle.** `ship_golden_profiles.py --check` stays **62 bundled / 0 missing / 0 mismatched /
   11 exempt**, and `--refresh-all` on a copy changes no fact.
3. **Host.** Extractor 166 lib + full corpus (options/route_verdicts/cut/codegen) with the ported
   27 timer tests; app 278; profile-core 60.
4. **Device** (the rig is on stock `boot_a`, build 750):
   - diting 5.10.236 generated profile: native document + timer block, block equal to the
     bundled asset's.
   - diting 5.10.246 Image: a timer-only document **without** `--facts-only`, matching
     `external/port/diting-246-2026-11-19/timer-246-run-document.json`.
   - duChamp 6.1.138: the generated block stays byte-identical to the shipped 6.1.138 block
     (already a recorded check, `timer-generation-5x-2026-11-23`).
5. No native/wire change: `cmp_disasm` is not re-run. APK versionCode moves by the commit count.

## 5. Work items (staged commits)

1. `refactor(extract): move the timer analyzer into extract_rs as src/timer/` — move the five
   modules, delete `tools/timer_extract`, fix imports, port `ikconfig` helpers. Host tests:
   `cargo test` on the merged crate (extractor 166 + timer 27).
2. `feat(extract): one document carries the native and timer facts` — the timer analysis call, the
   merge into the kernel block, `timer` emission, `timer_analysis` in `--options-out`, the CLI
   surface (§3.3), exit codes (§3.4). Store equivalence gate.
3. `refactor(app): one extractor for generation` — Gradle, repository, delete
   `TimerFragmentMerge`, store scripts. App + profile-core tests, bundle check.
4. `docs(evidence): …` + the device pass (§4.4); APK cut.

## 6. Risks and non-goals

- **Vendor drift** (the one real cost): the timer logic is upstream-derived. Mitigation is the
  module boundary + provenance headers + NOTICE; an upstream update is a diff of `src/timer/`.
  This obligation exists today — the copy was hand-made 2026-11-22.
- **Merge-order drift**: the combined renderer must reproduce the sweep's deep-merge result, not
  re-derive cred values independently. The store-equivalence gate is what proves it.
- **Failure isolation**: a timer failure must never fail the native parse; covered by tests on an
  image with no BTF and no template.
- Non-goals: no change to the timer exploit tool (`src/timer/` C sources), the profile schema, the
  native wire, the run path, or the W4 selection policy (the picker behaviour is unchanged; it
  already offers the timer when the block is viable).
