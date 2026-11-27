# Conditioned page-free plans: a durable operator acceptance

Status: **implemented 2026-11-28 (S1–S4), gate green for the restored entry.** L-level
(extractor → store → bundled profile; the store format and the extractor CLI
change, the app does not). Operator approved the plan and asked for (2) first.
Results in §10.

## 1. What is and is not a defect

Two different things are in play, and only one of them is a bug.

**The 16-entry regression is a bug.** `shared_scratch` (`options.rs:567`) required
every chosen route to agree, and `tcp_zerocopy` can never be page-free
(`plans_for`, `options.rs:401`), so its absent scratch vetoed the shared one on
every image with a feasible tcp route. That is fixed separately (plan (1)) and is
not this document's subject.

**The conditioned-region loss is not a bug in any component.** For
`5.10.101-android12-9-00005-ga829d48e78bd-ab9206161` the extractor did exactly
what its policy says: `s3c24xx_serial_ports` is `conditioned` (the premise "no
`samsung,*` node / driver" is a device fact the app cannot check before W1), and
`scene_document` admits only `!conditioned` plans (`options.rs:503-510`). The app
renders the document; the ship script copies it. The information that was lost was
never in the pipeline:

- The 2026-10-30 cut recorded the acceptance **only in the asset itself** —
  a hand-written `w1_scratch { image = 42422016 }` with the premise in a comment.
- 2026-11-23 (`cb502571`) made the asset a rendering of the store document. The
  document model has no field for "the operator accepted this conditioned plan",
  so the rendering could only apply the automatic rule, and the hand record was
  overwritten.

So the extractor's *decision* was right; the store had **no channel to carry the
operator's verdict** into the one source of truth. This plan adds that channel.

## 2. Goal

A bundled profile may use a conditioned anchor/scratch pair only when the store
records an explicit operator acceptance **for that device**, the acceptance names
the pair (not an address), and every regeneration re-derives and re-validates the
addresses from the image. Without a record, behaviour is unchanged: conditioned
plans never enter the automatic chain.

Non-goals: no schema v4, no app change, no wire change, no device-gate change.
The acceptance is a store-side fact; the native already consumes
`ghostlock.anchor.scratch` without caring how it was chosen.

## 3. Record

One file per device, beside that device's sidecars:

```
external/test-kernels/<release>/devices/<codename>/plan.txt
```

```hocon
# Conditioned page-free plan accepted by the operator for this device.
# Premise that must hold on the device: no samsung,* node / driver.
accept = dump_skip.zeroes + s3c24xx_serial_ports
note = diting has no samsung node; the 0x18-byte window read zero on the QEMU boot of this Image and on the device (2026-10-30)
verified = 2026-10-30
```

The parser is the store's line `key = value` shape: one line per key, `#`
comments skipped, an unknown key ignored. `accept` and `note` are single lines.

- `accept` is `<anchor_name> + <scratch_source>`, resolved against `NAMED`
  (`options.rs:33`) and against the route's `page_free` plans (`anchor_name` +
  `scratch_source`). `in-anchor` is a valid scratch source.
- One acceptance per device (one `accept` key). If a second is ever needed, the
  flag becomes repeatable then.
- A legacy entry with no `devices/<codename>/` dir may carry the same block in
  `meta.txt`. Per-device wins.
- The **addresses are never written here.** The extractor re-derives them from
  the Image and the sidecars on every run; a changed Image fails the acceptance
  instead of silently pointing at a stale window.

## 4. Extractor

1. New CLI flag: `--accept-conditioned <anchor_name>/<scratch_source>` (e.g.
   `--accept-conditioned dump_skip.zeroes/s3c24xx_serial_ports`; the record's
   `anchor + scratch` maps one-to-one). One flag, rejected if repeated. It
   requires `--options-out` (the store document) or `--anchor` (the conf cut);
   it is rejected for a name not in `NAMED`.
2. **One selection function.** Today the per-route plan choice is duplicated:
   inline in `scene_document` (`options.rs:503`) and `auto_plan` in the CLI path
   (`main.rs:1413`). Extract

   ```rust
   pub fn select_plan<'a>(
       route: &str,
       plans: &'a RoutePlans,
       accepted: Option<&Acceptance>,
   ) -> &'a Plan
   ```

   with the order `accepted pair (if this route offers it) → first unconditioned
   page-free → anchor_only → page`. `auto_plan` becomes `select_plan(.., None)`.
   Both call sites use it, so the document and `--format conf` cannot disagree
   (the existing comment at `main.rs:1399` already demands this).
3. **Fail closed.** If the acceptance names a pair no feasible route offers (or
   no candidate), `build` reports the error and the process exits non-zero
   (`ExtractError::Message` → exit 2, the existing generic failure; 7 stays
   "ambiguous", and the store harness already refuses anything but 0/7). An
   acceptance that is silently ignored is exactly the failure this plan exists to
   prevent. The message lists the pairs the image actually offers.
4. On success, print an `info:` line naming the accepted pair, its anchor/scratch
   addresses and the reserved slots, so the regeneration log is the evidence.
5. `shared_anchor` / `shared_scratch` then run over the chosen plans unchanged.

## 5. Multi-device semantics

The document carries one shared anchor/scratch (`ghostlock.anchor`); the
acceptance is per device. Rules, enforced by the store harness and the gate:

- The accepting device must be **the first entry in `devices =`** (the merge in
  `tests/common` takes the shared sections from the first device's run).
- **Every device the document names must carry the same acceptance** (or the
  entry must name only accepting devices). Otherwise the harness refuses with a
  message; a shared scratch applied to a non-accepting device is a claim nobody
  made.
- When a kernel is ever shared by devices that need different scratches, that is
  a schema decision (per-device scratch). Do not split the field implicitly.

`5.10.101-ga829` is `devices = diting` only, so it satisfies the rule as-is.

## 6. Gate and ship

- `tools/extract_rs/tests/page_free_document.rs` (added 2026-11-28) grows one
  branch: when the entry carries an acceptance, expect the document scratch to
  equal that pair's derived value from the accepting device's plans; otherwise
  keep the current rule (any unconditioned plan ⇒ scratch present). It also
  asserts the multi-device coverage above. Without `GHOSTLOCK_IMAGE_DIR` it
  skips, like the other store tests.
- `ship_golden_profiles.py`: `compose_document` needs no change (the document
  already carries the scratch). Its asset header gains one comment line naming
  the accepted pair and the device, read from the same record, so a reader can
  tell an automatic scratch from an accepted one. `--check` keeps the document
  as the oracle; the capability assertion lives in the Rust gate.
- `meta.txt`/the entry may add a `page_free = conditioned` marker for human
  searchability; it is not parsed.

## 7. Tests and gates

Host:

1. `options.rs` unit tests: acceptance selects the conditioned plan and the
   document carries its scratch; no acceptance ⇒ anchor-only (current
   behaviour); acceptance naming an unknown candidate or an unoffered pair fails;
   `select_plan` is identical to the old chain when `accepted` is `None`.
2. `page_free_document` with the store: `5.10.101-ga829` green; the 16 stay
   listed until plan (1) lands; the other 41 unchanged.
3. Regenerate the store (`generate_options --ignored`): the only document diff is
   `5.10.101-ga829` (plus plan (1)'s 16 when it lands).
4. `ship_golden_profiles.py --check` and the app/profile-core suites (no app code
   changes expected).

Device: none. `5.10.101-ga829` is a QEMU-tier candidate and the acceptance does
not change its tier; the asset header stays `QEMU tier`, `NOT DEVICE-VERIFIED`.

## 8. Slices

| # | change | gate |
|---|---|---|
| S1 | `select_plan` extraction + acceptance flag (no store record yet) | unit tests, corpus documents byte-identical |
| S2 | `plan.txt` parser in `tests/common` + harness flag + multi-device rules | harness unit test; corpus unchanged |
| S3 | gate branch + asset header line | `page_free_document` on the store; ship `--check` |
| S4 | add the record for `5.10.101-ga829`, regenerate | the store diff is that entry only; gate green for it |

## 9. Decisions requested

1. Record location: `devices/<codename>/plan.txt` (recommended) vs a `meta.txt`
   key.
2. Stale acceptance: refuse (recommended, exit 2) vs warn and fall back.
3. Asset header names the accepted pair (recommended) vs document-only.
4. Multi-device: require every named device to accept (recommended, refuse
   otherwise) vs widen the schema now.

## 10. Result (2026-11-28)

Implemented as designed, with two small deviations noted below.

- **Store record:** `external/test-kernels/5.10.101-…-ga829…/devices/diting/plan.txt`
  (`accept = dump_skip.zeroes + s3c24xx_serial_ports`, the 2026-10-30 premise).
- **Extractor:** `--accept-conditioned <anchor>/<scratch>`; `select_plan` is the one
  per-route choice (the superset document and the CLI's `--anchor` path both use
  it); `build` fails closed (exit 2) when the pair is not offered, listing the
  pairs the image does offer. Unit tests: 198 pass (3 new: the conditioned plan
  enters only through an acceptance, an unoffered pair is refused, the CLI
  spelling parses).
- **Store regeneration** (hardlink copy, canonical `generate_options` harness,
  181.7 s): the only semantic change across all 62 entries is
  `document.ghostlock.anchor.scratch: null -> "0x2874f00"` on ga829; the other 59
  entries are byte-order-only (semantic diff 0). The harness reproduces the store
  exactly for a no-acceptance run (0 semantic diffs), so the acceptance is the
  only input that moved.
- **Applied:** ga829's store document carries anchor `0x2a0d568` + scratch
  `0x2874f00`; the bundled asset is refreshed with the acceptance header line.
  `ship_golden_profiles.py --check`: **62 bundled, 0 missing, 0 mismatched,
  11 exempt**.
- **Gate:** `page_free_document` checks 59 entries; ga829 passes (the acceptance
  branch compares its derived anchor + scratch); the 16 known tcp-veto entries
  remain, pending plan (1).
- **Golden:** the native-document golden re-frozen — one row moved (ga829); app
  307/0 (1 skip), profile-core 62/0 (1 skip).
- **Fail-closed checks:** a pair the image does not offer → exit 2 with the
  offered list; the flag without `--options-out`/`--anchor` → exit 2.

Deviations from the draft, both recorded in §4/§6:
1. The flag requires `--options-out` or `--anchor` (not "--format conf" alone;
   the default conf output does not read the document).
2. A stale/unoffered acceptance reuses exit 2 (the existing generic failure)
   instead of a new code; the store harness already refuses anything but 0/7.

Open: plan (1), the `shared_scratch` tcp veto, which keeps the 16 red.

## 11. Related fix (2026-11-28): the shared-scratch tcp veto

The document's scratch consensus in `scene_document` required *every* chosen
plan to carry the same scratch. `tcp_zerocopy` never can (its fake lock is a
crafted object; `plans_for` gives it no page-free plans), so a feasible tcp
route collapsed the shared scratch to null on every sampled 6.1 build. The
consensus now filters to `plan.page_free` -- the same eligibility the anchor
consensus already had (`filter_map` on `plan.anchor`) -- so a route that cannot
carry a scratch does not vote. Two page-free routes that disagree still yield
null (the document has one shared field; the gate names that case).

Effect: **16 profiles regained their W1 scratch** -- the shipped page-free count
goes 43 -> 59 of 62 (anchor-only 18 -> 2: 6.1.157-gbd, whose image offers no
page-free placement, and the excluded sidecar-less 6.6.102-gab8; plus the
5.10.246 timer-only profile).

- Store: regeneration (182 s, canonical harness) changed exactly
  `document.ghostlock.anchor.scratch` on those 16; applying it left each file
  semantically identical to the regenerated copy. The ga829 acceptance entry is
  untouched.
- Bundle: the 16 assets refreshed; `ship --check` 62 bundled / 0 mismatched /
  11 exempt (the 6.1.145 NX769J among them now carries `scratch = "0x21f1000"`).
- Golden: 9 of the 16 are rows in the native-doc fixture (the other 7 are not
  rows there), re-frozen; app 307/0 (1 skip), profile-core 62/0 (1 skip).
- Gate: `page_free_document` **59 entries checked, all carry their scratch**;
  extractor 199 unit (the tcp-veto and the disagreement shapes pinned).
