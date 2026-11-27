#ifndef GHOSTLOCK_RUNTIME_CONFIG_H
#define GHOSTLOCK_RUNTIME_CONFIG_H

#include "profile/model.h"


#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace ghostlock::config {
    /* User-authoritative tri-state for a vr.ko behaviour. Auto consults the
     * version/probe recommendation; On and Off are final. */
    enum class VrKoMode : uint8_t {
        Auto = 0,
        On = 1,
        Off = 2,
    };

    /* One wire entry per line, empty lines dropped; a trailing newline is
     * legal. Pure, so the split has a host fixed-vector test. */
    inline std::vector<std::string> split_handoff_targets(std::string_view value) {
        std::vector<std::string> entries;
        while (!value.empty()) {
            const size_t newline = value.find('\n');
            const std::string_view line =
                    newline == std::string_view::npos ? value : value.substr(0, newline);
            if (!line.empty()) entries.emplace_back(line);
            if (newline == std::string_view::npos) break;
            value.remove_prefix(newline + 1);
        }
        return entries;
    }

    constexpr const char *vr_ko_mode_name(VrKoMode mode) noexcept {
        switch (mode) {
            case VrKoMode::On: return "on";
            case VrKoMode::Off: return "off";
            case VrKoMode::Auto: break;
        }
        return "auto";
    }

    /* Lenient parser for GHOSTLOCK_VRKO_TAG_CLEAR /
     * GHOSTLOCK_VRKO_SELINUX_BLOCK: on/1/true and off/0/false are the explicit
     * values; anything else is Auto. */
    inline VrKoMode parse_vr_ko_mode(const char *value) noexcept {
        if (value == nullptr) return VrKoMode::Auto;
        const std::string_view v(value);
        if (v == "on" || v == "1" || v == "true") return VrKoMode::On;
        if (v == "off" || v == "0" || v == "false") return VrKoMode::Off;
        return VrKoMode::Auto;
    }

    /* One runtime snapshot captured once from the environment. Paths own their
 * storage; callers convert to c_str() only at syscall/exec boundaries so no
 * temporary string pointer can outlive the snapshot. */
    class RuntimeConfig final {
    public:
        int32_t main_cpu = 0;
        int32_t consumer_cpu = 1;
        std::string home_dir = "/data/local/tmp";
        std::string root_script_path = "/data/local/tmp/.ghostlock_root.sh";
        /* Per-run KernelSU log (U01-S14): Kotlin passes GHOSTLOCK_KSU_LOG so a
   * previous run's markers can never be misread; falls back to the legacy
   * home-dir name for CLI runs. */
        std::string ksu_log_path = "/data/local/tmp/.ghostlock_ksu.log";
        /* Kernel-log dump directory from --dump-kernel-log; empty disables the dump. */
        std::string debug_dir;
        /* The app's run folder from GHOSTLOCK_RUN_DIR (direct entry only); the
         * exporter zips every file in it, so diagnostic sidecars written here
         * travel with the run log. Empty disables the sidecars. */
        std::string run_dir;
        /* GHOSTLOCK_ANCHOR_TRUSTED: the app proved this install's data predates
 * the current boot, so a missing anchor state file means no run has happened
 * since the region was zeroed, and slot 0 is safe. Without it a missing file
 * is refused -- see select_stack_anchor_slot(). */
        bool anchor_trusted = false;

        /* GHOSTLOCK_VRKO_TAG_CLEAR: clear the vendor's per-task tags. Auto asks
         * the backend for the probe/version recommendation; On/Off are final. */
        VrKoMode vrko_tag_clear = VrKoMode::Auto;

        /* GHOSTLOCK_VRKO_SELINUX_BLOCK: block only the /proc/vrp 0/1 enforce
         * write. Auto recommends it only for 5.10.198 + the node. */
        VrKoMode vrko_selinux_block = VrKoMode::Auto;

        /* GHOSTLOCK_HANDOFF_TARGETS: the resolver's feasible managers in
         * attempt order, one `kind|package|source|kmi` wire entry per line
         * (HandoffWire). The app stages every source for this process before
         * the native starts, and the root script's shared core attempts the
         * list in this order. Empty fails closed with a named reason: the app
         * refuses the run before this point, so an empty list is a bug, not a
         * case to guess at. */
        std::vector<std::string> handoff_targets;
        /* GHOSTLOCK_MODULE: the staged per-kernel module for the module
         * pipeline; empty on the W1 pipelines (W1 already made the device
         * permissive). */
        std::string module_path;
        /* GHOSTLOCK_MODULE_KO: the staged switch module's file, read (never
         * loaded) to learn the kernel module name for the end-of-run unload.
         * Not the MODULE_PATH insmod bench; empty on the W1 pipelines, where
         * there is no switch module to unload. */
        std::string module_ko;

        RuntimeConfig() noexcept = default;

        /* Capture the process environment exactly once. */
        int32_t init();

        /* Merge the profile's recommended CPU pair (validated). */
        int32_t apply_profile(const ghostlock::profile::TargetProfile *profile);

        void log() const;
    };

    /* The process snapshot is owned by ExploitSession. Callers reach it through
 * this accessor; the public reference alias is gone (CPP12/SESSION-01). */
    RuntimeConfig &runtime_config_snapshot() noexcept;
} // namespace ghostlock::config

#endif
