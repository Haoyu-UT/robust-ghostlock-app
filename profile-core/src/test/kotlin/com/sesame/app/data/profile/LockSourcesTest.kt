package com.sesame.app.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSourcesTest {
    @Test
    fun `the placements are offered best first, as far as the document supports`() {
        assertEquals(
            listOf(LockSources.PageFree, LockSources.AnchorPage, LockSources.Page),
            LockSources.options(anchorPresent = true, scratchPresent = true),
        )
        assertEquals(
            listOf(LockSources.AnchorPage, LockSources.Page),
            LockSources.options(anchorPresent = true, scratchPresent = false),
        )
        assertEquals(
            listOf(LockSources.Page),
            LockSources.options(anchorPresent = false, scratchPresent = false),
        )
        assertEquals(3, LockSources.All.size)
    }

    @Test
    fun `only the anchored native routes are capable`() {
        assertTrue(LockSources.routeCapable("select_stack"))
        assertTrue(LockSources.routeCapable("sigreturn"))
        assertTrue(LockSources.routeCapable("multicast_waiter"))
        assertFalse(LockSources.routeCapable("tcp_zerocopy"))
        assertFalse(LockSources.routeCapable(null))
        assertFalse(LockSources.routeCapable("timer_98260"))
    }
}
