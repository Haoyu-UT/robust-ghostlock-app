package com.sesame.app.domain.model

/**
 * The root-manager handoff model (handoff-resolution-plan.md §2.2): one
 * detector, one rule set, one verdict per discovered manager, and an ordered
 * attempt list. The transport stages the list; the native reads it.
 */

/** The manager families the root script can hand off to. [wire] is the token
 *  used by the transport list and the log lines. */
enum class HandoffKind(val wire: String) {
    KSU("ksu"),
    MAGISK("magisk"),
}

enum class HandoffState {
    FEASIBLE,
    INFEASIBLE,
}

/**
 * One discovered manager. Identity is the package plus its payload path: the
 * KSU scanner emits one row per executable-shaped ELF, so the same package can
 * appear more than once. Detection never stages anything.
 */
sealed interface RootManager {
    val kind: HandoffKind
    val packageName: String
    val label: String

    /** A KernelSU-family ksud that the scanner admitted as a candidate. */
    data class Ksu(
        override val packageName: String,
        override val label: String,
        val ksudPath: String,
        /** null = the `late-load --help` probe could not run. */
        val interfaceOk: Boolean?,
        /** `ManagerScanner.Candidate.suitable` — the candidacy gate. */
        val suitable: Boolean,
    ) : RootManager {
        override val kind: HandoffKind get() = HandoffKind.KSU
    }

    /**
     * An installed Magisk manager. [missing] names required native libs that
     * neither the extracted native dir nor the APK carries, so an empty set is
     * the "binaries present" fact.
     */
    data class Magisk(
        override val packageName: String,
        override val label: String,
        val nativeLibDir: String?,
        val apkPath: String,
        val missing: Set<String>,
    ) : RootManager {
        override val kind: HandoffKind get() = HandoffKind.MAGISK
    }
}

/**
 * One row per discovered manager, in detector order. A rejected row keeps its
 * reason; [manager] is never null.
 */
data class HandoffVerdict(
    val manager: RootManager,
    val state: HandoffState,
    /** Required; doubles as the evidence line. */
    val reason: String,
    /**
     * The KMI the resolver used for this row (the manager's own `current-kmi`
     * answer, or the release-mirror fallback). KSU only; null for Magisk.
     */
    val kmi: String? = null,
)

/**
 * The precondition's result: every candidate judged, nothing staged yet.
 * [feasible] is the attempt order — the stored choice first, then detector
 * order. An empty list means the run is refused.
 */
data class HandoffResolution(
    val verdicts: List<HandoffVerdict>,
    val feasible: List<HandoffVerdict>,
) {
    fun feasibleOf(kind: HandoffKind): List<HandoffVerdict> =
        feasible.filter { it.manager.kind == kind }

    fun verdictsOf(kind: HandoffKind): List<HandoffVerdict> =
        verdicts.filter { it.manager.kind == kind }

    val isEmpty: Boolean get() = feasible.isEmpty()
}

/**
 * A feasible manager plus the payload path *this process* staged for it, and
 * (KSU) the KMI the script must pass verbatim. [source] is process-local: the
 * app's filesDir copy, the service's working dir copy, the timer's workdir
 * copy — never a path from another uid's sandbox.
 */
data class HandoffTarget(
    val manager: RootManager,
    val source: String,
    val kmi: String?,
)
