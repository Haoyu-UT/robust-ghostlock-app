# The shared root-manager handoff core.
#
# Written verbatim into the root script by the native that owns it (ops.cpp's
# synthetic root script, the timer tool's stage script). Its parameters come
# from the sidecar beside the script (`$0.conf`), never from the environment:
# the DirtyFrag init shell does not inherit the app's environment, and the
# timer's fake-cred child has a fixed envp[].
#
# The wrapper may only add a prologue and epilogue around this core. It calls
# `handoff_run` once the permissive window is open, and may override the two
# hooks (defined as no-ops below) for route-specific work:
#
#   handoff_pre_attempt      once, before the first attempt (ops: the dump)
#   handoff_before_restore   after the last attempt, before enforcing returns
#                            (ops: unload the switch module)
#
# The attempt list is TARGETS, one wire entry per line, in order — each
# `kind|package|source|kmi` (the same form the app sends over AIDL; the
# receiving site has already staged `source` for this uid).

HANDOFF_PARAMS="$0.conf"
if [ -r "$HANDOFF_PARAMS" ]; then
  . "$HANDOFF_PARAMS"
fi
LOG="${LOG:-}"
SAFE_MODE="${SAFE_MODE:-0}"
VRKO_SELINUX_BLOCK="${VRKO_SELINUX_BLOCK:-auto}"
MODULE_PATH="${MODULE_PATH:-}"
MODULE_KO="${MODULE_KO:-}"
TARGETS="${TARGETS:-}"
# The directory a manager copy is exec'd from. Both OPPO guard generations act
# on the exec path and only /data/local/tmp is exempt in both, so a knob here
# exists for the host harness, not for production.
HANDOFF_EXEC_DIR="${HANDOFF_EXEC_DIR:-/data/local/tmp}"

handoff_pre_attempt() { :; }
handoff_before_restore() { :; }

handoff_log() {
  echo "$*" >>"${LOG:-/dev/null}"
  return 0
}

handoff_say() {
  echo "$*"
  handoff_log "$*"
}

# One restore for every exit of the handoff phase. The DirtyFrag shell may set
# but not read the enforce node: the successful write is the fact the stage
# can use, and it goes to stdout, which survives the enforcing restore where a
# $LOG write does not.
restore_enforce() {
  h_reason="${1:-handoff}"
  [ -e /sys/fs/selinux/enforce ] || return 0
  h_before=$(cat /sys/fs/selinux/enforce 2>/dev/null)
  handoff_log "[*] enforcing restore: reason=$h_reason before=${h_before:-unreadable}"
  if echo 1 > /sys/fs/selinux/enforce 2>/dev/null; then h_wrote=1; else h_wrote=0; fi
  h_after=$(cat /sys/fs/selinux/enforce 2>/dev/null)
  echo "ENFORCE-RESTORE reason=$h_reason before=${h_before:-unreadable} wrote=$h_wrote readback=${h_after:-unreadable}"
}

# Safe mode marks every module disabled before any manager starts.
handoff_safe_mode() {
  [ "$SAFE_MODE" = "1" ] || return 0
  handoff_log "[*] safe mode: disabling all modules under /data/adb/modules"
  n=0
  for m in /data/adb/modules/*/; do
    [ -d "$m" ] || continue
    if touch "${m}disable" 2>/dev/null; then
      n=$((n+1))
      handoff_log "  disabled ${m}"
    fi
  done
  handoff_log "[*] safe mode: $n module(s) disabled"
}

ksu_ready() {
  grep -q '^kernelsu' /proc/modules 2>/dev/null
}

# Polls for the module line; $1 = tenths of a second, $2 = the counter name.
ksu_wait() {
  kw_i=0
  while [ "$kw_i" -lt "$1" ]; do
    ksu_ready && return 0
    kw_i=$((kw_i+1))
    sleep 0.1
  done
  ksu_ready
}

# KernelSU: late-load the staged ksud. When the module is already loaded (root
# from boot) there is nothing to do; the readiness check is the same fact
# either way.
#
# The exec path is the guard's input, not the caller: both OPPO generations
# act on a /data exec (the old module reports a non-root one and blocks a root
# one, the new module blocks the non-root one), and only /data/local/tmp is
# exempt in both. The app's sandbox, where `source` was staged, is under /data,
# so the manager runs (and is probed) as a copy in the exempt directory.
handoff_ksu() {
  hk_kmi="$1"
  hk_src="$2"
  if ksu_ready; then
    handoff_log "[*] kernelsu already loaded; skipping late-load"
    handoff_say "[+] KernelSU already loaded"
    return 0
  fi
  if [ ! -x "$hk_src" ]; then
    handoff_log "[!] ksud missing: $hk_src"
    handoff_say "[!] KernelSU module not loaded"
    return 4
  fi
  hk_run="$HANDOFF_EXEC_DIR/ghostlock-ksud-$$"
  mkdir -p "$HANDOFF_EXEC_DIR" 2>/dev/null
  if cp -f "$hk_src" "$hk_run" 2>/dev/null && chmod 755 "$hk_run" 2>/dev/null; then
    handoff_log "[*] ksud exec copy: $hk_run"
  else
    handoff_log "[!] cannot copy ksud to $HANDOFF_EXEC_DIR; running $hk_src in place"
    rm -f "$hk_run" 2>/dev/null
    hk_run="$hk_src"
  fi
  # The authoritative probe, after root: the app's preflight may have been
  # refused (it cannot write the exempt directory as untrusted_app, and a
  # guard kills an exec from /data/app), leaving only static tokens. Same
  # contract as the app's scanner: `late-load --help` exits 0 and names both
  # flags this script passes. A probe the guard killed answers nothing.
  hk_help=$("$hk_run" late-load --help 2>&1)
  hk_help_rc=$?
  case "$hk_help" in *--kmi*) hk_if_kmi=1 ;; *) hk_if_kmi=0 ;; esac
  case "$hk_help" in *--allow-shell*) hk_if_shell=1 ;; *) hk_if_shell=0 ;; esac
  if [ "$hk_help_rc" -ne 0 ] || [ "$hk_if_kmi" -ne 1 ] || [ "$hk_if_shell" -ne 1 ]; then
    handoff_log "[!] late-load interface missing: rc=$hk_help_rc kmi=$hk_if_kmi allow-shell=$hk_if_shell source=$hk_src"
    handoff_say "[!] KernelSU late-load interface missing"
    [ "$hk_run" = "$hk_src" ] || rm -f "$hk_run" 2>/dev/null
    return 4
  fi
  handoff_log "[*] late-load interface confirmed (rc=$hk_help_rc --kmi=$hk_if_kmi --allow-shell=$hk_if_shell)"
  # The manager's own KMI, now that it can be asked. A first line that is not
  # a KMI (an error banner, an empty answer) leaves the resolver's value.
  hk_current=$("$hk_run" boot-info current-kmi 2>/dev/null | head -n 1)
  case "$hk_current" in
    android[0-9]*-[0-9]*)
      handoff_log "[*] kmi: manager answer '$hk_current' (resolver '$hk_kmi')"
      hk_kmi="$hk_current"
      ;;
    *)
      handoff_log "[*] kmi: no manager answer ('${hk_current:-empty}'); using '$hk_kmi'"
      ;;
  esac
  handoff_log "[*] late-load kmi=$hk_kmi source=$hk_run"
  chmod 755 "$hk_run" 2>/dev/null
  "$hk_run" late-load --kmi "$hk_kmi" --allow-shell >>"${LOG:-/dev/null}" 2>&1
  hk_rc=$?
  handoff_log "[*] late-load exit=$hk_rc"
  if ! ksu_wait 20; then
    # Samsung DEFEX kills a root exec of a /data binary; the same exec under a
    # whitelisted system path is what gets through. The mount is detached
    # immediately and lmkd never re-execs.
    handoff_log '[*] late-load did not load; retrying via the /system/bin/lmkd bind mount'
    umount -l /system/bin/lmkd 2>/dev/null
    if [ -e /system/bin/lmkd ] && mount --bind "$hk_run" /system/bin/lmkd 2>>"${LOG:-/dev/null}"; then
      /system/bin/lmkd late-load --kmi "$hk_kmi" --allow-shell >>"${LOG:-/dev/null}" 2>&1
      handoff_log "[*] mounted late-load exit=$?"
      umount -l /system/bin/lmkd 2>/dev/null
    else
      handoff_log '[!] /system/bin/lmkd bind mount unavailable'
    fi
  fi
  if ksu_wait 50; then
    handoff_say "[+] KernelSU module loaded"
    hk_out=0
  else
    handoff_say "[!] KernelSU module not loaded"
    hk_out=1
  fi
  [ "$hk_run" = "$hk_src" ] || rm -f "$hk_run" 2>/dev/null
  return "$hk_out"
}

# Magisk: reproduce magiskinit by hand while the window is permissive. The
# live policy patch needs security:security load_policy, which no shipped
# domain holds; everything that needs the pre-patch permissions runs here.
handoff_magisk() {
  hm_bin="$1"
  hm_tmp="${MAGISKTMP:-/debug_ramdisk}"
  handoff_log "[*] magisk: bin=$hm_bin tmp=$hm_tmp script_module=${MODULE_PATH:-none}"
  mkdir -p "$hm_tmp" /data/adb/magisk /data/adb/modules /data/adb/post-fs-data.d /data/adb/service.d 2>>"${LOG:-/dev/null}"
  if ! grep -q " $hm_tmp " /proc/mounts 2>/dev/null; then
    mount -t tmpfs -o mode=0755 tmpfs "$hm_tmp" 2>>"${LOG:-/dev/null}" || \
      handoff_log '[!] magisk: tmpfs mount failed; using the directory'
  fi
  for f in magisk magisk32 magiskboot magiskinit magiskpolicy busybox bootctl init-ld; do
    [ -f "$hm_bin/$f" ] || continue
    cp -f "$hm_bin/$f" "$hm_tmp/$f" 2>>"${LOG:-/dev/null}"
    cp -f "$hm_bin/$f" /data/adb/magisk/$f 2>>"${LOG:-/dev/null}"
    chmod 755 "$hm_tmp/$f" /data/adb/magisk/$f 2>/dev/null
  done
  ln -sf "$hm_tmp/magisk" "$hm_tmp/su"
  ln -sf "$hm_tmp/magisk" "$hm_tmp/resetprop"
  ln -sf "$hm_tmp/magiskpolicy" "$hm_tmp/supolicy"
  mkdir -p "$hm_tmp/.magisk/device" "$hm_tmp/.magisk/worker" 2>>"${LOG:-/dev/null}"
  touch "$hm_tmp/.magisk/config" 2>/dev/null
  hm_pol=''
  for p in /vendor/etc/selinux/precompiled_sepolicy /system/etc/selinux/precompiled_sepolicy; do
    [ -f "$p" ] && { hm_pol="$p"; break; }
  done
  if [ -x "$hm_tmp/magiskpolicy" ]; then
    if [ -n "$hm_pol" ]; then
      "$hm_tmp/magiskpolicy" --load "$hm_pol" --live --magisk >>"${LOG:-/dev/null}" 2>&1
      hm_pol_rc=$?
    else
      # No monolithic precompiled policy on this ROM: patch the live policy
      # (already the fixup's repacked one).
      "$hm_tmp/magiskpolicy" --live --magisk >>"${LOG:-/dev/null}" 2>&1
      hm_pol_rc=$?
    fi
    handoff_log "MAGISK-PHASE policy rc=$hm_pol_rc source=${hm_pol:-live}"
  else
    hm_pol_rc=1
    handoff_log '[!] magisk: magiskpolicy missing'
  fi
  if [ -x "$hm_tmp/magisk" ]; then
    "$hm_tmp/magisk" --post-fs-data >>"${LOG:-/dev/null}" 2>&1
    handoff_log "MAGISK-PHASE post-fs-data rc=$?"
  fi
  hm_pid=''
  for i in $(seq 1 30); do
    hm_pid=$(pidof magiskd 2>/dev/null)
    [ -n "$hm_pid" ] && break
    sleep 0.1
  done
  handoff_log "[*] magiskd=${hm_pid:-none}"
  if [ -z "$hm_pid" ]; then return 3; fi
  [ -x "$hm_tmp/magisk" ] && "$hm_tmp/magisk" --service >>"${LOG:-/dev/null}" 2>&1
  [ -x "$hm_tmp/magisk" ] && "$hm_tmp/magisk" --boot-complete >>"${LOG:-/dev/null}" 2>&1
  echo "MAGISK-SUMMARY magiskd=$hm_pid enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null) policy=${hm_pol:-live} policy_rc=${hm_pol_rc:-na} su=$(ls -l "$hm_tmp/su" 2>/dev/null)"
  return 0
}

# Splits one wire entry into kind / package / source / kmi. `cut`, not shell
# parameter stripping: the device's sh treats a bare `|` inside a ${var%%|*}
# pattern as an empty alternative and strips the whole value. All four fields
# are always written by the app; a short line yields an empty field, which the
# attempt loop then rejects by kind or by a missing source.
handoff_parse() {
  hp_kind=$(printf '%s\n' "$1" | cut -d'|' -f1)
  hp_pkg=$(printf '%s\n' "$1" | cut -d'|' -f2)
  hp_src=$(printf '%s\n' "$1" | cut -d'|' -f3)
  hp_kmi=$(printf '%s\n' "$1" | cut -d'|' -f4)
}

# The ordered attempt loop. One readiness contract: Magisk is ready when
# magiskd is alive, KSU when `kernelsu` is in /proc/modules; anything less
# is a failure of that target, and the next target is attempted.
handoff_run() {
  handoff_safe_mode
  handoff_pre_attempt
  hr_target=''
  hr_kind=''
  hr_idx=0
  hr_attempted=0
  # A here-document, not eval: an expanded `|` is data to read, never syntax
  # (mksh reparses an eval string; the wire entries contain separators).
  while IFS= read -r hr_entry; do
    [ -n "$hr_entry" ] || continue
    handoff_parse "$hr_entry"
    hr_attempted=$((hr_attempted+1))
    hr_rc=127
    hr_ready=0
    case "$hp_kind" in
      magisk)
        if [ -n "$hp_src" ]; then
          handoff_magisk "$hp_src"
          hr_rc=$?
        fi
        if [ -n "$(pidof magiskd 2>/dev/null)" ]; then hr_ready=1; fi
        ;;
      ksu)
        if [ -n "$hp_src" ] && [ -n "$hp_kmi" ]; then
          handoff_ksu "$hp_kmi" "$hp_src"
          hr_rc=$?
        fi
        if ksu_ready; then hr_ready=1; fi
        ;;
      *)
        handoff_log "[!] handoff: unknown target kind '$hp_kind'"
        ;;
    esac
    handoff_say "handoff-attempt idx=$hr_attempted kind=$hp_kind package=$hp_pkg rc=$hr_rc ready=$hr_ready"
    if [ "$hr_ready" -eq 1 ]; then
      hr_target=$hp_kind
      hr_idx=$hr_attempted
      handoff_say "handoff-target=$hp_kind idx=$hr_idx package=$hp_pkg"
      break
    fi
  done <<HANDOFF_TARGETS
${TARGETS}
HANDOFF_TARGETS
  handoff_before_restore
  restore_enforce handoff
  if [ -n "$hr_target" ]; then
    handoff_say "HANDOFF-SUMMARY handoff=ok target=$hr_target idx=$hr_idx enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
    return 0
  fi
  handoff_say "HANDOFF-SUMMARY handoff=manager-not-ready targets=$hr_attempted enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
  return 1
}
