# The secureguard patch module — plan and first unit (6.6)

Status: first unit built for the OnePlus 13s (`6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k`);
the on-device attempt was interrupted by the rig's own instability — see §6. Scope here is the
**6.6 line's exec block**; the plan for the other lines is PORT-PLAN item 2.

## 1. What is patched

The new-generation guard (`oplus_secure_guard_new`, loaded on the OnePlus from
`/vendor_dlkm/lib/modules/`) registers a kretprobe on `do_execveat_common`. Its return handler,
`oplus_exe_block_ret_handler`, is where the generation's policy is applied:

- `ldr w8, [x1, #0x118]` — `regs->syscallno`
- `cmp w8, #0xdd` — 221, `execve`
- a call whose result gates the rest (`is_unlocked()`; the armed copy flips it)

It is a `GLOBAL` symbol in the module's `.symtab` at `.text+0x4` (the KCFI type hash occupies
`.text+0x0`, so the entry is the perfect patch point and the hash is preserved). Its prologue is
`paciasp; sub sp, sp, #0xd0`. `CONFIG_ARM64_BTI_KERNEL` is not set, so no `bti c` is needed at the
entry.

The patch replaces the first two instructions with `mov x0, #0; ret`. The kretprobe's return value
is ignored by kprobes; what matters is that the handler's body (the kill/report logic) never runs.
The guard module's kretprobe remains registered; it just calls a no-op.

## 2. How the address is found

The module resolves the function by **kprobe symbol name**, not from userspace:

1. `register_kprobe(&kp)` with `.symbol_name = "oplus_exe_block_ret_handler"`. kprobes resolve the
   name through kallsyms, which includes module symbols (`CONFIG_KALLSYMS_ALL=y` on this kernel).
2. `kp.addr` is the runtime address; `unregister_kprobe` restores the original instruction.
3. The prologue is read and checked (`paciasp` at the entry) before any write; a mismatch refuses
   unless the `force` module parameter is set.

This avoids `/sys/module/.../sections/.text` and `/proc/kallsyms` readers, which this ROM's `su`
domain denies (SELinux) — and it is KASLR-independent.

## 3. How the text is written

`vmap` a writable alias of the module text page, write the two instructions through the alias,
`vunmap`, then `flush_icache_range` the target range. The module text is read-only in the vmalloc
mapping (`CONFIG_STRICT_KERNEL_RWX=y`); the alias is a fresh `PAGE_KERNEL` mapping of the same
physical page. The original two words are saved and written back on module unload, so the
experiment can be A/B'd without unloading the guard.

## 4. Build inputs

- Tree: `android15-6.6` at the commit in the target's release (`29d86c5fc9dd`) — one clone per line,
  checked out at the target's commit.
- Config: the target's own `/proc/config.gz`; `CONFIG_LOCALVERSION` emptied and the full suffix
  passed as `LOCALVERSION` so the vermagic matches the running kernel.
- `CONFIG_MODULE_SIG=y` is kept (it contributes to `struct module`); `MODULE_SIG_ALL` is turned off
  (no signing key needed; `SIG_FORCE` is off on the target). `CONFIG_WERROR` off.
- The loader on this ROM does not enforce vermagic: its own vendor modules carry
  `vermagic=6.6.89-android15-8-o-4k` and load on the 6.6.66 kernel (and our byte-patched armed copy
  loaded too). The build still matches the running release.
- No per-image CRC patch: the module imports only core symbols and resolves the guard by name; with
  no `Module.symvers` its `__versions` is empty, which the loader warns about and accepts.

## 5. Fail-closed rules

| rule | on failure |
|---|---|
| the symbol resolves to a kprobe-able address | the module refuses to load |
| the entry word is `paciasp` (or `force=1`) | refuse, nothing written |
| `vmalloc_to_page` + `vmap` succeed | refuse, nothing written |
| the write reads back as patched | log only (the experiment's evidence) |

## 6. The experiment attempt (OnePlus, 2026-11-27) and what it changed

The attempt ran on the OnePlus with the root its earlier exploit session had left loaded:

- The armed guard copy loads and registers (`main init end & succeed`). **The block it produced is
  on `/data/adb/ksud` execs** — the KSU userspace/root-tooling path — not on the app's own
  `/data/app` native libraries: with the armed guard, `run-as` executing the app's own
  `libextract.so` succeeded. The earlier model ("the new generation blocks any non-root `/data`
  exec") is too broad; the observed rule is narrower.
- The device then rebooted mid-attempt. First: `kernel_panic,oopq:_fatal_exadspion` (the vendor
  ADSP path — not the guard); after re-rooting via the app the phone rebooted again with a plain
  userspace `reboot` ~4 minutes later. The second cause is not established; it happened around
  the permissive window and the armed guard's block events, so the vendor daemon's reaction is a
  candidate, not a conclusion.
- Post-panic the late-loaded KSU is gone (it was per boot) and the re-rooted KSU's `su` refused
  the armed module load (`insmod: failed to load …: Operation not permitted`), so the patch A/B
  did not run. The phone is back on its normal boot: unlocked slot `_a`, enforcing, retail guard
  inert, no KSU.

Next, in order of cost:

1. **QEMU bench.** The guard's exec path does not need the vendor userspace daemon (the block was
   proven on the exact module in QEMU T3B). Boot the exact 6.6.66 Image, load the armed guard, run
   a guest `/data` exec, then load `sgpatch.ko` and repeat. This tests the mechanism with no phone
   root and no reboot risk.
2. **Phone retry.** Re-root via the app and, in the same root window, load the armed guard and
   `sgpatch` and run the A/B. First diagnose the EPERM: load the retail (signed) module and check
   the `su` process's seccomp/context, to separate a loader policy from the cracked signature.
3. A stable root source on the phone (the operator's setup) before another attempt.

The QEMU route is not blocked by KDP/clang: the module is built from the ACK tree with the
device's config and its own vermagic, and the loader's vermagic/signature relaxations measured on
the phone are the vendor's, not QEMU's.

## 7. QEMU bench (2026-11-27, later): what it proved and where it stopped

The bench boots the phone's own kernel Image under `qemu-system-aarch64 -machine virt`, with an
initramfs that loads `oplusboot.ko` (the `verified_bootstate` provider, from the stock vendor_boot
ramdisk), then the armed guard, runs `/data` execs as root and as uid 2000, then loads `sgpatch`
and repeats them.

**Proved:**

- The module pairing loads: `oplusboot` (`verifiedbootstate=` module parameter set to `green`);
  the guard registers (`main init end & succeed`); both the stock `6.6.66` Image and the phone's
  actual custom `6.6.102-lotus` Image (`external/port/cph2723-stock-kernel-2026-10-28/boot_a-Image`)
  work. The guard's boot-state line reads the parameter (`verified_bootstate is green`).
- **A real build defect found and fixed:** the host has no `pahole`, so Kconfig dropped
  `CONFIG_DEBUG_INFO_BTF(_MODULES)` and our `struct module` put `.exit` at `0x5a8` where the
  vendor's guard has `0x5b8` — the loader's `mod_sysfs_setup` then oopsed on our module. The
  vendor `.ko`'s own `.gnu.linkonce.this_module` is the authority; the fix is `KCFLAGS=
  -DCONFIG_DEBUG_INFO_BTF_MODULES` (KCFLAGS reaches `.mod.c`; ccflags-y does not — the glcred
  lesson) and the PAHOLE version must be passed on the make command line for Kconfig even when no
  real BTF is generated. After the fix the relocations match the vendor exactly.
- **The patch mechanism works on the vendor module:** `sgpatch` resolves
  `oplus_exe_block_ret_handler` by kprobe name, sees the runtime entry is a shadow-call-stack
  store (`str x30, [x18, #0x108]`, applied by module alternatives over the file's `paciasp`),
  patches `mov x0,#0; ret` (readback `d2800000 d65f03c0`), and restores on unload.
- **The exec policy, decoded from the handler:** a path under `/data` that is not
  `/data/local/tmp`, `/data/nativetest64` (or the third exempt prefix) continues; then
  `uid == 0 → execve_block`, `uid != 0 → execve_report`. The earlier "non-root is blocked"
  reading of this generation was wrong — it is the same direction as the 5.10 module, not
  inverted.

**Where it stopped:** the handler never acted on the guest's `execve`/`execveat`, root or uid
2000, so the A/B (block → patch → no block) could not be shown. The handler reads its path from a
vendor per-task KABI area (`[current+0x5a0] → +0x418 → +0xa8`), the same area the entry handler
fills; in the guest that snapshot is not populated the way the phone's `/data` + KSU userspace
flow populates it. Candidate causes, in order: the entry handler is not running (registration /
tracepoint), the KABI snapshot offsets differ between the custom kernel and the guard's build, or
the check needs a context the guest lacks (SELinux policy, the real module set).

The tripwire settled the first candidate: a module probing `do_execveat_common`, the guard's entry
handler and its ret handler, plus an independent kretprobe on the same symbol, shows the kprobe and
our kretprobe firing on every exec — but **the guard's entry and ret handlers are never invoked**.
That holds on the stock 6.6.66 Image, the phone's custom 6.6.102-lotus Image and the version-matched
6.6.89 Image (`cph2723-stock-kernel-2026-10-28/boot_b-Image`), with the boot state green and orange.
So the blocker is inside the guard's own kretprobe setup/registration, not the kernel/module pairing
the struct-module fix addressed. The runtime object was then dumped (its address from the module's
`/sys/module/.../sections/.data` plus the object's section offset). With the tripwire's own
`struct kprobe`/`struct kretprobe` offsets as the kernel's reference (`symbol_name` 0x30,
`handler` 0x80, `entry_handler` 0x88, `maxactive` 0x90, `data_size` 0x98):

- `kp.addr` = `do_execveat_common` and `kp.symbol_name` = `"do_execveat_common"` — the probe is on
  the right symbol;
- `entry_handler` = the guard's entry handler (`+0x256c`), `maxactive` = 40, `data_size` = 336;
- **`handler` (the return handler) is NULL**, and the kprobe's `hlist` node carries a poison value —
  the kprobe is not linked/active.

So the exec kretprobe was left inert by the guard's own init in this environment: the return
handler is missing and the probe is unhashed, which is exactly the observed "no entry/ret calls".
The remaining question is where the init's handler store is skipped (a conditional setup); the
phone's copy of the object is the control.

The init's structure was then read: `oplus_harden_init` registers each probe in turn, logs a
per-probe error and (for the later ones) branches to a cleanup that unregisters every probe and
returns the error; the success line (`secure_harden has been register.`) prints only after the last
registration succeeds — and it does print in the guest. The kernel's `register_kretprobe` does not
require a `handler` (`kernel/kprobes.c:2205`), so a NULL return handler is legal and the entry
handler should still run. That makes the observed state contradictory: success logged, yet the
object's hlist is poisoned and its entry handler never fires. Next discriminator: kprobe
`register_kretprobe`/`unregister_kretprobe` themselves in the tripwire and dump the object's state
right after the guard's init, to see which call actually touches it at runtime (and whether a
cleanup path ran despite the success log).

Bench sources and consoles are in `/tmp/qemu-sg-6.6/` (ephemeral); the module source and build
script are in `tools/secureguard_patch/`.

## 8. The device run (OnePlus, 2026-11-27): permanent root, and the handler neutralised in place

**Rig first.** The phone's root had been the app's per-boot late-load, and that su refused module
loads (`Operation not permitted`). It is not a device limit: the permanent-root configuration was
rebuilt by flashing `kernelsu_patched_init_boot.img` (`a3860bb0…`) to `init_boot_a`, slot `_a`,
with `boot_a` left on the stock 6.6.66 kernel (`5b6e45fc…`) — `boot.img` here is kernel-only and
the ramdisk lives in `init_boot`. After the reboot `kernelsu` is `Live`, `uname -r` is the stock
string, and `su` runs in `u:r:su:s0` (one-time Shell grant in the manager). **sgpatch now loads
and patches through that root**; the EPERM belonged to the late-load su domain, not to the
device.

**On the device the guard behaves as the QEMU bench could not show.** The retail module is inert
on the unlocked bootloader (`detected unlocked, init do noting`); the armed copy registers fully
(`verified_bootstate is orange`, `secure_harden has been register`, `main init end & succeed`).
sgpatch resolves and patches **both** handler symbols: `oplus_exe_block_ret_handler` at
`ffffffffd44a8271e4` and `do_execveat_common_entry_handler` at `ffffffffd44a82974c`, each entry
`paciasp` → readback `d2800000 d65f03c0`. (The runtime entry is `paciasp` on the phone; the SCS
store seen in QEMU does not appear in this kernel's runtime text.)

**The A/B.** With the armed guard, every root `su` command produces one guard event on
`/data/adb/ksud` — `oplus_report_execveat_new … result execve_block` + `need kill`. With sgpatch
patching `do_execveat_common_entry_handler`, **no guard events fire** across further `su`
commands; after `rmmod sgpatch` restores the original instructions, the events return on the next
command. The entry handler is where the device's block decision is logged, which is why the A/B
targets it; the default (return-handler) target was also resolved and patched earlier in the same
boot.

Two limits on what this proves, kept explicit:

- The guard's event names `/data/adb/ksud` on every `su` (the KSU userspace the su path execs);
  our own root execs of `/data/adb/ksud-test` and `/data/adb/tbtest` ran and produced no guard
  line naming their path. The proof is at handler-execution level, not a userspace-visible exec
  denial.
- The block does not stop KSU's `su` — SukiSU serves it through a per-process ksu fd — so the
  guard's enforcement on this rig is the audit/event path, not a working exec denial. A locked
  unit (the reporter's) is where the enforcement is user-visible; that is still the shipping
  scenario.

Evidence: `external/port/secureguard-patch-device-2026-11-27/` (README, `guard-dmesg.txt`,
`ab-events.txt`, `state.txt`).

## 9. The 5.10 generation (the reporter's module), A/B in QEMU (2026-11-27)

The reporter's Reno11 Pro runs the **5.10** `oplus_security_guard` + `oplus_secure_harden` chain,
which cannot be loaded on our phones (6.6; and the diting is Qualcomm/Xiaomi while these are
OPPO/MediaTek builds). The F.10 QEMU bench — the reporter's exact `Image` and all four retail
modules — already reproduces the guard's user-visible action, so the mechanism can be tested
there. It passes:

| step | guard | child |
|---|---|---|
| root exec of `/data/app/testbin`, guard alone | `execve_block`, retval −1 | **SIGKILL** (`status=0x9`) |
| load sgpatch (resolves `oplus_exe_block_ret_handler` at `ffffffc001a41744`, `paciasp` → `d2800000 d65f03c0`) | — | — |
| the same exec, patched | none | **survives** (`0`) |
| `rmmod sgpatch` (originals restored) | — | — |
| the same exec | `execve_block` | **SIGKILL** (`0x9`) |
| a uid-2000 exec | `execve_report` | survives |

So on this generation the whole block (decision and kill) is in `oplus_exe_block_ret_handler`;
patching it is sufficient, and the non-root report path is untouched.

Build notes for 5.10 (the build script now covers them):

- Tree `android12-5.10.245_r00` with `SUBLEVEL` set to 246; the target's own IKCONFIG; the
  config's `CONFIG_TRIM_UNUSED_KSYMS` must be cleared (`UNUSED_KSYMS_WHITELIST` names a vendor
  build-host path).
- `CROSS_COMPILE=aarch64-linux-gnu-` is required — 5.10 derives clang's `--target` from it; the
  script now passes it for every line.
- Layout verified against the vendor module: `this_module` 0x400, init/cleanup relocations at
  0x190/0x3c0.
- The loader warns `no symbol version for module_layout` (empty `__versions`); the guest accepts
  it, and a stricter loader needs `MODULE_INIT_IGNORE_MODVERSIONS`.

Still not covered: the reporter's **locked** phone (loader, policy, the real daemon) and which
check caused its `malicious_app_try_to_root_devices` reboot — both remain device questions.

Evidence: `external/port/sgpatch-f10-2026-11-27/` (README, `console.txt`, `init.c`,
`hashes.txt`).

## 10. The 6.6 QEMU contradiction, resolved with root (2026-11-27)

§7's open contradiction — the guard logging success while its handlers were never invoked — was a
**measurement artifact, not a guest-versus-device difference**. The guard's handlers are called
from inside kprobe context (`pre_handler_kretprobe`), and arm64 does not run a probe's handler for
a nested breakpoint: `reenter_kprobe()` counts it as missed and single-steps
(`arch/arm64/kernel/probes/kprobes.c:227-249`, from `kprobe_breakpoint_handler` :309-330). A
kprobe on a function invoked from a kprobe handler can never fire — in QEMU or on the phone.

Measured on the device with root (kptr_restrict raised, debugfs mounted, armed guard):

- A trace with probes at `do_execveat_common`, the entry handler, the report function and the
  return handler records only `do_execveat_common`; the guard's own
  `oplus_report_execveat_new … /data/adb/ksud result execve_block` appears in dmesg in the same
  window. The message is printed inside `oplus_report_execveat_new`, whose only caller is
  `do_execveat_common_entry_handler` — the handler ran, its kprobe did not.
- The breakpoint is genuinely in the text: a reader module sees `d4200080` (brk #4) at the entry
  with the original `sub sp, sp, #0xd0` at +4.
- Probes at `+0x20` inside the entry, report and return functions also never fire.
- Control: a kprobe on KernelSU's `ksu_sys_enter_handler` fires 1,803 times in a one-command
  window — module-text probing works.
- The static `do_execveat_common_kretprobe` object (poisoned list node, `handler = NULL`) is a
  template, not the registration the kernel used; its "inert" look misled the QEMU tripwire.

So §7's tripwire verdict is withdrawn as evidence; the QEMU experiments that probed the guard's
handlers were uninformative by construction. The device A/B in §8 used the guard's own log, not a
probe, and stands.

**What actually differs in the guest** is one step further out: the handler reads its path from a
per-task snapshot (`task+0x5a0 → +0x418 → +0xa8`, then a 4 KiB copy and a `/data` prefix compare).
On the phone that area carries a path — the guard's events name only `/data/adb/ksud`, the exec
KernelSU makes — while in the minimal guest it is empty/garbage, so the guard has nothing to match
and stays silent. Leading candidate: KernelSU's exec hook (or an OPPO module absent from the guest)
populates that vendor area. This is the next thing to test, not a defect in the patch.

Evidence: `external/port/6.6-kprobe-nested-2026-11-27/` (README, `observations.txt`).

## 11. §10 corrected: the 6.6 rule is caller-based, and QEMU reproduces it (2026-11-27, later)

§10 guessed "a per-task snapshot is not populated". The device's own BTF gives the exact chain:

```
task_struct.mm        (+0x5a0)
  -> mm_struct.exe_file (+0x418)
     -> &file.f_path    (+0xa8)
        -> d_path(&f_path, buf, 4096)
```

The guard checks **the executable the exec'ing process was started from**, not the exec target.
A process whose own exe is under `/data` (outside the exempt prefixes) is flagged when it performs
any exec — which is why the phone only ever names `/data/adb/ksud` (KernelSU's daemon, exe under
`/data`, spawning execs). Writing into that chain as a "snapshot filler" corrupts kernel state:
the attempt oopsed the guest in `__mmput`. The bench instead runs a helper from `/data/adb/ksud`
and has it exec a second binary.

The guest now reproduces the guard, and the A/B is clean (`external/port/6.6-caller-guard-2026-11-27/`):

| step | guard |
|---|---|
| exec `/data/adb/ksud` from `init` (caller exe `/init`) | silent |
| that process execs `/data/local/tmp/plainbin` | `execve_block`, `need kill! (/data/adb/ksud)` |
| same, `sgpatch` default (`oplus_exe_block_ret_handler`) | **still fires** |
| same, `symbol=do_execveat_common_entry_handler` | silent |
| after unload | fires again |

Two consequences:

- **On 6.6 the neutralisation point is the entry handler.** The default target
  (`oplus_exe_block_ret_handler`) does not stop the check or its report; the entry handler does.
  §8's phone A/B patched the entry handler, so it stands. The module should patch the entry
  handler (or both) when that symbol exists.
- **The 6.6 kernel does not kill.** `execve_block` and `need kill` are logged, the process
  continues — in the guest and on the phone. The enforced action is the report to the userspace
  daemon (ColorOS), not a kernel SIGKILL. That is a generation difference from 5.10, where the
  kernel killed the exec'd process (F.10 T3B).

QEMU is now usable for the 6.6 mechanism A/B (check → patch → silent → restore), though still not
for the daemon's action, the locked bootloader, or the real module set.

Evidence: `external/port/6.6-caller-guard-2026-11-27/` (README, `console.txt`, `init.c`,
`testbin.c`, `hashes.txt`).

## 12. One default for both generations (2026-11-27, last)

`symbol=` is now a comma-separated list, defaulting to
`do_execveat_common_entry_handler,oplus_exe_block_ret_handler` (superseded 2026-11-28 by §14,
which adds `oplus_root_check_post_handler`). Every entry that resolves and
has a recognised prologue is patched; missing entries are logged and skipped; the load fails only
when nothing was patched; unload restores every site in reverse. This puts the per-generation
neutralisation point in one invocation: 6.6 needs the entry handler, 5.10/6.1 the return handler,
and each generation lacks the other's symbol.

Both benches pass with the same source (`external/port/sgpatch-multi-2026-11-27/`):

- 6.6 (stock Image, caller test): both handlers patched, the guard's `execve_block` silent under
  the patch, restored after unload.
- 5.10 (reporter's exact Image and modules): the entry symbol is absent (`not present (-2),
  skipped`), the return handler patched, the root exec survives under the patch and is killed
  again after unload; the non-root report is untouched.

The single-symbol default in §1–§3 is superseded; §7's "which handler" statement stands as the
per-generation rationale.

**Decision (2026-11-27): per-handler, in place, derived at runtime, never unload.** Neutralise
the functions the guard registered as handlers and keep the module loaded (the community's only
catastrophic failure is the unload path: the vendor anti-tamper panics the device). The shipped
procedure derives the handler targets structurally from the module itself — no observed
per-build parameters, no behavioural probing on a user device; verification is structural
(anchor, span bytes, readback). Unknown builds fail closed. The reported limits are unchanged:
tracepoint-based checks need their own rule or avoidance, and the userspace daemon is outside a
kernel patch's promise.

## 13. The 6.1 generation (the Neo7 module), A/B in QEMU (2026-11-27, last)

The third sample. The store's exact `6.1.134-android14-11-o-gd252f36df142` Image plus the retail
`oplus_secure_guard.ko` and `oplusboot.ko` (the glcred bench's guest already proves they load and
arm). Same init as §9, with `/data/x` as the target:

| step | guard | child |
|---|---|---|
| T1 root exec, unpatched | `execve_block`; `oplus_exe_block_ret_handler, retval: -1, syscall no: 221, uid: 0` | SIGKILL (`0x9`) |
| sgpatch default | entry symbol `not present (-2), skipped`; `oplus_exe_block_ret_handler at …004 patched`, readback `d2800000 d65f03c0`; 1 patched | — |
| T2 patched | silent | runs, exits 0 |
| unload | 1 restored | — |
| T3 restored | `execve_block` | SIGKILL (`0x9`) |
| T4 uid 2000 / T5 `/data/local/tmp/y` | `execve_report` / silent | survive |

No tool change was needed, so the "one default covers all sampled generations" claim now has
three benches behind it (6.1: `external/port/sgpatch-6.1-2026-11-27/`). Two facts for the record:

- The handler begins at `.text+4` (the kCFI type hash `0x4877830c` occupies `.text+0`) and its
  entry is `paciasp`; `CONFIG_ARM64_BTI_KERNEL` is off on this kernel, so the two-word patch is
  the correct form and the hash at entry−4 is preserved.
- The handler is the §11 caller-exe chain (`task->mm` `0x5a0` → `mm->exe_file` `0x348` → `d_path`)
  but called from `sys_exit`, after commit, so it names the exec **target**; and it skips when
  `is_unlocked()` is true (the guest runs `oplusboot.verifiedbootstate=green`, the locked path).

What stays open on 6.1 is not this handler. The per-syscall credential-decrease pair is
**avoided, not patched**, and the chain never trips it: DirtyFrag changes no credentials, and the
W2 / `leak_cred` plants are kept out of syscalls by the victim spin (decided 2026-11-27; the pair
exists in all three sampled modules — 6.6 retail at `+0x12d0`/`+0x1330` — but has only ever been
exercised in the 6.1 glcred bench). Residuals: the spin's premise, and a
post-root `su` — now addressed by Scope B (§14): a uid→0 change inside an ioctl, outside the set\*id
exemption, is exactly the type-0 report the post-handler patch stops.

Build note: `build.sh` refuses `external/ack-6.1.138` here (the tree carries in-tree build
artifacts), so the bench built against the prepared `/tmp/ackbuild` and its vermagic reads
`6.1.138`; the guest loader warns (`no symbol version for module_layout`) and loads, and
`struct module` matches (`this_module` 0x440, the same as `glcred.ko`). Recipe and hashes are in
the evidence README.

## 14. Scope B: the credential post-handler (2026-11-28)

The §13 residual is a requirement, not a footnote. The PPJ110's `malicious_app_try_to_root_devices`
reboot is the userspace anti-root dialog acting on a **type-0** report, and the report's source is
the credential pair: `oplus_root_check_pre/post_handler` compares uid/euid/gid/egid at
`sys_enter`/`sys_exit` and, on a numeric decrease outside the set\*id span (143–214), calls
`oplus_root_check_succ` (type 0) + `oplus_root_killed`. A KernelSU `su` is `ioctl 29` and commits
10xxx/2000→0 — exactly that decrease. QEMU on the PPJ110 Image + retail modules:

| arm | guard | result |
|---|---|---|
| NOP ioctl | on | survives |
| cred ioctl (29), unpatched | on | `oplus_root_check_succ … sys_call_number@@29 curr_uid@@0` + SIGKILL (`0x9`) |
| same, guard off | off | survives, `uid=0 euid=0` |

(`external/port/ppj110-ioctl-cred-2026-11-27/`.) Without a patch, the handoff still roots the
phone, but the user's first `su` sends the dialog/15-second reboot (`OplusExSystemService`'s
`PowerManager.reboot("Malicious app try to root devices")`; `external/port/secureguard-daemons-2026-11-27/`).

### The change

`symbol=`'s default now adds `oplus_root_check_post_handler`:

```
do_execveat_common_entry_handler,oplus_exe_block_ret_handler,oplus_root_check_post_handler
```

- Patch the **post** handler, not the pre: the pre only saves the snapshot; the post is the pair's
  only caller of the type-0 report and `oplus_root_killed` (5.10 0x3e8, 6.1 0x11f0, 6.6 0x1330).
- Prologues: 5.10 `paciasp` / SCS store, 6.1 and 6.6 `paciasp`; the existing entry check and
  two-word patch apply, and the KCFI hash at entry−4 is untouched.
- 6.6: the symbol is present, but the pair was never exercised on the unlocked sample; on a
  locked unit it may be armed. It stays in the default defensively (a no-op when the pair is not
  registered).
- An operator who wants Scope A only can still pass `symbol=do_execveat_common_entry_handler,oplus_exe_block_ret_handler`.

### Gates

- **5.10 QEMU A/B** (exact Image + retail modules, the ioctl bench): unpatched ESC → type-0 +
  SIGKILL; `sgpatch` default loaded → ESC survives with uid 0 and no `ROOTCHECK-RC` line; unload →
  the next ESC is killed again (the post handler is restored). The exec handler is patched in the
  same load and is not exercised by this arm.
- **6.6 device readback** (unlocked sample, pair inert): the default resolves and patches
  `oplus_root_check_post_handler`, readback `d2800000 d65f03c0`, restore on unload. No behavioural
  test is possible on an unlocked unit.
- The 5.10/6.1 exec A/Bs (§12, §13) are unchanged: adding a third symbol does not alter their
  readbacks.

### Policy

Scope B blinds the vendor's per-syscall credential-escalation check for every app on the device.
That is the accepted cost of handing off usable root on a locked 5.10/6.1 OEM unit; the
alternative is root that reboots on first use. The exec checks (Scope A) remain patched too.
Residuals: the 6.6 pair's liveness on a locked unit; and the userspace daemon's action on other
event classes, which this patch does not touch.

### Verified (2026-11-28)

- **5.10 QEMU** (the reporter's exact Image + all four retail modules), one guest with the ioctl
  bench: unpatched ESC → type-0 report (`sys_call_number@@29`, `curr_uid@@0`) + SIGKILL (`0x9`);
  `sgpatch` default → entry skipped, `oplus_exe_block_ret_handler` and
  `oplus_root_check_post_handler` patched (`2 handler(s) patched`); patched ESC → survives with
  `uid=0 euid=0`, no `ROOTCHECK-RC` line; unload → `2 restored`; restored ESC → the same type-0
  kill again. Console and hashes: `external/port/sgpatch-scope-b-2026-11-28/`.
- **6.6 device readback** (OnePlus 13s, 6.6.66, unlocked): the default patched all three symbols
  (`do_execveat_common_entry_handler`, `oplus_exe_block_ret_handler`,
  `oplus_root_check_post_handler`, each `readback=d2800000 d65f03c0`), `3 handler(s) patched`,
  and unload restored 3. The pair is inert on the unlocked unit, so this is not a behavioural
  test; it confirms the third symbol resolves and restores on the target.
- **6.1 QEMU** (the Neo7's exact `6.1.134-android14-11-o-gd252f36df142` Image + retail
  `oplus_secure_guard.ko` and `oplusboot.ko`, with the glcred block accommodation because the MTK
  scheduler that allocates the guard's per-task snapshot is not a guest module): the same
  three-arm A/B — unpatched ESC (ioctl 29 cred write) → type-0
  `oplus_root_check_succ, payload:…$$sys_call_number@@29…$$curr_uid@@0…` + `oplus_root_killed,
  pid = …, uid = 0`, SIGKILL (`0x9`); sgpatch default → entry skipped, exec ret +
  post patched (`2 handler(s) patched`); patched ESC → survives, `uid=0 euid=0`; unload → `2
  restored`; restored ESC → the same kill again. Console:
  `external/port/sgpatch-scope-b-2026-11-28/console-6.1.txt`.
- The 5.10/6.1 exec A/Bs are unchanged: the third symbol does not touch the exec path.
