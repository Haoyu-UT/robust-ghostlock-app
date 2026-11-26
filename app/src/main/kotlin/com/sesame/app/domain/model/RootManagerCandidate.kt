package com.sesame.app.domain.model

/**
 * One installed root manager, as the picker lists it — the resolver's verdict,
 * not a second scan. [feasible] is whether the run's rules would use it; a
 * manager that is installed but infeasible stays visible with [verdict] as its
 * reason, because hiding it would make the run's refusal look arbitrary.
 */
data class RootManagerCandidate(
    val packageName: String,
    val label: String,
    /** The ksud path (KSU) or the manager's native lib dir (Magisk). */
    val path: String,
    val kind: HandoffKind,
    /** The evidence when feasible ("interface confirmed: …"), else the reason. */
    val verdict: String,
    val feasible: Boolean,
)
