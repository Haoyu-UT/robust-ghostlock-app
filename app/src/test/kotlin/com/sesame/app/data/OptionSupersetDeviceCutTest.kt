package com.sesame.app.data

import com.sesame.app.data.profile.PerDevicePhys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The on-device extractor records one full derivation per device sidecar set.
 * The cut selects the running device's entry and emits its pair; the document
 * itself is device-neutral until the cut states one.
 */
class OptionSupersetDeviceCutTest {
    private val release = "5.10.198-android12-9-00085-g226a9632f13d-ab11136126"

    private fun superset(): OptionSuperset {
        val document = CleanedProfileFixture.document(release)
        val ghostlock = document["ghostlock"].asValueMap()!!
        ghostlock["syscalls"] = valueMapOf(
            "select_stack" to valueMapOf(
                "native" to valueMapOf("waiter_shift" to -2L),
                "compat" to null,
            ),
            "sigreturn" to null,
            "multicast_waiter" to null,
            "tcp_zerocopy" to null,
        )
        val envelope = valueMapOf(
            "release" to release,
            "kernel_major" to 5L,
            "kernel_phys_load" to 2818572288L,
            "kernel_phys_offset" to null,
            "phys_source" to "uefi memory map",
            "routes" to valueListOf(
                valueMapOf(
                    "route" to "select_stack",
                    "carrier" to "native",
                    "verdict" to "feasible",
                    "reason" to "",
                    "anchor_capable" to true,
                    "geometry" to valueMapOf("waiter_shift" to -2L),
                ),
            ),
            "plans" to valueListOf(
                valueMapOf(
                    "route" to "select_stack",
                    "page" to valueMapOf(
                        "route" to "select_stack",
                        "anchor" to null,
                        "scratch" to null,
                        "page_free" to false,
                        "conditioned" to false,
                        "reason" to "page",
                    ),
                    "page_free" to valueListOf(),
                ),
            ),
            "document" to document,
            "devices" to valueListOf(
                valueMapOf(
                    "device" to "diting",
                    "kernel_phys_load" to 2818572288L,
                    "kernel_phys_offset" to null,
                    "phys_source" to "uefi memory map",
                ),
                valueMapOf(
                    "device" to "yunluo",
                    "kernel_phys_load" to 1073741824L,
                    "kernel_phys_offset" to 1073741824L,
                    "phys_source" to "vendor_boot header (MediaTek)",
                ),
            ),
        )
        return requireNotNull(OptionSuperset.parse(HoconSupport.render(envelope)))
    }

    private fun cutDocument(text: String): ValueMap =
        HoconSupport.parseValue(text).asValueMap() ?: error("cut is not an object")

    private fun pair(document: ValueMap, device: String): Pair<Long?, Long?> =
        com.sesame.app.data.profile.ProfileResolver.physOf(
            PerDevicePhys.resolveFor(document, listOf(device)).document,
        )

    @Test
    fun `the cut selects the device pair and emits a v3 map`() {
        val superset = superset()
        assertEquals("diting", superset.device)
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page)!!)
        assertEquals(3, (document["schema_version"] as Number).toInt())
        assertEquals(2818572288L to null, pair(document, "diting"))
    }

    @Test
    fun `the cut can select another device`() {
        val superset = superset()
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page, "yunluo")!!)
        assertEquals(1073741824L to 1073741824L, pair(document, "yunluo"))
    }

    @Test
    fun `a superset without devices carries no pair`() {
        /* The cleaned document has no global physical pair: it is a per-device
         * entry. A cut with no device list keeps the native defaults, and a
         * platform that needs the pair refuses it at import (fail-closed). */
        val superset = superset().copy(devices = emptyList())
        val plans = superset.plansFor("select_stack") ?: error("no plans")
        val document = cutDocument(superset.cut("select_stack", plans.page)!!)
        assertEquals(3, (document["schema_version"] as Number).toInt())
        assertEquals(null to null,
            com.sesame.app.data.profile.ProfileResolver.physOf(document))
    }
}
