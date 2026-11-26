package com.sesame.app.data

import android.content.Context
import com.sesame.app.data.profile.ModuleConfig
import java.io.File
import java.security.MessageDigest

/**
 * Stages the module chain's bundled artifact: opens the `module` block's
 * `asset` from the APK, verifies `asset_sha256`, applies the profile's
 * per-image CRCs to the asset's `__versions` ([ModulePatch]), and writes the
 * result where the DirtyFrag stage reads it.
 *
 * The canonical asset stays in the APK untouched; every run patches a copy.
 * A missing asset, a hash mismatch, or a patch refusal returns [StageResult.Failed]
 * with the reason, and the pipeline resolver refuses the chain before delivery.
 */
object BundledModule {
    data class Staged(
        val file: File,
        /** The APK asset's hash — the value the profile's `sha256` must match. */
        val canonicalSha256: String,
        /** The patched bytes actually delivered. */
        val deliveredSha256: String,
        val patched: Boolean,
        /** The asset's build-time `module_layout` CRC, for the layout log. */
        val assetLayout: Long,
        /** The target Image's `module_layout` the profile applies. */
        val profileLayout: Long,
    )

    sealed interface StageResult {
        data class Ok(val staged: Staged) : StageResult
        data class Failed(val reason: String) : StageResult
    }

    /** True when the asset opens and its bytes match the block's hash. */
    fun verify(context: Context, spec: ModuleConfig): Boolean {
        val bytes = readAsset(context, spec.asset) ?: return false
        return sha256(bytes).equals(spec.assetSha256, ignoreCase = true)
    }

    fun stage(
        context: Context,
        spec: ModuleConfig,
        release: String,
        destDir: File,
    ): StageResult {
        val bytes = readAsset(context, spec.asset)
            ?: return StageResult.Failed("module asset ${spec.asset} is missing")
        val canonical = sha256(bytes)
        if (!canonical.equals(spec.assetSha256, ignoreCase = true)) {
            return StageResult.Failed("module asset_sha256 mismatch")
        }
        val patched = when (val result = ModulePatch.apply(bytes, spec.crcs)) {
            is ModulePatch.Result.Failed -> return StageResult.Failed("module patch: ${result.reason}")
            is ModulePatch.Result.Ok -> result
        }
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            return StageResult.Failed("cannot create ${destDir.absolutePath}")
        }
        /* The staged name is release-qualified so triage can tell which
         * profile produced the file; the module's kernel-visible name comes
         * from `.modinfo`, not from the path. */
        val out = File(destDir, "dfswitch-$release.ko")
        return runCatching {
            out.writeBytes(patched.bytes)
            StageResult.Ok(
                Staged(
                    file = out,
                    canonicalSha256 = canonical,
                    deliveredSha256 = sha256(patched.bytes),
                    patched = patched.patched,
                    assetLayout = patched.assetLayout,
                    profileLayout = spec.crcs.getValue("module_layout"),
                ),
            )
        }.getOrElse { StageResult.Failed("cannot write ${out.name}: ${it.message}") }
    }

    private fun readAsset(context: Context, asset: String): ByteArray? = runCatching {
        context.assets.open(asset).use { it.readBytes() }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
