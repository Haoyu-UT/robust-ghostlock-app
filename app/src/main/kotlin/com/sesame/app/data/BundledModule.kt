package com.sesame.app.data

import android.content.Context
import com.sesame.app.data.profile.ModuleConfig
import java.io.File
import java.security.MessageDigest

/**
 * Stages the module chain's bundled artifact: opens the `module` block's
 * `asset` from the APK, verifies `asset_sha256`, and writes it where the
 * DirtyFrag stage reads it. A missing asset or a hash mismatch returns null;
 * the pipeline resolver then refuses the chain before any delivery.
 */
object BundledModule {
    fun stage(context: Context, spec: ModuleConfig, destDir: File): File? {
        val bytes = runCatching {
            context.assets.open(spec.asset).use { it.readBytes() }
        }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(spec.assetSha256, ignoreCase = true)) return null
        if (!destDir.isDirectory && !destDir.mkdirs()) return null
        val out = File(destDir, File(spec.asset).name)
        return runCatching {
            out.writeBytes(bytes)
            out
        }.getOrNull()
    }
}
