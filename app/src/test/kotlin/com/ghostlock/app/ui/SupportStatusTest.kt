package com.ghostlock.app.ui

import com.ghostlock.app.domain.model.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportStatusTest {
    private fun state(
        hasProfile: Boolean = true,
        shizukuEnabled: Boolean = false,
        shizuku: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
    ) = GhostlockUiState(
        executionHasProfile = hasProfile,
        shizukuEnabled = shizukuEnabled,
        shizukuStatus = shizuku,
    )

    @Test
    fun `a resolved profile is Ready`() {
        assertEquals(SupportStatus.Ready, supportStatusOf(state()))
    }

    @Test
    fun `nothing loaded is Missing`() {
        assertEquals(SupportStatus.Missing, supportStatusOf(state(hasProfile = false)))
    }

    @Test
    fun `Shizuku enabled but not ready is AccessPending`() {
        val status = supportStatusOf(
            state(shizukuEnabled = true, shizuku = ShizukuStatus.NOT_RUNNING),
        )
        assertEquals(SupportStatus.AccessPending(ShizukuStatus.NOT_RUNNING), status)
    }

    @Test
    fun `Shizuku ready stays Ready`() {
        assertEquals(
            SupportStatus.Ready,
            supportStatusOf(state(shizukuEnabled = true, shizuku = ShizukuStatus.READY)),
        )
    }

    @Test
    fun `no profile outranks Shizuku`() {
        assertEquals(
            SupportStatus.Missing,
            supportStatusOf(
                state(hasProfile = false, shizukuEnabled = true, shizuku = ShizukuStatus.NOT_RUNNING),
            ),
        )
    }
}
