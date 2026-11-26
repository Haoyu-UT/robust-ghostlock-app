package com.sesame.app.data

import android.app.Application
import com.sesame.app.data.profile.ModuleBlock
import com.sesame.app.data.profile.ModuleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The delivery patch over the real bundled assets: the profile's CRCs applied
 * to `__versions` by name, in place, with the same bytes for the same image
 * and a refusal on any name or value mismatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModulePatchTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val starRelease = "5.4.233-qgki-gbb70cde46897"
    private val venusRelease = "5.4.274-qgki-g76ac0b265f44"

    private fun profileSpec(release: String): ModuleConfig {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$release.conf"),
        ).asValueMap() ?: error("$release: unreadable profile")
        val block = (document["ghostlock"] as? Map<*, *>)?.get("module")
            ?: error("$release: module block missing")
        return ModuleBlock.parse(block).first ?: error("$release: invalid module block")
    }

    private fun asset(spec: ModuleConfig): ByteArray =
        context.assets.open(spec.asset).use { it.readBytes() }

    @Test
    fun `the same image's crcs are a no-op`() {
        val spec = profileSpec(starRelease)
        val bytes = asset(spec)
        val result = ModulePatch.apply(bytes, spec.crcs)
        assertTrue("expected a successful patch: $result", result is ModulePatch.Result.Ok)
        result as ModulePatch.Result.Ok
        assertFalse("the shipped values already match", result.patched)
        assertEquals(0x4e974a74L, result.assetLayout)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test
    fun `another image's crcs change only the versions entries`() {
        val star = profileSpec(starRelease)
        val venus = profileSpec(venusRelease)
        val bytes = asset(star)
        val result = ModulePatch.apply(bytes, venus.crcs)
        assertTrue("expected a successful patch: $result", result is ModulePatch.Result.Ok)
        result as ModulePatch.Result.Ok
        assertTrue("venus differs in module_layout", result.patched)
        assertEquals(0x4e974a74L, result.assetLayout)
        assertEquals(bytes.size, result.bytes.size)

        val entries = ModulePatch.parse(result.bytes)
        assertEquals(0x894a26aeL, entries.first { it.name == "module_layout" }.crc)
        assertEquals(0xc5850110L, entries.first { it.name == "printk" }.crc)

        val changed = bytes.indices.filter { bytes[it] != result.bytes[it] }
        assertEquals("only module_layout's four bytes differ", 4, changed.size)
        assertTrue(
            "a changed byte outside a version entry",
            changed.all { i -> entries.any { i in it.offset until it.offset + 8 } },
        )
    }

    @Test
    fun `the name set must match the asset exactly`() {
        val spec = profileSpec(starRelease)
        val bytes = asset(spec)
        assertTrue(ModulePatch.apply(bytes, spec.crcs - "printk") is ModulePatch.Result.Failed)
        assertTrue(
            ModulePatch.apply(bytes, spec.crcs + ("extra" to 0x1L)) is ModulePatch.Result.Failed,
        )
        assertTrue(
            ModulePatch.apply(bytes, spec.crcs - "module_layout") is ModulePatch.Result.Failed,
        )
    }

    @Test
    fun `values are non-zero and 32-bit`() {
        val spec = profileSpec(starRelease)
        val bytes = asset(spec)
        assertTrue(
            ModulePatch.apply(bytes, spec.crcs + ("printk" to 0L)) is ModulePatch.Result.Failed,
        )
        assertTrue(
            ModulePatch.apply(bytes, spec.crcs + ("printk" to 0x1_0000_0000L))
                is ModulePatch.Result.Failed,
        )
    }

    @Test
    fun `the patch is idempotent`() {
        val star = profileSpec(starRelease)
        val venus = profileSpec(venusRelease)
        val once = ModulePatch.apply(asset(star), venus.crcs) as ModulePatch.Result.Ok
        val twice = ModulePatch.apply(once.bytes, venus.crcs) as ModulePatch.Result.Ok
        assertFalse("the second application changes nothing", twice.patched)
        assertArrayEquals(once.bytes, twice.bytes)
    }

    @Test
    fun `malformed input is refused`() {
        assertTrue(ModulePatch.apply(ByteArray(0), mapOf("module_layout" to 1L)) is ModulePatch.Result.Failed)
        val bytes = asset(profileSpec(starRelease))
        bytes[0] = 0
        assertTrue(ModulePatch.apply(bytes, profileSpec(starRelease).crcs) is ModulePatch.Result.Failed)
    }
}
