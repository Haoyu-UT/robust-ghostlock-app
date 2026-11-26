package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffResolution
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.HandoffVerdict
import com.sesame.app.domain.model.RootManager

/**
 * The handoff rule set (handoff-resolution-plan.md §2.2/§2.4): the stored
 * choice, the detected managers and the managers' own KMI answers become a
 * [HandoffResolution] — a verdict per manager and an ordered attempt list.
 * Pure: no I/O, no staging, no profile, no route. Every route gets the same
 * answer, and the picker can call the same function.
 *
 * KSU feasibility is the manager's own interface: `boot-info supported-kmis`
 * succeeds and `boot-info current-kmi` yields a member of that list. A manager
 * that cannot answer falls back to the release mirror (the script's old
 * derivation plus the `<5.10` line). Magisk is feasible when its required
 * native libs are present.
 */
object HandoffResolver {

    private const val KSU_ABSENT = "no ksud with late-load --help"
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
        kmiAnswers: Map<String, ManagerScanner.KmiAnswer>,
        magisk: List<RootManager.Magisk>,
        storedChoice: String?,
    ): HandoffResolution {
        val verdicts = ArrayList<HandoffVerdict>(ksu.size + magisk.size)
        for (candidate in ksu) {
            if (!candidate.suitable) {
                verdicts += HandoffVerdict(candidate.toManager(), HandoffState.INFEASIBLE, candidate.verdict)
                continue
            }
            val rule = ksuRule(release, kmiAnswers[candidate.path])
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
     * The manager's own interface first; the release mirror only when the
     * manager cannot answer (no `boot-info`, or `supported-kmis` failing).
     */
    private fun ksuRule(release: String, answer: ManagerScanner.KmiAnswer?): Rule {
        if (answer?.supported != null) {
            val current = answer.current
                ?: return Rule(
                    HandoffState.INFEASIBLE,
                    answer.failure ?: "manager cannot report its KMI",
                )
            if (current !in answer.supported) {
                return Rule(
                    HandoffState.INFEASIBLE,
                    "supported KMI list does not contain $current",
                )
            }
            return Rule(HandoffState.FEASIBLE, "supported-kmis lists $current", current)
        }
        return releaseFallback(release)
    }

    /**
     * The release mirror: `ops.cpp`'s old derivation plus the `<5.10` line the
     * script never had (it refused 5.4 only because the uname carried no
     * android token). The KMI it derives is the value `late-load` receives.
     */
    private fun releaseFallback(release: String): Rule {
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
     * The family lines (run log, run-store meta, refusal, picker headers): a
     * projection computed where it is printed, never stored. A feasible row of
     * that kind wins; else the first row's reason; else a fixed absent line.
     */
    fun familyLines(resolution: HandoffResolution, storedChoice: String?): List<String> =
        HandoffKind.entries.map { kind ->
            val feasible = resolution.feasibleOf(kind)
            val rows = resolution.verdictsOf(kind)
            val reason = when {
                feasible.isNotEmpty() -> {
                    val head = feasible.first()
                    if (head.manager.packageName == storedChoice) {
                        "selected:${head.manager.packageName}"
                    } else {
                        "available (${feasible.size} candidate" +
                            (if (feasible.size == 1) "" else "s") + ")"
                    }
                }

                rows.isNotEmpty() -> rows.first().reason
                kind == HandoffKind.KSU -> KSU_ABSENT
                else -> MAGISK_ABSENT
            }
            val state = if (feasible.isEmpty()) "infeasible" else "feasible"
            "handoff=${kind.wire} state=$state reason=$reason"
        }
}
