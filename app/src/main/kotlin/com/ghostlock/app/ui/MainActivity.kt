package com.ghostlock.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghostlock.app.GhostlockApplication
import com.ghostlock.app.BuildConfig
import com.ghostlock.app.R
import com.ghostlock.app.domain.model.VrKoMode

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<GhostlockViewModel> {
        viewModelFactory {
            initializer {
                GhostlockViewModel(
                    (application as GhostlockApplication).createRepository(),
                    application,
                )
            }
        }
    }

    private var pendingDocumentRequest: DocumentRequest? = null
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val request = pendingDocumentRequest
        pendingDocumentRequest = null
        if (uri != null && request != null) viewModel.onDocumentResult(request, uri.toString())
    }

    private val profileDocumentCreator =
        /* octet-stream, not text/plain: SAF appends ".txt" to a text/plain
         * document whose name is not a recognised text extension, which turned
         * "…conf" into "…conf.txt". */
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            viewModel.onExportProfileDocumentPicked(uri?.toString())
        }

    /* The whole report: every run plus the crash-evidence captures. */
    private val reportBundleDocumentCreator =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            viewModel.onExportReportBundleDocumentPicked(uri?.toString())
        }

    /* A user-supplied bugreport. The picker is opened at the system's "Bug
     * reports" root when it exists; the importer validates the zip. */
    private val bugreportPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onBugreportPicked(result.data?.data?.toString())
            }
        }

    /* The run-status notification is the run's visible proof and the reason the
     * app keeps running when the KSU manager takes the foreground. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize()
        handleSendIntent(intent)
        /* Debug-only test hook: `am start --ez second_way true` turns the second
         * way on and persists it, so a harness restart keeps the setting. */
        if (BuildConfig.DEBUG && intent?.hasExtra("second_way") == true) {
            viewModel.toggleSecondWay(intent.getBooleanExtra("second_way", false))
        }
        /* Debug-only: `am start --ez probe_check true` runs the DirtyFrag
         * viability probe without an attack and records a clear result. */
        if (BuildConfig.DEBUG && intent?.hasExtra("probe_check") == true) {
            if (intent.getBooleanExtra("probe_marker", false)) {
                /* Seeds the watchdog marker a killed probe would leave. */
                File(filesDir, ".df-probe-in-progress").writeText("seeded by probe_marker")
            }
            viewModel.runDirtyfragProbeCheck()
        }
        /* Debug-only: `am start --ez preflight_check true` runs the ROM
         * preflight (the stage's cave/hook analysis) without an attack. */
        if (BuildConfig.DEBUG && intent?.hasExtra("preflight_check") == true) {
            viewModel.runDirtyfragPreflightCheck()
        }
        /* Debug-only: `am start --ez fallback_test true` arms the one-shot
         * pre-W1 fallback gate (two-entry order + a forced DirtyFrag failure). */
        if (BuildConfig.DEBUG && intent?.hasExtra("fallback_test") == true) {
            viewModel.setFallbackTest(intent.getBooleanExtra("fallback_test", false))
        }
        setContent {
            GhostlockRoute(viewModel, ::handleEffect)
        }
        setupSystemBars()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessStatus()
        /* Returning from About phone is how the Developer-options gate ends;
         * the report screen advances to the generation instructions. */
        viewModel.onReportResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    /** "Share" from the system bugreport notification lands here. */
    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) viewModel.onBugreportPicked(uri.toString())
    }

    private fun handleEffect(effect: GhostlockEffect) {
        when (effect) {
            is GhostlockEffect.PickDocument -> {
                pendingDocumentRequest = effect.request
                /* A profile document may carry any extension/MIME, so the
                 * picker stays unfiltered; the app validates the entry. */
                documentPicker.launch(arrayOf("*/*"))
            }

            is GhostlockEffect.CreateReportBundleDocument ->
                reportBundleDocumentCreator.launch(effect.suggestedName)

            GhostlockEffect.PickBugreport -> launchBugreportPicker()

            GhostlockEffect.OpenDeveloperOptions ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

            GhostlockEffect.OpenAboutPhone ->
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

            is GhostlockEffect.ShareFile -> shareFile(effect.uri.toUri())

            is GhostlockEffect.ToastText ->
                Toast.makeText(this, effect.text, Toast.LENGTH_LONG).show()

            is GhostlockEffect.CreateProfileDocument ->
                profileDocumentCreator.launch(effect.suggestedName)

            is GhostlockEffect.Share -> shareOffsets(effect.uri.toUri())
            is GhostlockEffect.Toast -> Toast.makeText(this, effect.resourceId, Toast.LENGTH_SHORT).show()
            is GhostlockEffect.Clipboard -> {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("ghostlock-log", effect.text))
            }

            is GhostlockEffect.KeepScreenAwake -> if (effect.enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            GhostlockEffect.OpenShizuku -> packageManager
                .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?.let(::startActivity)

            GhostlockEffect.RequestNotificationPermission -> if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            is GhostlockEffect.OpenManager -> {
                val launch = packageManager.getLaunchIntentForPackage(effect.packageName)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    Toast.makeText(this, R.string.run_notif_manager_missing, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Opens the system picker at the shell app's "Bug reports" root. The
     * provider is protected by MANAGE_DOCUMENTS — which the app cannot hold —
     * but the system picker has it, and the returned document comes with a
     * read grant the importer can use. On a ROM without the provider the
     * initial URI is ignored and the normal picker location is shown.
     *
     * The root URI is the working form; the document URI is not accepted, and
     * an already-running picker instance may ignore a new initial URI.
     */
    private fun launchBugreportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            /* The report is the full bugreport zip; the sibling dumpstate log
             * and stats files are text and are not the report. The extras
             * enable the zip while dimming those text siblings. */
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(
                    SHELL_BUGREPORT_AUTHORITY,
                    SHELL_BUGREPORT_ROOT,
                ),
            )
        }
        bugreportPicker.launch(intent)
    }

    /** Hands the finished bundle to the system share sheet. */
    private fun shareFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(intent, getString(R.string.report_share_chooser)),
        )
    }

    private fun shareOffsets(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_share)))
    }

    private fun setupSystemBars() {
        val controller = window.decorView.windowInsetsController ?: return
        val lightStatus = if (resources.getBoolean(R.bool.window_light_status_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else {
            0
        }
        val lightNavigation = if (resources.getBoolean(R.bool.window_light_navigation_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        } else {
            0
        }
        controller.setSystemBarsAppearance(
            lightStatus or lightNavigation,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /* The shell app's DocumentsProvider and the root it exposes for
         * completed bugreports (AOSP's BugreportStorageProvider). */
        const val SHELL_BUGREPORT_AUTHORITY = "com.android.shell.documents"
        const val SHELL_BUGREPORT_ROOT = "bugreport"
    }
}

@Composable
private fun GhostlockRoute(
    viewModel: GhostlockViewModel,
    onEffect: (GhostlockEffect) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect(onEffect)
    }
    GhostlockApp(
        state = state,
        actions = object : GhostlockActions {
            override fun onRun() = viewModel.onRun()
            override fun onProfileInvalid() = viewModel.onProfileInvalid()
            override fun onStatusClick() = viewModel.onStatusClick()
            override fun onCloseExecutionSheet() = viewModel.onCloseExecutionSheet()
            override fun onCopyLogs() = viewModel.copyLogs()
            override fun onParseOta() = viewModel.promptParseUrl()
            override fun onParseImage() = viewModel.parseOffsets()
            override fun onGenerateProfile() = viewModel.promptParseSource()
            override fun onCpuPairSelected(index: Int) = viewModel.selectCpuPair(index)
            override fun onSafeModeChanged(enabled: Boolean) = viewModel.toggleSafeMode(enabled)
            override fun onForceAttackTestChanged(enabled: Boolean) =
                viewModel.toggleForceAttackTest(enabled)

            override fun onVrKoTagClearChanged(mode: VrKoMode) =
                viewModel.setVrKoTagClear(mode)

            override fun onVrKoSelinuxBlockChanged(mode: VrKoMode) =
                viewModel.setVrKoSelinuxBlock(mode)

            override fun onShizukuChanged(enabled: Boolean) = viewModel.toggleShizuku(enabled)
            override fun onOpenManagerPicker() = viewModel.onOpenManagerPicker()
            override fun onOpenManager() = viewModel.onOpenManager()
            override fun onDialogItemSelected(index: Int) = viewModel.onDialogItemSelected(index)
            override fun onDialogInputChange(value: String) = viewModel.onDialogInputChange(value)
            override fun onDialogConfirm(value: String) = viewModel.onDialogConfirm(value)
            override fun onDialogDismiss() = viewModel.onDialogDismiss()
            override fun onDialogDismissFinished() = viewModel.onDialogDismissFinished()
            override fun onOverwriteConfirm() = viewModel.onOverwriteConfirm()
            override fun onOverwriteDismiss() = viewModel.onOverwriteDismiss()
            override fun onExecutionFieldChanged(path: String, value: String) =
                viewModel.updateExecutionField(path, value)

            override fun onRouteChanged(index: Int) = viewModel.onRouteChanged(index)
            override fun onFallbackChanged(index: Int) = viewModel.onFallbackChanged(index)
            override fun onExportProfile() = viewModel.onExportProfile()
            override fun onSaveProfileEdits() = viewModel.onSaveProfileEdits()
            override fun onSaveProfileAs() = viewModel.onSaveProfileAs()
            override fun onExportProfileEdits() = viewModel.onExportProfileEdits()
            override fun onRevertProfileEdits() = viewModel.onRevertProfileEdits()
            override fun onOpenAdvanced() = viewModel.onOpenAdvanced()
            override fun onCloseAdvanced() = viewModel.onCloseAdvanced()
            override fun onOpenVrKo() = viewModel.onOpenVrKo()
            override fun onCloseVrKo() = viewModel.onCloseVrKo()
            override fun onOpenMeasurements() = viewModel.onOpenMeasurements()
            override fun onCloseMeasurements() = viewModel.onCloseMeasurements()
            override fun onShowAbout() = viewModel.onShowAbout()
            override fun onCloseAbout() = viewModel.onCloseAbout()
            override fun onDialogExportLog() = viewModel.onDialogExportLog()
            override fun onStrideOptionSelected(value: Int) = viewModel.onStrideOptionSelected(value)
            override fun onStrideUse() = viewModel.onStrideUse()
            override fun onStrideSkip() = viewModel.onStrideSkip()
            override fun onStridePickerDismiss() = viewModel.onStridePickerDismiss()
            override fun onExportRunLog() = viewModel.onExportRunLog()
            override fun onRunLogPickerDismiss() = viewModel.onRunLogPickerDismiss()
            override fun onClearRunLogs() = viewModel.onClearRunLogs()
            override fun onExportReportBundle() = viewModel.onExportReportBundle()
            override fun onAddBugreport() = viewModel.onAddBugreport()
            override fun onReportGenerateBugreport() = viewModel.onReportGenerateBugreport()
            override fun onOpenDeveloperOptions() = viewModel.onOpenDeveloperOptions()
            override fun onOpenAboutPhone() = viewModel.onOpenAboutPhone()
            override fun onReportNext() = viewModel.onReportNext()
            override fun onReportBack() = viewModel.onReportBack()
            override fun onReportGoToImport() = viewModel.onReportGoToImport()
            override fun onReportAppend() = viewModel.onReportAppend()
            override fun onReportExportOnly() = viewModel.onReportExportOnly()
            override fun onReportRecheckDeveloper() = viewModel.onReportRecheckDeveloper()
            override fun onReportShare() = viewModel.onReportShare()
            override fun onDebugKernelLogChanged(enabled: Boolean) =
                viewModel.onDebugKernelLogChanged(enabled)

            override fun onOpenParameters() = viewModel.onOpenParameters()
            override fun onCloseParameters() = viewModel.onCloseParameters()
            override fun onOpenLoadConfig() = viewModel.onOpenLoadConfig()
            override fun onCloseLoadConfig() = viewModel.onCloseLoadConfig()
            override fun onSelectProfileFile() = viewModel.onSelectProfileFile()
            override fun onClearExternalProfile() = viewModel.onClearExternalProfile()
            override fun onOpenMergePreview() = viewModel.onOpenMergePreview()
            override fun onCloseMergePreview() = viewModel.onCloseMergePreview()
            override fun onExportMergedProfile() = viewModel.onExportMergedProfile()
            override fun onOpenProfileDiff() = viewModel.onOpenProfileDiff()
            override fun onCloseProfileDiff() = viewModel.onCloseProfileDiff()
            override fun onRebaseToBundle() = viewModel.onRebaseToBundle()
            override fun onOpenLegacyCleanup() = viewModel.onOpenLegacyCleanup()
            override fun onCloseLegacyCleanup() = viewModel.onCloseLegacyCleanup()
            override fun onLegacyExport(name: String, applyOverride: Boolean) =
                viewModel.onLegacyExport(name, applyOverride)

            override fun onLegacyCleanupRequest() = viewModel.onLegacyCleanupRequest()
            override fun onLegacyCleanupConfirm() = viewModel.onLegacyCleanupConfirm()
            override fun onLegacyCleanupDismiss() = viewModel.onLegacyCleanupDismiss()
            override fun onOpenBuiltinProfiles() = viewModel.onOpenBuiltinProfiles()
            override fun onCloseBuiltinProfiles() = viewModel.onCloseBuiltinProfiles()
            override fun onSelectBuiltinProfile(release: String?) =
                viewModel.onSelectBuiltinProfile(release)

            override fun onOpenProfileOverrides() = viewModel.onOpenProfileOverrides()
            override fun onCloseProfileOverrides() = viewModel.onCloseProfileOverrides()
            override fun onOpenAdvancedOverrides() = viewModel.onOpenAdvancedOverrides()
            override fun onCloseAdvancedOverrides() = viewModel.onCloseAdvancedOverrides()
            override fun onProfileOverrideChanged(path: String, value: String) =
                viewModel.onProfileOverrideChanged(path, value)
        },
    )
}
