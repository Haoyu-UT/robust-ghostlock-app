package com.sesame.app.data

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the platform-facing scan(): package enumeration, the self/system
 * filters, the executable-shape prefilter, the token scan, and the static KMI
 * asset-name list (no exec anywhere — the preflight never runs a manager
 * binary).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerScannerScanTest {
    private lateinit var app: Application
    private lateinit var pm: PackageManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as Application
        pm = app.packageManager
    }

    private fun fakeKsudElf(assets: Boolean = true, supportedKmis: Boolean = false): ByteArray {
        val bytes = ByteArray(4096)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2                     // ELFCLASS64
        bytes[5] = 1                     // ELFDATA2LSB
        bytes[6] = 1                     // EV_CURRENT
        put16(bytes, 16, 3)              // ET_DYN
        put16(bytes, 18, 183)            // EM_AARCH64
        put64(bytes, 32, 64)             // e_phoff
        put16(bytes, 54, 56)             // e_phentsize
        put16(bytes, 56, 1)              // e_phnum
        put32(bytes, 64, 3)              // PT_INTERP
        val text = buildString {
            append("late-load\u0000--allow-shell\u0000kernelsu.ko\u0000KernelSU\u0000")
            if (supportedKmis) append("supported-kmis\u0000")
            if (assets) {
                // The asset names sit in one blob, concatenated with no separator
                // (the shipped binaries do exactly this); the leading X also
                // breaks a whole-word walk, as a preceding string does.
                append("Xandroid12-5.10_kernelsu.koandroid12-5.10_susfs_guard_lkm.ko")
                append("android15-6.6_kernelsu.ko\u0000")
            }
            append("(.* )?(\\d+\\.\\d+)(\\S+)?(android\\d+)(.*)\u0000")
        }
        text.toByteArray().copyInto(bytes, 200)
        return bytes
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
        }
    }

    private fun put64(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
        }
    }

    private fun install(pkg: String, system: Boolean, ksud: Boolean, elf: ByteArray = fakeKsudElf()) {
        val dir = File(app.cacheDir, "libs-$pkg-${System.nanoTime()}").apply { mkdirs() }
        if (ksud) File(dir, "libksud.so").writeBytes(elf)
        val info = ApplicationInfo().apply {
            packageName = pkg
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            nativeLibraryDir = dir.absolutePath
        }
        shadowOf(pm).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = info
            },
        )
    }

    @Test
    fun `scan keeps a user-installed ksud candidate and drops self and system apps`() {
        install("com.example.manager", system = false, ksud = true)
        install("com.sesame.app", system = false, ksud = true)
        install("com.android.settings", system = true, ksud = true)

        val result = ManagerScanner.scan(app).candidates

        assertEquals(listOf("com.example.manager"), result.map { it.packageName })
        val candidate = result.single()
        assertTrue(candidate.lateLoadToken)
        assertTrue(candidate.allowShellToken)
        assertTrue(candidate.suitable)
        /* The asset names KSU's own list is built from — the susfs guard and the
         * bare parser regex must not enter it. */
        assertEquals(listOf("android12-5.10", "android15-6.6"), candidate.kmis)
    }

    @Test
    fun `scan includes a builtin manager installed as a system app`() {
        install("me.weishu.kernelsu", system = true, ksud = true)
        val result = ManagerScanner.scan(app).candidates
        assertEquals(listOf("me.weishu.kernelsu"), result.map { it.packageName })
        assertTrue(result.single().builtin)
    }

    @Test
    fun `scan admits a manager that answers supported-kmis but carries no assets`() {
        install("com.example.thin", system = false, ksud = true, elf = fakeKsudElf(assets = false, supportedKmis = true))
        val candidate = ManagerScanner.scan(app).candidates.single()
        assertTrue(candidate.suitable)
        assertTrue(candidate.kmis.isEmpty())
        assertEquals("static late-load candidate, no KMI assets", candidate.verdict)
    }

    @Test
    fun `scan rejects the exploit native's script tokens as a manager`() {
        /* Our own embedded handoff script carries `late-load`, `--allow-shell`
         * and `kernelsu.ko`, but no KMI assets and no `supported-kmis`: it is a
         * row, never a choice. */
        install("com.example.exploit", system = false, ksud = true, elf = fakeKsudElf(assets = false))
        val candidate = ManagerScanner.scan(app).candidates.single()
        assertFalse(candidate.suitable)
        assertEquals("not suitable", candidate.verdict)
    }

    @Test
    fun `scan ignores packages without an executable-shaped lib`() {
        install("com.example.plain", system = false, ksud = false)
        assertTrue(ManagerScanner.scan(app).candidates.isEmpty())
    }

    /* ---- the probe-first / visibility path --------------------------------- */

    private class FakeSource(
        private val self: String,
        private val probes: Map<String, PackageInfo>,
        private val enumerated: List<PackageInfo>,
        private val permission: PermissionInfo? = null,
        private val granted: Boolean = false,
        private val installer: String? = null,
    ) : PackageSource {
        override fun selfPackage(): String = self
        override fun packageInfo(packageName: String, flags: Int): PackageInfo? = probes[packageName]
        override fun installedPackages(flags: Int): List<PackageInfo> = enumerated
        override fun permissionInfo(name: String, flags: Int): PermissionInfo? = permission
        override fun isGranted(permission: String): Boolean = granted
        override fun installerPackageName(packageName: String): String? = installer
        override fun label(applicationInfo: ApplicationInfo): String = applicationInfo.packageName
    }

    private fun packageInfo(
        pkg: String,
        system: Boolean = false,
        libDir: String? = null,
        apk: String? = null,
    ): PackageInfo = PackageInfo().apply {
        packageName = pkg
        applicationInfo = ApplicationInfo().apply {
            packageName = pkg
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            nativeLibraryDir = libDir
            sourceDir = apk
        }
    }

    private fun libDir(pkg: String, elf: ByteArray? = fakeKsudElf()): String {
        val dir = File(app.cacheDir, "libs-$pkg-${System.nanoTime()}").apply { mkdirs() }
        if (elf != null) File(dir, "libksud.so").writeBytes(elf)
        return dir.absolutePath
    }

    @Test
    fun `a declared manager is found when enumeration returns only the app itself`() {
        val manager = packageInfo("com.resukisu.resukisu", libDir = libDir("resukisu"))
        val source = FakeSource(
            self = "com.sesame.app",
            probes = mapOf("com.resukisu.resukisu" to manager),
            enumerated = listOf(packageInfo("com.sesame.app")),
        )
        val scan = ManagerScanner.scan(source)
        assertEquals(listOf("com.resukisu.resukisu"), scan.candidates.map { it.packageName })
        assertTrue(scan.candidates.single().suitable)
        assertEquals(1, scan.visibility.enumerationCount)
        assertTrue(scan.visibility.possiblyFiltered)
    }

    @Test
    fun `the canary proves an unfiltered enumeration when a non-declared user app is visible`() {
        val source = FakeSource(
            self = "com.sesame.app",
            probes = emptyMap(),
            enumerated = listOf(
                packageInfo("com.sesame.app"),
                packageInfo("com.example.other", system = false),
            ),
        )
        val scan = ManagerScanner.scan(source)
        assertEquals(1, scan.visibility.canaryCount)
        assertTrue(scan.visibility.provenUnfiltered)
        assertFalse(scan.visibility.possiblyFiltered)
    }

    @Test
    fun `the app-list permission state reports support and the grant bit`() {
        val permission = PermissionInfo().apply {
            packageName = "com.lbe.security.miui"
            name = ManagerScanner.APP_LIST_PERMISSION
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        }
        val defined = FakeSource(
            self = "com.sesame.app",
            probes = emptyMap(),
            enumerated = emptyList(),
            permission = permission,
            granted = false,
        )
        val state = ManagerScanner.appListPermission(defined)
        assertTrue(state.supported)
        assertEquals("com.lbe.security.miui", state.definingPackage)
        assertFalse(state.granted)
        assertTrue(state.canAsk)

        val granted = FakeSource(
            self = "com.sesame.app",
            probes = emptyMap(),
            enumerated = emptyList(),
            permission = permission,
            granted = true,
        )
        assertTrue(ManagerScanner.appListPermission(granted).granted)

        val undefined = FakeSource(
            self = "com.sesame.app",
            probes = emptyMap(),
            enumerated = emptyList(),
            permission = null,
        )
        assertFalse(ManagerScanner.appListPermission(undefined).supported)
    }

    @Test
    fun `a ksud only inside the APK is found by the member fallback`() {
        val apk = File(app.cacheDir, "manager-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libksud.so"))
            zip.write(fakeKsudElf())
            zip.closeEntry()
        }
        val manager = packageInfo(
            "com.sukisu.ultra",
            libDir = libDir("empty", elf = null),
            apk = apk.absolutePath,
        )
        val source = FakeSource(
            self = "com.sesame.app",
            probes = mapOf("com.sukisu.ultra" to manager),
            enumerated = listOf(manager),
        )
        val scan = ManagerScanner.scan(source)
        val candidate = scan.candidates.single()
        assertTrue(candidate.suitable)
        assertTrue(candidate.path!!.contains("!/lib/arm64-v8a/libksud.so"))
        assertTrue(scan.visibility.declaredSeen.contains("com.sukisu.ultra"))
    }

    @Test
    fun `a probed manager with no payload stays visible as a row`() {
        val manager = packageInfo("com.sukisu.ultra", libDir = null)
        val source = FakeSource(
            self = "com.sesame.app",
            probes = mapOf("com.sukisu.ultra" to manager),
            enumerated = listOf(manager),
        )
        val scan = ManagerScanner.scan(source)
        val candidate = scan.candidates.single()
        assertNull(candidate.path)
        assertFalse(candidate.suitable)
        assertTrue(candidate.verdict.startsWith("installed;"))
    }
}
