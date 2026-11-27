package com.sesame.app.data

import android.content.Context
import android.content.pm.PackageInfo
import com.sesame.app.domain.model.RootManager
import java.io.File
import java.util.zip.ZipFile

/**
 * Locates an installed Magisk manager and stages the binaries the root script
 * copies for the late install (the G2a recipe). The manager APK is the source
 * of truth: the extracted native directory is used when present, the APK's
 * `lib/arm64-v8a` members otherwise, and `magisk32` always comes from the
 * APK's `lib/armeabi-v7a` member (a 64-bit app never sees that directory).
 *
 * The staged directory is inside the app's files dir; the DirtyFrag root
 * script runs as the init shell and can read it (the same directory already
 * receives the script's own log).
 *
 * Detection ([detect]) is separate from staging ([stage]): staging deletes
 * and rewrites its destination, so it belongs to the selected manager only.
 */
object MagiskStage {
    /** The official manager first, then the common alpha fork. */
    val PACKAGES = listOf("com.topjohnwu.magisk", "io.github.vvb2060.magisk")

    private val NATIVE = listOf(
        "magisk" to "libmagisk.so",
        "magiskinit" to "libmagiskinit.so",
        "magiskpolicy" to "libmagiskpolicy.so",
        "magiskboot" to "libmagiskboot.so",
        "busybox" to "libbusybox.so",
        "bootctl" to "libbootctl.so",
        "init-ld" to "libinit-ld.so",
    )
    private val REQUIRED = setOf("magisk", "magiskinit", "magiskpolicy")

    data class Staged(val packageName: String, val dir: File)

    /**
     * Every installed manager of the supported packages, in [PACKAGES] order.
     * A row is emitted for each installed package; [RootManager.Magisk.missing]
     * names the required libs neither the native dir nor the APK carries.
     * Read-only: nothing is extracted or written here.
     */
    fun detect(context: Context): List<RootManager.Magisk> {
        val pm = context.packageManager
        val found = ArrayList<RootManager.Magisk>(PACKAGES.size)
        for (pkg in PACKAGES) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            val app = info.applicationInfo ?: continue
            val nativeDir = app.nativeLibraryDir
            val apk = File(app.sourceDir)
            val missing = REQUIRED.filter { name ->
                val lib = nativeLib(name)
                val extracted = nativeDir?.let { File(it, lib).isFile } ?: false
                !extracted && !apkHas(apk, "lib/arm64-v8a/$lib")
            }.toSet()
            found += RootManager.Magisk(
                packageName = pkg,
                label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(pkg),
                nativeLibDir = nativeDir,
                apkPath = apk.absolutePath,
                missing = missing,
            )
        }
        return found
    }

    /** The installed manager whose binaries [stage] would copy, or null. */
    fun installed(context: Context): String? = managerInfo(context)?.packageName

    private fun managerInfo(context: Context): PackageInfo? = managerInfo(context, null)

    /**
     * [only] null keeps today's rule (the first installed supported package);
     * a package name stages that manager, and null when it is not installed.
     */
    private fun managerInfo(context: Context, only: String?): PackageInfo? {
        val pm = context.packageManager
        return (if (only != null) listOf(only) else PACKAGES).asSequence()
            .mapNotNull { runCatching { pm.getPackageInfo(it, 0) }.getOrNull() }
            .firstOrNull()
    }

    /** The manager package whose binaries were staged, or null when none is usable. */
    fun stage(context: Context, dest: File, packageName: String? = null): Staged? {
        val info = managerInfo(context, packageName) ?: return null
        val app = info.applicationInfo ?: return null
        val apk = File(app.sourceDir)
        dest.deleteRecursively()
        if (!dest.isDirectory && !dest.mkdirs()) return null
        val libDir = app.nativeLibraryDir?.let(::File)
        val staged = mutableSetOf<String>()
        for ((name, lib) in NATIVE) {
            val source = libDir?.let { File(it, lib) }?.takeIf { it.isFile }
            val ok = if (source != null) {
                runCatching { source.copyTo(File(dest, name), overwrite = true) }.isSuccess
            } else {
                unzipMember(apk, "lib/arm64-v8a/$lib", File(dest, name))
            }
            if (ok) {
                File(dest, name).setExecutable(true)
                staged += name
            }
        }
        /* The 32-bit daemon half; the root script skips a missing file. */
        if (unzipMember(apk, "lib/armeabi-v7a/libmagisk.so", File(dest, "magisk32"))) {
            File(dest, "magisk32").setExecutable(true)
        }
        if (!staged.containsAll(REQUIRED)) return null
        return Staged(info.packageName, dest)
    }

    /** The APK member name staging would read for a required binary. */
    private fun nativeLib(name: String): String =
        NATIVE.firstOrNull { it.first == name }?.second ?: "lib$name.so"

    private fun apkHas(apk: File, member: String): Boolean = runCatching {
        ZipFile(apk).use { zip -> zip.getEntry(member) != null }
    }.getOrDefault(false)

    private fun unzipMember(apk: File, member: String, out: File): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(member) ?: return@use false
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { sink -> input.copyTo(sink) }
            }
            true
        }
    }.getOrDefault(false)
}
