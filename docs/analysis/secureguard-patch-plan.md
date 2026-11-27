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
