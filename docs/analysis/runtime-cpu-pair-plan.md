# Runtime CPU pair selection and a verified helper pin (plan)

Status: operator-directed 2026-11-16 ("picking a pair is not critical — pick 2, big cores first,
available at runtime"). Level L (attack path: the route's CPU pair and the compat helper's dance).
Evidence that motivated it: `external/port/realme-pin-repro-2026-11-16/` (the same-core
reproduction on the duchamp) and `external/github_error_reports/6.1.134-.../README.md` (issue #6's
two `last_kmsg` records, both on the waiter's CPU 4 against a 4/5 profile pair).

## Problem

The pair is a compile/profile constant (4/5) pinned blindly:

- `RuntimeConfig::init()` defaults to 0/1 and only checks `sched_getaffinity` (the cpuset);
  `apply_profile()` takes the profile pair if both are in that mask. A CPU can be in the mask and
  still refuse `sched_setaffinity` (`cpu_active` on the vendor core_ctl — the diting's class,
  and the Realme's `CPU: 4` evidence), and the check cannot see that.
- The compat helper's `pin_cpu()` is `(void) sched_setaffinity(...)`: the waiter and consumer both
  keep their inherited mask when the pin fails. On the Realme that put the consumer on the
  waiter's CPU, and the scheduler's own context switch overwrote the stamped waiter on the dead
  stack frame. On the duchamp reproduction the kernel even printed
  `Overriding affinity for ... (ghostlock32) to CPUs 0-3` and the walk still crashed.

The pair itself is not critical: the route only requires the waiter and consumer on **two
distinct CPUs**, and the stamp survives when the consumer is not on the waiter's CPU.

## Design

1. **Native: choose the pair at runtime.** In `apply_profile()` (after the profile pair is read
   for the log), enumerate the process's usable CPUs: every CPU in the current `sched_getaffinity`
   mask that also accepts a `sched_setaffinity({cpu})` probe. Rank by
   `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` descending, then CPU id ascending, and
   take the first two as `main`/`consumer`. The profile pair remains the logged recommendation and
   the fallback when fewer than two CPUs are usable.
2. **Helper: verify the pins.** `pin_cpu()` returns false unless `sched_setaffinity` succeeds and
   `sched_getaffinity` shows exactly the requested CPU. The waiter and consumer check at thread
   start (and that the two CPUs differ); on failure they set a `cpu_error` flag, stop the other
   threads, and the main thread returns `status = Retryable`, `step = 34` **without running the
   dance**. The native logs the attempt and backs off; the stage fails cleanly instead of
   panicking the kernel.
3. **Residual.** A vendor affinity override that lands *after* the helper's start check is not
   preventable without root; the pair selection and the check remove the observed failure (a pin
   that never took effect) and turn the remaining race into the carrier's ordinary rate.

## Gates

- Native zero-warning build and `lint-tidy`.
- A new host test for the pure ranking (`cpu_select_test`), plus the full `native-host-tests`.
- The helper rebuilds clean.
- Device: the standalone same-core harness on the duchamp with a pair whose pins the shell cpuset
  refuses must now return `step=34` and **not** reboot; then an app-path run on the duchamp with
  the compat multicast external profile, checking the logged runtime pair and W1.
