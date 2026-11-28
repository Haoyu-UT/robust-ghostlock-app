package com.sesame.app.data.profile

/**
 * The run's lock / W1 delivery. The document carries one shared anchor, so
 * these are the placements it can express, best (least page-dependent) first:
 *
 *  - [PageFree]   anchor lock + scratch W1; the reclaimed page is not prepared.
 *  - [AnchorPage] anchor lock + page W1; the anchor's scratch is cleared.
 *  - [Page]       page lock + page W1; the anchor block is cleared.
 *
 * The native derives the effective mode from the rendered document
 * (`page_free = route capable ∧ anchor ∧ scratch`), so a choice is applied at
 * render time by [ProfileTransport] and never written into the exported
 * document. The generation flow owns which anchor the document states; this is
 * the run-time override over that choice.
 */
object LockSources {
    const val PageFree = "page_free"
    const val AnchorPage = "anchor_page"
    const val Page = "page"

    /** Every value, best first. */
    val All = listOf(PageFree, AnchorPage, Page)

    /**
     * The routes that can lock on a zeroed anchor. Mirrors the native policy
     * (`route_anchor_capable`): `tcp_zerocopy`'s fake lock is a crafted object,
     * so a zero anchor cannot drive its walk.
     */
    private val AnchorCapable = setOf("select_stack", "sigreturn", "multicast_waiter")

    fun routeCapable(route: String?): Boolean = route != null && route in AnchorCapable

    /**
     * The modes an anchor-capable route may run here, best first. One entry
     * means there is no choice (the document states no anchor, or no scratch);
     * the picker hides a single-option row.
     */
    fun options(anchorPresent: Boolean, scratchPresent: Boolean): List<String> = when {
        anchorPresent && scratchPresent -> All
        anchorPresent -> listOf(AnchorPage, Page)
        else -> listOf(Page)
    }
}
