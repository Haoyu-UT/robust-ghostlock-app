package com.sesame.app.ui

import com.sesame.app.R
import com.sesame.app.domain.model.LockState
import com.sesame.app.domain.model.ProfileLayers

/**
 * One typed dialog. The UI state holds at most one at a time; the old
 * type/title/items/input postback fields are gone with it.
 */
sealed interface AppDialog {
    val titleRes: Int

    data class Notice(
        override val titleRes: Int,
        val messageRes: Int = 0,
        val message: String = "",
        val docUrl: String? = null,
        /** Offer "export log & report" on this dialog. */
        val exportLog: Boolean = false,
        val dismissLabelRes: Int = R.string.dialog_dismiss,
    ) : AppDialog

    data class Confirm(
        override val titleRes: Int,
        val messageRes: Int = 0,
        val message: String = "",
        val confirmLabelRes: Int,
        val dismissLabelRes: Int = R.string.cancel,
        val exportLog: Boolean = false,
    ) : AppDialog

    data class ListDialog(
        override val titleRes: Int,
        /** Optional explanation above the choices (the phys image prompts). */
        val messageRes: Int = 0,
        val items: List<String> = emptyList(),
        val itemResIds: List<Int> = emptyList(),
        /** Optional success-grade chip per item (route picker). */
        val grades: List<DialogChip?> = emptyList(),
        /** A leading row given as a string resource (the page option row). */
        val leadingResId: Int? = null,
        val currentIndex: Int = -1,
    ) : AppDialog

    /**
     * The route matrix: the backend banners (GhostLock, with its syscall /
     * carrier / lock / DirtyFrag choices, and the timer). [lock] is parsed from
     * the current document when the sheet opens — capability is not app state —
     * and the sheet's edits are a draft until 保存.
     */
    data class RouteMatrix(val lock: LockState?) : AppDialog {
        override val titleRes: Int = R.string.route_choice_title
    }

    data class Input(
        override val titleRes: Int,
        val labelRes: Int,
        val value: String = "",
        val confirmLabelRes: Int,
    ) : AppDialog

    data class NotificationPermission(
        override val titleRes: Int,
        val messageRes: Int,
    ) : AppDialog

    /**
     * The OEM app-list permission rationale (TAF 108-2022). [message] carries
     * the refusal's reason lines when the dialog answers a blocked run;
     * otherwise [messageRes] is the startup rationale.
     */
    data class AppListPermission(
        override val titleRes: Int,
        val messageRes: Int = 0,
        val message: String = "",
    ) : AppDialog

    /** A permanent app-list denial: the system settings page is the only path. */
    data class AppListSettings(
        override val titleRes: Int,
        val messageRes: Int,
    ) : AppDialog

    /**
     * A blocking, non-dismissible progress dialog for a long step that is part
     * of the generation flow (the stride probe). It has no buttons: the step
     * ends on its own, and back/tap must not leave the flow half-done.
     */
    data class Progress(
        override val titleRes: Int,
        val messageRes: Int,
    ) : AppDialog
}
