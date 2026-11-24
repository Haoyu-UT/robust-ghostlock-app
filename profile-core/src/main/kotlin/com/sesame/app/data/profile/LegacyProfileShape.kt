package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue

/**
 * Converts an authored document in the pre-release layout to the cleaned v3
 * shape. The bundled assets are already cleaned; this exists for the
 * generator, whose Rust renderer still emits the pre-release layout until it
 * switches, and for a file a user saved with an older build.
 *
 * It is the same mapping as `tools/migrate_profile_shape.py` (the one-off
 * asset conversion): image-relative symbols, `image_base` recovered from the
 * symbol both blocks state, the timer's derived credential overlay filling the
 * shared `kernel.struct.cred`, and credential references named by their
 * offsets.
 */
object LegacyProfileShape {
    private val Syscalls = ProfileResolver.Syscalls

    fun convert(document: Map<String, Any?>): Map<String, Any?>? {
        if (ProfileResolver.isCleanedShape(document)) return document.copyValue().asValueMap() ?: document
        val release = document["release"] as? String ?: return null
        val base = imageBase(document)
        val branches = branches(document)
        val selected = branches.keys.sorted().firstOrNull()
        if (document["ghostlock"] is Map<*, *> || document["timer"] is Map<*, *>) return null
        val ghostlock = ghostlock(document, branches, selected)
        val timer = timer(document, base)
        if (ghostlock == null && timer == null) return null
        return linkedMapOf(
            "schema_version" to ProfileResolver.ProfileSchemaVersion.toLong(),
            "release" to release,
            "devices" to devices(document),
            "kernel" to kernel(document, base, selected?.let { branches[it] }),
            "ghostlock" to ghostlock,
            "timer" to timer,
        )
    }

    // ---- blocks -----------------------------------------------------------

    private fun branches(document: Map<String, Any?>): LinkedHashMap<String, ValueMap> {
        val out = LinkedHashMap<String, ValueMap>()
        document["route"].asValueMap()?.forEach { (name, value) ->
            value.asValueMap()?.let { out[name] = it }
        }
        val fallback = document["fallback"].asValueMap()
        val target = (fallback?.get("to") as? String)?.takeIf { it.isNotEmpty() && it != "none" }
        if (target != null && target !in out) {
            fallback?.get("route").asValueMap()?.get(target).asValueMap()
                ?.let { out[target] = it }
        }
        return out
    }

    private fun legacyCred(document: Map<String, Any?>) = document["cred"].asValueMap()

    private fun legacyTimer(document: Map<String, Any?>) =
        document["timer_98260"].asValueMap()

    /** The image's link base, recovered from the symbol both blocks state. */
    private fun imageBase(document: Map<String, Any?>): ULong? {
        val va = unsigned(legacyTimer(document)?.get("selinux_state")) ?: return null
        val offset = number(document["offset"].asValueMap()?.get("selinux_enforcing"))
            ?.toULong() ?: return null
        if (va <= offset) return null
        val base = va - offset
        if (base % 0x200000uL != 0uL || base < 0x8000_0000uL) return null
        return base
    }

    private fun relative(timer: ValueMap?, key: String, base: ULong?): ULong? {
        val va = unsigned(timer?.get(key)) ?: return null
        if (base == null || va < base) return null
        return va - base
    }

    private fun kernel(
        document: Map<String, Any?>,
        base: ULong?,
        selectedBranch: ValueMap?,
    ): Map<String, Any?> {
        val offset = document["offset"].asValueMap()
        val timer = legacyTimer(document)
        val task = document["task_struct"].asValueMap()
        val layout = document["waiter_layout"].asValueMap()
        val snitch = document["kernelsnitch"].asValueMap()
        val selinux = document["selinux"].asValueMap()
        val shapeValue = number(layout?.get("compact"))
            ?: number(selectedBranch?.get("compact_waiter"))
        val shape = when (shapeValue) {
            1L -> "compact"
            0L -> "node"
            else -> null
        }
        val taskOffset = number(layout?.get("task")) ?: number(selectedBranch?.get("task_offset"))
        val lockOffset = number(layout?.get("lock")) ?: number(selectedBranch?.get("lock_offset"))
        return linkedMapOf(
            "image_base" to hex(base),
            "symbols" to linkedMapOf<String, Any?>(
                "init_task" to hex(number(offset?.get("init_task"))),
                "init_cred" to hex(number(offset?.get("init_cred"))),
                "root_task_group" to hex(number(offset?.get("root_task_group"))),
                "empty_zero_page" to hex(number(offset?.get("empty_zero_page"))),
                "selinux_state" to hex(number(offset?.get("selinux_enforcing"))),
                "selinux_blob_sizes" to hex(number(offset?.get("selinux_blob_sizes"))),
                "security_hook_heads" to hex(number(offset?.get("security_hook_heads"))),
                "nf_logger" to hex(number(offset?.get("slide_nfulnl_logger"))),
                "loggers_0_1" to hex(number(offset?.get("slide_loggers_0_1"))),
                "boot_id" to hex(number(offset?.get("slide_boot_id"))),
                "panic_on_warn" to hex(relative(timer, "panic_on_warn", base)),
                "root_user" to hex(relative(timer, "root_user", base)),
                "init_user_ns" to hex(relative(timer, "init_user_ns", base)),
                "init_ucounts" to hex(relative(timer, "init_ucounts", base)),
                "init_groups" to hex(relative(timer, "init_groups", base)),
            ),
            "struct" to linkedMapOf<String, Any?>(
                "task" to linkedMapOf<String, Any?>(
                    "prio" to number(task?.get("prio")),
                    "normal_prio" to number(task?.get("normal_prio")),
                    "sched_task_group" to number(task?.get("sched_task_group")),
                    "pi_lock" to number(task?.get("pi_lock")),
                    "pi_waiters" to number(task?.get("pi_waiters")),
                    "pi_top_task" to number(task?.get("pi_top_task")),
                    "pi_blocked_on" to number(task?.get("pi_blocked_on")),
                    "pid" to number(task?.get("pid")),
                    "tgid" to number(task?.get("tgid")),
                    "atomic_flags" to number(task?.get("atomic_flags")),
                    "real_cred" to number(task?.get("real_cred")),
                    "cred" to number(task?.get("cred")),
                    "comm" to number(task?.get("comm")),
                    "tasks" to number(task?.get("tasks")),
                    "seccomp" to number(task?.get("seccomp")),
                ),
                "cred" to credential(document),
                "waiter" to linkedMapOf<String, Any?>(
                    "shape" to shape,
                    "pi_tree" to number(layout?.get("pi_tree")),
                    "task" to taskOffset,
                    "lock" to lockOffset,
                    "wake_state" to number(layout?.get("wake_state")),
                ),
                "rt_mutex" to linkedMapOf<String, Any?>(
                    "size" to number(snitch?.get("rt_mutex_size")),
                ),
                "selinux_state" to linkedMapOf<String, Any?>(
                    "enforcing" to number(selinux?.get("enforcing")),
                    "initialized" to number(selinux?.get("initialized")),
                ),
            ),
            "heap" to linkedMapOf<String, Any?>(
                "stride" to number(snitch?.get("mm_struct_sz")),
                "collisions" to number(snitch?.get("collisions")),
            ),
            "dirtyfrag" to if (isKdp(document) || pipelineContains(document, "dirtyfrag")) {
                "feasible"
            } else {
                "unknown"
            },
            "kdp" to isKdp(document),
        )
    }

    private fun credential(document: Map<String, Any?>): Map<String, Any?> {
        val cred = legacyCred(document) ?: emptyMap<String, Any?>()
        val timer = legacyTimer(document)
        fun overlay(key: String): Long? = number(timer?.get(key))?.takeIf { it != 0L }
        fun small(value: Long?): Long? = value?.takeIf { it != 0L }

        val named = linkedMapOf(
            "user" to overlay("cred_user_offset"),
            "user_ns" to overlay("cred_user_ns_offset"),
            "ucounts" to overlay("cred_ucounts_offset"),
            "group_info" to overlay("cred_group_info_offset"),
        )
        val positional = listOf("user", "user_ns", "ucounts", "group_info")
        val refs = linkedMapOf<String, Map<String, Any?>>()
        for ((index, name) in positional.withIndex()) {
            val offset = small(number(cred["ref${index}_offset"]))
            val image = number(cred["ref${index}_image"])?.takeIf { it != 0L }
            if (offset == null && image == null) continue
            val target = named.entries.firstOrNull { it.value != null && it.value == offset }?.key
                ?: name
            refs[target] = linkedMapOf(
                "offset" to (offset ?: named[target]),
                "image" to hex(image),
            )
        }
        for ((name, offset) in named) {
            if (offset != null && name !in refs) {
                refs[name] = linkedMapOf("offset" to offset, "image" to null)
            }
        }
        return linkedMapOf(
            "copy_size" to number(cred["copy_size"]),
            "usage" to linkedMapOf(
                "offset" to number(cred["usage_offset"]),
                "size" to (overlay("cred_usage_size") ?: number(cred["usage_size"])),
                "value" to number(cred["usage_value"]),
            ),
            "uid" to (overlay("cred_uid_offset") ?: number(cred["uid_offset"])),
            "security" to (overlay("cred_security_offset") ?: number(cred["security_offset"])),
            "security_sid" to overlay("security_sid_offset"),
            "caps" to linkedMapOf(
                "offset" to (overlay("cred_caps_offset") ?: number(cred["caps_offset"])),
                "count" to (overlay("cred_caps_count") ?: number(cred["caps_count"])),
                "mask" to hex(number(cred["caps_value"])),
            ),
            "refs" to linkedMapOf<String, Any?>(
                "user" to (refs["user"] ?: emptyRef()),
                "user_ns" to (refs["user_ns"] ?: emptyRef()),
                "ucounts" to (refs["ucounts"] ?: emptyRef()),
                "group_info" to (refs["group_info"] ?: emptyRef()),
            ),
        )
    }

    private fun emptyRef(): Map<String, Any?> =
        linkedMapOf("offset" to null, "image" to null)

    private fun ghostlock(
        document: Map<String, Any?>,
        branches: LinkedHashMap<String, ValueMap>,
        selected: String?,
    ): Map<String, Any?>? {
        val module = document["module"].asValueMap()
        if (branches.isEmpty() && module == null) return null
        val syscalls = linkedMapOf<String, Any?>()
        for (name in Syscalls) {
            syscalls[name] = branches[name]?.let { branchBlocks(name, it) }
        }
        val anchor = selected?.let { anchor(branches[it], document) }
        return linkedMapOf<String, Any?>(
            "syscall" to selected,
            "carrier" to carrierChoice(selected?.let { branches[it] }),
            "anchor" to anchor,
            "syscalls" to if (branches.isEmpty()) null else syscalls,
            "module" to module?.let {
                linkedMapOf(
                    "asset" to it["asset"],
                    "sha256" to it["asset_sha256"],
                    "vendor_files" to it["vendor_files"],
                )
            },
            /* A hand-written pre-release document may state tuning; it moves
             * under the engine and wins over the presets. */
            "execution" to document["execution"].asValueMap()?.copyValue().asValueMap(),
        )
    }

    private fun carrierFlags(branch: ValueMap): Pair<Boolean, Boolean> {
        val carriers = (branch["carriers"] as? List<*>)
            ?.mapNotNull { (it as? String)?.lowercase() }
        if (carriers != null) {
            return ("native" in carriers) to ("compat" in carriers)
        }
        val compat = number(branch["compat_carrier"]) == 1L
        return (!compat) to compat
    }

    private fun futexAbi(branch: ValueMap): String =
        if (number(branch["compat_futex_abi"]) == 1L) "time64" else "time32"

    private fun branchBlocks(name: String, branch: ValueMap): Map<String, Any?> {
        val (native, compat) = carrierFlags(branch)
        fun n(key: String): Long? = number(branch[key])
        return when (name) {
            "select_stack" -> linkedMapOf(
                "native" to if (native) {
                    linkedMapOf("waiter_shift" to n("waiter_shift"))
                } else {
                    null
                },
                "compat" to if (compat) {
                    linkedMapOf(
                        "waiter_home" to n("waiter_home"),
                        "dst_depth" to n("select_dst_depth"),
                        "futex_abi" to futexAbi(branch),
                    )
                } else {
                    null
                },
            )

            "multicast_waiter" -> linkedMapOf(
                "native" to if (native) {
                    linkedMapOf(
                        "waiter_off" to n("waiter_off"),
                        "buffer_size" to n("buffer_size"),
                    )
                } else {
                    null
                },
                "compat" to if (compat) {
                    linkedMapOf(
                        "waiter_off" to n("compat_waiter_off"),
                        "buffer_size" to n("compat_buffer_size"),
                        "futex_abi" to futexAbi(branch),
                    )
                } else {
                    null
                },
            )

            "sigreturn" -> linkedMapOf(
                "native" to linkedMapOf(
                    "waiter_off" to linkedMapOf(
                        "plain" to n("waiter_off_plain"),
                        "sve" to n("waiter_off_sve"),
                    ),
                ),
                "compat" to null,
            )

            else -> linkedMapOf("native" to linkedMapOf<String, Any?>(), "compat" to null)
        }
    }

    private fun carrierChoice(branch: ValueMap?): String? {
        if (branch == null) return null
        val (native, compat) = carrierFlags(branch)
        return when {
            compat && !native -> "compat"
            native && !compat -> null
            else -> "native"
        }
    }

    private fun anchor(branch: ValueMap?, document: Map<String, Any?>): Map<String, Any?>? {
        if (branch == null) return null
        val image = number(branch["lock_anchor_image"])?.takeIf { it != 0L }
        val bytes = number(branch["lock_anchor_bytes"])
        val stride = number(branch["lock_anchor_stride"])
        val scratch = number(document["w1_scratch"].asValueMap()?.get("image"))
            ?.takeIf { it != 0L }
        if (image == null && bytes == null && stride == null && scratch == null) return null
        return linkedMapOf(
            "image" to hex(image),
            "bytes" to bytes,
            "stride" to stride,
            "scratch" to hex(scratch),
        )
    }

    private fun timer(document: Map<String, Any?>, base: ULong?): Map<String, Any?>? {
        val timer = legacyTimer(document) ?: return null
        val siteVa = unsigned(timer["static_timer_site"])
        val siteOffset: ULong? = when {
            siteVa == null -> null
            base != null && siteVa >= base -> siteVa - base
            base == null -> siteVa
            else -> null
        }
        val low = number(timer["task_alloc_site_lower_21bit"])
        return linkedMapOf(
            "trigger" to when (timer["trigger"]) {
                "core_state" -> "core_state"
                else -> "killable"
            },
            "match_bits" to 21L,
            "sites" to linkedMapOf(
                "timer" to linkedMapOf("offset" to hex(siteOffset)),
                "task_alloc" to linkedMapOf(
                    "low_bits" to hex(low?.let { (it and 0x1FFFFFL).toULong() }),
                ),
            ),
            "object_size" to number(timer["timer_object_size"]),
            "task_alloc_size" to number(timer["task_alloc_size"]),
            "node_offset" to number(timer["timer_node_offset"]),
            "cred" to linkedMapOf(
                "request_size" to number(timer["cred_request_size"]),
                "alloc_size" to number(timer["cred_alloc_size"]),
            ),
            "trace" to linkedMapOf(
                "gfp" to linkedMapOf(
                    "offset" to number(timer["page_alloc_gfp_offset"]),
                    "size" to number(timer["page_alloc_gfp_size"]),
                ),
            ),
        )
    }

    private fun devices(document: Map<String, Any?>): Map<String, Any?>? {
        val load = document["kernel_phys_load"].asValueMap()
        val offset = document["kernel_phys_offset"].asValueMap()
        if (load == null && offset == null) return null
        val ids = linkedSetOf<String>()
        load?.keys?.forEach { ids += it }
        offset?.keys?.forEach { ids += it }
        return ids.associateWithTo(linkedMapOf()) { id ->
            fun entry(map: ValueMap?): String? {
                val key = map?.keys?.firstOrNull { it.equals(id, ignoreCase = true) }
                return hex(number(map?.get(key)))
            }
            linkedMapOf(
                "phys" to linkedMapOf(
                    "load" to entry(load),
                    "offset" to entry(offset),
                ),
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun isKdp(document: Map<String, Any?>): Boolean =
        number(document["kdp"]) == 1L

    private fun pipelineContains(document: Map<String, Any?>, name: String): Boolean =
        when (val raw = document["pipeline"]) {
            is String -> raw.trim().lowercase() == name
            is List<*> -> raw.any { (it as? String)?.trim()?.lowercase() == name }
            else -> false
        }

    private fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLongOrNull(16)
            else text.toLongOrNull()
        }
        else -> null
    }

    private fun unsigned(value: Any?): ULong? = when (value) {
        is Number -> value.toLong().toULong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toULongOrNull(16)
            else text.toULongOrNull()
        }
        else -> null
    }

    private fun hex(value: Long?): String? =
        value?.toULong()?.let { "0x" + it.toString(16) }

    private fun hex(value: ULong?): String? =
        value?.let { "0x" + it.toString(16) }
}
