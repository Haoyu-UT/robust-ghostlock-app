package com.ghostlock.app.ui

import com.ghostlock.app.domain.model.ShizukuStatus

/**
 * The support verdict — three facts only (2026-11-21 operator decision):
 * a profile exists (builtin or imported), a profile exists but access is not
 * ready, or nothing exists. A profile that fails verification is rejected at
 * import/generation and never reaches this state, so there is no blocked or
 * invalid state here.
 */
sealed interface SupportStatus {
    data object Ready : SupportStatus
    data class AccessPending(val status: ShizukuStatus) : SupportStatus
    data object Missing : SupportStatus
}

internal fun supportStatusOf(state: GhostlockUiState): SupportStatus = when {
    !state.executionHasProfile -> SupportStatus.Missing
    state.shizukuEnabled && state.shizukuStatus != ShizukuStatus.READY ->
        SupportStatus.AccessPending(state.shizukuStatus)
    else -> SupportStatus.Ready
}
