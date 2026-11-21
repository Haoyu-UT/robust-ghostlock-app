# The module + Magisk implementation (5.4) — L-level plan

**Status 2026-11-22: design proposal, branch-local, no code until approved.** This file lives only
on `feat/dirtyfrag-5x` and changes nothing by itself. It is the plan for a *new implementation of
the same goal* the GhostLock pipeline pursues — root on a locked phone — for the 5.4 qgki line,
where the existing pipelines cannot run: no route is feasible (G1), and KernelSU cannot be
late-loaded (no supported kernel module for a 5.4 KMI). The new chain uses the DirtyFrag primitive
without W1, a per-kernel module as the SELinux switch, and Magisk as the userspace root manager.

The profile question is section 3; the proposed answer is additive and does not change the meaning
of any existing key or value.

## 1. The chain

```
app          resolve pipeline "module" (section 3) → ROM preflight → primitive probe
             └─ build the page-cache plan, start the DirtyFrag stage        (no W1, no route)
init         libc++ sentry ctor runs the stub → abstract socket → root script (uid 0, init domain)
             ├─ insmod the release's module      (transition to vendor_modprobe, module_load)
             ├─ module sets selinux_state = false → permissive             (the W1 replacement)
             ├─ Magisk late install, still in the init domain:
             │     tmpfs MAGISKTMP, binaries staged by the app, magiskpolicy --live,
             │     magiskd (post-fs-data → service → boot-complete)
             ├─ restore Enforcing
             └─ restore the patched blocks, print the verdict
app          verify: getenforce, magiskd alive, su from an external shell, run store completed
```

**What it removes:** W1 and the whole futex/scheduler class; the native W1 invocation; the route
geometry; KernelSU and its manager scanner for this pipeline; any credential write (KDP is
irrelevant — the second way never forges credentials).

**What it keeps:** DirtyFrag as the primitive (ESP page-cache write, libc++ sentry hook, init
execution, the init shell and its socket), and the per-kernel module idea already gated.

## 2. Why this shape

- **KernelSU late-load is not available on 5.4.** Late-load is a kernel-module load for the exact
  KMI; upstream supports GKI kernels and vendor 5.4 qgki is outside that set. A module can in
  principle be built (the gate shows the loader accepts one), but building a KSU port for 5.4 is a
  product, not a handoff.
- **Magisk needs no kernel module.** It is a daemon, a live policy patch, and mounts. The
  DirtyFrag chain already gives code execution inside init (uid 0, `u:r:init:s0`), which is exactly
  the context Magisk's first stage would have.
- **The module is the SELinux switch.** The live policy patch (`magiskpolicy --load … --live`)
  checks `security:security load_policy`, and **no domain in the shipped star/venus policies has
  that permission** — only the class definition and a neverallow. Under Enforcing the patch is
  denied from every domain. It must run while the device is permissive, and the module
  (`selinux_state = false`) is the cheapest way to get there without W1. This is the load-bearing
  finding: the module and Magisk are not alternatives, they compose.
- **The rest fits the init domain.** On both ROMs init carries `mounton` on rootfs/tmpfs/
  system_file/vendor_file, a broad `property_service set` (so `ctl.restart` works), and
  `sys_admin`/`mknod`/`chown`; `vendor_modprobe` carries `sys_module` + `module_load` for the
  `insmod` step. The Magisk setup must run in init, only the module load drops to vendor_modprobe.
- **Evidence already in hand:** the loader accepts a module built from the exact config on both 5.4
  Images (QEMU) and on the 5.10 and 6.1 phones; the DirtyFrag chain reaches an init shell on the
  diting; the ROM preflight passes on both 5.4 packages. What is not yet measured: the primitive on
  a real 5.4 phone, and the Magisk late install itself.

## 3. Profile representation — additive, pipeline-scoped

### Constraints

1. Every existing profile (61 bundled assets, user imports, snapshots) must parse and behave
   identically.
2. `schema_version` means *phys-field shape* (`1` scalars/absent, `2` per-device maps). It is not a
   chain version and must not be repurposed.
3. The Kotlin resolver refuses unknown top-level keys and unknown pipeline values — a new document
   read by an old app must fail closed, never be misread.
4. The native wire document is written only for a W1 run. The new chain has no wire; the DirtyFrag
   stage is invoked as `--dirtyfrag <plan>` and reads no profile.
5. A profile is a *kernel-image* document. Artefacts (the module `.ko`), installed managers, and
   runtime choices do not belong in it.

### Proposal

**P1 — one new value in the existing ordered list.** `pipeline` already names the chains:
`["ghostlock"]`, `["dirtyfrag"]`, `["dirtyfrag", "ghostlock"]`. Add a third value:

```hocon
pipeline = [ "module" ]
```

It is not a repurposing: no existing value changes meaning, and the resolver's walk (first viable
entry, resolved before any stage starts) needs no new mechanism — only a third viability callback.

**P2 — requirements become pipeline-scoped, not global.** Today `route`, `task_struct`, `cred` and
`offset` are unconditionally required. A module profile needs none of them: there is no W1, no
route, no kernel-memory geometry. Validate as:

- the W1 field groups are required **iff** the list contains a W1-based entry (`ghostlock` or
  `dirtyfrag`); a list of `["module"]` may omit them entirely;
- `route`, when present, is still validated exactly as today (one branch);
- `release`, `schema_version`, `kernel_major` stay required (all three are cheap and always
  extractable);
- the KDP rule widens: `kdp = 1` now accepts the strict `["dirtyfrag"]` **or** `["module"]` — both
  avoid the credential write.

This is conditional validation, not a version bump. Every existing document satisfies the stricter
branch unchanged; only new-pipeline documents get the relaxed one.

**P3 — no new top-level keys in v1.** The module is addressed by `release`:

```
app/src/main/assets/modules/<uname -r>.ko     one per supported release
app/src/main/assets/modules/index.conf        release → sha256, vermagic, provenance line
```

The app verifies the hash, checks the vermagic matches the running kernel, stages it into
`filesDir`, and refuses the pipeline when no asset matches. Nothing about the module appears in
the profile; a profile and an asset meet by `release`, exactly as profiles and kernels do.

**P4 — the handoff is implied by the pipeline, not configured.** `module` ⇒ Magisk; the existing
values keep KernelSU. If a kernel ever supports both handoffs, add an optional `handoff` key then
— with a default that preserves today's behaviour. Do not add it speculatively.

**P5 — the extractor emits `module` only where nothing else is feasible.** Rule: when the DirtyFrag
primitive is feasible and **no** W1-based pipeline is (the 5.4 case), emit `["module"]`; otherwise
emit exactly what it emits today. Consequence: every existing store document is byte-identical and
only previously-infeasible 5.4 images gain a pipeline. Preferring the module elsewhere is a later,
measured decision.

### Compatibility matrix

| reader → / profile ↓ | current app/build | new app/build |
|---|---|---|
| existing profiles | unchanged | unchanged (strict branch of P2) |
| new `module` profile | refused: unknown pipeline (fail closed) | runs the new chain if the release has an asset |
| external import | same rules | same rules; the module pipeline also needs the release asset |

The extractor/Wire/native golden corpus must not move (P5); the resolver gains a third value and
the conditional rules; `PROFILE_SCHEMA.md`/`_ZH.md` gain a pipeline section (the `pipeline` key is
currently undocumented there) and the pipeline-scoped rules.

### Alternatives rejected

| alternative | why not |
|---|---|
| a new top-level `implementation`/`chain` key, `pipeline` untouched | two sources of truth; the resolver already walks one ordered list |
| `schema_version = 3` for the new chain | conflates phys-field shape with chain choice; forces a migration over 61 assets for no parsing gain |
| `route { module {} }` | `route` is the carrier (futex/select/multicast), not the chain; a module chain has no carrier |
| module path/CRC inside the profile | an artefact, not configuration; it rots, cannot be hashed usefully, and would put an executable pointer in a user-editable document |
| handoff as a profile key | installed managers are runtime state; the kernel image only constrains which handoffs are possible, and today that is already one-to-one with the pipeline |
| make `route` optional globally | weakens validation for every W1-based profile for no benefit |

### Open profile questions

- The name: `module` is proposed; `dfmodule` is clearer but longer. No compatibility cost until
  shipped.
- Whether `["module", "ghostlock"]` should ever be emitted (module preferred, W1 as fallback).
  Recommendation: not before the module has a measured device record; the fallback would also
  re-impose the W1 requirements under P2.
- Hand-written module profiles (operator/unsupported kernels): allowed, and fail closed at resolve
  when the release has no bundled module.

## 4. Components and changes

| area | change |
|---|---|
| `profile-core` `ProfileResolver` | add `module` to `PipelineNames`; pipeline-scoped required groups (P2); KDP rule |
| `profile-core` schema docs | document `pipeline` (missing today) and the pipeline-scoped rules; EN+ZH together |
| `PipelineResolver` | third entry + viability callback (module asset, ROM preflight, Magisk source) |
| `AndroidGhostlockRepository` | module pipeline path: no W1 native run, no `nativeDocument`; still `runDirtyfragStage`; preflight refusal replaces the ksud gate for this pipeline; runtime env passes the handoff + module path to the stage |
| native runtime config / `ops.cpp` | the root script branches on `GHOSTLOCK_HANDOFF=magisk`: insmod → wait permissive → Magisk setup → restore Enforcing; the KSU block stays for existing pipelines. No new invocation mode and no wire change (the stage is already `--dirtyfrag <plan>`) |
| module bundle | per-release `.ko` build (exact config, vendor clang, Image CRCs), `index.conf` manifest, hash/vermagic verification, app-side asset loader |
| Magisk staging | extend the manager scanner to locate an installed Magisk manager and stage its binaries, as the ksud scanner does today; refuse with a clear reason when none is installed |
| extractor | P5 emission rule; no corpus change; schema/option docs |
| app UI | pipeline display for `module`; the module/Magisk readiness on the support card; no new profile editor fields |

## 5. Gates

**G1 — static (host).** Validation/resolver/schema tests, including the fail-closed matrix above;
corpus byte-identity (`options_ondevice`/`route_verdicts_ondevice`/`cut_ondevice`); the ship
script's module-asset check; the ROM preflight extension (libc++ cave, `insmod`/`vendor_modprobe`,
Magisk source) on both 5.4 packages. The policy checks in section 2 are already done.

**G2a — diting, Magisk only.** Run the *existing* DirtyFrag pipeline (W1 is device-proven there)
with the runtime handoff switched to Magisk. Isolates the new part: policy patch in the permissive
window, daemon, su, re-enforce, daemon survival, app recovery. Requires a test build only.

**G2b — diting, module replaces W1.** Build the real module for `5.10.236`, add it to a test
bundle, run a `pipeline = ["module"]` test profile. This is the new chain end to end on a phone
with known-good parts.

**G3 — 5.4 device (star/venus).** The real gate: primitive + module + Magisk on the target line.
Pass = `su` works from an external shell and the manager sees root, Enforcing is restored,
magiskd is gone after reboot, no bootloop, run store completed.

No stage may fall back after the stub has run: a failure is restore-and-report (W1-style
discipline).

## 6. Failure and restore semantics

- The permissive window is bounded by the module load and the policy patch; the script restores
  Enforcing on every exit path, the written pages are restored regardless, and a failure is
  `RESULT=FAIL` — never a silent permissive device.
- A module that fails to load leaves the device as it was; the chain reports and reboots clean.
- If the Magisk setup fails after the policy patch, the device is still enforcing afterwards; the
  run store records the failure and the manager is simply not functional.
- The module has no persisted state; a reboot removes everything (temporary root, as today).

## 7. Risks and open questions

- **Per-kernel module farm.** Every supported release needs a built, hashed module. The build is
  offline and reproducible (exact config + vendor clang + Image CRCs); the risk is process, not
  technology. Decide which 5.4 releases are supported and who builds them.
- **Magisk binary sourcing and drift.** Using the user's installed manager avoids redistribution
  (Magisk is GPLv3) but couples us to its binary layout and version. Probe and pin versions; fail
  closed on an unexpected layout.
- **Zygote restart.** The example restarts zygote; it kills the app mid-run and may not be needed
  for `su`. Test with and without before adopting it.
- **su exposure on Android 13/14.** `/system/xbin` does not exist on either ROM; the example's xbin
  bind is legacy. The modern exposure (Magisk's own mount mechanism or the manager's path) must be
  designed and tested.
- **Vendor policy differences.** `magiskpolicy` patches the precompiled vendor policy; the rules
  Magisk expects must still apply after the patch. Verify by checking magiskd and `su` under
  Enforcing, not by the patch's exit code.
- **Primitive on real 5.4 hardware** is still unmeasured (the probe exists; the 5.4 unit is G3).
- **App UX**: the new pipeline changes the preflight refusal (Magisk missing instead of ksud
  missing), the pipeline label, and the post-run state (a working manager, not KSU).

## 8. Non-goals

- Replacing `ghostlock`/`dirtyfrag` on the kernels where they already work; changing KSU/Shizuku;
  persistent root; attestation bypass; bundling Magisk itself.
