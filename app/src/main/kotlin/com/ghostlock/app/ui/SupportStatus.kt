package com.ghostlock.app.ui

import com.ghostlock.app.domain.model.ShizukuStatus

/**
 * The one support verdict the Home card and the run gate render. V1 derives it
 * from the existing facts in one pure function; N3 turns it into the stored
 * `SessionState.support` with a single writer and a generation (see
 * `docs/analysis/support-status-state-plan.md`).
 */
sealed interface SupportStatus {
    data object Ready : SupportStatus
    data class AccessPending(val status: ShizukuStatus) : SupportStatus
    data class FieldsInvalid(val paths: Set<String>) : SupportStatus
    data object PhysicalPairMissing : SupportStatus
    data object ProfileMissing : SupportStatus
    data object CopyUnreadable : SupportStatus
    data class ProfileBlocked(val reason: String) : SupportStatus
    data object KernelUnsupported : SupportStatus
    data class ResolutionFailed(val detail: String?) : SupportStatus
}

internal fun supportStatusOf(state: GhostlockUiState): SupportStatus {
    val layers = state.profileLayers
    val blocked = layers?.externalError
    return when {
        state.externalProfileUnreadable -> SupportStatus.CopyUnreadable
        blocked != null -> SupportStatus.ProfileBlocked(blocked)
        !state.kernelSupported -> SupportStatus.KernelUnsupported
        state.devicePairMissing -> SupportStatus.PhysicalPairMissing
        state.profileInvalidPaths.isNotEmpty() ->
            SupportStatus.FieldsInvalid(state.profileInvalidPaths)
        !state.executionHasProfile -> SupportStatus.ProfileMissing
        state.shizukuEnabled && state.shizukuStatus != ShizukuStatus.READY ->
            SupportStatus.AccessPending(state.shizukuStatus)
        else -> SupportStatus.Ready
    }
}
