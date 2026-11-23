package com.sesame.app.ui

import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.ui.state.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportStatusTest {
    private fun state(
        hasProfile: Boolean = true,
        shizukuEnabled: Boolean = false,
        shizuku: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
        shizukuInstalled: Boolean = true,
    ) = SessionState(
        support = SessionState.SupportState(hasProfile = hasProfile),
        access = SessionState.AccessState(
            shizukuEnabled = shizukuEnabled,
            shizukuStatus = shizuku,
            shizukuInstalled = shizukuInstalled,
        ),
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
        assertEquals(SupportStatus.AccessPending(ShizukuStatus.NOT_RUNNING, installed = true), status)
    }

    @Test
    fun `AccessPending carries a missing manager`() {
        val status = supportStatusOf(
            state(
                shizukuEnabled = true,
                shizuku = ShizukuStatus.NOT_RUNNING,
                shizukuInstalled = false,
            ),
        )
        assertEquals(SupportStatus.AccessPending(ShizukuStatus.NOT_RUNNING, installed = false), status)
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
