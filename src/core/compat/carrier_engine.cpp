/*
 * The compat carrier's run mode (AArch32 helper).
 *
 * One invocation = one attempt. The fd table is parked on a never-ready
 * descriptor before the threads start, so the waiter thread's only syscall
 * between its `FUTEX_WAIT_REQUEUE_PI` return and the walk is `_newselect`
 * itself -- the copy that must overwrite the stale rt_mutex_waiter strip. The
 * main thread drives CMP_REQUEUE_PI and the deadline; the consumer rotates
 * sched_setattr on the waiter's tid; the waiter drains and disarms its own
 * pi_blocked_on, then reports.
 *
 * This file is compiled for armv7 only. It deliberately depends on nothing
 * from the native's session/profile/attack layers.
 */

#include "compat/carrier_engine.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <netinet/in.h>
#include <pthread.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#ifndef __NR_futex_time64
#define __NR_futex_time64 422
#endif
#ifndef __NR__newselect
#define __NR__newselect 142
#endif
#ifndef __NR_timerfd_create
#define __NR_timerfd_create 353
#endif
#ifndef __NR_sched_setattr
#define __NR_sched_setattr 380
#endif
/* arm EABI numbers: the arm64 compat table aliases 294 to
 * __arm64_sys_setsockopt, which is what makes the handler's
 * is_compat_task() branch reachable from this process. */
#ifndef __NR_socket
#define __NR_socket 281
#endif
#ifndef __NR_setsockopt
#define __NR_setsockopt 294
#endif

namespace {
    using ghostlock::compat::CarrierCommand;
    using ghostlock::compat::CarrierFutexAbi;
    using ghostlock::compat::CarrierResult;

    int g_log_fd = 1;

    constexpr int kRouteOk = 0;
    constexpr int kRouteRetryable = 1;
    constexpr int kRouteDirty = 3;
    constexpr int32_t kDrainMs = 1000;
    /* The multicast mode's post-copy consumer drive: a pure userspace spin (a
     * clock read could be a syscall on some bionic paths, so the bound is a
     * counter). The select path gets its window from the blocking syscall. */
    constexpr uint64_t kDriveSpins = 200000000ULL;
    constexpr int32_t kFdCount = 320;
    constexpr int32_t kSetBytes = 0x28;
    constexpr int32_t kBackupBase = 384;
    constexpr int32_t kNeverReadyHigh = 700;
    constexpr int32_t kMaxBackups = 322;

    struct SchedAttr final {
        uint32_t size;
        uint32_t sched_policy;
        uint64_t sched_flags;
        int32_t sched_nice;
        uint32_t sched_priority;
        uint64_t sched_runtime;
        uint64_t sched_deadline;
        uint64_t sched_period;
    };

    struct FutexTs32 final {
        int32_t sec;
        int32_t nsec;
    };
    struct FutexTs64 final {
        int64_t sec;
        int64_t nsec;
    };
    union FutexTimeout final {
        FutexTs32 t32;
        FutexTs64 t64;
    };

    struct FdPlan final {
        struct Entry final {
            int32_t fd;
            int32_t backup;
        };
        Entry entries[kMaxBackups];
        int32_t count = 0;
        int32_t never_ready = -1;
    };

    struct Engine final {
        const CarrierCommand *cmd = nullptr;
        std::atomic<int> waiter_ready{0};
        std::atomic<int> owner_started{0};
        std::atomic<int> waiter_waiting{0};
        std::atomic<int> owner_chain_done{0};
        std::atomic<int> owner_stop{0};
        std::atomic<int> consumer_go{0};
        std::atomic<int> consumer_stop{0};
        std::atomic<int> consumer_calls{0};
        std::atomic<int> consumer_success{0};
        std::atomic<int> consumer_inflight{0};
        std::atomic<int> route_done{0};
        std::atomic<int> waiter_tid{0};
        /* Set when a thread's CPU pin did not take effect (cpuset ceiling or a
         * vendor affinity override). The dance is refused, not run blind. */
        std::atomic<int> cpu_error{0};
        /* The pair handshake before the dance: each thread reads its current
         * CPU, re-pins (preferred, then the highest available), reads back and
         * reports the effective CPU; the main thread requires two distinct
         * reported CPUs and may ask the consumer to re-resolve a constant
         * number of times. */
        std::atomic<int> cpu_eff[2];   /* 0 = waiter, 1 = consumer */
        std::atomic<int> cpu_ready[2];
        std::atomic<int> cpu_go{0};
        std::atomic<int> cpu_retry{0};
        uint32_t wait_futex = 0;
        uint32_t target_futex = 0;
        uint32_t chain_futex = 0;
        int32_t select_result = 0;
        int32_t select_errno = 0;
        int32_t route_result = 0;
        int32_t route_errno = 0;
        int32_t drain_ms = 0;
        int32_t step = 0;
        int32_t error_number = 0;
    };

    void log_line(const char *message) noexcept {
        const size_t length = strlen(message);
        (void) !write(g_log_fd, message, length);
        (void) !write(g_log_fd, "\n", 1);
    }

    void log_value(const char *label, long value) noexcept {
        char buffer[96];
        const int written = snprintf(buffer, sizeof(buffer), "%s %ld", label, value);
        if (written > 0) {
            (void) !write(g_log_fd, buffer, static_cast<size_t>(written));
            (void) !write(g_log_fd, "\n", 1);
        }
    }

    int64_t now_ms() noexcept {
        struct timespec ts {};
        (void) clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000 +
               static_cast<int64_t>(ts.tv_nsec) / 1000000;
    }

    void relax() noexcept {
        __asm__ volatile("yield" ::: "memory");
    }

    void sleep_poll(const CarrierCommand &command) noexcept {
        const uint32_t usec = command.state_poll_us != 0 ? command.state_poll_us : 1000;
        struct timespec ts {};
        ts.tv_sec = static_cast<time_t>(usec / 1000000U);
        ts.tv_nsec = static_cast<long>(usec % 1000000U) * 1000L;
        (void) nanosleep(&ts, nullptr);
    }

    void sleep_us(uint32_t usec) noexcept {
        struct timespec ts {};
        ts.tv_sec = static_cast<time_t>(usec / 1000000U);
        ts.tv_nsec = static_cast<long>(usec % 1000000U) * 1000L;
        (void) nanosleep(&ts, nullptr);
    }

    bool time64_abi(const CarrierCommand &command) noexcept {
        return command.futex_abi == static_cast<uint32_t>(CarrierFutexAbi::Time64);
    }

    long futex_op(const CarrierCommand &command, uint32_t *uaddr, int32_t op,
                  uint32_t val, const void *timeout, uint32_t *uaddr2,
                  uint32_t val3) noexcept {
        if (time64_abi(command)) {
            return syscall(__NR_futex_time64, uaddr, op, val, timeout, uaddr2, val3);
        }
        return syscall(__NR_futex, uaddr, op, val, timeout, uaddr2, val3);
    }

    /* FUTEX_WAIT and FUTEX_LOCK_PI take an **absolute** CLOCK_MONOTONIC
     * timeout. The native's waiter_thread computes now + route_wait_ms; a
     * "relative" value here is already in the past and the wait returns
     * immediately -- which is exactly what the first QEMU engine run did. */
    const void *absolute_timeout(const CarrierCommand &command, uint32_t ms,
                                 FutexTimeout *storage) noexcept {
        struct timespec now {};
        (void) clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t sec = static_cast<int64_t>(now.tv_sec) + static_cast<int64_t>(ms / 1000U);
        int64_t nsec = static_cast<int64_t>(now.tv_nsec) +
                       static_cast<int64_t>(ms % 1000U) * 1000000LL;
        if (nsec >= 1000000000LL) { sec++; nsec -= 1000000000LL; }
        if (time64_abi(command)) {
            storage->t64.sec = sec;
            storage->t64.nsec = nsec;
            return &storage->t64;
        }
        storage->t32.sec = static_cast<int32_t>(sec);
        storage->t32.nsec = static_cast<int32_t>(nsec);
        return &storage->t32;
    }

    /* A relative timeout for FUTEX_WAIT / FUTEX_LOCK_PI's blocking path. */
    const void *relative_timeout(const CarrierCommand &command, uint32_t ms,
                                 FutexTimeout *storage) noexcept {
        if (time64_abi(command)) {
            storage->t64.sec = static_cast<int64_t>(ms / 1000U);
            storage->t64.nsec = static_cast<int64_t>(ms % 1000U) * 1000000LL;
            return &storage->t64;
        }
        storage->t32.sec = static_cast<int32_t>(ms / 1000U);
        storage->t32.nsec = static_cast<int32_t>(ms % 1000U) * 1000000;
        return &storage->t32;
    }

    /* The native's ghost_disarm shape: an already-expired absolute timeout on
     * FUTEX_LOCK_PI forces the blocking path, whose ETIMEDOUT clears this
     * thread's pi_blocked_on. */
    const void *expired_timeout(const CarrierCommand &command,
                                FutexTimeout *storage) noexcept {
        if (time64_abi(command)) {
            storage->t64.sec = 0;
            storage->t64.nsec = 0;
            return &storage->t64;
        }
        storage->t32.sec = 0;
        storage->t32.nsec = 0;
        return &storage->t32;
    }

    /* Pin one thread and verify the kernel kept it. A cpuset ceiling or a
     * vendor affinity override can accept or silently rewrite the request;
     * sched_getaffinity is the only readback that says which CPU the thread
     * will actually run on. */
    bool pin_cpu(uint32_t cpu) noexcept {
        cpu_set_t set;
        CPU_ZERO(&set);
        CPU_SET(static_cast<int>(cpu), &set);
        if (sched_setaffinity(0, sizeof(set), &set) != 0) return false;
        cpu_set_t actual;
        CPU_ZERO(&actual);
        if (sched_getaffinity(0, sizeof(actual), &actual) != 0) return false;
        int count = 0;
        bool has_requested = false;
        for (int i = 0; i < CPU_SETSIZE; i++) {
            if (CPU_ISSET(i, &actual)) {
                count++;
                if (i == static_cast<int>(cpu)) has_requested = true;
            }
        }
        return has_requested && count == 1;
    }

    /* The available-highest fallback, kept self-contained: probe every CPU in
     * the current mask, rank by cpuinfo_max_freq (the cluster order the native
     * picker uses) and take the first that is not the other thread's CPU. */
    int fallback_cpu(int exclude, const cpu_set_t *allowed) noexcept {
        int best = -1;
        long best_khz = -1;
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (cpu == exclude || !CPU_ISSET(cpu, allowed)) continue;
            cpu_set_t one;
            CPU_ZERO(&one);
            CPU_SET(cpu, &one);
            if (sched_setaffinity(0, sizeof(one), &one) != 0) continue;
            char path[96];
            std::snprintf(path, sizeof(path),
                          "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
            long khz = 0;
            if (FILE *f = std::fopen(path, "r")) {
                char buffer[32] = {};
                if (std::fgets(buffer, sizeof(buffer), f)) khz = std::strtol(buffer, nullptr, 10);
                std::fclose(f);
            }
            if (khz > best_khz) {
                best_khz = khz;
                best = cpu;
            }
        }
        (void) sched_setaffinity(0, sizeof(*allowed), allowed);
        return best;
    }

    /* Before the dance: log the CPU the thread is on, try the preferred CPU,
     * fall back to the highest available one, verify the readback and log it.
     * Constant retries; false when no attempt satisfies the condition. */
    bool resolve_cpu(Engine *engine, int role, uint32_t preferred, int exclude,
                     const cpu_set_t *allowed, const char *name) noexcept {
        for (int attempt = 0; attempt < 4; attempt++) {
            const int before = sched_getcpu();
            const int target = attempt == 0
                                   ? static_cast<int>(preferred)
                                   : fallback_cpu(exclude, allowed);
            char line[160];
            std::snprintf(line, sizeof(line),
                          "compat-engine: %s cpu before=%d target=%d attempt=%d",
                          name, before, target, attempt);
            log_line(line);
            if (target >= 0 && target != exclude &&
                pin_cpu(static_cast<uint32_t>(target))) {
                std::snprintf(line, sizeof(line),
                              "compat-engine: %s cpu after=%d target=%d ok",
                              name, sched_getcpu(), target);
                log_line(line);
                engine->cpu_eff[role].store(target);
                return true;
            }
            std::snprintf(line, sizeof(line),
                          "compat-engine: %s cpu target=%d refused", name, target);
            log_line(line);
            usleep(1000);
        }
        return false;
    }

    int32_t open_never_ready() noexcept {
        const long tfd = syscall(__NR_timerfd_create, CLOCK_MONOTONIC, TFD_CLOEXEC);
        if (tfd >= 0) {
            const int32_t high = fcntl(static_cast<int32_t>(tfd), F_DUPFD_CLOEXEC, kNeverReadyHigh);
            (void) close(static_cast<int32_t>(tfd));
            return high;
        }
        int32_t pipe_fds[2] = {-1, -1};
        if (pipe(pipe_fds) != 0) return -1;
        const int32_t high = fcntl(pipe_fds[0], F_DUPFD_CLOEXEC, kNeverReadyHigh);
        (void) close(pipe_fds[0]);
        (void) close(pipe_fds[1]);
        return high;
    }

    /* Back up every open fd the route may touch, then park all of them on the
     * never-ready descriptor. fd 319 is included unconditionally so the fd
     * table grows past nfds without needing a bit in the bitmap. */
    int32_t setup_fds(FdPlan *plan) noexcept {
        plan->never_ready = open_never_ready();
        if (plan->never_ready < 0) return -1;
        for (int32_t fd = 0; fd < kFdCount; fd++) {
            const int32_t flags = fcntl(fd, F_GETFD);
            if (flags < 0) continue;
            if (plan->count >= kMaxBackups) return -2;
            const int32_t backup = fcntl(fd, F_DUPFD_CLOEXEC, kBackupBase);
            if (backup < 0) return -1;
            plan->entries[plan->count].fd = fd;
            plan->entries[plan->count].backup = backup;
            plan->count++;
        }
        for (int32_t fd = 0; fd < kFdCount; fd++) {
            if (dup2(plan->never_ready, fd) < 0) return -1;
        }
        return 0;
    }

    bool restore_fds(FdPlan *plan) noexcept {
        bool ok = true;
        for (int32_t i = plan->count - 1; i >= 0; i--) {
            const FdPlan::Entry &entry = plan->entries[i];
            if (dup2(entry.backup, entry.fd) < 0) ok = false;
            (void) close(entry.backup);
        }
        plan->count = 0;
        if (plan->never_ready >= 0) {
            (void) close(plan->never_ready);
            plan->never_ready = -1;
        }
        return ok;
    }

    int32_t wait_consumer_idle(Engine *engine, int32_t deadline_ms) noexcept {
        engine->consumer_go.store(0);
        const int64_t started = now_ms();
        for (;;) {
            if (engine->consumer_inflight.load() == 0) {
                return static_cast<int32_t>(now_ms() - started);
            }
            if (now_ms() - started >= deadline_ms) return -1;
            relax();
        }
    }

    void *waiter_main(void *arg) {
        auto *engine = static_cast<Engine *>(arg);
        const CarrierCommand &command = *engine->cmd;
        cpu_set_t allowed;
        if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
            engine->cpu_error.store(1);
            return nullptr;
        }
        if (!resolve_cpu(engine, 0, command.waiter_cpu, -1, &allowed, "waiter")) {
            engine->cpu_error.store(1);
            engine->consumer_stop.store(1);
            engine->owner_stop.store(1);
            return nullptr;
        }
        engine->cpu_ready[0].store(1);
        while (!engine->cpu_go.load()) {
            if (engine->cpu_error.load() != 0) return nullptr;
            relax();
        }
        engine->waiter_tid.store(static_cast<int32_t>(syscall(__NR_gettid)));
        log_line("compat-engine: waiter start");
        if (futex_op(command, &engine->chain_futex, FUTEX_LOCK_PI, 0, nullptr,
                     nullptr, 0) != 0) {
            engine->error_number = errno;
        }
        engine->waiter_ready.store(1);
        while (!engine->owner_started.load() && !engine->owner_stop.load()) {
            sleep_poll(command);
        }
        if (engine->owner_stop.load()) {
            (void) futex_op(command, &engine->chain_futex, FUTEX_UNLOCK_PI, 0,
                            nullptr, nullptr, 0);
            return nullptr;
        }
        FutexTimeout timeout {};
        const void *wait_timeout =
            absolute_timeout(command, command.route_wait_ms, &timeout);
        engine->waiter_waiting.store(1);
        log_line("compat-engine: waiter parked");
        errno = 0;
        const long wait_ret = futex_op(command, &engine->wait_futex,
                                       FUTEX_WAIT_REQUEUE_PI, 0, wait_timeout,
                                       &engine->target_futex, 0);
        const int32_t wait_errno = errno;
        int32_t route_fd = -1;
        if (command.mode == static_cast<uint32_t>(ghostlock::compat::CarrierMode::Multicast)) {
            /* The compat MCAST_BLOCK_SOURCE copy: the waiter's only syscall
             * between the futex return and the consumer's walk. The kernel's
             * is_compat_task() branch copies command.stamp over the stale
             * waiter; the walk is triggered after the copy, as the 64-bit
             * multicast route does. The diagnostics are deferred as well: the
             * timed-out futex leaves the kernel's pi_blocked_on pointing at the
             * waiter on this stack, so even a log write in this window is a
             * reuse the walk can see (2026-11-12 the device's deaths clustered
             * on these lines). They are logged after the drain. */
            errno = 0;
            route_fd = static_cast<int32_t>(
                syscall(__NR_socket, AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
            if (route_fd >= 0) {
                /* Exactly before the dangerous part: re-read, re-pin and verify.
                 * The stamp is placed by the setsockopt below, so a syscall
                 * here cannot clobber anything. */
                const int pre_cpu = sched_getcpu();
                log_value("compat-engine: waiter pre-copy cpu", pre_cpu);
                if (!pin_cpu(static_cast<uint32_t>(engine->cpu_eff[0].load()))) {
                    engine->cpu_error.store(1);
                    engine->consumer_stop.store(1);
                    engine->owner_stop.store(1);
                    log_line("compat-engine: waiter cpu changed before the copy; refusing");
                    return nullptr;
                }
                errno = 0;
                const long set = syscall(
                    __NR_setsockopt, route_fd, IPPROTO_IP,
                    static_cast<int>(command.option), command.stamp,
                    static_cast<unsigned int>(command.stamp_bytes));
                engine->route_result = static_cast<int32_t>(set);
                engine->route_errno = set == 0 ? 0 : errno;
                /* No syscall between the copy and the walk -- not even a log
                 * write or close(fd): every syscall reuses this stack and
                 * overwrites the stamped strip. The trigger and the wait are
                 * userspace only (vDSO clock, yield). */
                engine->consumer_go.store(1);
                for (uint64_t spin = 0;
                     spin < kDriveSpins && engine->consumer_calls.load() == 0 &&
                     engine->cpu_error.load() == 0;
                     spin++) {
                    relax();
                }
            } else {
                engine->route_result = -1;
                engine->route_errno = errno;
            }
        } else {
            log_value("compat-engine: waiter futex ret", wait_ret);
            log_value("compat-engine: waiter futex errno", wait_errno);
            log_line("compat-engine: waiter woke; entering _newselect");
            engine->consumer_go.store(1);
            struct timeval tv {};
            tv.tv_sec = static_cast<time_t>(command.select_timeout_us / 1000000U);
            tv.tv_usec = static_cast<suseconds_t>(command.select_timeout_us % 1000000U);
            errno = 0;
            const long selected = syscall(
                __NR__newselect, static_cast<int>(command.nfds),
                const_cast<uint8_t *>(command.bitmap),
                const_cast<uint8_t *>(command.bitmap) + kSetBytes,
                const_cast<uint8_t *>(command.bitmap) + 2 * kSetBytes, &tv);
            engine->select_result = static_cast<int32_t>(selected);
            engine->select_errno = errno;
            log_value("compat-engine: _newselect ret", selected);
        }
        engine->drain_ms = wait_consumer_idle(engine, kDrainMs);
        if (command.mode == static_cast<uint32_t>(ghostlock::compat::CarrierMode::Multicast)) {
            /* The walk is over; the waiter may syscall again. These are the
             * multicast path's deferred diagnostics (see the note before the
             * socket call). */
            log_value("compat-engine: waiter futex ret", wait_ret);
            log_value("compat-engine: waiter futex errno", wait_errno);
            log_line("compat-engine: waiter woke; entering the route park");
            log_value("compat-engine: setsockopt ret", engine->route_result);
            log_value("compat-engine: setsockopt errno", engine->route_errno);
            if (route_fd >= 0) (void) close(route_fd);
        }
        log_value("compat-engine: drain ms", engine->drain_ms);
        FutexTimeout expired {};
        const uint32_t dummy = 0x80000000U | static_cast<uint32_t>(getpid());
        uint32_t dummy_word = dummy;
        (void) futex_op(command, &dummy_word, FUTEX_LOCK_PI, 0,
                        expired_timeout(command, &expired), nullptr, 0);
        engine->route_done.store(1);
        (void) futex_op(command, &engine->chain_futex, FUTEX_UNLOCK_PI, 0,
                        nullptr, nullptr, 0);
        while (!engine->owner_chain_done.load() && !engine->owner_stop.load()) {
            sleep_poll(command);
        }
        return nullptr;
    }

    void *owner_main(void *arg) {
        auto *engine = static_cast<Engine *>(arg);
        const CarrierCommand &command = *engine->cmd;
        errno = 0;
        const long locked = futex_op(command, &engine->target_futex,
                                     FUTEX_LOCK_PI, 0, nullptr, nullptr, 0);
        log_value("compat-engine: owner target lock ret", locked);
        if (locked != 0) {
            log_value("compat-engine: owner target lock errno", errno);
        }
        while (!engine->waiter_ready.load() && !engine->owner_stop.load()) {
            sleep_poll(command);
        }
        if (engine->owner_stop.load()) {
            if (locked == 0) {
                (void) futex_op(command, &engine->target_futex,
                                FUTEX_UNLOCK_PI, 0, nullptr, nullptr, 0);
            }
            return nullptr;
        }
        engine->owner_started.store(1);
        (void) futex_op(command, &engine->chain_futex, FUTEX_LOCK_PI, 0,
                        nullptr, nullptr, 0);
        engine->owner_chain_done.store(1);
        while (!engine->owner_stop.load()) sleep_poll(command);
        if (locked == 0) {
            (void) futex_op(command, &engine->target_futex, FUTEX_UNLOCK_PI, 0,
                            nullptr, nullptr, 0);
        }
        return nullptr;
    }

    void *consumer_main(void *arg) {
        auto *engine = static_cast<Engine *>(arg);
        const CarrierCommand &command = *engine->cmd;
        cpu_set_t allowed;
        if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
            engine->cpu_error.store(1);
            return nullptr;
        }
        /* Serialize on the waiter (the stamp holder): its effective CPU is the
         * one to avoid. Userspace spin only -- a lock here would be a futex
         * syscall on the very stacks the dance depends on. */
        const int64_t wait_deadline = now_ms() + 2000;
        while (engine->cpu_eff[0].load() < 0) {
            if (engine->cpu_error.load() != 0) return nullptr;
            if (now_ms() >= wait_deadline) {
                engine->cpu_error.store(1);
                engine->consumer_stop.store(1);
                engine->owner_stop.store(1);
                log_line("compat-engine: consumer timed out waiting for the waiter cpu");
                return nullptr;
            }
            relax();
        }
        int seen_retry = engine->cpu_retry.load();
        for (;;) {
            const int exclude = engine->cpu_eff[0].load();
            if (!resolve_cpu(engine, 1, command.consumer_cpu, exclude, &allowed, "consumer")) {
                engine->cpu_error.store(1);
                engine->consumer_stop.store(1);
                engine->owner_stop.store(1);
                return nullptr;
            }
            engine->cpu_ready[1].store(1);
            while (!engine->cpu_go.load() && engine->cpu_retry.load() == seen_retry) {
                if (engine->cpu_error.load() != 0) return nullptr;
                relax();
            }
            if (engine->cpu_go.load()) break;
            seen_retry = engine->cpu_retry.load();
        }
        int32_t seen = 0;
        while (!engine->consumer_stop.load()) {
            const int32_t sequence = engine->consumer_go.load();
            if (sequence == 0 || sequence == seen) {
                relax();
                continue;
            }
            seen = sequence;
            int32_t calls_this_sequence = 0;
            while (!engine->consumer_stop.load() &&
                   engine->consumer_go.load() == sequence) {
                if (command.delay_us != 0) sleep_us(command.delay_us);
                for (uint32_t burst = 0;
                     burst < command.consumer_burst_calls; burst++) {
                    if (engine->consumer_stop.load() ||
                        engine->consumer_go.load() != sequence) {
                        break;
                    }
                    /* inflight BEFORE calls: the waiter's drain spins on
                     * consumer_calls and then waits for consumer_inflight;
                     * calls-first let it see a started call before inflight
                     * was set, run close(fd) (a syscall on the waiter's
                     * stack) and clobber the stamp under the walk. */
                    engine->consumer_inflight.store(1);
                    engine->consumer_calls.fetch_add(1);
                    const int walk_cpu = sched_getcpu();
                    if (walk_cpu != engine->cpu_eff[1].load() ||
                        !pin_cpu(static_cast<uint32_t>(engine->cpu_eff[1].load()))) {
                        log_value("compat-engine: consumer cpu changed before the walk", walk_cpu);
                        engine->cpu_error.store(1);
                        engine->consumer_stop.store(1);
                        break;
                    }
                    SchedAttr attr {};
                    attr.size = sizeof(attr);
                    attr.sched_policy = 3; /* SCHED_BATCH */
                    attr.sched_nice = (calls_this_sequence % 19) + 1;
                    errno = 0;
                    long ret = syscall(__NR_sched_setattr,
                                       engine->waiter_tid.load(), &attr, 0);
                    if (ret != 0 && !engine->consumer_stop.load() &&
                        engine->consumer_go.load() == sequence) {
                        FutexTimeout fallback {};
                        const void *fallback_timeout =
                            relative_timeout(command, 50, &fallback);
                        const long fret = futex_op(
                            command, &engine->target_futex, FUTEX_LOCK_PI, 0,
                            fallback_timeout, nullptr, 0);
                        if (fret == 0) {
                            (void) futex_op(command, &engine->target_futex,
                                            FUTEX_UNLOCK_PI, 0, nullptr, nullptr, 0);
                            ret = 0;
                        }
                    }
                    if (ret == 0) engine->consumer_success.fetch_add(1);
                    engine->consumer_inflight.store(0);
                    calls_this_sequence++;
                    if (static_cast<uint32_t>(calls_this_sequence) >=
                        command.consumer_max_calls) {
                        engine->consumer_go.store(0);
                        break;
                    }
                }
            }
        }
        return nullptr;
    }
} // namespace

namespace ghostlock::compat {
    void set_log_fd(int fd) {
        if (fd >= 0) g_log_fd = fd;
    }

    void run_carrier_attempt(const CarrierCommand &command, CarrierResult *result) {
        Engine engine;
        engine.cmd = &command;
        engine.cpu_eff[0].store(-1);
        engine.cpu_eff[1].store(-1);
        result->status = static_cast<uint32_t>(kRouteRetryable);

        FdPlan plan;
        const int32_t fd_setup = setup_fds(&plan);
        if (fd_setup != 0) {
            result->status = static_cast<uint32_t>(kRouteDirty);
            result->step = 40;
            result->error_number = fd_setup;
            result->kernel_disarmed = 0;
            log_line("compat-engine: fd setup failed");
            return;
        }
        log_line("compat-engine: fds parked; starting race");

        pthread_t waiter_thread {};
        pthread_t owner_thread {};
        pthread_t consumer_thread {};
        int32_t thread_error = pthread_create(&consumer_thread, nullptr, consumer_main, &engine);
        if (thread_error == 0) {
            thread_error = pthread_create(&owner_thread, nullptr, owner_main, &engine);
        }
        if (thread_error == 0) {
            thread_error = pthread_create(&waiter_thread, nullptr, waiter_main, &engine);
        }
        if (thread_error != 0) {
            result->status = static_cast<uint32_t>(kRouteDirty);
            result->step = 41;
            result->error_number = thread_error;
            engine.consumer_stop.store(1);
            engine.owner_stop.store(1);
            (void) restore_fds(&plan);
            log_line("compat-engine: thread create failed");
            return;
        }

        /* CPU pair handshake, before anything dangerous: both threads log the
         * CPU they were on, pin (preferred, then available-highest), read back
         * and report. The pair must be two distinct CPUs; the consumer may be
         * asked to re-resolve a constant number of times. */
        bool pair_error = false;
        int waiter_cpu = -1;
        int consumer_cpu = -1;
        for (int round = 0; round < 4; round++) {
            const int64_t pair_deadline = now_ms() + 2000;
            while ((engine.cpu_ready[0].load() == 0 || engine.cpu_ready[1].load() == 0) &&
                   now_ms() < pair_deadline && engine.cpu_error.load() == 0) {
                sleep_poll(command);
            }
            waiter_cpu = engine.cpu_eff[0].load();
            consumer_cpu = engine.cpu_eff[1].load();
            if (engine.cpu_error.load() != 0 || engine.cpu_ready[0].load() == 0 ||
                engine.cpu_ready[1].load() == 0 || waiter_cpu < 0 || consumer_cpu < 0) {
                pair_error = true;
                break;
            }
            if (waiter_cpu != consumer_cpu) break;
            if (round == 3) {
                pair_error = true;
                break;
            }
            log_line("compat-engine: cpu pair equal; re-resolving the consumer");
            engine.cpu_ready[1].store(0);
            engine.cpu_retry.fetch_add(1);
        }
        if (pair_error) {
            engine.cpu_error.store(1);
            engine.consumer_stop.store(1);
            engine.owner_stop.store(1);
            result->status = static_cast<uint32_t>(kRouteRetryable);
            result->step = 34;
            result->error_number = EINVAL;
            result->kernel_disarmed = 0;
            log_line("compat-engine: cpu pair never satisfied; refusing the dance");
            return;
        }
        log_value("compat-engine: waiter effective cpu", waiter_cpu);
        log_value("compat-engine: consumer effective cpu", consumer_cpu);
        engine.cpu_go.store(1);

        const int64_t deadline = now_ms() + static_cast<int64_t>(command.route_done_timeout_ms);
        bool dirty = false;
        while (!engine.waiter_waiting.load() || !engine.owner_started.load()) {
            if (engine.cpu_error.load()) break;
            if (now_ms() >= deadline) {
                dirty = true;
                break;
            }
            sleep_poll(command);
        }
        if (engine.cpu_error.load()) {
            /* One of the two CPUs did not take the pin. Running the dance on
             * the waiter's CPU is the first-call strip clobber (2026-11-16
             * reproduction); refuse cleanly and let the native back off. */
            engine.consumer_stop.store(1);
            engine.owner_stop.store(1);
            result->status = static_cast<uint32_t>(kRouteRetryable);
            result->step = 34;
            result->error_number = EINVAL;
            result->kernel_disarmed = 0;
            log_line("compat-engine: cpu pair unusable; refusing the dance");
            return;
        }
        if (!dirty) {
            if (command.setup_settle_us != 0) sleep_us(command.setup_settle_us);
            log_value("compat-engine: wait word at CMP",
                      static_cast<long>(engine.wait_futex));
            log_value("compat-engine: target word at CMP",
                      static_cast<long>(engine.target_futex));
            errno = 0;
            const long requeued = futex_op(command, &engine.wait_futex,
                                           FUTEX_CMP_REQUEUE_PI, 1,
                                           reinterpret_cast<const void *>(1),
                                           &engine.target_futex, 0);
            log_value("compat-engine: CMP_REQUEUE_PI ret", requeued);
            log_value("compat-engine: CMP_REQUEUE_PI errno", errno);
            while (!engine.route_done.load() && now_ms() < deadline &&
                   engine.cpu_error.load() == 0) {
                sleep_poll(command);
            }
            if (engine.cpu_error.load() != 0) {
                engine.consumer_stop.store(1);
                engine.owner_stop.store(1);
                result->status = static_cast<uint32_t>(kRouteRetryable);
                result->step = 34;
                result->error_number = EINVAL;
                result->kernel_disarmed = 0;
                log_line("compat-engine: cpu changed under the dance; refusing");
                return;
            }
            if (!engine.route_done.load()) dirty = true;
        }

        engine.consumer_go.store(0);
        engine.consumer_stop.store(1);
        engine.owner_stop.store(1);
        if (!dirty) {
            (void) pthread_join(waiter_thread, nullptr);
            (void) pthread_join(owner_thread, nullptr);
            (void) pthread_join(consumer_thread, nullptr);
        }

        bool restored = false;
        if (!dirty) {
            restored = restore_fds(&plan);
        } else {
            /* A walk may still be in flight: leave the parked fds, as the
             * native's dirty stop does. */
            plan.count = 0;
            if (plan.never_ready >= 0) {
                (void) close(plan.never_ready);
                plan.never_ready = -1;
            }
        }

        const int32_t calls = engine.consumer_calls.load();
        const int32_t successes = engine.consumer_success.load();
        result->select_result = engine.select_result;
        result->select_errno = engine.select_errno;
        result->route_result = engine.route_result;
        result->route_errno = engine.route_errno;
        result->calls = calls;
        result->successes = successes;
        result->drain_ms = engine.drain_ms;
        result->userspace_clean = (!dirty && restored) ? 1U : 0U;
        result->kernel_disarmed = (!dirty && engine.drain_ms >= 0) ? 1U : 0U;
        if (dirty || engine.drain_ms < 0) {
            result->status = static_cast<uint32_t>(kRouteDirty);
            result->step = dirty ? 42 : 38;
        } else if (calls > 0 && successes > 0) {
            result->status = static_cast<uint32_t>(kRouteOk);
            result->step = 0;
        } else {
            result->status = static_cast<uint32_t>(kRouteRetryable);
            result->step = 33;
            result->error_number =
                command.mode ==
                        static_cast<uint32_t>(ghostlock::compat::CarrierMode::Multicast)
                    ? engine.route_errno
                    : engine.select_errno;
        }
        log_value("compat-engine: status", static_cast<long>(result->status));
        log_value("compat-engine: calls", calls);
        log_value("compat-engine: successes", successes);
    }
} // namespace ghostlock::compat
