package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffResolution
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.HandoffVerdict
import com.sesame.app.domain.model.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffWireTest {
    private fun ksu(pkg: String, path: String = "/x/$pkg/libksud.so") =
        RootManager.Ksu(pkg, pkg, path, kmis = emptyList(), suitable = true)

    @Test
    fun `a ksu entry carries package origin and kmi`() {
        val line = HandoffWire.encode(ksu("com.resukisu.resukisu"), "android12-5.10")
        assertEquals("ksu|com.resukisu.resukisu|/x/com.resukisu.resukisu/libksud.so|android12-5.10", line)

        val decoded = HandoffWire.decode(line)!!
        assertEquals(HandoffKind.KSU, decoded.manager.kind)
        assertEquals("com.resukisu.resukisu", decoded.manager.packageName)
        assertEquals("/x/com.resukisu.resukisu/libksud.so", (decoded.manager as RootManager.Ksu).ksudPath)
        assertEquals("android12-5.10", decoded.kmi)
    }

    @Test
    fun `a magisk entry has no kmi`() {
        val manager = RootManager.Magisk("com.topjohnwu.magisk", "Magisk", "/x/lib", "", emptySet())
        val line = HandoffWire.encode(manager, null)
        assertEquals("magisk|com.topjohnwu.magisk|/x/lib|", line)
        val decoded = HandoffWire.decode(line)!!
        assertEquals(HandoffKind.MAGISK, decoded.manager.kind)
        assertNull(decoded.kmi)
        assertEquals("/x/lib", (decoded.manager as RootManager.Magisk).nativeLibDir)
    }

    @Test
    fun `malformed entries are dropped`() {
        assertNull(HandoffWire.decode(""))
        assertNull(HandoffWire.decode("ksu|pkg"))
        assertNull(HandoffWire.decode("other|pkg|/x|"))
        assertNull(HandoffWire.decode("ksu||/x|"))
        assertEquals(1, HandoffWire.decodeAll(listOf("ksu|p|/x|", "junk")).size)
    }

    @Test
    fun `encode of a plan keeps the attempt order`() {
        val verdict = { pkg: String ->
            HandoffVerdict(ksu(pkg), HandoffState.FEASIBLE, "ok")
        }
        val plan = HandoffResolution(
            verdicts = listOf(verdict("b"), verdict("a")),
            feasible = listOf(verdict("b"), verdict("a")),
        )
        val lines = HandoffWire.encode(plan)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("ksu|b|"))
        assertTrue(lines[1].startsWith("ksu|a|"))
    }
}
