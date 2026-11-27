package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The static detector and KMI rule. The parser tests pin KSU's own semantics
 * (`boot_patch.rs::parse_kmi`): the regex `(.* )?(\d+\.\d+)(\S+)?(android\d+)(.*)`,
 * leftmost match, result `{android}-{major.minor}` — and the vermagic fallback
 * feeds it the line `modinfo` would print.
 */
class ManagerScannerTest {
    private fun candidate(
        pkg: String,
        kmis: List<String> = emptyList(),
        supported: Boolean = true,
        late: Boolean = true,
        allow: Boolean = true,
        builtin: Boolean = false,
    ) = ManagerScanner.Candidate(pkg, pkg, "/x/$pkg", builtin, late, allow, supported, kmis)

    private fun modulesWith(vermagic: String): File {
        val dir = createTempDirectory("modules").toFile()
        File(dir, "vendor.ko").writeBytes(
            "abc\u0000vermagic=$vermagic\u0000def\u0000".toByteArray(),
        )
        return dir
    }

    @Test
    fun `suitability needs the tokens and one manager-owned fact`() {
        // Tokens + the supported-kmis interface (no assets).
        assertTrue(candidate("a").suitable)
        // Tokens + a KMI asset list (no supported-kmis string).
        assertTrue(candidate("a", kmis = listOf("android12-5.10"), supported = false).suitable)
        // Our own embedded handoff script carries only the tokens.
        assertFalse(candidate("a", supported = false).suitable)
        assertFalse(candidate("a", allow = false).suitable)
        assertFalse(candidate("a", late = false).suitable)
    }

    @Test
    fun `resolve prefers the selected suitable package`() {
        val list = listOf(candidate("a"), candidate("b"))
        assertEquals("b", ManagerScanner.resolve(list, "b")?.packageName)
    }

    @Test
    fun `resolve falls back to the first suitable`() {
        val list = listOf(candidate("a", late = false), candidate("b"))
        assertEquals("b", ManagerScanner.resolve(list, "a")?.packageName)
    }

    @Test
    fun `resolve returns null when nothing is suitable`() {
        assertNull(ManagerScanner.resolve(listOf(candidate("a", late = false)), null))
    }

    @Test
    fun `parse matches KSU on the three rig releases`() {
        assertEquals(
            "android12-5.10",
            ManagerScanner.parseKmi("5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"),
        )
        assertEquals(
            "android14-6.1",
            ManagerScanner.parseKmi("6.1.138-android14-11-g44bda9e8f6e9-ab13792638"),
        )
        assertEquals(
            "android15-6.6",
            ManagerScanner.parseKmi("6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k"),
        )
    }

    @Test
    fun `parse handles the vermagic line modinfo prints`() {
        assertEquals(
            "android14-6.1",
            ManagerScanner.parseKmi(
                "vermagic: 6.1.134-android14-11-o-gd252f36df142 " +
                    "SMP preempt mod_unload modversions aarch64",
            ),
        )
        assertEquals(
            "android12-5.10",
            ManagerScanner.parseKmi(
                "vermagic: 5.10.236-android12-9-00003-gfb24cf99ad97 " +
                    "SMP preempt mod_unload modversions aarch64",
            ),
        )
    }

    @Test
    fun `parse refuses what KSU refuses`() {
        assertNull(ManagerScanner.parseKmi("5.4.233-qgki-gbb70cde46897"))
        assertNull(ManagerScanner.parseKmi("custom"))
        assertNull(ManagerScanner.parseKmi(""))
    }

    @Test
    fun `current kmi prefers uname and names its source`() {
        val answer = ManagerScanner.currentKmi(
            "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284",
            modulesWith("5.10.236-android12-9-00003-gfb24cf99ad97 SMP preempt aarch64"),
        )
        assertEquals("android12-5.10", answer.value)
        assertEquals("uname", answer.source)
        assertNull(answer.failure)
    }

    @Test
    fun `current kmi falls back to the vendor module vermagic`() {
        val answer = ManagerScanner.currentKmi(
            "5.4.233-qgki-gbb70cde46897",
            modulesWith("5.10.236-android12-9-00003-gfb24cf99ad97 SMP preempt aarch64"),
        )
        assertEquals("android12-5.10", answer.value)
        assertEquals("vermagic", answer.source)
    }

    @Test
    fun `current kmi fails with KSU's own message when neither branch answers`() {
        val answer = ManagerScanner.currentKmi(
            "5.4.233-qgki-gbb70cde46897",
            modulesWith("5.4.233-qgki-gbb70cde46897 SMP preempt mod_unload modversions aarch64"),
        )
        assertNull(answer.value)
        assertEquals("none", answer.source)
        assertEquals("Failed to get KMI from boot/modules", answer.failure)
    }

    @Test
    fun `the vermagic reader takes the modinfo value, not the rest of the file`() {
        val dir = modulesWith("6.6.66-android15-8-g29d86c5fc9dd-4k SMP preempt aarch64")
        assertEquals(
            "6.6.66-android15-8-g29d86c5fc9dd-4k SMP preempt aarch64",
            ManagerScanner.vermagicOf(File(dir, "vendor.ko")),
        )
    }
}
