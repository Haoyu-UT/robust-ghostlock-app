package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffResolution
import com.sesame.app.domain.model.HandoffTarget
import com.sesame.app.domain.model.RootManager

/**
 * The handoff target list as it travels between processes (the Shizuku AIDL)
 * and, from S2, as the native's `GHOSTLOCK_HANDOFF_TARGETS` environment entry.
 *
 * One entry per line: `kind|packageName|origin|kmi`. [RootManager.Ksu]'s
 * origin is the ksud path the receiving side copies from; Magisk's is its
 * native lib dir (empty when only the APK carries the libs — the receiver
 * stages from the package itself). `kmi` is empty for Magisk and on the
 * release-mirror fallback is the derived value.
 */
object HandoffWire {
    const val EnvTargets = "GHOSTLOCK_HANDOFF_TARGETS"

    private const val SEPARATOR = "|"

    /** The wire entry for a manager, without a staged path (the receiver stages). */
    fun encode(manager: RootManager, kmi: String?): String {
        val origin = when (manager) {
            is RootManager.Ksu -> manager.ksudPath
            is RootManager.Magisk -> manager.nativeLibDir.orEmpty()
        }
        return entry(manager.kind.wire, manager.packageName, origin, kmi)
    }

    /** The wire entry for an already-staged target (this process's copy). */
    fun encodeTarget(target: HandoffTarget): String =
        entry(target.manager.kind.wire, target.manager.packageName, target.source, target.kmi)

    /** One `GHOSTLOCK_HANDOFF_TARGETS` value in attempt order. */
    fun encodeTargets(targets: List<HandoffTarget>): String =
        targets.joinToString("\n") { encodeTarget(it) }

    private fun entry(kind: String, packageName: String, source: String, kmi: String?): String =
        listOf(kind, packageName, source, kmi.orEmpty()).joinToString(SEPARATOR)

    /** The transport list, in attempt order. */
    fun encode(resolution: HandoffResolution): List<String> =
        resolution.feasible.map { encode(it.manager, it.kmi) }

    /**
     * Decodes one entry; null when it is malformed. The decoded target's
     * [HandoffTarget.source] is the origin — a staging site overwrites it with
     * its own copy before the script sees it. `suitable` is true because the
     * app already decided; the wire carries the decision, not the probe.
     */
    fun decode(line: String): HandoffTarget? {
        val parts = line.split(SEPARATOR)
        if (parts.size != 4) return null
        val packageName = parts[1]
        if (packageName.isEmpty()) return null
        val origin = parts[2]
        val kmi = parts[3].ifEmpty { null }
        val kind = HandoffKind.entries.firstOrNull { it.wire == parts[0] } ?: return null
        val manager = when (kind) {
            HandoffKind.KSU -> RootManager.Ksu(
                packageName = packageName,
                label = packageName,
                ksudPath = origin,
                interfaceOk = null,
                suitable = true,
            )

            HandoffKind.MAGISK -> RootManager.Magisk(
                packageName = packageName,
                label = packageName,
                nativeLibDir = origin.ifEmpty { null },
                apkPath = "",
                missing = emptySet(),
            )
        }
        return HandoffTarget(manager, origin, kmi)
    }

    /** All decodable entries, in order; malformed lines are dropped. */
    fun decodeAll(lines: List<String>): List<HandoffTarget> =
        lines.mapNotNull { decode(it) }
}
