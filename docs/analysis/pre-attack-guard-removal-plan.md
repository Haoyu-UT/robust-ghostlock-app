# Remove the pre-attack KernelSU guard (and its `su` probe) — 2026-11-27

## The claim being tested

`Cve2026_43499Policy::run_setup` carried this guard, added 2026-10:

> "Robustness guard: running the attack where KernelSU already owns root drives the re-enforce
> path that panics the kernel at the first PI route, and the objective is already met. Bail out
> cleanly instead…"

It was implemented as `ksu_root_owned()` — `popen("su -c 'id -u'")` from the **unprivileged app
context** — and bypassed by the debug `--force-attack` flag (the Advanced "Force attack test"
toggle). Two problems: the probe tests *the app's su authorization* while the mechanism it names
is *the module's state*, and every app-uid `su` is an in-syscall privilege escalation that the
OPPO guard's harden check reports (`capa_harden`), the leading candidate for the PPJ110's
`malicious_app_try_to_root_devices` reboots.

## The test (2026-11-27, diting, build 956)

One fresh-boot run through `scripts/series-run.sh` (CAPTURE=0), record
`external/port/pre-attack-guard-2026-11-27/`:

- KernelSU resident from boot (`kernelsu` in `/proc/modules`), shell `su` works;
- the native ran **unauthorized** (`uid=10267`, `untrusted_app`) — so the guard did not fire and
  the attack ran, which is exactly the configuration the comment calls dangerous;
- pipeline `dirtyfrag` (profile order) → `--w1-only`; W1 executed the **first PI route**
  (`pselect route done calls=1 success=1 status=0 clean=1/1`), then
  `W1-only: SELinux permissive`;
- the run completed (`state=completed`), the box stayed up, no oops/panic.

The mechanism the comment names is refuted for the first PI route under a resident KernelSU. The
full chain (W2/W3) with a resident module remains untested — recorded as a residual, not a reason
to keep the guard.

## The change

1. Remove the guard block, `ksu_root_owned()` (and its host-test stub) — the app-uid `su` probe.
2. Remove the now-dead `--force-attack` path end to end: native flag + parameter through
   `orchestrator`/`pipeline`/`backend_contract`/backend, the AIDL `forceAttack` argument, the
   Shizuku runner/service, the repository pref and argv, the Advanced toggle, its strings, and
   the session actions/state. A toggle whose only effect was skipping a removed guard would lie.
3. Remove the other app-uid `su`: `RootCrashCollector` (crash capture when Shizuku is
   unavailable). It failed without an app-authorized manager and is a guard event when one is
   present; the Shizuku path remains, and the rig's captures use the host scripts.

Product-side result: no app-uid `su` invocation remains; the two guard-report sources on our path
(the pre-attack probe and the crash collector) are gone.

## Gates

- Native: zero-warning build, `lint-tidy`, all `native-host-tests`; `cmp_disasm` reviewed (this
  changes the backend's code shape — expect a count delta, and the known FAIL is the missing
  multicast control).
- App: `:app:testDebugUnitTest` + `:profile-core:test` green; AIDL changed on both sides in one
  APK, so no stale-service compatibility is assumed.
- One device run on the rig after the change (same harness, expect the same DirtyFrag result).
