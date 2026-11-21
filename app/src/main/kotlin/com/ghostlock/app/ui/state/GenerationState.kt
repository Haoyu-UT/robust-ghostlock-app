package com.ghostlock.app.ui.state

import androidx.compose.runtime.Stable
import com.ghostlock.app.ui.GenerationStep

/** The profile-generation flow's slice: which of the screen's steps is up. */
@Stable
data class GenerationState(
    val step: GenerationStep = GenerationStep.Idle,
)
