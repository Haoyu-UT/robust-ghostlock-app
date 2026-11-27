package com.sesame.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S1's manifest gate: the handoff detector's whole-device scan relies on
 * QUERY_ALL_PACKAGES, and the two Magisk packages are declared in `<queries>`
 * so detection survives if that permission is ever removed.
 */
class HandoffManifestTest {
    private fun manifest(): String {
        val candidates = listOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File("").absolutePath}")
        return file.readText()
    }

    @Test
    fun `the whole-device package permission stays declared`() {
        assertTrue(
            manifest().contains(
                "<uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\" />",
            ),
        )
    }

    @Test
    fun `both Magisk packages are declared in queries`() {
        val text = manifest()
        assertTrue(text.contains("<package android:name=\"com.topjohnwu.magisk\" />"))
        assertTrue(text.contains("<package android:name=\"io.github.vvb2060.magisk\" />"))
    }

    @Test
    fun `the OEM app-list permission stays declared`() {
        assertTrue(
            manifest().contains(
                "<uses-permission android:name=\"com.android.permission.GET_INSTALLED_APPS\" />",
            ),
        )
    }

    @Test
    fun `the app-list opt-out meta-data is absent`() {
        /* Declaring do_not_need_get_installed_apps switches the gate off and
         * makes the permission unrequestable; it must never be added. The
         * manifest comment names it, so the assertion looks for the tag. */
        assertTrue(
            !manifest().contains(
                "<meta-data android:name=\"do_not_need_get_installed_apps\"",
            ),
        )
    }

    @Test
    fun `every probed manager package is declared in queries`() {
        val text = manifest()
        for (pkg in ManagerScanner.PROBE_PACKAGES + MagiskStage.PACKAGES) {
            assertTrue(
                "missing <queries> entry for $pkg",
                text.contains("<package android:name=\"$pkg\" />"),
            )
        }
    }
}
