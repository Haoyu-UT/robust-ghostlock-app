package com.sesame.app.domain.model

/**
 * Why the run-start pre-flight refused to start the native.
 *
 * Only failures the app can decide from its own state belong here (class 1 of
 * the 2026-11-07 exit audit): they are checked before the log sheet opens, so
 * no run-store entry and no native process are created. Post-run patterns
 * (dirty race, chain failures, signals) are a separate mapping.
 */
enum class PreflightFailure {
    /** The anchor state file is absent/unreadable and the install postdates the boot. */
    ANCHOR_STATE_UNCONFIRMED,

    /** Every anchor slot this boot is spent; another attempt would walk live .bss. */
    ANCHOR_BUDGET_SPENT,

    /** The state file's slot grid is not this profile's grid (or its count is invalid). */
    ANCHOR_GRID_MISMATCH,

    /** No late-load-capable KernelSU-family manager is installed. */
    NO_ROOT_MANAGER,

    /**
     * No manager was found and the system may be hiding the installed-app
     * list from this app (the OEM app-list permission is not granted, or
     * package visibility filters the enumeration). The dialog says so and
     * offers the grant when the permission exists.
     */
    APP_LIST_RESTRICTED,
}

/**
 * The OEM app-list permission's support and grant state (TAF 108-2022,
 * `com.android.permission.GET_INSTALLED_APPS`). [supported] is the OS listing
 * the permission; [granted] is the per-app runtime grant. The app asks
 * whenever the OS lists it and the grant is off — probe-first detection does
 * not suppress the ask (manager-detection-visibility-plan §4.3).
 */
data class AppListPermissionState(
    val supported: Boolean,
    val definingPackage: String?,
    val granted: Boolean,
) {
    val canAsk: Boolean get() = supported && !granted
}

sealed interface PreflightResult {
    /**
     * The run may start. [plan] is the handoff resolution the run consumes —
     * returned rather than stashed, so a plan cannot outlive its pre-flight.
     * [appList] is the one-line visibility audit for the run log.
     */
    data class Passed(
        val plan: HandoffResolution,
        val appList: String? = null,
    ) : PreflightResult

    /**
     * [reasons] carries the derived family lines (for the no-manager refusal)
     * so the dialog can name both families and why each was rejected.
     */
    data class Blocked(
        val failure: PreflightFailure,
        val reasons: List<String> = emptyList(),
    ) : PreflightResult
}
