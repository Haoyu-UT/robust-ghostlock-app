package com.sesame.app.data

import android.app.Application
import com.sesame.app.data.profile.ModuleBlock
import com.sesame.app.data.profile.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledModuleTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private val starRelease = "5.4.233-qgki-gbb70cde46897"

    private fun spec(release: String = starRelease): ModuleConfig {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$release.conf"),
        ).asValueMap() ?: error("$release: unreadable profile")
        val block = (document["ghostlock"] as? Map<*, *>)?.get("module")
            ?: error("$release: module block missing")
        val (config, errors) = ModuleBlock.parse(block)
        if (config == null || errors.isNotEmpty()) {
            error("$release: module block invalid: ${errors.map { it.fieldPath }}")
        }
        return config.copy(vendorFiles = emptyList())
    }

    @Test
    fun `the bundled pair stages after the hash check`() {
        val dest = Files.createTempDirectory("module-stage").toFile()
        try {
            /* The home's readiness card uses verify without writing anything. */
            assertTrue("the bundled pair must verify", BundledModule.verify(context, spec()))
            assertFalse(
                "a wrong hash must not verify",
                BundledModule.verify(context, spec().copy(assetSha256 = "0".repeat(64))),
            )
            assertFalse(
                "a missing asset must not verify",
                BundledModule.verify(context, spec().copy(asset = "modules/does-not-exist.ko")),
            )

            val staged = BundledModule.stage(context, spec(), starRelease, dest)
            assertTrue(
                "the bundled asset did not stage: $staged",
                staged is BundledModule.StageResult.Ok,
            )
            staged as BundledModule.StageResult.Ok
            assertEquals(10_528L, staged.staged.file.length())
            assertEquals("dfswitch-$starRelease.ko", staged.staged.file.name)
            assertFalse("the shipped CRCs equal the asset's", staged.staged.patched)
            assertEquals(0x4e974a74L, staged.staged.profileLayout)
            assertEquals(0x4e974a74L, staged.staged.assetLayout)
            assertEquals(64, staged.staged.deliveredSha256.length)

            val badHash = BundledModule.stage(
                context,
                spec().copy(assetSha256 = "0".repeat(64)),
                starRelease,
                dest,
            )
            assertTrue(
                "a wrong hash must not stage",
                badHash is BundledModule.StageResult.Failed,
            )

            val missing = BundledModule.stage(
                context,
                spec().copy(asset = "modules/does-not-exist.ko"),
                starRelease,
                dest,
            )
            assertTrue(
                "a missing asset must not stage",
                missing is BundledModule.StageResult.Failed,
            )
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun `both module pairs stage`() {
        val dest = Files.createTempDirectory("module-stage-both").toFile()
        try {
            for (release in listOf(
                "5.4.233-qgki-gbb70cde46897",
                "5.4.274-qgki-g76ac0b265f44",
            )) {
                val staged = BundledModule.stage(context, spec(release), release, dest)
                assertTrue(
                    "$release did not stage: $staged",
                    staged is BundledModule.StageResult.Ok,
                )
                staged as BundledModule.StageResult.Ok
                assertEquals("$release size", 10_528L, staged.staged.file.length())
                assertEquals("dfswitch-$release.ko", staged.staged.file.name)
            }
        } finally {
            dest.deleteRecursively()
        }
    }
}
