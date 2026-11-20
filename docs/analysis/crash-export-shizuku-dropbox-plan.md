# Crash export: make the exporter actually use Shizuku, and read the kernel log where it exists

**Date:** 2026-11-21. **Scope:** app-only (Kotlin + AIDL), no native, no wire/profile change.

## Problem

The automatic crash capture has a Shizuku path (`collectCrashEvidence` →
`ShizukuExploitRunner.collectCrash` → `GhostlockUserService.readBootFacts`/`openPstore`), but:

1. **It never asks.** `readCrashFacts` uses Shizuku only when `status()` is already `READY`;
   `status()` demands a shell-run server, a granted permission and a live binder. A
   `PERMISSION_REQUIRED` or `NOT_RUNNING` state falls silently to the `su` path and then to a bare
   "no shell available" capture. The export sheet's retry was additionally gated on the *Shizuku
   mode* being enabled, so even a ready server was ignored when the user runs the direct entry.
2. **It reads the one thing shell cannot.** `openPstore` opens `/sys/fs/pstore/<name>` directly;
   those nodes are root-only on Android, so a shell-run server always fails. A denied open and an
   absent file were both recorded as `missing`, which is why a report could not say whether pstore
   was empty or merely unreadable.
3. **The shell-readable channel was not used at all.** Android's `system_server` reads pstore at
   boot and stores the previous kernel console as the DropBox entry `SYSTEM_LAST_KMSG`. `shell`
   may read DropBox. That is exactly the no-root kernel-log channel the venus report needs.

## Change

- **AIDL `IGhostlockUserService` + method 5** `writeDropboxLastKmsg(ParcelFileDescriptor): long`:
  the service probes the `dumpsys dropbox` listing for the tagged entry and streams
  `dumpsys dropbox --print SYSTEM_LAST_KMSG` into the app's file. Returns the byte count, `0` for
  an empty entry and `-1` when the tag is absent. Bounded at 4 MiB (a console-ramoops entry is a
  few hundred KiB).
- **`readBootFacts()`** gains one `pstore.<name>=exists:<size> | denied | missing` fact per
  allowlisted node (`Os.stat` + `ErrnoException`). The app keeps that fact unless a root-run
  server can actually open the node.
- **`ShizukuExploitRunner`**: `crashAccessStatus()` accepts a **shell- or root-run** server for the
  crash collector (the run entry's `status()` is unchanged); `awaitCrashAccess()` waits out the
  permission dialog; `collectCrash` writes `kernel-log.txt` from DropBox and records
  `dropbox.last_kmsg=ok:<bytes>|empty|absent|failed`.
- **Export flow** (`onExportRunLog`): when the stored evidence has no kernel log, it calls
  `collectCrashEvidenceWithShizuku()`, which requests the permission from this user-initiated
  path and waits up to 20 s. If Shizuku is installed but not running, a toast says so instead of
  exporting silently. The start-up capture stays silent (no prompts from a background start).
- **Root path** (`RootCrashCollector` script): same DropBox capture into `kernel-log.txt`, plus a
  `pstore-listing.txt` (`ls -l /sys/fs/pstore`) so a rooted device records what pstore holds.

`kernel-log.txt` is already the name `CrashEvidenceStore` treats as kernel evidence and
`RunLogStore.bundleZip` already ships every file of a capture, so the bundle carries it without
further changes.

## Tests and gates

- New `DropboxKmsgTest` pins the entry-line matcher: a real listing line matches, the `Searching
  for:` header does not, and a prefixed tag (`SYSTEM_LAST_KMSG_OLD`) does not.
- App unit suite and `:app:assembleDebug`.
- Device: the diting (root, no Shizuku installed) must produce a capture with the new
  `pstore.*` facts and `dropbox.last_kmsg=absent` and no new prompt; the shell-side commands are
  verified with `adb shell` (same uid as Shizuku): DropBox listing readable, pstore `denied`.
  A full Shizuku-run test needs the Shizuku manager installed (none of the rigs has it); the
  permission-request path is covered by the code path above and by the toast/fallback on a device
  without it.

## Risks

- `dumpsys dropbox` output size: capped; the listing read is bounded at 2 MiB.
- A root-run Shizuku now qualifies for the *crash* path only; `status()` for the exploit entry is
  deliberately untouched, so the run environment (shell) does not change silently.
- The permission prompt on export is user-initiated; a denied dialog falls through to the existing
  choice flow after the 20 s bound.
