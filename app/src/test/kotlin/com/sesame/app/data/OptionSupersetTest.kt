package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `--options-out` contract: the app reads every option and applies the
 * display rules itself (auto-take one safe option, offer several without page,
 * offer page only when nothing safe exists). The generation flow chooses the
 * one shared anchor; the route stays the home picker's at run time.
 */
class OptionSupersetTest {
    private val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"

    private fun plan(
        route: String,
        anchor: Long? = null,
        scratch: Long? = null,
        pageFree: Boolean = false,
        conditioned: Boolean = false,
        anchorName: String? = null,
        scratchSource: String? = null,
        inAnchor: Boolean = false,
    ): ValueMap = valueMapOf(
        "route" to route,
        "anchor" to anchor?.let {
            valueMapOf("image" to it, "bytes" to 4096L, "stride" to 32L)
        },
        "scratch" to scratch?.let {
            valueMapOf(
                "image" to it,
                "window" to 0L,
                "reserved_first" to if (inAnchor) 8L else null,
                "reserved_last" to if (inAnchor) 8L else null,
            )
        },
        "page_free" to pageFree,
        "conditioned" to conditioned,
        "anchor_name" to anchorName,
        "scratch_source" to scratchSource,
        "reason" to if (pageFree) "unconditional page-free plan" else "page",
    )

    private fun sample(): OptionSuperset {
        val document = CleanedProfileFixture.document(release)
        /* The pair's one home: the schema-shaped device map. */
        document["devices"] = valueMapOf(
            "duchamp" to valueMapOf(
                "phys" to valueMapOf("load" to "0x40000000", "offset" to "0x40000000"),
            ),
        )
        val kernel = document["kernel"].asValueMap()!!
        kernel["dirtyfrag"] = "feasible"
        val ghostlock = document["ghostlock"].asValueMap()!!
        ghostlock["syscalls"] = valueMapOf(
            "select_stack" to valueMapOf(
                "native" to valueMapOf("waiter_shift" to 1L),
                "compat" to valueMapOf(
                    "waiter_home" to 432L,
                    "dst_depth" to 480L,
                    "futex_abi" to "time32",
                ),
            ),
            "sigreturn" to null,
            "multicast_waiter" to null,
            "tcp_zerocopy" to valueMapOf(
                "native" to valueMapOf(),
                "compat" to null,
            ),
        )
        val plans = valueListOf(
            valueMapOf(
                "route" to "select_stack",
                "page" to plan("select_stack"),
                "anchor_only" to plan(
                    "select_stack", anchor = 35802320L,
                    anchorName = "__dump_skip.zeroes",
                ),
                "page_free" to valueListOf(
                    plan(
                        "select_stack", anchor = 35802320L, scratch = 35459072L,
                        pageFree = true, anchorName = "__dump_skip.zeroes",
                        scratchSource = "bm_pmd",
                    ),
                    plan(
                        "select_stack", anchor = 35459072L, scratch = 35459072L,
                        pageFree = true, anchorName = "bm_pmd",
                        scratchSource = "in-anchor", inAnchor = true,
                    ),
                    plan(
                        "select_stack", anchor = 35947744L, scratch = 35459072L,
                        pageFree = true, conditioned = true,
                        anchorName = "s3c24xx_serial_ports",
                        scratchSource = "bm_pmd",
                    ),
                ),
            ),
            valueMapOf(
                "route" to "tcp_zerocopy",
                "page" to plan("tcp_zerocopy"),
                "page_free" to valueListOf(),
            ),
        )
        val envelope = valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "kernel_major" to 6L,
            "routes" to valueListOf(
                valueMapOf(
                    "route" to "select_stack",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "pselect/futex geometry derived",
                    "anchor_capable" to true,
                    "geometry" to valueMapOf("compact_waiter" to 1L, "waiter_shift" to 1L),
                    "alternatives" to valueListOf(
                        valueMapOf(
                            "carrier" to "compat",
                            "verdict" to "feasible",
                            "reason" to "AArch32 compat carrier",
                            "geometry" to valueMapOf(
                                "compat_carrier" to 1L,
                                "waiter_home" to 432L,
                                "select_dst_depth" to 480L,
                                "compat_futex_abi" to 0L,
                            ),
                        ),
                    ),
                ),
                valueMapOf(
                    "route" to "tcp_zerocopy",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "cap 0x40",
                    "anchor_capable" to false,
                    "geometry" to valueMapOf("compact_waiter" to 1L),
                ),
            ),
            "document" to document,
            "device_analysis" to valueListOf(
                valueMapOf(
                    "device" to "duchamp",
                    "phys_source" to "vendor_boot DTB /memory",
                    "candidates" to valueListOf(),
                    "plans" to plans,
                ),
            ),
        )
        return requireNotNull(OptionSuperset.parse(HoconSupport.render(envelope)))
    }

    @Test
    fun `all options are exposed and the UI filters them`() {
        val superset = sample()
        assertEquals(release, superset.release)
        val plans = requireNotNull(superset.plansFor("select_stack"))
        assertEquals(2, plans.safeOptions.size)
        assertEquals(1, plans.conditionalOptions.size)
        assertEquals("__dump_skip.zeroes + bm_pmd", plans.safeOptions[0].label)
        assertEquals("bm_pmd", plans.safeOptions[1].label)
        assertNotNull(plans.anchorOnly)
        assertEquals("__dump_skip.zeroes", plans.anchorOnly?.anchorName)
        assertEquals(ScratchInAnchor, plans.safeOptions[1].scratchSource)
        assertFalse(plans.safeOptions[0].scratchSource == ScratchInAnchor)
        val tcp = requireNotNull(superset.plansFor("tcp_zerocopy"))
        assertTrue(tcp.safeOptions.isEmpty())
        assertNull(tcp.anchorOnly)
    }

    @Test
    fun `the display rule follows the operator decision`() {
        val superset = sample()
        val plans = requireNotNull(superset.plansFor("select_stack"))
        val decision = plans.decision()
        require(decision is OptionDecision.Offer)
        assertEquals(2, decision.options.size)
        assertFalse(decision.pageOffered)

        val single = plans.copy(pageFree = listOf(plans.safeOptions.first()))
        require(single.decision() is OptionDecision.Auto)

        val conditionalOnly = plans.copy(pageFree = listOf(plans.conditionalOptions.first()))
        val offer = conditionalOnly.decision()
        require(offer is OptionDecision.Offer)
        assertTrue(offer.pageOffered)
        assertEquals(1, offer.options.size)

        val none = plans.copy(pageFree = emptyList())
        assertTrue(none.decision() is OptionDecision.NoPageFree)
    }

    @Test
    fun `the anchor step reads a route that can use an anchor`() {
        val superset = sample()
        assertTrue(requireNotNull(superset.route("select_stack")).anchorCapable)
        assertFalse(requireNotNull(superset.route("tcp_zerocopy")).anchorCapable)
        /* The plan lists are route-independent, so a capable route's list is
         * the document's list even when the report named tcp first. */
        val plans = requireNotNull(superset.anchorPlans())
        assertEquals("select_stack", plans.route)
        assertEquals(2, plans.safeOptions.size)
        assertNotNull(plans.anchorOnly)
    }

    @Test
    fun `a report with no anchor-capable route offers no plan`() {
        /* tcp_zerocopy's placements are derived like every route's, but the
         * route cannot lock on a zeroed anchor: offering one would write an
         * anchor into a document that can never use it. */
        val superset = sample()
        val tcpOnly = superset.copy(
            routes = listOf(requireNotNull(superset.route("tcp_zerocopy"))),
        )
        assertNull(tcpOnly.anchorPlans())
    }

    @Test
    fun `withAnchor changes only the shared anchor block`() {
        val superset = sample()
        val plans = requireNotNull(superset.plansFor("select_stack"))
        val pageFree = requireNotNull(superset.withAnchor(plans.safeOptions[0]))
        /* Every route branch survives: the route is the run-time choice. */
        assertTrue(pageFree.contains("select_stack {"))
        assertTrue(pageFree.contains("tcp_zerocopy {"))
        assertTrue(pageFree.contains("sigreturn = null"))
        assertTrue(pageFree.contains("image = \"0x2224cd0\""))
        assertTrue(pageFree.contains("scratch = \"0x21d1000\""))
        /* Page mode clears the shared block and leaves everything else alone. */
        val page = requireNotNull(superset.withAnchor(plans.page))
        assertTrue(page.contains("anchor = null"))
        assertTrue(page.contains("tcp_zerocopy {"))
    }

    @Test
    fun `withAnchor needs a document`() {
        val superset = sample().copy(document = null)
        assertNull(superset.withAnchor(superset.plansFor("select_stack")!!.safeOptions[0]))
    }
}
