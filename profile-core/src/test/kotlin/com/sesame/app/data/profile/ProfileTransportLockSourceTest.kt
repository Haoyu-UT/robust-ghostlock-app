package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The run's lock/W1 delivery is applied to the rendered copy only: page-free
 * leaves the document as generated, anchor+page clears the scratch, page clears
 * the anchor. The resolved document is never rewritten.
 */
class ProfileTransportLockSourceTest {
    private fun document(): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284",
        "ghostlock" to valueMapOf(
            "syscall" to "select_stack",
            "anchor" to valueMapOf(
                "image" to "0x2a3a590",
                "bytes" to 4096L,
                "stride" to 32L,
                "scratch" to "0x2a3a600",
            ),
        ),
    )

    private fun anchor(lockSource: String?): ValueMap? =
        ProfileTransport
            .document(document(), lockSource = lockSource)["ghostlock"]
            .asValueMap()
            ?.get("anchor")
            .asValueMap()

    @Test
    fun `page-free and no override keep the anchor and scratch`() {
        val pageFree = anchor(LockSources.PageFree)!!
        assertEquals("0x2a3a590", pageFree["image"])
        assertEquals("0x2a3a600", pageFree["scratch"])
        assertEquals("0x2a3a600", anchor(null)!!["scratch"])
    }

    @Test
    fun `anchor plus page clears only the scratch`() {
        val anchor = anchor(LockSources.AnchorPage)!!
        assertEquals("0x2a3a590", anchor["image"])
        assertNull(anchor["scratch"])
    }

    @Test
    fun `page clears the whole anchor block`() {
        assertNull(anchor(LockSources.Page))
    }

    @Test
    fun `the resolved document is never modified`() {
        val document = document()
        ProfileTransport.document(document, lockSource = LockSources.Page)
        val anchor = (document["ghostlock"] as Map<*, *>)["anchor"].asValueMap()!!
        assertEquals("0x2a3a600", anchor["scratch"])
    }
}
