package com.ghostlock.app.ui.state

/**
 * The narrow action surfaces the four main screens take. Each one names only
 * the verbs its screen uses; the view model adapts them onto its own methods.
 */
interface HomeActions {
    fun run()
    fun statusClick()
    fun showDeviceInfo()
    fun openLastRunLog()
    fun exportReport()
    fun openAdvanced()
    fun openGeneration()
    fun selectProfileFile()
    fun openParameters()
}

interface RunSettingsActions {
    fun cpuPairSelected(index: Int)
    fun safeModeChanged(enabled: Boolean)
    fun shizukuChanged(enabled: Boolean)
    fun openVrKo()
    fun close()
}

interface AdvancedActions {
    fun close()
    fun openRunSettings()
    fun forceAttackChanged(enabled: Boolean)
    fun openManagerPicker()
    fun openParameters()
    fun openMeasurements()
    fun debugKernelLogChanged(enabled: Boolean)
    fun showAbout()
}

interface ParametersActions {
    fun close()
    fun openBuiltin()
    fun exportProfile()
    fun clearExternalProfile()
}
