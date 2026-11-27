/* Host fixed-vector test for the vr.ko tri-state parser. The UI's On/Off is
 * final; only an explicit value leaves Auto, and the old boolean spellings stay
 * understandable so an unreleased build's environment cannot flip a default. */

#include "../session/runtime_config.h"

#include <cassert>
#include <cstdio>

#include <string>

using namespace ghostlock;

int32_t main(void) {
    using config::VrKoMode;

    assert(config::parse_vr_ko_mode(nullptr) == VrKoMode::Auto);
    assert(config::parse_vr_ko_mode("") == VrKoMode::Auto);
    assert(config::parse_vr_ko_mode("auto") == VrKoMode::Auto);
    assert(config::parse_vr_ko_mode("garbage") == VrKoMode::Auto);

    assert(config::parse_vr_ko_mode("on") == VrKoMode::On);
    assert(config::parse_vr_ko_mode("1") == VrKoMode::On);
    assert(config::parse_vr_ko_mode("true") == VrKoMode::On);

    assert(config::parse_vr_ko_mode("off") == VrKoMode::Off);
    assert(config::parse_vr_ko_mode("0") == VrKoMode::Off);
    assert(config::parse_vr_ko_mode("false") == VrKoMode::Off);

    assert(std::string(config::vr_ko_mode_name(VrKoMode::Auto)) == "auto");
    assert(std::string(config::vr_ko_mode_name(VrKoMode::On)) == "on");
    assert(std::string(config::vr_ko_mode_name(VrKoMode::Off)) == "off");

    /* GHOSTLOCK_HANDOFF_TARGETS: the wire entries, in order; empty lines and a
     * trailing newline are harmless. */
    const auto targets = config::split_handoff_targets(
        "ksu|com.resukisu.resukisu|/x/ksu|android12-5.10\n"
        "magisk|com.topjohnwu.magisk|/x/magiskbin|\n");
    assert(targets.size() == 2);
    assert(targets[0] == "ksu|com.resukisu.resukisu|/x/ksu|android12-5.10");
    assert(targets[1] == "magisk|com.topjohnwu.magisk|/x/magiskbin|");
    assert(config::split_handoff_targets("").empty());
    assert(config::split_handoff_targets("\n\n").empty());
    assert(config::split_handoff_targets("a\n\nb").size() == 2);

    puts("runtime_config_test: ok");
    return 0;
}
