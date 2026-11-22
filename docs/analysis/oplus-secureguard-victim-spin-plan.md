# OPlus SecureGuard — the victim-spin protocol and the dead-victim stop (plan, 2026-11-22)

Level: **L** (attack-critical path — the victim protocol and the W2 verification). Plan first, then
code, per `AGENTS.md`.

## What the 2026-11-22 run proved

The Realme Neo7 run (GitHub issue #6, sixth report; filed under
`external/github_error_reports/6.1.134-android14-11-o-gd252f36df142/`) is the first device run on
which W2's write is known to have landed on 6.1.134. It failed anyway:

- the W2 store set the victim's `cred = init_cred` while the victim was blocked in `read()`;
- the ROM's `oplus_secure_guard.ko` saw the credential change at that syscall's exit and
  `SIGKILL`ed the victim (`pid 10371`, `old_euid 10310`, `curr_euid 0`, `sys_call_number 63`);
- the verification then read EOF (attempt 1) and EPIPE (attempts 2–4) because the victim was dead;
- three further attempts ran, each writing `init_cred` at `child_task + 0x838` in a **freed**
  `task_struct`, and the next route call panicked in its own walk.

The module's exact rule (disassembly in `external/port/oplus-secureguard-2026-11-22/`):

```text
sys_enter: save uid/euid/gid/egid + syscallno on the task's kernel stack
sys_exit:  if (saved.uid == 0) return;                  // already root at entry
           if (saved.uid/euid/gid/egid > current) -> incident
           incident: syscall in {143..147,149,203,204,208,210,213,214} ? return
                     comm starts with "dumpstate" ? return
                     kevent + send_sig(SIGKILL, current, 0)
```

It is a **transition** check. A credential written while the task executes no syscall is invisible:
the next syscall enters with `uid == 0` and takes the first short-circuit.

## Goal

1. Make the W2 write land while the victim is in user space, so SecureGuard never sees the
   transition.
2. Stop the stage the moment the victim is dead, so no later attempt writes into freed memory or
   buys another route call.
3. Keep the handoff's `ksud` out of the module's exec kill path (`/data` other than
   `/data/local/tmp` and `/data/nativetest*`, for a uid-0 task).

## Slice 1 — the shared-page spin (the evasion)

Add a one-page `MAP_SHARED | MAP_ANONYMOUS` mapping before `fork()`, two aligned words:
`spin_ready` (child → parent) and `spin_go` (parent → child). No syscalls are involved after the
child reads the command; the flags are plain userspace loads/stores with acquire/release atomics.

Protocol, per W2 attempt:

```text
parent                                   child
write(cmd_pipe, 'S')                     read(cmd_pipe) -> 'S'      (syscall, pre-transition)
                                         spin_ready = 1
poll spin_ready (userspace)              pure userspace spin: while (!spin_go) ;
route (the W2 write lands here)          ...
spin_go = 1                              leaves the spin
                                         write(uid_pipe, MARK)      (first syscall after the write:
                                                                    pre-handler saves uid = 0)
read(uid_pipe, MARK)
write(cmd_pipe, 'C'); read uid            read(cmd_pipe) -> 'C'; write(uid_pipe, uid)
```

The child must not block in any syscall between `spin_ready = 1` and `spin_go = 1`. Pin the child
away from the route's `{main, consumer}` pair so a spinning core does not perturb the timing.

**The affinity is inherited, and that matters here.** `fork()` gives the child the calling thread's
mask, and the calling thread is the native's main thread, already pinned to `main_cpu` (the log's
`main thread running on cpu=N` precedes the fork). Today the child sleeps in `read()` immediately,
so this is invisible; a naive spin would put it on the **same CPU as the route's waiter**. Before
setting `spin_ready`, the child (or the parent, by pid) must:

- `sched_setaffinity` to a CPU outside `{main, consumer}` — a little core on the supported SoCs;
- lower its scheduling weight (`SCHED_IDLE` or nice 19) so anything else on that core wins;
- if no such CPU is in its mask, log (`victim spin: no off-pair cpu, spinning in place`) and
  proceed — correctness is unaffected, only isolation is lost.

All of these are syscalls made before the transition; they are invisible to the guard.


Notes:

- The marker is a fixed 4-byte value so the parent can tell "the child ran as root" from a stale
  read; the existing `'C'`/uid exchange stays unchanged after it.
- The child must re-arm the spin before *every* attempt (not only the first): a missed write that
  leaves the victim not-root is safe to re-spin, and a write that landed but failed verification
  is also safe to re-spin (the transition is already past; `saved.uid == 0`).
- The route's W2 write is the only kernel write; no protocol page is needed (the route is
  page-free on this profile). The shared page is userspace-only.

## Slice 2 — the dead-victim stop

`verify_w2_stage` must distinguish three outcomes, not two:

| outcome | signal | action |
|---|---|---|
| reply is the uid | `read() == 4` | existing check |
| write missed / child alive but silent | `read() != 4`, command sent | retry |
| **victim is gone** | `read() == 0` (EOF), or `write(cmd) != 1 && errno == EPIPE` | **fail the stage now** |

Implementation: a `victim_dead` flag in `w2_stage_context` (set by the verify), checked by
`retry_write_stage` before the next attempt (and after the final attempt). Log
`W2 verify: victim gone (EOF); not retrying`. The same classification is worth having in the
`F`/`M` verifies later in the chain.

## Slice 3 — the handoff's ksud path

`oplus_exe_block_ret_handler` `d_path`s the exec target on `execve` exit and, for `uid == 0`,
`send_sig(SIGKILL)`s the task for any `/data` path except `/data/local/tmp` and
`/data/nativetest*`. The root script's first candidate (`$HOME_DIR/ksud` =
`/data/user/0/<pkg>/files/ksud`) and the `/data/app/.../lib/arm64/libksud.so` fallback are both
killed. Change the root script on this ROM to `cp "$KSUD" /data/local/tmp/<name>` + `chmod 755` +
exec from there; keep the existing candidate order for non-OPlus ROMs, or always stage the copy
(the root script has the privileges and SELinux is permissive).

Detection is optional for the copy — doing it on every ROM costs one `cp` and removes a
device-specific branch. Log the chosen path.

## What this does not change

- No route, profile, wire or schema change. `WAITER_OFF`, the anchor, the pipeline are untouched.
- W3 and the handoff need no change: every syscall after the transition enters with `uid == 0`,
  so the root check short-circuits. (The exec hook is handled by slice 3.)

## Deployment: universal or runtime-gated

The spin is a workaround for one vendor check, and every other rig's current protocol is measured.
Two shapes:

1. **Universal** — one victim protocol on every device. One path to test and maintain; it would
   also cover any other ROM carrying the same check (the OnePlus 13s, 6.6.66, is the obvious
   candidate — its `vendor_boot` should be checked for `oplus_secure_guard.ko`, and if present its
   rule compared). Requires the regression series to prove neutrality.
2. **Runtime-gated** — spin only when the check can act: `oplus_secure_guard` in `/proc/modules`
   **and** `ro.boot.verifiedbootstate` not `orange` (the module itself returns early when
   unlocked). Every other device keeps today's code, so no measured rate can move. The cost is a
   second path, exercised almost only by the reporter.

Decision: implement the check as a decision, run the diting (select and sigreturn) and duchamp
series with the spin forced on, and drop the gate only if both reproduce their baselines.

The DirtyFrag pipeline (`--w1-only`) does not run the W2 victim, so the OnePlus 13s's DirtyFrag
flow is untouched either way.

## Gates


- `make -C src ghostlock` zero warnings, `make -C src lint-tidy`, `make -C src native-host-tests`.
- `tools/cmp_disasm.py` (attack-path change), read by hand for the known control failure.
- A host test for the `retry_write_stage` classification (EOF/EPIPE ⇒ one attempt) and for the
  spin protocol's state machine.
- Device: a non-OPlus rig (diting 5.10.236, duchamp 6.1.138) to prove the spin and the dead-victim
  stop are neutral where the guard is absent — W2 still lands on attempt 1 and the chain completes.
- Device: the reporter's Neo7 (build from slice 1+2) — the W2 verify must read the uid and the
  chain must pass W2; then slice 3 for the handoff.

## Risks and open questions

- **The hook's userspace consumer.** `oplus_root_killed` is the kernel kill; the module also
  `kevent`s every incident (and every exec2 report) to a userspace daemon. Whether that daemon
  acts on a report it receives independently is unmeasured. The first device run answers it.
- **A kevent may already have been sent** for the pre-fix run's incident; the reporter's next run
  starts from a fresh boot.
- **The spin's timing**: with the child pinned off-pair (slice 1) the route keeps its CPUs and its
  cluster; the residual is a runqueue entry on a little core for one route call. The regression
  series, not this argument, decides whether that is neutral on each rig.
- **The panic is not fully attributed.** The walk's bad `waiter->lock` is the `+0x188` family; the
  stale target (writing into a freed `task_struct`) is a plausible amplifier. Removing the retries
  removes the amplifier either way; the route's own per-call rate on this device is still
  unmeasured and should be read from a short series after slice 1+2.

## Alternatives considered

- **Block the victim in an exempt syscall** (e.g. `connect` 203, which the post-handler allows):
  fragile (needs a genuinely blocking connect) and only works for one syscall shape. The spin is
  deterministic.
- **Unload or neuter `oplus_secure_guard`**: the module's code and data are in module memory whose
  base is not known without a leak, and `delete_module` needs `CAP_SYS_MODULE`. The spin needs no
  kernel write.
- **Never give the victim uid 0** (capability-only): the handoff's `ksud` and the root script
  expect uid 0, and a crafted cred would need a kernel page the page-free route does not have.
