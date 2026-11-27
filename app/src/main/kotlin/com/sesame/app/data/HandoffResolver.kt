package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffResolution
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.HandoffVerdict
import com.sesame.app.domain.model.RootManager
import com.sesame.app.domain.model.RootManagerCandidate

/**
 * The handoff rule set (handoff-resolution-plan.md §2.2/§8): the stored
 * choice, the detected managers and the device's KMI become a
 * [HandoffResolution] — a verdict per manager and an ordered attempt list.
 * Pure: no I/O, no staging, no profile, no route. Every route gets the same
 * answer, and the picker can call the same function.
 *
 * KSU feasibility is KSU's own check, done statically: the device's KMI
 * (derived by KSU's parser) must be one of the manager's embedded
 * `<kmi>_kernelsu.ko` assets. A manager that carries no such asset names falls
 * back to the release mirror. Asset membership is a necessary condition only —
 * the ordered attempt loop remains the authority on a real load. Magisk is
 * feasible when its required native libs are present.
 */
object HandoffResolver {

    private const val KSU_ABSENT = "no ksud with late-load support"
    private const val MAGISK_ABSENT = "not installed"

    /**
     * One verdict per discovered manager, in detector order (KSU candidates
     * first, then Magisk), and the feasible rows in attempt order: the stored
     * choice's row first, then detector order. An empty [HandoffResolution.feasible]
     * is the refusal.
     */
    fun resolve(
        release: String,
        ksu: List<ManagerScanner.Candidate>,
        currentKmi: ManagerScanner.CurrentKmi?,
        magisk: List<RootManager.Magisk>,
        storedChoice: String?,
    ): HandoffResolution {
        val verdicts = ArrayList<HandoffVerdict>(ksu.size + magisk.size)
        for (candidate in ksu) {
            if (!candidate.suitable) {
                verdicts += HandoffVerdict(candidate.toManager(), HandoffState.INFEASIBLE, candidate.verdict)
                continue
            }
            val rule = ksuRule(release, candidate, currentKmi)
            verdicts += HandoffVerdict(candidate.toManager(), rule.state, rule.reason, rule.kmi)
        }
        for (manager in magisk) {
            val state = if (manager.missing.isEmpty()) HandoffState.FEASIBLE else HandoffState.INFEASIBLE
            val reason = if (manager.missing.isEmpty()) {
                "binaries present (${MAGISK_REQUIRED.joinToString(", ")})"
            } else {
                "native libs incomplete (missing ${manager.missing.sorted().joinToString(", ")})"
            }
            verdicts += HandoffVerdict(manager, state, reason)
        }
        return HandoffResolution(verdicts, orderFeasible(verdicts, storedChoice))
    }

    /** The libs [RootManager.Magisk] must carry for the handoff to run. */
    val MAGISK_REQUIRED = listOf("magisk", "magiskinit", "magiskpolicy")

    private data class Rule(val state: HandoffState, val reason: String, val kmi: String? = null)

    /**
     * KSU's own rule, statically: the device KMI must be one of the manager's
     * embedded `<kmi>_kernelsu.ko` assets (the set `supported-kmis` prints).
     * A manager whose payload carries no asset names falls back to the release
     * mirror, which still prefers the KSU-derived KMI when it has one.
     */
    private fun ksuRule(
        release: String,
        candidate: ManagerScanner.Candidate,
        currentKmi: ManagerScanner.CurrentKmi?,
    ): Rule {
        if (candidate.kmis.isNotEmpty()) {
            val current = currentKmi?.value
                ?: return Rule(
                    HandoffState.INFEASIBLE,
                    currentKmi?.failure ?: "Failed to get KMI from boot/modules",
                )
            if (current !in candidate.kmis) {
                return Rule(
                    HandoffState.INFEASIBLE,
                    "provides ${candidate.kmis.joinToString(", ")}; kernel is $current",
                )
            }
            return Rule(HandoffState.FEASIBLE, "provides $current", current)
        }
        return releaseFallback(release, currentKmi?.value)
    }

    /**
     * The release mirror for a manager that carries no KMI assets:
     * `ops.cpp`'s old derivation plus the `<5.10` line the script never had.
     * When KSU's own parser already answered (the vermagic branch included),
     * its token is the one `late-load` receives and only the line check
     * remains.
     */
    private fun releaseFallback(release: String, current: String?): Rule {
        if (current != null) {
            val line = current.substringAfter('-', "")
            val major = line.substringBefore('.').toIntOrNull()
            val minor = line.substringAfter('.', "").toIntOrNull()
            if (major != null && minor != null && (major < 5 || (major == 5 && minor < 10))) {
                return Rule(HandoffState.INFEASIBLE, "kernel line <5.10 has no late-load module")
            }
            return Rule(HandoffState.FEASIBLE, "release mirror (no KMI assets): $current", current)
        }
        val version = release.substringBefore('-')
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()
        if (major == null || minor == null) {
            return Rule(HandoffState.INFEASIBLE, "uname -r has no numeric <major>.<minor>")
        }
        if (major < 5 || (major == 5 && minor < 10)) {
            return Rule(HandoffState.INFEASIBLE, "kernel line <5.10 has no late-load module")
        }
        val android = ANDROID_TOKEN.find(release)?.value
            ?: return Rule(HandoffState.INFEASIBLE, "uname -r carries no android<NN> token")
        val kmi = "$android-$major.$minor"
        return Rule(HandoffState.FEASIBLE, "release fallback: $kmi", kmi)
    }

    private val ANDROID_TOKEN = Regex("android[0-9]+")

    /** The stored choice's first feasible row is moved to the head; the rest keep detector order. */
    private fun orderFeasible(
        verdicts: List<HandoffVerdict>,
        storedChoice: String?,
    ): List<HandoffVerdict> {
        val feasible = verdicts.filter { it.state == HandoffState.FEASIBLE }
        if (storedChoice == null) return feasible
        val index = feasible.indexOfFirst { it.manager.packageName == storedChoice }
        if (index <= 0) return feasible
        return listOf(feasible[index]) + feasible.filterIndexed { i, _ -> i != index }
    }

    /**
     * The picker's rows: every manager the user could act on — a KSU candidate
     * that passed candidacy, and every installed Magisk — with the resolver's
     * feasibility and evidence. Unsuitable KSU candidates are not choices (the
     * run's meta still records them).
     */
    fun choices(resolution: HandoffResolution): List<RootManagerCandidate> =
        resolution.verdicts.mapNotNull { verdict ->
            when (val manager = verdict.manager) {
                is RootManager.Ksu -> {
                    if (!manager.suitable) {
                        null
                    } else {
                        RootManagerCandidate(
                            packageName = manager.packageName,
                            label = manager.label,
                            path = manager.ksudPath,
                            kind = HandoffKind.KSU,
                            verdict = verdict.reason,
                            feasible = verdict.state == HandoffState.FEASIBLE,
                        )
                    }
                }

                is RootManager.Magisk -> RootManagerCandidate(
                    packageName = manager.packageName,
                    label = manager.label,
                    path = manager.nativeLibDir.orEmpty(),
                    kind = HandoffKind.MAGISK,
                    verdict = verdict.reason,
                    feasible = verdict.state == HandoffState.FEASIBLE,
                )
            }
        }

    /**
     * The family lines (run log, run-store meta, refusal, picker headers): a
     * projection computed where it is printed, never stored. A feasible row of
     * that kind wins; else the first row's reason; else a fixed absent line.
     */
    fun familyLines(resolution: HandoffResolution, storedChoice: String?): List<String> {
        val globalHead = resolution.feasible.firstOrNull()
        return HandoffKind.entries.map { kind ->
            val feasible = resolution.feasibleOf(kind)
            val rows = resolution.verdictsOf(kind)
            val storedRow = rows.firstOrNull { it.manager.packageName == storedChoice }
            val reason = when {
                feasible.isNotEmpty() -> {
                    val head = feasible.first()
                    when {
                        head.manager.packageName == storedChoice ->
                            "selected:${head.manager.packageName}"

                        storedRow != null && storedRow.state == HandoffState.INFEASIBLE ->
                            "selected:${storedChoice} infeasible — using " +
                                head.manager.packageName

                        else -> "available (${feasible.size} candidate" +
                            (if (feasible.size == 1) "" else "s") + ")"
                    }
                }

                rows.isEmpty() -> if (kind == HandoffKind.KSU) KSU_ABSENT else MAGISK_ABSENT

                /* The stored choice lives in this family and cannot run: name
                 * it and the fallback the run will use, keeping its reason. */
                storedRow != null && storedRow.state == HandoffState.INFEASIBLE &&
                    globalHead != null ->
                    "selected:${storedChoice} infeasible — using " +
                        "${globalHead.manager.packageName}; ${storedRow.reason}"

                else -> rows.first().reason
            }
            val state = if (feasible.isEmpty()) "infeasible" else "feasible"
            "handoff=${kind.wire} state=$state reason=$reason"
        }
    }
}
