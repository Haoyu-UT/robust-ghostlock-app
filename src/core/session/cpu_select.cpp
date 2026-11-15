#include "session/cpu_select.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>

#include <sched.h>

namespace ghostlock::cpu_select {
    namespace {
        long read_max_khz(int cpu) {
            char path[96];
            std::snprintf(path, sizeof(path),
                          "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
            FILE *f = std::fopen(path, "r");
            if (!f) return 0;
            char buffer[32] = {};
            const bool read = std::fgets(buffer, sizeof(buffer), f) != nullptr;
            std::fclose(f);
            return read ? std::strtol(buffer, nullptr, 10) : 0;
        }
    } // namespace

    bool select_pair(std::vector<Candidate> candidates, int *main_cpu,
                     int *consumer_cpu) {
        candidates.erase(
            std::remove_if(candidates.begin(), candidates.end(),
                           [](const Candidate &c) { return c.cpu < 0; }),
            candidates.end());
        std::sort(candidates.begin(), candidates.end(),
                  [](const Candidate &a, const Candidate &b) {
                      if (a.max_khz != b.max_khz) return a.max_khz > b.max_khz;
                      return a.cpu < b.cpu;
                  });
        if (candidates.size() < 2) return false;
        if (main_cpu) *main_cpu = candidates[0].cpu;
        if (consumer_cpu) *consumer_cpu = candidates[1].cpu;
        return true;
    }

    std::vector<Candidate> available_candidates() {
        std::vector<Candidate> out;
        cpu_set_t allowed;
        if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) return out;
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (!CPU_ISSET(static_cast<size_t>(cpu), &allowed)) continue;
            cpu_set_t one;
            CPU_ZERO(&one);
            CPU_SET(static_cast<size_t>(cpu), &one);
            /* A cpuset ceiling or an inactive CPU refuses here even though the
             * affinity mask still lists it; this probe is the only truth. */
            if (sched_setaffinity(0, sizeof(one), &one) != 0) continue;
            out.push_back({cpu, read_max_khz(cpu)});
        }
        (void) sched_setaffinity(0, sizeof(allowed), &allowed);
        return out;
    }
} // namespace ghostlock::cpu_select
