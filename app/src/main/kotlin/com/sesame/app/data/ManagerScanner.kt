package com.sesame.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.sesame.app.domain.model.AppListPermissionState
import com.sesame.app.domain.model.RootManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The manager-detection seam: everything the scanner reads from the platform,
 * so the filtered-enumeration states the OEM app-list permission produces can
 * be exercised in unit tests. Production is [AndroidPackageSource].
 */
internal interface PackageSource {
    fun selfPackage(): String
    fun packageInfo(packageName: String, flags: Int): PackageInfo?
    fun installedPackages(flags: Int): List<PackageInfo>
    fun permissionInfo(name: String, flags: Int): PermissionInfo?
    fun isGranted(permission: String): Boolean
    fun installerPackageName(packageName: String): String?
    fun label(applicationInfo: ApplicationInfo): String
}

private class AndroidPackageSource(private val context: Context) : PackageSource {
    private val pm = context.packageManager

    override fun selfPackage(): String = context.packageName

    override fun packageInfo(packageName: String, flags: Int): PackageInfo? =
        runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull()

    override fun installedPackages(flags: Int): List<PackageInfo> =
        runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())

    override fun permissionInfo(name: String, flags: Int): PermissionInfo? =
        runCatching { pm.getPermissionInfo(name, flags) }.getOrNull()

    override fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun installerPackageName(packageName: String): String? =
        runCatching { pm.getInstallerPackageName(packageName) }.getOrNull()

    override fun label(applicationInfo: ApplicationInfo): String =
        applicationInfo.loadLabel(pm).toString()
}

/**
 * Finds KernelSU-family managers that can late-load on this device and ranks
 * them, without executing anything. The pipeline is the 2026-11-06 design
 * (PORT: ksud-judge-poc) with the 2026-11-27 static-KMI amendment
 * (handoff-resolution-plan §8) and the 2026-11-28 probe-first amendment
 * (manager-detection-visibility-plan §4.1):
 *
 *  1. probe the known manager packages directly (`getPackageInfo`), which
 *     works under the OEM app-list gate because `getPackageInfo` is not one of
 *     the four APIs that gate controls and the packages are `<queries>`-declared;
 *  2. enumerate the rest of the installed apps as an additive source for
 *     unknown forks (this one *is* gated; it may return only the app itself);
 *  3. per package, keep executable-shaped aarch64 ELFs (ET_DYN + PT_INTERP,
 *     or ET_EXEC) — a 4 KiB prefilter, so ordinary shared libraries cost
 *     almost nothing;
 *  4. token-scan those candidates (`late-load`, `allow_shell`, capsule /
 *     bootstrap markers, `kernelsu.ko`) and collect every `<kmi>_kernelsu.ko`
 *     asset name — the exact set `boot-info supported-kmis` prints, because
 *     KSU's own list is every embedded asset stripped of that literal suffix;
 *  5. derive the device's KMI exactly as KSU's `get_current_kmi` does: its
 *     `parse_kmi` regex on `uname -r`, else the first vendor module's
 *     `vermagic` through the same parser, read statically from the file.
 *
 * A probe package whose payload is only inside its APK
 * (`extractNativeLibs=false`) yields a `KsudPayload` member spec; detection
 * never extracts, staging copies the bytes.
 *
 * Suitability is the static late-load interface (`late-load` and
 * `allow-shell` tokens). The preflight used to execute `late-load --help` and
 * `boot-info …`; both are gone: every app exec under `/data` is a SecureGuard
 * event (the 6.6 module is caller-based, the 5.10/6.1 target-based), and a
 * probe killed by a signal is no verdict. The authoritative interface probe
 * runs as root in the handoff, from the exempt `/data/local/tmp` (§7 of the
 * plan), and the ordered attempt loop remains the authority on a real load.
 * The KMI asset list is KSU's necessary condition only — it does not prove
 * late-load succeeds (seccomp, a vendor module blacklist, vermagic/SELinux can
 * still refuse it).
 *
 * The `--version` banner is deliberately not used: forks print release names
 * (`ksud samsung-v2.2 (uapi: 5)`), and the interface is the contract that
 * matters.
 */
object ManagerScanner {
    const val PREF_KEY = "manager_package"

    /** The OEM app-list runtime permission (TAF 108-2022). */
    const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    private const val MAX_KMI_CHARS = 32
    private const val CANARY_SAMPLE = 16

    /** Built-in KernelSU-family managers, priority order. */
    val BUILTIN = listOf(
        "me.weishu.kernelsu.pr",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    /**
     * Known managers that are not built-in: probed and `<queries>`-declared so
     * an installed one is visible as a row ("installed; no late-load").
     */
    val EXTRA = listOf("com.sukisu.ultra")

    /** Every package probed directly; `<queries>` must cover this + Magisk. */
    val PROBE_PACKAGES = BUILTIN + EXTRA

    /**
     * Every package declared in `<queries>`: the probe list, the Magisk
     * packages (detected by [MagiskStage]), and the Shizuku manager. Used to
     * keep the canary from counting a declared package as proof of an
     * unfiltered enumeration.
     */
    val DECLARED_PACKAGES: List<String> =
        PROBE_PACKAGES + MagiskStage.PACKAGES + listOf("moe.shizuku.privileged.api")

    data class Candidate(
        val packageName: String,
        val label: String,
        /**
         * The payload's readable location: a file path, or a
         * `<apk>!/lib/arm64-v8a/<member>` spec when only the APK carries it.
         * Null = installed, but no late-load-shaped ksud was found.
         */
        val path: String?,
        val builtin: Boolean,
        val lateLoadToken: Boolean,
        val allowShellToken: Boolean,
        /** The `supported-kmis` interface token (a manager's own answer machinery). */
        val supportedKmisToken: Boolean = false,
        /**
         * The `<kmi>_kernelsu.ko` asset names found in this payload — the same
         * set `boot-info supported-kmis` prints (`assets::list_supported_kmi`
         * strips that literal suffix). Empty means the build keeps no per-KMI
         * assets, so the resolver uses the release mirror.
         */
        val kmis: List<String> = emptyList(),
    ) {
        /**
         * The static late-load interface **and** one manager-owned fact: KMI
         * assets, or the `supported-kmis` interface token. The second half
         * excludes a false positive that carries our own embedded handoff
         * script's strings (`late-load`, `--allow-shell`, `kernelsu.ko`) but
         * cannot answer a manager's `boot-info` — the exploit app's own native.
         * A null payload can never be suitable.
         */
        val suitable: Boolean
            get() = path != null && lateLoadToken && allowShellToken &&
                (kmis.isNotEmpty() || supportedKmisToken)

        val verdict: String
            get() = when {
                path == null -> "installed; no late-load-capable ksud in its libs"
                suitable && kmis.isNotEmpty() ->
                    "static late-load candidate, ${kmis.size} KMI asset(s)"
                suitable -> "static late-load candidate, no KMI assets"
                else -> "not suitable"
            }

        /** Label shown in the picker. */
        fun displayName(): String = "$label ($packageName) — $verdict"

        /** The handoff model's view of this candidate: one discovered manager. */
        fun toManager(): RootManager.Ksu = RootManager.Ksu(
            packageName = packageName,
            label = label,
            ksudPath = path.orEmpty(),
            kmis = kmis,
            suitable = suitable,
        )
    }

    /**
     * The scan's detection result plus the visibility evidence the preflight,
     * the picker and `scan_test` report. The canary is a one-sided proof: a
     * non-declared user app in the enumeration proves the OS is not filtering
     * it; an empty canary is consistent with filtering (or with a device that
     * has no third-party apps) and is never proof.
     */
    data class VisibilityAudit(
        val permissionDefined: Boolean,
        val permissionPackage: String?,
        val permissionGranted: Boolean,
        val enumerationCount: Int,
        val userAppCount: Int,
        val canaryCount: Int,
        val canary: List<String>,
        val declaredSeen: List<String>,
        val probedInstalled: List<String>,
    ) {
        val provenUnfiltered: Boolean get() = canaryCount > 0
        val possiblyFiltered: Boolean get() = !provenUnfiltered
        val canAsk: Boolean get() = permissionDefined && !permissionGranted

        /** One line for the log, `scan_test` and the run log. */
        fun summary(): String = buildString {
            append("app-list permission=")
            append(
                when {
                    !permissionDefined -> "undefined"
                    permissionGranted -> "granted"
                    else -> "defined(${permissionPackage ?: "?"}) not granted"
                },
            )
            append(" enumeration=").append(enumerationCount)
            append(" canary=").append(canaryCount)
        }
    }

    data class ScanResult(
        val candidates: List<Candidate>,
        val visibility: VisibilityAudit,
    )

    /** The app-list permission's support and grant state. */
    fun appListPermission(context: Context): AppListPermissionState =
        appListPermission(AndroidPackageSource(context))

    internal fun appListPermission(source: PackageSource): AppListPermissionState {
        val info = source.permissionInfo(APP_LIST_PERMISSION, 0)
        return AppListPermissionState(
            supported = info != null,
            definingPackage = info?.packageName,
            granted = info != null && source.isGranted(APP_LIST_PERMISSION),
        )
    }

    /** Scan installed managers. Blocking I/O only, no exec: call from Dispatchers.IO. */
    fun scan(context: Context): ScanResult = scan(AndroidPackageSource(context))

    internal fun scan(source: PackageSource): ScanResult {
        val self = source.selfPackage()
        /* Probe first: the known packages are visible under `<queries>` and
         * `getPackageInfo` is not one of the four OEM-gated enumeration APIs. */
        val probed = LinkedHashMap<String, PackageInfo>()
        for (pkg in PROBE_PACKAGES) {
            source.packageInfo(pkg, 0)?.let { probed[pkg] = it }
        }
        val enumerated = source.installedPackages(0)
        val all = LinkedHashMap<String, PackageInfo>(probed)
        for (info in enumerated) {
            val pkg = info.packageName
            if (pkg == self || all.containsKey(pkg)) continue
            val app = info.applicationInfo ?: continue
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && pkg !in BUILTIN) continue
            all[pkg] = info
        }
        val found = ArrayList<Candidate>()
        for ((pkg, info) in all) {
            if (pkg == self) continue
            val app = info.applicationInfo ?: continue
            val label = runCatching { source.label(app) }.getOrDefault(pkg)
            found += candidatesFor(pkg, label, app, probed = pkg in probed)
        }
        return ScanResult(
            candidates = found.sortedWith(
                compareByDescending<Candidate> { it.suitable }
                    .thenByDescending { it.kmis.isNotEmpty() }
                    .thenByDescending { it.builtin }
                    .thenBy { it.label.lowercase() },
            ),
            visibility = audit(source, self, enumerated, probed),
        )
    }

    /** Selected package when it is still suitable, else the best suitable one. */
    fun resolve(candidates: List<Candidate>, selected: String?): Candidate? =
        candidates.firstOrNull { it.packageName == selected && it.suitable }
            ?: candidates.firstOrNull { it.suitable }

    /**
     * A cheap presence check for the home badge — no payload scan, no exec:
     * the first installed manager of either family over the *usable* known
     * list. The run's precondition is the authority; this only keeps the
     * module card from claiming that a manager is missing when one is
     * installed. `EXTRA` (SukiSU Ultra) is deliberately excluded here: its
     * bundled ksud has no late-load, so a badge built on presence alone would
     * call it "ready"; the picker and the refusal name it instead.
     */
    fun installedManager(context: Context): String? {
        val pm = context.packageManager
        return (MagiskStage.PACKAGES + BUILTIN).firstOrNull { packageName ->
            runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess
        }
    }

    /* ---- payload discovery ---------------------------------------------- */

    private class PayloadSource(
        val spec: String,
        val size: Long,
        val open: () -> InputStream?,
    )

    private fun candidatesFor(
        pkg: String,
        label: String,
        app: ApplicationInfo,
        probed: Boolean,
    ): List<Candidate> {
        val out = ArrayList<Candidate>()
        var ksudFound = false
        val nativeDir = app.nativeLibraryDir?.let { dir -> runCatching { File(dir).listFiles() }.getOrNull() }
        if (nativeDir != null) {
            for (file in nativeDir) {
                if (!file.isFile || file.length() > MAX_SCAN_BYTES) continue
                val source = PayloadSource(
                    spec = file.absolutePath,
                    size = file.length(),
                    open = { runCatching { FileInputStream(file) }.getOrNull() },
                )
                val candidate = candidateFrom(source, pkg, label) ?: continue
                out += candidate
                ksudFound = true
            }
        }
        /* The APK fallback is bounded to known packages: an APK built with
         * extractNativeLibs=false keeps its libs inside the archive, and the
         * native dir can be empty. Unknown forks keep the native-dir scan. */
        if (!ksudFound && probed) {
            for (source in apkPayloads(app)) {
                val candidate = candidateFrom(source, pkg, label) ?: continue
                out += candidate
                ksudFound = true
            }
        }
        if (!ksudFound && probed) {
            /* Installed, but no late-load-shaped ksud: keep a row so the
             * picker and the refusal say so instead of "not found". */
            out += Candidate(
                packageName = pkg,
                label = label,
                path = null,
                builtin = pkg in BUILTIN,
                lateLoadToken = false,
                allowShellToken = false,
            )
        }
        return out
    }

    private fun candidateFrom(source: PayloadSource, pkg: String, label: String): Candidate? {
        if (source.size > MAX_SCAN_BYTES) return null
        if (!isExecutableShaped(source)) return null
        val payload = scanPayload(source)
        if (!payload.tokens.ksudLike) return null
        return Candidate(
            packageName = pkg,
            label = label,
            path = source.spec,
            builtin = pkg in BUILTIN,
            lateLoadToken = payload.tokens.lateLoad,
            allowShellToken = payload.tokens.allowShell,
            supportedKmisToken = payload.tokens.supportedKmis,
            kmis = payload.kmis,
        )
    }

    /**
     * `lib/arm64-v8a/` members of the base APK and its splits, as payload
     * sources addressed by [KsudPayload] member specs. Only the known-package
     * fallback path calls this.
     */
    private fun apkPayloads(app: ApplicationInfo): List<PayloadSource> {
        val apks = ArrayList<File>()
        app.sourceDir?.let { apks += File(it) }
        app.splitSourceDirs?.forEach { apks += File(it) }
        val out = ArrayList<PayloadSource>()
        for (apk in apks) {
            if (!apk.isFile) continue
            runCatching {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (!entry.name.startsWith("lib/arm64-v8a/")) continue
                        if (entry.size <= 0 || entry.size > MAX_SCAN_BYTES) continue
                        val apkPath = apk.absolutePath
                        val member = entry.name
                        out += PayloadSource(
                            spec = KsudPayload.memberSpec(apkPath, member),
                            size = entry.size,
                            open = { KsudPayload.open(KsudPayload.memberSpec(apkPath, member)) },
                        )
                    }
                }
            }
        }
        return out
    }

    /* ---- visibility audit ----------------------------------------------- */

    private fun audit(
        source: PackageSource,
        self: String,
        enumerated: List<PackageInfo>,
        probed: Map<String, PackageInfo>,
    ): VisibilityAudit {
        val declared = DECLARED_PACKAGES.toSet()
        val installer = source.installerPackageName(self)
        val canary = ArrayList<String>()
        val declaredSeen = ArrayList<String>()
        var userApps = 0
        for (info in enumerated) {
            val app = info.applicationInfo ?: continue
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!system) userApps++
            if (info.packageName in declared) declaredSeen += info.packageName
            if (!system && info.packageName != self && info.packageName != installer &&
                info.packageName !in declared
            ) {
                canary += info.packageName
            }
        }
        val permission = source.permissionInfo(APP_LIST_PERMISSION, 0)
        return VisibilityAudit(
            permissionDefined = permission != null,
            permissionPackage = permission?.packageName,
            permissionGranted = permission != null && source.isGranted(APP_LIST_PERMISSION),
            enumerationCount = enumerated.size,
            userAppCount = userApps,
            canaryCount = canary.size,
            canary = canary.take(CANARY_SAMPLE),
            declaredSeen = declaredSeen,
            probedInstalled = PROBE_PACKAGES.filter { it in probed },
        )
    }

    /* ---- KMI derivation ------------------------------------------------- */

    /**
     * The KMI `late-load` would use, derived exactly as KSU's
     * `get_current_kmi()` does and without executing anything.
     * [source] names the branch that answered (`uname`, `vermagic`, `none`).
     */
    data class CurrentKmi(
        /** The KMI token, e.g. `android12-5.10`; null = detection failed. */
        val value: String?,
        val source: String,
        /** KSU's own message when [value] is null. */
        val failure: String?,
    )

    private const val VENDOR_MODULES = "/vendor/lib/modules"

    fun currentKmi(
        release: String,
        vendorModules: File = File(VENDOR_MODULES),
    ): CurrentKmi {
        parseKmi(release)?.let { return CurrentKmi(it, "uname", null) }
        val fromVermagic = vermagicKmi(vendorModules)
        if (fromVermagic != null) return CurrentKmi(fromVermagic, "vermagic", null)
        return CurrentKmi(null, "none", "Failed to get KMI from boot/modules")
    }

    /** KSU's `boot_patch::parse_kmi`: leftmost match of that regex, `{android}-{major.minor}`. */
    private val KMI_PARSE = Regex("(.* )?(\\d+\\.\\d+)(\\S+)?(android\\d+)(.*)")

    /**
     * Byte-for-byte the manager's parser (`boot_patch.rs::parse_kmi`): apply
     * the regex to the string, take group 2 (the kernel `major.minor`) and
     * group 4 (the `android<NN>` token), return `{group4}-{group2}`. A string
     * the manager would refuse returns null.
     */
    fun parseKmi(version: String): String? {
        val cap = KMI_PARSE.find(version) ?: return null
        val kernel = cap.groupValues[2]
        val android = cap.groupValues[4]
        if (kernel.isEmpty() || android.isEmpty()) return null
        return "$android-$kernel"
    }

    /**
     * KSU's `parse_kmi_from_modules`, read statically: the first `*.ko` under
     * `/vendor/lib/modules` (name order, as `read_dir` order is unspecified),
     * its `.modinfo` `vermagic` entry, then the same parser applied to the
     * line `modinfo` would have printed (`vermagic: <value>`). No exec.
     */
    private fun vermagicKmi(dir: File): String? {
        val module = runCatching {
            dir.listFiles()?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".ko") }
                ?.sortedBy { it.name }
                ?.firstOrNull()
        }.getOrNull() ?: return null
        val value = runCatching { vermagicOf(module) }.getOrNull() ?: return null
        return parseKmi("vermagic: $value")
    }

    /** The raw `vermagic=` value from a module's modinfo entries. */
    internal fun vermagicOf(file: File): String? {
        if (file.length() > MAX_SCAN_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val needle = "vermagic=".toByteArray()
        val at = indexOf(bytes, needle) ?: return null
        var end = at + needle.size
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, at + needle.size, end - at - needle.size, Charsets.UTF_8)
    }

    private fun indexOf(buffer: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || buffer.size < needle.size) return null
        val last = buffer.size - needle.size
        outer@ for (start in 0..last) {
            for (index in needle.indices) {
                if (buffer[start + index] != needle[index]) continue@outer
            }
            return start
        }
        return null
    }

    /* ---- ELF and token scan --------------------------------------------- */

    private data class Tokens(
        val lateLoad: Boolean,
        val allowShell: Boolean,
        val capsule: Boolean,
        val bootstrap: Boolean,
        val kernelKo: Boolean,
        val brand: Boolean,
        val supportedKmis: Boolean,
    ) {
        val ksudLike: Boolean
            get() = lateLoad || capsule || bootstrap || (brand && kernelKo)
    }

    private data class Payload(val tokens: Tokens, val kmis: List<String>)

    /** ELF, little-endian aarch64, ET_DYN + PT_INTERP (PIE) or ET_EXEC. */
    private fun isExecutableShaped(source: PayloadSource): Boolean {
        val head = ByteArray(4096)
        val count = runCatching {
            source.open()?.use { stream -> stream.read(head) } ?: 0
        }.getOrDefault(0)
        if (count < 64) return false
        if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return false
        }
        if (head[5].toInt() != 1) return false            // ELFDATA2LSB
        val type = u16(head, 16)
        if (u16(head, 18) != 183) return false            // EM_AARCH64
        if (type == 2) return true                        // ET_EXEC
        if (type != 3) return false                       // ET_DYN
        val phoff = u64(head, 32)
        val phentsize = u16(head, 54)
        val phnum = u16(head, 56)
        var index = 0
        while (index < phnum && index < 64) {
            val offset = phoff + index.toLong() * phentsize
            if (offset + 4 > count.toLong()) break
            if (u32(head, offset.toInt()) == 3L) return true // PT_INTERP
            index++
        }
        return false
    }

    /**
     * One streaming pass: the ksud tokens and every `<kmi>_kernelsu.ko` asset
     * name. The whole payload is read (the names can live anywhere in the
     * asset table), bounded by [MAX_SCAN_BYTES] before the call.
     */
    private fun scanPayload(source: PayloadSource): Payload {
        var lateLoad = false
        var allowShell = false
        var capsule = false
        var bootstrap = false
        var kernelKo = false
        var brand = false
        var supportedKmis = false
        val kmis = LinkedHashSet<String>()
        val chunk = ByteArray(64 * 1024 + CARRY)
        var keep = 0
        try {
            source.open()?.use { stream ->
                while (true) {
                    val read = stream.read(chunk, keep, 64 * 1024)
                    if (read <= 0) break
                    val length = keep + read
                    lateLoad = lateLoad || contains(chunk, length, LATE_LOAD)
                    allowShell = allowShell || contains(chunk, length, ALLOW_SHELL) ||
                        contains(chunk, length, ALLOW_SHELL_UNDERSCORE)
                    capsule = capsule || contains(chunk, length, CAPSULE)
                    bootstrap = bootstrap || contains(chunk, length, BOOTSTRAP)
                    kernelKo = kernelKo || contains(chunk, length, KERNEL_KO)
                    brand = brand || contains(chunk, length, BRAND)
                    supportedKmis = supportedKmis || contains(chunk, length, SUPPORTED_KMIS)
                    collectKmis(chunk, length, kmis)
                    keep = minOf(CARRY, length)
                    System.arraycopy(chunk, length - keep, chunk, 0, keep)
                }
            }
        } catch (_: Exception) {
            // A partially-read file still yields whatever was seen.
        }
        return Payload(
            Tokens(lateLoad, allowShell, capsule, bootstrap, kernelKo, brand, supportedKmis),
            kmis.toList(),
        )
    }

    /**
     * Every `<name>_kernelsu.ko` occurrence's name, restricted to the KMI shape
     * the asset names have (`android<NN>-<major>.<minor>`). The suffix filter is
     * KSU's own (`_kernelsu.ko`), so `_susfs_guard_lkm.ko` names and the bare
     * parser regexes in the binary never enter the list.
     *
     * The names sit in one string blob and are often **concatenated with no
     * separator** (`…_kernelsu.koandroid12-5.10_susfs_guard_lkm.ko…`), so the
     * token is taken as the rightmost KMI-shaped tail of the bytes before the
     * suffix — never as a whole-word walk, which a preceding string breaks.
     */
    private fun collectKmis(buffer: ByteArray, length: Int, out: MutableSet<String>) {
        val suffix = KERNEL_KO_ASSET
        if (length < suffix.size) return
        var at = 0
        while (at <= length - suffix.size) {
            var match = true
            for (index in suffix.indices) {
                if (buffer[at + index] != suffix[index]) {
                    match = false
                    break
                }
            }
            if (!match) {
                at++
                continue
            }
            val begin = maxOf(0, at - MAX_KMI_CHARS)
            val window = String(buffer, begin, at - begin, Charsets.US_ASCII)
            KMI_TAIL.find(window)?.let { out += it.value }
            at += suffix.size
        }
    }

    private val KMI_TAIL = Regex("android[0-9]+-[0-9]+\\.[0-9]+$")

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xff) or ((buffer[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(buffer: ByteArray, offset: Int): Long =
        (u16(buffer, offset).toLong()) or (u16(buffer, offset + 2).toLong() shl 16)

    private fun u64(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (buffer[offset + index].toLong() and 0xff)
        }
        return value
    }

    private fun contains(buffer: ByteArray, length: Int, needle: ByteArray): Boolean {
        if (length < needle.size) return false
        val last = length - needle.size
        outer@ for (start in 0..last) {
            for (index in needle.indices) {
                if (buffer[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * The carry keeps enough of the previous chunk for the longest token and
     * for a KMI name that starts before a chunk boundary.
     */
    private const val CARRY = 96

    private val LATE_LOAD = "late-load".toByteArray()
    private val ALLOW_SHELL = "--allow-shell".toByteArray()
    private val ALLOW_SHELL_UNDERSCORE = "allow_shell".toByteArray()
    private val CAPSULE = "KSULKM1".toByteArray()
    private val BOOTSTRAP = "ksu_bootstrap".toByteArray()
    private val KERNEL_KO = "kernelsu.ko".toByteArray()
    private val KERNEL_KO_ASSET = "_kernelsu.ko".toByteArray()
    private val BRAND = "KernelSU".toByteArray()
    private val SUPPORTED_KMIS = "supported-kmis".toByteArray()
}
