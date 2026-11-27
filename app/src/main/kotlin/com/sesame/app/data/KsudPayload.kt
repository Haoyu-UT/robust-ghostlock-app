package com.sesame.app.data

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A ksud payload's location: either a readable file, or a member inside a
 * manager APK (`<apk>!/lib/arm64-v8a/libksud.so`) when the APK does not
 * extract its native libs. Detection never extracts; the staging site copies
 * the bytes into its own work dir.
 */
object KsudPayload {
    private const val SEP = "!/"

    fun memberSpec(apk: String, member: String): String = apk + SEP + member

    fun open(spec: String): InputStream? {
        val at = spec.indexOf(SEP)
        if (at < 0) return runCatching { File(spec).inputStream() }.getOrNull()
        val apk = File(spec.substring(0, at))
        val member = spec.substring(at + SEP.length)
        return runCatching {
            val zip = ZipFile(apk)
            val entry = zip.getEntry(member)
            if (entry == null) {
                zip.close()
                null
            } else {
                object : FilterInputStream(zip.getInputStream(entry)) {
                    override fun close() {
                        runCatching { super.close() }
                        runCatching { zip.close() }
                    }
                }
            }
        }.getOrNull()
    }

    /** True when [spec] currently names readable bytes. */
    fun readable(spec: String): Boolean = open(spec)?.use { true } ?: false

    /** Copies the payload at [spec] into [dest]; false when it cannot be read. */
    fun copyTo(spec: String, dest: File): Boolean = runCatching {
        open(spec)?.use { input -> dest.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)
}
