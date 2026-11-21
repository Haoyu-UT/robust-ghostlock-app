package com.ghostlock.app.ui.state

import androidx.compose.runtime.Stable

/**
 * The builtin-profile picker's slice: the bundled releases and the unfilled
 * reference templates, plus the manually selected release (null means Auto).
 */
@Stable
data class BuiltinState(
    val releases: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
    val active: String? = null,
)
