# Facts-only generation — a timer-only profile for a GhostLock-refused image

Status 2026-11-25 — approved by the operator (Fix B). Changes the extractor's CVE-refusal branch
and the app's generation flow; no native wire, no profile schema change.

## Problem

The diting 5.10.246 line is patched for GhostLock (CVE-2026-43499) and DirtyFrag, but open to the
timer engine (CVE-2026-98260). `ghostlock-extract` refuses the image at the CVE check (exit 6),
and `parseSource` only derives the timer block when the native extractor exits 0:

```kotlin
if (exit == 0 && parsedFile.isFile) { … timerBlock = deriveTimerBlock(…) }
…
if (code != 0 || !parsedFile.isFile) return ParseResult.Failed(code)
```

So the app cannot generate a timer-only profile on-device for a GhostLock-refused image. The
bundled profile exists only because it was assembled host-side.

## Design

1. `ghostlock-extract` gets `--facts-only`. When the CVE check refuses:
   - without the flag: current behaviour (exit 6; `--analysis` still reports and returns);
   - with the flag: warn and **continue the full derivation** (kallsyms, BTF/template layout,
     pipeline facts, route geometry), then emit a **facts-only document**:
     `ghostlock = null`, `devices = null`, the full `kernel` block (image_base, symbols, struct,
     heap, dirtyfrag, kdp), `timer = null`. The document is produced by
     `report::render_conf_facts_only`; the route-superset render is bypassed.
2. On a CVE-vulnerable image `--facts-only` is a **no-op** (the check passes, the normal document
   is emitted), so the flag cannot change any image that generates today.
3. The app always passes `--facts-only`. After the extractor exits 0 it derives the timer block as
   today. A facts-only document is detected by `ghostlock == null`; the app routes it to
   `finishTimerOnly` (the existing MediaTek timer-only path) regardless of platform. When the
   document's `kernel.dirtyfrag` is `feasible` the app logs a note that a facts-only document has
   no ghostlock block, so this flow cannot build the DirtyFrag ladder — but it still derives the
   timer route (a working profile beats a refusal). It fails only when no timer block was derived.

## The marking is structural

A facts-only document has `ghostlock = null`. The native profile loader already refuses it
("ghostlock is null; no native route to run"), so a facts-only document can never reach a native
run even if it bypassed the app branch. `TimerOnlyDocument.from` (which nulls ghostlock/devices
for the projection) therefore only has to merge the timer block in.

## No-regression argument

`--facts-only` only changes behaviour on the branch that currently returns exit 6 — i.e. images
the app already cannot generate. Every image that generates today keeps its exact document.

## Tests

- extractor: `render_conf_facts_only` emits `ghostlock = null`, `devices = null`, and the kernel
  block; `render_conf` is unchanged (the existing 12 fixtures).
- extractor gates: `cargo test`.
- app: existing Kotlin suites; the on-device `derive_test` debug intent runs the whole path against
  the 5.10.246 Image and records `result=Parsed timerDerived=true`.
