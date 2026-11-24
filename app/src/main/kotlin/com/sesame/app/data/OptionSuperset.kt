package com.sesame.app.data

/**
 * The extractor's `--options-out` document: every route, anchor candidate and
 * page-free plan for one image, plus the common profile blocks. One parse
 * produces it; a runnable profile is a **cut** of it.
 *
 * The extractor omits nothing. The UI applies the display rules over the full
 * list ([RoutePlans.safeOptions] / [RoutePlans.conditionalOptions]): a single
 * safe option is taken automatically, several safe options are offered without
 * a page row, and page is offered only when no safe option exists.
 */
data class OptionAnchor(
    val image: Long,
    val bytes: Long,
    val stride: Long,
)

data class OptionScratch(
    val image: Long,
    val window: Long,
    val reservedFirst: Long?,
    val reservedLast: Long?,
) {
    /** The window lies inside the anchor region and reserves lock slots. */
    val inAnchor: Boolean get() = reservedFirst != null
}

/** The `scratchSource` spelling for a scratch inside the anchor's own region. */
const val ScratchInAnchor = "in-anchor"

data class OptionPlan(
    val route: String,
    val anchor: OptionAnchor?,
    val scratch: OptionScratch?,
    val pageFree: Boolean,
    val conditioned: Boolean,
    val anchorName: String?,
    val scratchSource: String?,
    val reason: String,
) {
    /** A short technical label (logs and tests): the anchor region and, when it
     * is not in-anchor, the scratch region. */
    val label: String
        get() {
            val anchor = anchorName ?: "page"
            val scratch = scratchSource?.takeIf { it != ScratchInAnchor }
            return if (scratch == null) anchor else "$anchor + $scratch"
        }
}

/** One non-primary carrier of a route, with its own geometry. */
data class OptionRouteVariant(
    val carrier: String,
    val verdict: String,
    val reason: String,
    val geometry: Map<String, Long>,
)

data class OptionRoute(
    val route: String,
    val verdict: String,
    val reason: String,
    val anchorCapable: Boolean,
    val geometry: Map<String, Long>,
    /** The primary carrier: `native` or `compat`. */
    val carrier: String = "native",
    /** The route's other feasible carriers (the carrier × route matrix). */
    val alternatives: List<OptionRouteVariant> = emptyList(),
)

/** One `pipelines[]` verdict from the extractor's maximal pass. */
data class OptionPipeline(
    val pipeline: String,
    val verdict: String,
    val reason: String,
)

/** One device sidecar set's physical derivation from the superset's `devices[]`. */
data class OptionDevice(
    val device: String,
    val physLoad: Long?,
    val physOffset: Long?,
    val physSource: String,
)

data class RoutePlans(
    val route: String,
    val page: OptionPlan,
    /** The safe anchor with a page W1, when no page-free plan is selected. */
    val anchorOnly: OptionPlan?,
    /** Every page-free plan: safe first, then conditioned. */
    val pageFree: List<OptionPlan>,
) {
    /** Options the UI may auto-take or offer; page is not shown when non-empty. */
    val safeOptions: List<OptionPlan>
        get() = pageFree.filter { !it.conditioned }

    /** The needs-review options, offered only when no safe option exists. */
    val conditionalOptions: List<OptionPlan>
        get() = pageFree.filter { it.conditioned }
}

/** The UI's display rule over the full option list (operator decision). */
sealed interface OptionDecision {
    /** Exactly one safe page-free option: take it without asking. */
    data class Auto(val plan: OptionPlan) : OptionDecision

    /** Offer these plans. When [pageOffered] is true the UI adds a page row. */
    data class Offer(val options: List<OptionPlan>, val pageOffered: Boolean) : OptionDecision

    /** Nothing page-free; the page-based path (and the lock-source choice). */
    data object NoPageFree : OptionDecision
}

fun RoutePlans.decision(): OptionDecision = when {
    safeOptions.size == 1 -> OptionDecision.Auto(safeOptions.single())
    safeOptions.isNotEmpty() -> OptionDecision.Offer(safeOptions, pageOffered = false)
    conditionalOptions.isNotEmpty() -> OptionDecision.Offer(conditionalOptions, pageOffered = true)
    else -> OptionDecision.NoPageFree
}

data class OptionSuperset(
    val release: String,
    val kernelMajor: Int?,
    val physLoad: Long?,
    val physOffset: Long?,
    val physSource: String,
    val routes: List<OptionRoute>,
    val plans: List<RoutePlans>,
    /** `profile[block][key]` from the route-less common render; values keep
     * their HOCON spelling (`"null"`, quoted strings, decimals). */
    val profile: Map<String, Map<String, String>>,
    /** Every device derivation the extractor recorded (`--device`); empty on
     * a legacy device-neutral pass. */
    val devices: List<OptionDevice> = emptyList(),
    /** The image's pipeline verdicts (`pipelines[]`); empty on a superset
     * written before the selector existed. */
    val pipelines: List<OptionPipeline> = emptyList(),
    /** The extractor's default pipeline order (highest precedence first); the
     * cut writes it into the profile's `pipeline` list. */
    val pipelineOrder: List<String> = emptyList(),
) {
    /** The device key this superset was generated for, when it has one. */
    val device: String? get() = devices.firstOrNull()?.device

    /** The pipelines this image can run; the extractor requires a choice when
     * more than one is feasible. */
    val feasiblePipelines: List<OptionPipeline>
        get() = pipelines.filter { it.verdict == Feasible }

    fun route(name: String): OptionRoute? = routes.firstOrNull { it.route == name }

    fun plansFor(name: String): RoutePlans? = plans.firstOrNull { it.route == name }

    /**
     * Materialize a runnable profile: the common blocks (rendered once by the
     * extractor) plus the selected route, anchor and scratch. No derivation
     * happens here.
     */
    fun cut(
        route: String,
        plan: OptionPlan,
        device: String? = null,
        pipelineOrder: List<String>? = null,
        carrier: String? = null,
    ): String? {
        val routeOption = routes.firstOrNull { it.route == route && it.verdict == Feasible }
            ?: return null
        /* The carrier choice is a geometry choice inside the route; the primary
         * is the default, and an absent or unknown carrier fails closed. With
         * no carrier named the cut is the profile form: every feasible carrier
         * is listed in `carriers` and their geometry joins one route block. */
        val carriers = if (carrier != null) {
            listOf(carrier)
        } else {
            (listOf(routeOption.carrier) +
                routeOption.alternatives
                    .filter { it.verdict == Feasible }
                    .map { it.carrier })
                .distinct()
        }
        if (carrier != null && carrier !in carriers) return null
        val geometry = when {
            carrier == null || carrier == routeOption.carrier -> routeOption.geometry
            else -> routeOption.alternatives
                .firstOrNull { it.carrier == carrier && it.verdict == Feasible }
                ?.geometry ?: return null
        }
        val top = profile[""] ?: return null
        val selected = device?.let { name -> devices.firstOrNull { it.device.equals(name, true) } }
            ?: devices.firstOrNull()
        fun lit(block: Map<String, String>?, key: String): Any? =
            block?.get(key)?.let(::literal)
        fun number(block: Map<String, String>?, key: String): Long? =
            lit(block, key) as? Long
        fun hex(value: Long?): String? = value?.let { "0x" + it.toULong().toString(16) }
        fun hexOf(block: Map<String, String>?, key: String): String? = hex(number(block, key))
        fun block(name: String): Map<String, String>? = profile[name]
        fun geometryNumber(source: Map<String, Long>, key: String): Long? = source[key]
        fun futexAbi(source: Map<String, Long>): String =
            if (geometryNumber(source, "compat_futex_abi") == 1L) "time64" else "time32"

        val offset = block("offset")
        val task = block("task_struct")
        val cred = block("cred")
        val snitch = block("kernelsnitch")
        val selinux = block("selinux")
        val waiterLayout = block("waiter_layout")
        val carriersOfRoute = carriers
        val nativeDeclared = "native" in carriersOfRoute
        val compatDeclared = "compat" in carriersOfRoute
        val selectedCarrier = when {
            compatDeclared && !nativeDeclared -> "compat"
            nativeDeclared && !compatDeclared -> null
            nativeDeclared -> "native"
            else -> null
        }
        val primaryGeometry = routeOption.geometry
        val compatGeometry = routeOption.alternatives
            .firstOrNull { it.carrier == "compat" && it.verdict == Feasible }
            ?.geometry ?: emptyMap()
        val compact = number(waiterLayout, "compact")
            ?: geometryNumber(primaryGeometry, "compact_waiter")
        val waiterTask = number(waiterLayout, "task")
            ?: geometryNumber(primaryGeometry, "task_offset")
            ?: geometryNumber(compatGeometry, "task_offset")
        val waiterLock = number(waiterLayout, "lock")
            ?: geometryNumber(primaryGeometry, "lock_offset")
            ?: geometryNumber(compatGeometry, "lock_offset")
        val order = pipelineOrder ?: this.pipelineOrder
        val dirtyfrag = if ("dirtyfrag" in order) "feasible" else "unknown"
        val symbolNames = listOf(
            "init_task", "init_cred", "root_task_group", "empty_zero_page",
            "selinux_state", "selinux_blob_sizes", "security_hook_heads",
            "nf_logger", "loggers_0_1", "boot_id",
        )
        val offsetSource = mapOf(
            "init_task" to "init_task",
            "init_cred" to "init_cred",
            "root_task_group" to "root_task_group",
            "empty_zero_page" to "empty_zero_page",
            "selinux_state" to "selinux_enforcing",
            "selinux_blob_sizes" to "selinux_blob_sizes",
            "security_hook_heads" to "security_hook_heads",
            "nf_logger" to "slide_nfulnl_logger",
            "loggers_0_1" to "slide_loggers_0_1",
            "boot_id" to "slide_boot_id",
        )
        val symbols = linkedMapOf<String, Any?>()
        for (name in symbolNames) {
            symbols[name] = hexOf(offset, offsetSource.getValue(name))
        }
        for (name in listOf(
            "panic_on_warn", "root_user", "init_user_ns", "init_ucounts", "init_groups",
        )) {
            symbols[name] = null
        }
        val taskFields = listOf(
            "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
            "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags",
            "real_cred", "cred", "comm", "tasks", "seccomp",
        )
        val taskBlock = linkedMapOf<String, Any?>()
        for (field in taskFields) taskBlock[field] = number(task, field)
        val refNames = listOf("user", "user_ns", "ucounts", "group_info")
        val refs = linkedMapOf<String, Any?>()
        for ((index, name) in refNames.withIndex()) {
            refs[name] = linkedMapOf<String, Any?>(
                "offset" to number(cred, "ref${index}_offset"),
                "image" to hexOf(cred, "ref${index}_image"),
            )
        }
        val credBlock = linkedMapOf<String, Any?>(
            "copy_size" to number(cred, "copy_size"),
            "usage" to linkedMapOf(
                "offset" to number(cred, "usage_offset"),
                "size" to number(cred, "usage_size"),
                "value" to number(cred, "usage_value"),
            ),
            "uid" to number(cred, "uid_offset"),
            "security" to number(cred, "security_offset"),
            "security_sid" to null,
            "caps" to linkedMapOf(
                "offset" to number(cred, "caps_offset"),
                "count" to number(cred, "caps_count"),
                "mask" to hexOf(cred, "caps_value"),
            ),
            "refs" to refs,
        )
        val kernel = linkedMapOf<String, Any?>(
            "image_base" to null,
            "symbols" to symbols,
            "struct" to linkedMapOf<String, Any?>(
                "task" to taskBlock,
                "cred" to credBlock,
                "waiter" to linkedMapOf<String, Any?>(
                    "shape" to when (compact) {
                        1L -> "compact"
                        0L -> "node"
                        else -> null
                    },
                    "pi_tree" to number(waiterLayout, "pi_tree"),
                    "task" to waiterTask,
                    "lock" to waiterLock,
                    "wake_state" to number(waiterLayout, "wake_state"),
                ),
                "rt_mutex" to linkedMapOf<String, Any?>(
                    "size" to number(snitch, "rt_mutex_size"),
                ),
                "selinux_state" to linkedMapOf<String, Any?>(
                    "enforcing" to number(selinux, "enforcing"),
                    "initialized" to number(selinux, "initialized"),
                ),
            ),
            "heap" to linkedMapOf<String, Any?>(
                "stride" to null,
                "collisions" to number(snitch, "collisions"),
            ),
            "dirtyfrag" to dirtyfrag,
            "kdp" to (number(top, "kdp") == 1L || lit(top, "kdp") == true),
        )
        val branch = linkedMapOf<String, Any?>()
        when (route) {
            "select_stack" -> {
                branch["native"] = if (nativeDeclared) {
                    linkedMapOf<String, Any?>(
                        "waiter_shift" to geometryNumber(primaryGeometry, "waiter_shift"),
                    )
                } else {
                    null
                }
                branch["compat"] = if (compatDeclared) {
                    linkedMapOf<String, Any?>(
                        "waiter_home" to geometryNumber(compatGeometry, "waiter_home"),
                        "dst_depth" to geometryNumber(compatGeometry, "select_dst_depth"),
                        "futex_abi" to futexAbi(compatGeometry),
                    )
                } else {
                    null
                }
            }
            "multicast_waiter" -> {
                branch["native"] = if (nativeDeclared) {
                    linkedMapOf<String, Any?>(
                        "waiter_off" to geometryNumber(primaryGeometry, "waiter_off"),
                        "buffer_size" to geometryNumber(primaryGeometry, "buffer_size"),
                    )
                } else {
                    null
                }
                branch["compat"] = if (compatDeclared) {
                    linkedMapOf<String, Any?>(
                        "waiter_off" to (geometryNumber(compatGeometry, "compat_waiter_off")
                            ?: geometryNumber(compatGeometry, "waiter_off")),
                        "buffer_size" to (geometryNumber(compatGeometry, "compat_buffer_size")
                            ?: geometryNumber(compatGeometry, "buffer_size")),
                        "futex_abi" to futexAbi(compatGeometry),
                    )
                } else {
                    null
                }
            }
            "sigreturn" -> {
                branch["native"] = linkedMapOf<String, Any?>(
                    "waiter_off" to linkedMapOf<String, Any?>(
                        "plain" to geometryNumber(primaryGeometry, "waiter_off_plain"),
                        "sve" to geometryNumber(primaryGeometry, "waiter_off_sve"),
                    ),
                )
                branch["compat"] = null
            }
            else -> {
                branch["native"] = linkedMapOf<String, Any?>()
                branch["compat"] = null
            }
        }
        val syscalls = linkedMapOf<String, Any?>()
        for (name in listOf("select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy")) {
            syscalls[name] = if (name == route) branch else null
        }
        val anchor = plan.anchor?.let {
            linkedMapOf<String, Any?>(
                "image" to hex(it.image),
                "bytes" to it.bytes,
                "stride" to it.stride,
                "scratch" to plan.scratch?.let { scratch -> hex(scratch.image) },
            )
        }
        val ghostlock = linkedMapOf<String, Any?>(
            "syscall" to route,
            "carrier" to selectedCarrier,
            "anchor" to anchor,
            "syscalls" to syscalls,
            "module" to null,
            "execution" to null,
        )
        val document = linkedMapOf<String, Any?>(
            "schema_version" to 3L,
            "release" to ((lit(top, "release") as? String) ?: release),
            "devices" to selected?.let {
                linkedMapOf<String, Any?>(
                    it.device to linkedMapOf(
                        "phys" to linkedMapOf(
                            "load" to hex(it.physLoad),
                            "offset" to hex(it.physOffset),
                        ),
                    ),
                )
            },
            "kernel" to kernel,
            "ghostlock" to ghostlock,
            "timer" to null,
        )
        return HoconSupport.render(document) + "\n"
    }

    companion object {
        const val Feasible = "feasible"

        private val TopLevelKeys = listOf(
            "release",
            "schema_version",
            "kernel_major",
            "kernel_phys_load",
            "kernel_phys_offset",
        )

        private val CommonBlocks = listOf(
            "kernelsnitch",
            "task_struct",
            "cred",
            "offset",
            "waiter_layout",
        )

        fun parse(text: String): OptionSuperset? = runCatching {
            val root = HoconSupport.parseValue(text).asValueMap() ?: return@runCatching null
            val profile = (root["profile"].asValueMap() ?: return@runCatching null)
                .mapValues { (_, block) ->
                    block.asValueMap().orEmpty()
                        .mapValues { (_, value) -> value?.toString() ?: "null" }
                }
            val routes = root["routes"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val route = map["route"] as? String ?: return@mapNotNull null
                val alternatives = map["alternatives"].asValueList().orEmpty().mapNotNull { alt ->
                    val altMap = alt.asValueMap() ?: return@mapNotNull null
                    val carrier = altMap["carrier"] as? String ?: return@mapNotNull null
                    OptionRouteVariant(
                        carrier = carrier,
                        verdict = altMap["verdict"] as? String ?: "",
                        reason = altMap["reason"] as? String ?: "",
                        geometry = geometryOf(altMap["geometry"]),
                    )
                }
                OptionRoute(
                    route = route,
                    verdict = map["verdict"] as? String ?: "",
                    reason = map["reason"] as? String ?: "",
                    anchorCapable = map["anchor_capable"] as? Boolean ?: false,
                    geometry = geometryOf(map["geometry"]),
                    carrier = map["carrier"] as? String ?: "native",
                    alternatives = alternatives,
                )
            }
            val plans = root["plans"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val route = map["route"] as? String ?: return@mapNotNull null
                val page = plan(map["page"]) ?: return@mapNotNull null
                RoutePlans(
                    route = route,
                    page = page,
                    anchorOnly = plan(map["anchor_only"]),
                    pageFree = map["page_free"].asValueList().orEmpty()
                        .mapNotNull { plan(it) },
                )
            }
            val devices = root["devices"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val device = map["device"] as? String ?: return@mapNotNull null
                OptionDevice(
                    device = device,
                    physLoad = (map["kernel_phys_load"] as? Number)?.toLong(),
                    physOffset = (map["kernel_phys_offset"] as? Number)?.toLong(),
                    physSource = map["phys_source"] as? String ?: "unset",
                )
            }
            val pipelines = root["pipelines"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val pipeline = map["pipeline"] as? String ?: return@mapNotNull null
                OptionPipeline(
                    pipeline = pipeline,
                    verdict = map["verdict"] as? String ?: "",
                    reason = map["reason"] as? String ?: "",
                )
            }
            val pipelineOrder = root["pipeline_order"].asValueList().orEmpty()
                .mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            OptionSuperset(
                release = root["release"] as? String ?: return@runCatching null,
                kernelMajor = (root["kernel_major"] as? Number)?.toInt(),
                physLoad = (root["kernel_phys_load"] as? Number)?.toLong(),
                physOffset = (root["kernel_phys_offset"] as? Number)?.toLong(),
                physSource = root["phys_source"] as? String ?: "unset",
                routes = routes,
                plans = plans,
                profile = profile,
                devices = devices,
                pipelines = pipelines,
                pipelineOrder = pipelineOrder,
            )
        }.getOrNull()

        private fun geometryOf(value: Any?): Map<String, Long> =
            value.asValueMap().orEmpty()
                .mapNotNull { (key, item) -> (item as? Number)?.let { key to it.toLong() } }
                .toMap()

        private fun plan(value: Any?): OptionPlan? {
            val map = value.asValueMap() ?: return null
            return OptionPlan(
                route = map["route"] as? String ?: return null,
                anchor = map["anchor"].asValueMap()?.let { anchor ->
                    OptionAnchor(
                        image = (anchor["image"] as? Number)?.toLong() ?: return@let null,
                        bytes = (anchor["bytes"] as? Number)?.toLong() ?: return@let null,
                        stride = (anchor["stride"] as? Number)?.toLong() ?: return@let null,
                    )
                },
                scratch = map["scratch"].asValueMap()?.let { scratch ->
                    OptionScratch(
                        image = (scratch["image"] as? Number)?.toLong() ?: return@let null,
                        window = (scratch["window"] as? Number)?.toLong() ?: 0,
                        reservedFirst = (scratch["reserved_first"] as? Number)?.toLong(),
                        reservedLast = (scratch["reserved_last"] as? Number)?.toLong(),
                    )
                },
                pageFree = map["page_free"] as? Boolean ?: false,
                conditioned = map["conditioned"] as? Boolean ?: false,
                anchorName = map["anchor_name"] as? String,
                scratchSource = map["scratch_source"] as? String,
                reason = map["reason"] as? String ?: "",
            )
        }

        /** Turn a rendered-profile value back into a HOCON literal. */
        private fun literal(value: String): Any? = when {
            value == "null" -> null
            value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
                value.substring(1, value.length - 1)
            value.toLongOrNull() != null -> value.toLong()
            else -> value
        }
    }
}
