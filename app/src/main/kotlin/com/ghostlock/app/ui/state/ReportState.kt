package com.ghostlock.app.ui.state

import androidx.compose.runtime.Stable
import com.ghostlock.app.domain.model.CrashEvidenceInfo
import com.ghostlock.app.domain.model.RunLogEntry
import com.ghostlock.app.ui.ReportWizardStep

/**
 * The report flow's slice: which wizard screen is up, the evidence in hand,
 * the retained runs, and the last bundle written. Derived from the view
 * model's single state flow; every mutation goes through its actions.
 */
@Stable
data class ReportState(
    val step: ReportWizardStep = ReportWizardStep.Choice,
    /** The screens this visit will pass through, in order. */
    val steps: List<ReportWizardStep> = emptyList(),
    val lastReportName: String? = null,
    val lastReportUri: String? = null,
    val exportDone: Boolean = false,
    val runLogs: List<RunLogEntry> = emptyList(),
    val pickerVisible: Boolean = false,
    val importing: Boolean = false,
    val generating: Boolean = false,
    val crashEvidence: CrashEvidenceInfo? = null,
    val crashShellAvailable: Boolean = false,
)
