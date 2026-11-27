package com.sesame.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import com.sesame.app.domain.model.RootManager
import java.io.File
import java.io.FileInputStream

/**
 * Finds KernelSU-family managers that can late-load on this device and ranks
 * them, without executing anything. The pipeline is the 2026-11-06 design
 * (PORT: ksud-judge-poc) with the 2026-11-27 static-KMI amendment
 * (handoff-resolution-plan §8):
 *
 *  1. enumerate user-installed packages (and the built-in managers even when
 *     system-installed);
 *  2. per package, keep executable-shaped aarch64 ELFs (ET_DYN + PT_INTERP,
 *     or ET_EXEC) — a 4 KiB prefilter, so ordinary shared libraries cost
 *     almost nothing;
 *  3. token-scan those candidates (`late-load`, `allow_shell`, capsule /
 *     bootstrap markers, `kernelsu.ko`) and collect every `<kmi>_kernelsu.ko`
 *     asset name — the exact set `boot-info supported-kmis` prints, because
 *     KSU's own list is every embedded asset stripped of that literal suffix;
 *  4. derive the device's KMI exactly as KSU's `get_current_kmi` does: its
 *     `parse_kmi` regex on `uname -r`, else the first vendor module's
 *     `vermagic` through the same parser, read statically from the file.
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

    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    private const val MAX_KMI_CHARS = 32

    /** Built-in KernelSU-family managers, priority order. */
    val BUILTIN = listOf(
        "me.weishu.kernelsu.pr",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    data class Candidate(
        val packageName: String,
        val label: String,
        val path: String,
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
         */
        val suitable: Boolean
            get() = lateLoadToken && allowShellToken && (kmis.isNotEmpty() || supportedKmisToken)

        val verdict: String
            get() = when {
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
            ksudPath = path,
            kmis = kmis,
            suitable = suitable,
        )
    }

    /** Scan installed managers. Blocking I/O only, no exec: call from Dispatchers.IO. */
    fun scan(context: Context): List<Candidate> {
        val pm = context.packageManager
        val self = context.packageName
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        val found = ArrayList<Candidate>()
        for (info in packages) {
            val app = info.applicationInfo ?: continue
            if (info.packageName == self) continue
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && info.packageName !in BUILTIN) continue
            val dir = app.nativeLibraryDir ?: continue
            val files = runCatching { File(dir).listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.length() > MAX_SCAN_BYTES) continue
                if (!isExecutableShaped(file)) continue
                val payload = scanPayload(file)
                if (!payload.tokens.ksudLike) continue
                found += Candidate(
                    packageName = info.packageName,
                    label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    path = file.absolutePath,
                    builtin = info.packageName in BUILTIN,
                    lateLoadToken = payload.tokens.lateLoad,
                    allowShellToken = payload.tokens.allowShell,
                    supportedKmisToken = payload.tokens.supportedKmis,
                    kmis = payload.kmis,
                )
            }
        }
        return found.sortedWith(compareByDescending<Candidate> { it.suitable }
            .thenByDescending { it.kmis.isNotEmpty() }
            .thenByDescending { it.builtin }
            .thenBy { it.label.lowercase() })
    }

    /** Selected package when it is still suitable, else the best suitable one. */
    fun resolve(candidates: List<Candidate>, selected: String?): Candidate? =
        candidates.firstOrNull { it.packageName == selected && it.suitable }
            ?: candidates.firstOrNull { it.suitable }

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

    /**
     * A cheap presence check for the home badge — no scan, no exec: the first
     * installed manager of either family. The run's precondition is the
     * authority; this only keeps the module card from claiming that a manager
     * is missing when one is installed.
     */
    fun installedManager(context: Context): String? {
        val pm = context.packageManager
        return (MagiskStage.PACKAGES + BUILTIN).firstOrNull { packageName ->
            runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess
        }
    }

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
    private fun isExecutableShaped(file: File): Boolean {
        val head = ByteArray(4096)
        val count = try {
            FileInputStream(file).use { it.read(head) }
        } catch (_: Exception) {
            return false
        }
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
     * name. The whole file is read (the names can live anywhere in the asset
     * table), bounded by [MAX_SCAN_BYTES] before the call.
     */
    private fun scanPayload(file: File): Payload {
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
            FileInputStream(file).use { stream ->
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
