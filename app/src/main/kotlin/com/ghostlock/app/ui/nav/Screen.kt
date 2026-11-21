package com.ghostlock.app.ui.nav

import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * The app's screens — the only navigation vocabulary. The stack of these is the
 * single source of navigation state; no screen owns a `*Visible` flag.
 */
internal sealed interface Screen : NavKey {
    data object Main : Screen
    data object VrKo : Screen
    data object Advanced : Screen
    data object About : Screen
    data object Measurements : Screen
    data object Parameters : Screen
    data object LoadConfig : Screen
    data object Builtin : Screen
    data object MergePreview : Screen
    data object ProfileDiff : Screen
    data object Legacy : Screen
    data object ProfileOverride : Screen
    data object AdvancedOverride : Screen
}
