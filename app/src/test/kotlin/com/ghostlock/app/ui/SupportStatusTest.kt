package com.ghostlock.app.ui

import com.ghostlock.app.domain.model.ProfileLayers
import com.ghostlock.app.domain.model.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportStatusTest {
    private fun state(
        kernelSupported: Boolean = true,
        unreadable: Boolean = false,
        pairMissing: Boolean = false,
        hasProfile: Boolean = true,
        invalid: Set<String> = emptySet(),
        shizukuEnabled: Boolean = false,
        shizuku: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
        layers: ProfileLayers? = null,
    ) = GhostlockUiState(
        kernelSupported = kernelSupported,
        externalProfileUnreadable = unreadable,
        devicePairMissing = pairMissing,
        executionHasProfile = hasProfile,
        profileInvalidPaths = invalid,
        shizukuEnabled = shizukuEnabled,
        shizukuStatus = shizuku,
        profileLayers = layers,
    )

    @Test
    fun `all green is Ready`() {
        assertEquals(SupportStatus.Ready, supportStatusOf(state()))
    }

    @Test
    fun `an unreadable copy outranks every other fault`() {
        val status = supportStatusOf(
            state(unreadable = true, kernelSupported = false, pairMissing = true),
        )
        assertEquals(SupportStatus.CopyUnreadable, status)
    }

    @Test
    fun `a refused profile carries its reason`() {
        val status = supportStatusOf(
            state(layers = ProfileLayers(externalError = "the map does not name diting")),
        )
        assertEquals(SupportStatus.ProfileBlocked("the map does not name diting"), status)
    }

    @Test
    fun `an uncovered kernel is KernelUnsupported`() {
        assertEquals(
            SupportStatus.KernelUnsupported,
            supportStatusOf(state(kernelSupported = false)),
        )
    }

    @Test
    fun `a covered MTK kernel without a pair is PhysicalPairMissing`() {
        assertEquals(
            SupportStatus.PhysicalPairMissing,
            supportStatusOf(state(pairMissing = true)),
        )
    }

    @Test
    fun `invalid fields are reported before a missing profile`() {
        val status = supportStatusOf(state(invalid = setOf("offset.init_task")))
        assertEquals(SupportStatus.FieldsInvalid(setOf("offset.init_task")), status)
    }

    @Test
    fun `a covered kernel with no profile is ProfileMissing`() {
        assertEquals(
            SupportStatus.ProfileMissing,
            supportStatusOf(state(hasProfile = false)),
        )
    }

    @Test
    fun `Shizuku enabled but not ready is AccessPending`() {
        val status = supportStatusOf(
            state(shizukuEnabled = true, shizuku = ShizukuStatus.NOT_RUNNING),
        )
        assertTrue(status is SupportStatus.AccessPending)
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            (status as SupportStatus.AccessPending).status,
        )
    }

    @Test
    fun `Shizuku ready stays Ready`() {
        assertEquals(
            SupportStatus.Ready,
            supportStatusOf(state(shizukuEnabled = true, shizuku = ShizukuStatus.READY)),
        )
    }
}
