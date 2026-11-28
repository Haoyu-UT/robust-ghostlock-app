package com.sesame.app.data

/**
 * The extractor's `--options-out` document: every route, anchor candidate and
 * page-free plan for one image, plus the cleaned v3 profile document. One
 * parse produces it; the generation flow writes its chosen anchor plan into the
 * document and leaves the route choice to the home picker.
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
)

/** The `scratchSource` spelling for a scratch inside the anchor's own region. */
const val ScratchInAnchor = "in-anchor"

data class OptionPlan(
    val anchor: OptionAnchor?,
    val scratch: OptionScratch?,
    val conditioned: Boolean,
    val anchorName: String?,
    val scratchSource: String?,
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

data class OptionRoute(
    val route: String,
    val verdict: String,
    /**
     * The route can use the shared lock anchor. `tcp_zerocopy` cannot: its fake
     * lock is a crafted object, so a zeroed region never drives its walk. The
     * extractor states this per route; a document written before the field
     * existed falls back to the same rule.
     */
    val anchorCapable: Boolean = true,
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
    val routes: List<OptionRoute>,
    val plans: List<RoutePlans>,
    /** The cleaned v3 profile document the extractor rendered for this image:
     * the route-superset form. Only the shared anchor is chosen here; the route
     * (and its carrier) is the home picker's at run time. Null when the image
     * has no feasible route. */
    val document: ValueMap?,
) {
    fun route(name: String): OptionRoute? = routes.firstOrNull { it.route == name }

    fun plansFor(name: String): RoutePlans? = plans.firstOrNull { it.route == name }

    /**
     * The plan list the generation flow's anchor step reads.
     *
     * The document carries one shared `ghostlock.anchor`, and the extractor
     * derives the same placements for every route — the plan list depends on
     * the image's anchor/scratch candidates, and only the capability is per
     * route. So the first anchor-capable feasible route's list *is* the
     * document's list: which route a report happens to name first must not
     * decide whether the user gets the anchor choice.
     *
     * Null when no feasible route can use an anchor (`tcp_zerocopy` only): the
     * document states no anchor, and offering a plan would write one the route
     * cannot lock with.
     */
    fun anchorPlans(): RoutePlans? =
        routes.firstOrNull { it.verdict == Feasible && it.anchorCapable }
            ?.let { plansFor(it.route) }

    /**
     * Writes the chosen anchor plan into the extractor's document and renders
     * it.
     *
     * Only the shared `ghostlock.anchor` block changes: the schema has one, so
     * it is the single profile fact the generation flow must choose. Every
     * `ghostlock.syscalls` branch is left intact — the route stays a superset,
     * chosen by the home picker at run time. A page plan (`anchor == null`)
     * clears the block, which is the anchor-less page mode.
     */
    fun withAnchor(plan: OptionPlan): String? {
        val base = document?.copyValue()?.asValueMap() ?: return null
        val ghostlock = base["ghostlock"].asValueMap() ?: return null
        fun hex(value: Long?): String? = value?.let { "0x" + it.toULong().toString(16) }
        ghostlock["anchor"] = plan.anchor?.let { anchor ->
            linkedMapOf<String, Any?>(
                "image" to hex(anchor.image),
                "bytes" to anchor.bytes,
                "stride" to anchor.stride,
                "scratch" to plan.scratch?.let { scratch -> hex(scratch.image) },
            )
        }
        return HoconSupport.render(base) + "\n"
    }

    companion object {
        const val Feasible = "feasible"

        /** The one route whose fake lock is a crafted object, so no anchor. */
        const val TcpRoute = "tcp_zerocopy"

        fun parse(text: String): OptionSuperset? = runCatching {
            val root = HoconSupport.parseValue(text).asValueMap() ?: return@runCatching null
            val document = root["document"].asValueMap()
            val routes = root["routes"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val route = map["route"] as? String ?: return@mapNotNull null
                OptionRoute(
                    route = route,
                    verdict = map["verdict"] as? String ?: "",
                    anchorCapable = (map["anchor_capable"] as? Boolean)
                        ?: (route != TcpRoute),
                )
            }
            /* The per-device evidence: the plans are derived against the
             * device's pair, so the first analysis record's plans are the
             * list the picker uses. The pair itself is in the document. */
            val analysis = root["device_analysis"].asValueList().orEmpty()
                .mapNotNull { entry -> entry.asValueMap() }
            val plans = analysis.firstOrNull()
                ?.get("plans").asValueList().orEmpty()
                .mapNotNull { entry ->
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
            OptionSuperset(
                release = root["release"] as? String ?: return@runCatching null,
                routes = routes,
                plans = plans,
                document = document,
            )
        }.getOrNull()

        private fun plan(value: Any?): OptionPlan? {
            val map = value.asValueMap() ?: return null
            return OptionPlan(
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
                    )
                },
                conditioned = map["conditioned"] as? Boolean ?: false,
                anchorName = map["anchor_name"] as? String,
                scratchSource = map["scratch_source"] as? String,
            )
        }

    }
}
