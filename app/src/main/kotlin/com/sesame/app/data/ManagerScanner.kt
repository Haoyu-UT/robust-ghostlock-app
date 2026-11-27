package com.sesame.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.sesame.app.domain.model.RootManager
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Finds KernelSU-family managers that can late-load on this device and ranks
 * them. The pipeline is the 2026-11-06 design (PORT: ksud-judge-poc):
 *
 *  1. enumerate user-installed packages (and the built-in managers even when
 *     system-installed);
 *  2. per package, keep executable-shaped aarch64 ELFs (ET_DYN + PT_INTERP,
 *     or ET_EXEC) — a 4 KiB prefilter, so ordinary shared libraries cost
 *     almost nothing;
 *  3. token-scan only those candidates (`late-load`, `allow_shell`, capsule /
 *     bootstrap markers, `kernelsu.ko`);
 *  4. execute `late-load --help` for ksud-like candidates and require the
 *     exact interface this app calls (`--kmi` and `--allow-shell`).
 *
 * The `--version` banner is deliberately not used: forks print release names
 * (`ksud samsung-v2.2 (uapi: 5)`), and the interface probe is the contract
 * that matters. Suitability: a confirmed interface wins; a candidate whose
 * probe cannot run (SELinux denies exec, timeout) or is killed by a signal
 * (a vendor guard's exec hook kills the child) falls back to the static
 * late-load tokens — a killed probe is no answer, not a missing interface.
 * The authoritative probe runs again as root in the handoff, from the exempt
 * `/data/local/tmp`, which the app cannot write as `untrusted_app`.
 * There is deliberately no persistent cache — the whole pipeline is ~0.2 s
 * warm on a regular phone, and a stale "capable" verdict is the one failure
 * mode worth avoiding.
 */
object ManagerScanner {
    const val PREF_KEY = "manager_package"

    private const val TAG = "GhostlockScan"

    /**
     * The only path both OPlus guard generations exempt: the old module reports
     * a non-root `/data` exec and blocks a root one, the new module blocks the
     * non-root exec outright — and `/data/app`, where the manager's library
     * lives, is not exempt in either.
     */
    private const val PROBE_DIR = "/data/local/tmp/ghostlock-probe"

    private const val PROBE_TIMEOUT_MS = 2000L
    private const val MAX_PROBE_OUTPUT = 8192
    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024

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
        /** null = the probe could not run (exec denied, timeout, crash). */
        val interfaceOk: Boolean?,
    ) {
        val suitable: Boolean
            get() = when {
                interfaceOk == true -> true
                interfaceOk == false -> false
                else -> lateLoadToken && allowShellToken
            }

        val verdict: String
            get() = when {
                interfaceOk == true -> "late-load interface confirmed"
                interfaceOk == false -> "late-load interface missing"
                lateLoadToken && allowShellToken -> "static late-load candidate (probe unavailable)"
                else -> "not suitable"
            }

        /** Label shown in the picker. */
        fun displayName(): String = "$label ($packageName) — $verdict"

        /** The handoff model's view of this candidate: one discovered manager. */
        fun toManager(): RootManager.Ksu = RootManager.Ksu(
            packageName = packageName,
            label = label,
            ksudPath = path,
            interfaceOk = interfaceOk,
            suitable = suitable,
        )
    }

    /** Scan installed managers. Blocking I/O and probes: call from Dispatchers.IO. */
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
                val tokens = scanTokens(file)
                if (!tokens.ksudLike) continue
                val probe = run(file, "late-load", "--help")
                val iface = when {
                    probe.unavailable -> null
                    /* A guard's execve hook kills the child before it can
                     * answer; that is no verdict about the interface. */
                    probe.killed -> null
                    /* It ran and said nothing: likewise no verdict. */
                    probe.output.isBlank() -> null
                    else -> probe.exit == 0 &&
                        probe.output.contains("--allow-shell") &&
                        probe.output.contains("--kmi")
                }
                found += Candidate(
                    packageName = info.packageName,
                    label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    path = file.absolutePath,
                    builtin = info.packageName in BUILTIN,
                    lateLoadToken = tokens.lateLoad,
                    allowShellToken = tokens.allowShell,
                    interfaceOk = iface,
                )
            }
        }
        return found.sortedWith(compareByDescending<Candidate> { it.suitable }
            .thenByDescending { it.interfaceOk == true }
            .thenByDescending { it.builtin }
            .thenBy { it.label.lowercase() })
    }

    /** Selected package when it is still suitable, else the best suitable one. */
    fun resolve(candidates: List<Candidate>, selected: String?): Candidate? =
        candidates.firstOrNull { it.packageName == selected && it.suitable }
            ?: candidates.firstOrNull { it.suitable }

    /**
     * The manager's own KMI interface (handoff-resolution-plan §2.4):
     * `boot-info supported-kmis` doubles as the capability probe, then
     * `boot-info current-kmi`. Nothing is re-derived from uname here — the
     * manager's `current-kmi` is authoritative, and a manager that cannot
     * answer falls back to the release mirror in the resolver.
     */
    data class KmiAnswer(
        /** The embedded `*_kernelsu.ko` names; null = the manager cannot answer. */
        val supported: List<String>?,
        /** The KMI to pass to `late-load --kmi`; null = it failed. */
        val current: String?,
        /** The manager's own message when [current] is null. */
        val failure: String?,
    )

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

    /** Two extra execs per suitable candidate; call from Dispatchers.IO. */
    fun probeKmi(candidate: Candidate): KmiAnswer {
        val file = File(candidate.path)
        val supported = run(file, "boot-info", "supported-kmis")
        if (supported.unavailable || supported.exit != 0) {
            return KmiAnswer(supported = null, current = null, failure = null)
        }
        val names = supported.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (names.isEmpty()) {
            return KmiAnswer(supported = null, current = null, failure = null)
        }
        val current = run(file, "boot-info", "current-kmi")
        if (current.unavailable || current.exit != 0) {
            return KmiAnswer(
                supported = names,
                current = null,
                failure = firstLine(current.output) ?: "Failed to get KMI from boot/modules",
            )
        }
        val value = firstLine(current.output)
        return if (value == null) {
            KmiAnswer(names, null, "boot-info current-kmi printed nothing")
        } else {
            KmiAnswer(names, value, null)
        }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private data class Tokens(
        val lateLoad: Boolean,
        val allowShell: Boolean,
        val capsule: Boolean,
        val bootstrap: Boolean,
        val kernelKo: Boolean,
        val brand: Boolean,
    ) {
        val ksudLike: Boolean
            get() = lateLoad || capsule || bootstrap || (brand && kernelKo)
    }

    private data class Probe(val exit: Int?, val output: String, val unavailable: Boolean) {
        /**
         * The child was terminated by a signal, in the shell's 128+N
         * convention. A vendor guard's execve hook is one such killer; either
         * way the manager never answered, so this probe carries no verdict.
         */
        val killed: Boolean get() = exit != null && exit in 129..192
    }

    /**
     * Probes are never executed in place: `/data/app` is exactly the path the
     * guard watches. The copy under `/data/local/tmp` is the exempt one; a
     * staging or exec refusal reads as "probe unavailable", so the static-token
     * verdict still applies. There is deliberately no in-place fallback.
     */
    private fun run(file: File, vararg args: String): Probe {
        val staged = stageForProbe(file) ?: return Probe(null, "", true)
        return try {
            runStaged(staged, args)
        } finally {
            runCatching { staged.delete() }
        }
    }

    private fun stageForProbe(file: File): File? {
        val dir = File(PROBE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "probe: cannot create $PROBE_DIR")
            return null
        }
        val name = "${Integer.toHexString(file.absolutePath.hashCode())}-${file.name}"
        val dest = File(dir, name)
        return try {
            file.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val executable = dest.setExecutable(true, false)
            Log.i(TAG, "probe: staged ${file.absolutePath} -> ${dest.absolutePath} executable=$executable")
            dest
        } catch (error: Exception) {
            Log.w(
                TAG,
                "probe: staging ${file.absolutePath} failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            runCatching { dest.delete() }
            null
        }
    }

    private fun runStaged(file: File, args: Array<out String>): Probe {
        val command = ArrayList<String>(args.size + 1)
        command += file.absolutePath
        command += args
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            Log.w(
                TAG,
                "probe: exec ${file.absolutePath} failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            return Probe(null, "", true)
        }
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.length < MAX_PROBE_OUTPUT) {
                            output.append(buffer, 0, minOf(count, MAX_PROBE_OUTPUT - output.length))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
            reader.interrupt()
            return Probe(null, output.toString(), true)
        }
        runCatching { reader.join(500) }
        val exit = runCatching { process.exitValue() }.getOrNull()
        if (exit != null && exit in 129..192) {
            Log.w(TAG, "probe: ${file.absolutePath} terminated by signal ${exit - 128}; no verdict")
        }
        return Probe(exit, output.toString(), false)
    }

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

    private fun scanTokens(file: File): Tokens {
        var lateLoad = false
        var allowShell = false
        var capsule = false
        var bootstrap = false
        var kernelKo = false
        var brand = false
        val chunk = ByteArray(64 * 1024 + 16)
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
                    if (lateLoad && allowShell && (capsule || bootstrap)) {
                        // Everything this app needs is present; stop reading.
                        return Tokens(lateLoad, allowShell, capsule, bootstrap, kernelKo, brand)
                    }
                    keep = minOf(15, length)
                    System.arraycopy(chunk, length - keep, chunk, 0, keep)
                }
            }
        } catch (_: Exception) {
            // A partially-read file still yields whatever tokens were seen.
        }
        return Tokens(lateLoad, allowShell, capsule, bootstrap, kernelKo, brand)
    }

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

    private val LATE_LOAD = "late-load".toByteArray()
    private val ALLOW_SHELL = "--allow-shell".toByteArray()
    private val ALLOW_SHELL_UNDERSCORE = "allow_shell".toByteArray()
    private val CAPSULE = "KSULKM1".toByteArray()
    private val BOOTSTRAP = "ksu_bootstrap".toByteArray()
    private val KERNEL_KO = "kernelsu.ko".toByteArray()
    private val BRAND = "KernelSU".toByteArray()
}
