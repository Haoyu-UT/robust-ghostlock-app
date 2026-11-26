# One module asset per kernel line — the per-image ABI comes from the profile

**Status:** design for operator approval, 2026-11-26. L-level (delivery path + profile
format); no code is written yet. The slices and gates below are the implementation
contract.

**Question.** The 5.4 module chain ships one `dfswitch.ko` per release
(`modules/5.4.233-….ko`, `modules/5.4.274-….ko`), each hand-built against that Image. Can
one build per kernel **line** serve every Image, with the per-image numbers supplied by the
profile, without giving up the loader's ABI checks?

**Answer.** Yes. The module's only per-image content is five 32-bit CRC words in
`__versions`; the release string in `vermagic` is already ignored by the loader when CRCs
are present, and the struct layout is the line's. So: one asset per line, the CRC values in
the profile document, applied to the asset's `__versions` entries at delivery, after the
asset's sha256 is verified. The loader stays the enforcer — a wrong value is a clean
`ENOEXEC`, not a load.

Two things this plan explicitly does **not** do: compile on the device (the exact ACK tree
per release is the cost, not the compile), and carry the SELinux `enforcing` offset in the
profile (it is derivable at run time from symbols; see §3.5).

## 1. What the loader checks, and what is therefore per-image

In `external/ack-5.4.233/kernel/module.c` (identical shape on 5.10/6.1):

| gate | code | compared | depends on |
|---|---|---|---|
| vermagic | `same_magic()` `:1371`, called `:3275` | the *suffix* (`SMP preempt mod_unload modversions aarch64`); the release string is skipped iff the module has a `__versions` section | `CONFIG_SMP`, `PREEMPT`, `MODULE_UNLOAD`, `MODVERSIONS` |
| struct layout | `check_modstruct_version()` `:1352`, called `:3995` | CRC of `module_layout` — a dummy function whose signature names `struct module`, `modversion_info`, `kernel_param`, `kernel_symbol`, `tracepoint` (`:4746`) | the kernel's own headers + config |
| per-symbol CRC | `check_version()` `:1303`, called `:1487` | each `__versions` entry (`struct modversion_info { unsigned long crc; char name[56]; }`) vs the kernel's `__crc_<sym>` | the Image's build |
| symbol resolution | `simplify_symbols()` `:2366` | every import exists and is exported | the Image's export set |

The module parameters are irrelevant to all of this: `parse_args` runs at `:4086`, after
every gate and after relocation, and `do_init_module` at `:4114`. The checks read the ELF
image, never the module's data.

Measured facts that license one asset per line:

- **`__versions` is patchable.** The venus asset was historically the star-tree build with
  its per-image metadata; the star-built code with venus's CRCs loaded in the venus Image
  under QEMU (`finit_module rc=0`). Only `module_layout` differs between the two; `printk`,
  `register_kprobe`, `unregister_kprobe` and `__ubsan_handle_cfi_check_fail_abort` are
  identical (`external/port/dirtyfrag-5x-gate-2026-11-21/g4-module-abi.md`).
- **The release string is not the gate.** The control in the same record: the venus module
  on the star kernel fails with `disagrees about version of symbol module_layout`, not a
  vermagic error.
- **The compiler is not the gate.** The 5.4 kernels were built with clang 11.0.2 and accept
  a clang-21 module; the CFI type hashes agree across that span.

## 2. The decision this reverses

`profile-core/src/main/kotlin/com/sesame/app/data/profile/ModuleBlock.kt` documents the
current stance:

> The image's module ABI (vermagic, `sizeof(struct module)`, the `module_layout` CRC) is not
> duplicated here: the build gate pins the exact Image config, the loader enforces the ABI
> (a wrong magic half or CRC is a clean `ENOEXEC`), and `asset_sha256` binds the exact
> artifact. A profile-side ABI claim could only re-assert what the bundle already fixes; the
> app cannot read the running kernel's ABI to check it against.

That premise holds only while the asset is per-release. Once the asset is shared by a line,
the bundle no longer fixes the per-image CRC, and the document becomes the only place it can
live. The reasoning survives in a modified form:

- The app still cannot read the running kernel's ABI, so the values are **Image-derived at
  generation time** (or fixed by a bundled gate), never probed at run time.
- The loader remains the enforcer; the profile states what the loader will compare against.
- Fail-closed is preserved: a profile whose values do not match refuses to load on the
  device, with a named symbol in the kernel log.

Bounding the change: the resolver already refuses the module pipeline unless the bundled
layer is authoritative (`AndroidGhostlockRepository.kt:1628`, "module pipeline is
bundled-only"), so an imported or externally selected document can never name a module. Only
bundled documents carry `module`, and there are exactly two (the hand-finished 5.4
documents). **No on-device CRC derivation is in scope.**

## 3. Design

### 3.1 Asset

One asset per line, for example `modules/5.4-line.ko` (10,344 B class), built from the
representative tree — `external/ack-5.4.233` — with the module source of §3.5. The venus
precedent is the proof that the ACK tree's `struct module` section is accepted by the other
release on the same line.

A new image joins a line asset only through a gate, not a schema edit: its own QEMU run must
show `/sys/fs/selinux/enforce` 1 → 0 with the line asset patched with that Image's CRCs
(§5). `module_layout` inequality (star ≠ venus today) is not evidence of incompatibility and
must not be turned into an equality check; the gate is the license.

### 3.2 Profile block

Extend `ghostlock.module` with `crcs`, a name → value map:

```hocon
module {
  asset = "modules/5.4-line.ko"
  sha256 = "<canonical asset hash>"
  crcs {
    module_layout = "0x4e974a74"          # 0x894a26ae on venus
    printk = "0xc5850110"
    register_kprobe = "0x3ce77caf"
    unregister_kprobe = "0xeb3f8466"
    __ubsan_handle_cfi_check_fail_abort = "0xdf7a4c69"
  }
  vendor_files = [ … ]
}
```

Keys are the module's import names, exactly. `sha256` keeps binding the canonical
(unpatched) asset.

Schema work:

- `ModuleBlock.kt`: add `crcs` to `BlockKeys`; require it whenever the block is present; each
  value a non-zero `Long` (decimal or `0x…`); each name a valid symbol name; update the
  class comment to §2's invariant.
- `ProfileResolver.kt:57` `ModuleKeys`: add `crcs`.
- New resolver rule: the document's `release` line (`major.minor`) and the asset's fleet
  line (`modules/<line>-…`) must match. Catches a copy-pasted document before I/O.

### 3.3 Delivery

The patcher runs inside `BundledModule.stage` (`app/.../data/BundledModule.kt`), after the
sha256 check and before the file is written:

1. read the asset, verify `assetSha256`;
2. parse the ELF section table, locate `__versions`;
3. for each entry, find the name in `crcs`; write the value into the low 4 bytes of the
   8-byte `crc` field and zero the high 4 (the kernel compares as `u32`);
4. refuse on any name mismatch in either direction, a missing/duplicate entry, or a short
   section;
5. write the patched bytes under a release-qualified name (`dfswitch-<release>.ko`) and log
   canonical hash, delivered hash, and the five applied values.

The existing delivery (`AndroidGhostlockRepository.kt:1405-1448`) is unchanged: it reads the
staged file, pads it, and builds the 16-byte IV plan. The patcher must be an in-place
rewrite — no section moves, no size change — so `.modinfo` keeps the module's name (the
unload path in `7a117291` reads it from there) and the `alt/sha1` story stays simple.

### 3.4 Vermagic

Never patched. With a `__versions` section present the release half is skipped by
`same_magic()`, and the suffix is the line's compile-time constant. The QEMU gate is where a
suffix mismatch would surface (it is a refusal, not a corruption).

### 3.5 The `enforcing` offset — runtime, not profile

The switch's target byte is the one value that must not be a blind write or a profile claim:

| family | +0 | +1 | +2 | +3 |
|---|---|---|---|---|
| 5.4.233 / 5.4.274 (measured) | `disabled` | **`enforcing`** | `checkreqprot` | `initialized` |
| 5.10 / 5.15 / 6.1 | `enforcing` | `checkreqprot` | `initialized` | |
| 6.6 / 6.12 | `enforcing` | `initialized` | | |

(5.4: `five-four-support-plan.md` §1.1, disassembly of both Images; family drift:
`page-free-routes-plan.md` §2.1.)

Design:

- `DFSWITCH_LINE=54`: `enforcing` is `+1` (the 5.4 `disabled` field is unconditional). The
  module writes `+1` only.
- `DFSWITCH_LINE=gki` (5.10+): `offset = kallsyms_lookup_name("selinux_disable") ? 1 : 0` —
  `disabled`'s presence is exactly `CONFIG_SECURITY_SELINUX_DISABLE` on those lines.
- Every line: `if (!kallsyms_lookup_name("sel_write_enforce")) return -EOPNOTSUPP;` —
  `sel_write_enforce` is compiled only under `CONFIG_SECURITY_SELINUX_DEVELOP` and stubbed
  to `NULL` otherwise (`selinuxfs.c:133-190`). With DEVELOP=n the switch cannot work, and
  the module refuses instead of writing `checkreqprot` and reading back success.
- `+0` is never written. On 5.4 `disabled` is inert (only `selinux_disable()` reads it,
  `hooks.c:7388/7393`, and it deletes the hooks rather than restoring them), but the same
  write on 6.6/6.12 would zero `initialized` — the failure the page path's acceptance
  predicate exists to avoid. One rule for all lines beats a per-family exception.

`tools/dirtyfrag_module/{dfswitch.c,Makefile,build.sh}`: replace the required
`DFSWITCH_ENFORCING_OFFSET` with a required `DFSWITCH_LINE` (`54` or `gki`; anything else
refused), and log `offset=… guard=develop`.

### 3.6 Fail-closed rules

| rule | where | on failure |
|---|---|---|
| asset sha256 | `BundledModule.stage` | null → resolver refuses the chain (existing) |
| line match (release ↔ asset name) | `ProfileResolver` | config error, module pipeline unavailable |
| `crcs` names == asset `__versions` names | `ModulePatch` | refusal, no bytes delivered |
| every crc non-zero, name valid | `ModuleBlock` | config error |
| DEVELOP (`sel_write_enforce` present) | module init | `-EOPNOTSUPP`, nothing written |
| ABI (vermagic suffix, `module_layout`, remaining CRCs) | kernel loader | `ENOEXEC`, device stays up |

## 4. Slices

Each slice is independently shippable and gated. Branch: a topic branch cut from `document`
(`feat/module-single-asset`), per `BRANCH-POLICY.md`.

**S1 — module source: line selector, DEVELOP guard, runtime offset.**
Files: `tools/dirtyfrag_module/dfswitch.c`, `Makefile`, `build.sh`. Rebuild the star and
venus assets (5.4, per-release names kept for this slice) and the diting bench (5.10, not
bundled); update the two profiles' `module.sha256`.
Gates: zero-warning module build; `qemu-module-switch-gate.sh` on both 5.4 Images reads
1 → 0; the pre-fix asset remains the negative control (1 → 1); diting bench logs the guard
and the derived offset; app/profile-core suites unaffected.

**S2 — schema.**
Files: `ModuleBlock.kt`, `ProfileResolver.kt`, `ModuleBlockTest.kt`,
`ProfileResolverTest.kt`, `ProfileMigrationEquivalenceTest` fixtures, `FieldLabels.kt`
(EN+ZH for the new paths). Add `crcs`; add the line rule.
Gates: profile-core + app suites; the two 5.4 documents re-render with the new key; store
regeneration unchanged for every non-module profile.

**S3 — delivery patcher.**
Files: new `app/.../data/ModulePatch.kt`; `BundledModule.kt`; `BundledModuleTest.kt`; a
`RunStore` meta line (`module crcs=… delivered=…`).
Gates: unit tests over the real assets — star CRCs, venus CRCs, wrong name set,
duplicate/missing entry, idempotence; a host end-to-end test that stages the asset, patches
it, and re-reads the five entries. No native change ⇒ `cmp_disasm` not applicable (state
that in the slice record).

**S4 — one asset per line and the bundle check.**
Files: replace the two `.ko` assets with `modules/5.4-line.ko`; the two documents'
`module.asset`/`sha256`; `tools/ship_golden_profiles.py` (`--check` verifies, per module
profile: asset exists, hash matches, crcs names equal the asset's `__versions` names, and
— where a recorded symvers exists — the values); `BuiltinProfilesTest`.
The 5.4 documents are hand-finished (there is no 5.4 store entry); the values come from
`external/port/dirtyfrag-5x-gate-2026-11-21/g4d-switch-modules/*.symvers`. Future lines use
the same recorded-symvers procedure.
Gates: `ship --check` green; app tests; APK built and hashed.

**S5 — gates and record.**
QEMU: the **same** `5.4-line.ko`, patched with star's CRCs on the star Image and venus's on
the venus Image, both 1 → 0; negative control: star-patched asset on the venus Image must be
refused with `disagrees about version of symbol module_layout` and the guest must stay up.
Diting (5.10 bench): guard and offset log. Device: only if a 5.4 unit is available — the
star reporter, by arrangement; the operator has none. Evidence:
`external/port/module-single-asset-2026-11-26/` (README, consoles, hashes, patcher log); NAS
push if an APK is cut (`BUILD.txt`).

## 5. Risks and open questions

1. **`module_layout` is a conservative hash.** Star and venus differ while the loader-visible
   layout matches; an equality check would break the feature. The line asset's coverage is
   exactly its QEMU-gated image set, recorded per pair.
2. **A wrong profile now fails at load, not at build.** Intended, but it moves the failure
   from CI to the device: the QEMU gate and the ship check are the only pre-device nets.
3. **The patcher is on the attack path** (bytes entering a vendor_file before insmod). It is
   Kotlin-only and host-tested, but it needs review like any delivery change.
4. **Provenance.** After patching, the delivered hash differs from `asset_sha256`. Every log
   and meta line must name which hash is which, or triage will chase a phantom mismatch.
5. **`DFSWITCH_LINE=gki` on 5.15/6.x** is derived from the same two symbols but has no QEMU
   gate yet; it must not be bundled until it does. Only 5.4 and the 5.10 bench are in scope.
6. **The `.modinfo` name** must survive the patch byte-for-byte; the unload path
   (`7a117291`) reads it from the staged file.

## 6. Approval checklist

- [ ] §2's invariant reversal: the bundled document carries the per-image ABI values.
- [ ] `module.crcs` as a schema key (profile-core + docs labels).
- [ ] The delivery patcher (`ModulePatch`) as an attack-path change.
- [ ] `DFSWITCH_LINE` replacing `DFSWITCH_ENFORCING_OFFSET`, with the DEVELOP guard.
- [ ] The line-asset policy: a new image is bundled only after its own QEMU gate.

Approval to proceed on S1 may be given separately; S2–S4 land behind the schema record.
