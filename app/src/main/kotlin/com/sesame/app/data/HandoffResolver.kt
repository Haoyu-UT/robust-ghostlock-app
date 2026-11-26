package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffResolution
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.HandoffVerdict
import com.sesame.app.domain.model.RootManager

/**
 * The handoff rule set (handoff-resolution-plan.md §2.2): the stored choice and
 * the detected managers become a [HandoffResolution] — a verdict per manager
 * and an ordered attempt list. Pure: no I/O, no staging, no profile, no route.
 * Every route gets the same answer, and the picker can call the same function.
 *
 * S1 scope: the KSU rule is today's candidacy gate (`Candidate.suitable`).
 * Magisk rows are detected and reported but not selectable yet — S2 adds their
 * feasibility rule, the KMI probes and the release fallback.
 */
object HandoffResolver {

    /** S2 replaces this with the libs-present rule. */
    private const val MAGISK_NOT_SELECTABLE = "Magisk handoff is not enabled in this build"

    private const val KSU_ABSENT = "no ksud with late-load --help"
    private const val MAGISK_ABSENT = "not installed"

    /**
     * One verdict per discovered manager, in detector order (KSU candidates
     * first, then Magisk), and the feasible rows in attempt order: the stored
     * choice's row first, then detector order. An empty [HandoffResolution.feasible]
     * is the refusal.
     */
    fun resolve(
        ksu: List<ManagerScanner.Candidate>,
        magisk: List<RootManager.Magisk>,
        storedChoice: String?,
    ): HandoffResolution {
        val verdicts = ArrayList<HandoffVerdict>(ksu.size + magisk.size)
        for (candidate in ksu) {
            verdicts += HandoffVerdict(
                manager = candidate.toManager(),
                state = if (candidate.suitable) HandoffState.FEASIBLE else HandoffState.INFEASIBLE,
                reason = candidate.verdict,
            )
        }
        for (manager in magisk) {
            /* S1: detected, never selectable. An already-incomplete manager
             * keeps the precise reason S2 will use. */
            val reason = if (manager.missing.isEmpty()) {
                MAGISK_NOT_SELECTABLE
            } else {
                "native libs incomplete (missing ${manager.missing.sorted().joinToString(", ")})"
            }
            verdicts += HandoffVerdict(manager, HandoffState.INFEASIBLE, reason)
        }
        return HandoffResolution(verdicts, orderFeasible(verdicts, storedChoice))
    }

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
