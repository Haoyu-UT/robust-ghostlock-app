package com.sesame.app.data.profile

/**
 * The carrier vocabulary a route branch declares.
 *
 * The document no longer carries a per-route `route.<name>.compat_carrier`
 * flag: a branch declares its carriers by the blocks it states
 * (`ghostlock.syscalls.<name>.native` / `.compat`), and the app injects the
 * wire's `compat_carrier` from the chosen carrier when it cuts the run.
 *
 * See docs/analysis/route-choice-matrix-plan.md.
 */
object RouteCarriers {
    const val Native = "native"
    const val Compat = "compat"
}
