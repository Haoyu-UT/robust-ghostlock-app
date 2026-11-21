package com.ghostlock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.ExecutionFieldValue
import com.ghostlock.app.domain.model.MeasuredKernelInfo
import com.ghostlock.app.domain.model.MeasurementMergeRow
import com.ghostlock.app.domain.model.ProfileDiffRow
import com.ghostlock.app.domain.model.ProfileFieldNode
import com.ghostlock.app.domain.model.ProfileLayers
import com.ghostlock.app.domain.model.CrashEvidenceInfo
import com.ghostlock.app.domain.model.RunLogEntry
import com.ghostlock.app.domain.model.ShizukuStatus
import com.ghostlock.app.domain.model.UserProfileFile
import com.ghostlock.app.domain.model.VrKoMode
import com.ghostlock.app.ui.nav.NavController
import com.ghostlock.app.ui.nav.Screen
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

data class GhostlockUiState(
    val deviceName: String = "",
    val kernelRelease: String = "",
    val socName: String = "",
    val kernelSupported: Boolean = false,
    /** The kernel is covered but has no usable physical-memory pair (MTK). */
    val devicePairMissing: Boolean = false,
    /**
     * The imported copy cannot be read, so there is no profile and no run: the
     * home names the real cause and sends the user to re-import instead of
     * claiming the kernel is unsupported.
     */
    val externalProfileUnreadable: Boolean = false,
    val shizukuEnabled: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
    /** Root-manager choice (package name); null means Auto. */
    val selectedManagerPackage: String? = null,
    val running: Boolean = false,
    /** The last run's terminal status, shown in the sheet footer. */
    val runStatus: RunStatus = RunStatus.Idle,
    /** Profile generation: which step the dedicated screen shows. */
    val generationStep: GenerationStep = GenerationStep.Idle,
    val cpuPairLabels: List<String> = emptyList(),
    val cpuPairIndex: Int = 0,
    /** CPU pair from the resolved profile when it differs from the device pick. */
    val customCpuPair: CpuPair? = null,
    val safeModeEnabled: Boolean = false,
    val forceAttackTestEnabled: Boolean = false,
    val vrKoTagClear: VrKoMode = VrKoMode.AUTO,
    val vrKoSelinuxBlock: VrKoMode = VrKoMode.AUTO,
    val tcpRouteEnabled: Boolean = true,
    val compact: Boolean = false,
    val executionSheetVisible: Boolean = false,
    val executionSheetDismissible: Boolean = false,
    /** The one open dialog, if any. */
    val dialog: AppDialog? = null,
    val overwriteDialogVisible: Boolean = false,
    val overwriteMessage: String = "",
    val logLines: List<GhostlockLogLine> = emptyList(),
    /** Runs retained on the device; the report sheet only uses this to offer clearing. */
    val runLogs: List<RunLogEntry> = emptyList(),
    val runLogPickerVisible: Boolean = false,
    /** The newest crash-evidence capture, if any. */
    val crashEvidence: CrashEvidenceInfo? = null,
    val bugreportImporting: Boolean = false,
    /** A shell is generating a system bug report for the report flow. */
    val bugreportGenerating: Boolean = false,
    /** Shizuku (shell or root) is available to the crash/report path. */
    val crashShellAvailable: Boolean = false,
    /** The report flow's current screen. */
    val reportWizardStep: ReportWizardStep = ReportWizardStep.Choice,
    /**
     * The screens this visit will pass through, in order. A direct export has
     * one entry and no step counter; the bug-report paths have two to four.
     */
    val reportFlowSteps: List<ReportWizardStep> = emptyList(),
    /** The last bundle written in this flow, for the saved/share block. */
    val lastReportName: String? = null,
    val lastReportUri: String? = null,
    val reportExportDone: Boolean = false,
    val executionRelease: String = "",
    val executionHasProfile: Boolean = false,
    val executionFields: List<ExecutionFieldValue> = emptyList(),
    val executionEditing: Map<String, String> = emptyMap(),
    val measuredKernels: List<MeasuredKernelInfo> = emptyList(),
    val debugKernelLogEnabled: Boolean = true,
    /** Stored document edited by the open session; null for the builtin. */
    val editTargetName: String? = null,
    val profileOverrideRelease: String = "",
    val profileOverrideRoots: List<ProfileFieldNode> = emptyList(),
    val profileOverrideEditing: Map<String, String> = emptyMap(),
    /** Controller-reported geometry violations, dotted paths. */
    val profileInvalidPaths: Set<String> = emptySet(),
    /** Explicit route from the profile; null means geometry inference. */
    val profileRoute: String? = null,
    /** Declared fallback route; null/"none" means disabled. */
    val profileFallback: String? = null,
    /** Manually selected builtin source; null means automatic matching. */
    val activeBuiltinProfile: String? = null,
    /** Unfilled reference templates, listed separately on the builtin picker. */
    val builtinTemplates: List<String> = emptyList(),
    /** Builtin releases sorted by similarity to the device kernel. */
    val builtinProfiles: List<String> = emptyList(),
    /** The app-private imported copy exists (the external selection). */
    val importedProfilePresent: Boolean = false,
    /** Which layers the last resolution applied, and which it deliberately did not. */
    val profileLayers: ProfileLayers? = null,
    /** The slab-stride choice sheet; non-null while a probe result awaits. */
    val stridePicker: StridePickerUiState? = null,
    /** Measured facts that would change the active profile. */
    val mergeRows: List<MeasurementMergeRow> = emptyList(),
    /** Diff of the editor session against the bundle. */
    val profileDiffRows: List<ProfileDiffRow> = emptyList(),
    /** One-time legacy app-data cleanup. */
    val legacyPresent: Boolean = false,
    val legacyProfiles: List<UserProfileFile> = emptyList(),
    val legacyOverridePresent: Boolean = false,
    val legacyCleanupConfirm: Boolean = false,
)


/**
 * The report flow's screens. Choice decides whether a system bug report is
 * attached; a shell-backed capture with a kernel log skips straight to Export.
 */
enum class ReportWizardStep { Choice, Developer, Generate, Import, Export }

/** The last attack run's state; the run-status notification mirrors it. */
enum class RunStatus { Idle, Running, Success, Failure }

/** The profile-generation flow's step; the generation screen renders one at a time. */
enum class GenerationStep { Idle, Parsing, Confirm, Save, Failed }

/**
 * One line per layer that is present but deliberately not applied. Shared by
 * the start-up notice and the profile screen's banner, so the two can never
 * describe the same state differently.
 */
@Composable
internal fun layerNoticeBody(layers: ProfileLayers): String = buildList {
    val imported = layers.imported
    if (imported != null && !layers.importedApplied) {
        add(stringResource(R.string.layer_import_not_applied, imported))
    }
    if (layers.overridesPresent && !layers.overridesApplied) {
        add(stringResource(R.string.layer_overrides_not_applied))
    }
}.joinToString("\n\n")


data class GhostlockLogLine(val text: String, val color: Int)

interface GhostlockActions {
    fun onRun()
    fun onProfileInvalid()
    fun onStatusClick()
    fun onShowDeviceInfo()
    fun onOpenLastRunLog()
    fun onOpenRunSettings()
    fun onCloseRunSettings()
    fun onOpenGeneration()
    fun onCloseGeneration()
    fun onSaveGeneratedProfile()
    fun onRestartGeneration()
    fun onCloseExecutionSheet()
    fun onCopyLogs()
    fun onExportRunLog()
    fun onRunLogPickerDismiss()
    fun onClearRunLogs()
    fun onExportReportBundle()
    fun onAddBugreport()
    fun onReportGenerateBugreport()
    fun onCopyBugreportCommand()
    fun onOpenDeveloperOptions()
    fun onOpenAboutPhone()
    fun onReportNext()
    fun onReportBack()
    fun onReportGoToImport()
    fun onReportAppend()
    fun onReportExportOnly()
    fun onReportRecheckDeveloper()
    fun onReportShare()
    fun onSelectProfileFile()
    fun onClearExternalProfile()
    fun onParseOta()
    fun onParseImage()
    fun onGenerateProfile()
    fun onCpuPairSelected(index: Int)
    fun onSafeModeChanged(enabled: Boolean)
    fun onForceAttackTestChanged(enabled: Boolean)
    fun onVrKoTagClearChanged(mode: VrKoMode)
    fun onVrKoSelinuxBlockChanged(mode: VrKoMode)
    fun onShizukuChanged(enabled: Boolean)
    fun onOpenManagerPicker()
    fun onOpenManager()
    fun onDialogItemSelected(index: Int)
    fun onDialogInputChange(value: String)
    fun onDialogConfirm(value: String)
    fun onDialogDismiss()
    fun onDialogExportLog()
    fun onOverwriteConfirm()
    fun onOverwriteDismiss()
    fun onStrideOptionSelected(value: Int)
    fun onStrideUse()
    fun onStrideSkip()
    fun onStridePickerDismiss()
    fun onExecutionFieldChanged(path: String, value: String)
    fun onRouteChanged(index: Int)
    fun onFallbackChanged(index: Int)
    fun onExportProfile()
    fun onSaveProfileEdits()
    fun onSaveProfileAs()
    fun onExportProfileEdits()
    fun onRevertProfileEdits()
    fun onOpenAdvanced()
    fun onCloseAdvanced()
    fun onOpenVrKo()
    fun onCloseVrKo()
    fun onOpenMeasurements()
    fun onCloseMeasurements()
    fun onShowAbout()
    fun onCloseAbout()
    fun onDebugKernelLogChanged(enabled: Boolean)
    fun onOpenParameters()
    fun onCloseParameters()
    fun onOpenLoadConfig()
    fun onCloseLoadConfig()
    fun onOpenMergePreview()
    fun onCloseMergePreview()
    fun onExportMergedProfile()
    fun onOpenProfileDiff()
    fun onCloseProfileDiff()
    fun onRebaseToBundle()
    fun onOpenLegacyCleanup()
    fun onCloseLegacyCleanup()
    fun onLegacyExport(name: String, applyOverride: Boolean)
    fun onLegacyCleanupRequest()
    fun onLegacyCleanupConfirm()
    fun onLegacyCleanupDismiss()
    fun onOpenBuiltinProfiles()
    fun onCloseBuiltinProfiles()
    fun onSelectBuiltinProfile(release: String?)
    fun onOpenProfileOverrides()
    fun onCloseProfileOverrides()
    fun onOpenAdvancedOverrides()
    fun onCloseAdvancedOverrides()
    fun onProfileOverrideChanged(path: String, value: String)
}


private fun closeScreen(screen: Screen, actions: GhostlockActions) {
    when (screen) {
        Screen.Main -> Unit
        Screen.VrKo -> actions.onCloseVrKo()
        Screen.Advanced -> actions.onCloseAdvanced()
        Screen.RunSettings -> actions.onCloseRunSettings()
        Screen.Generation -> actions.onCloseGeneration()
        Screen.About -> actions.onCloseAbout()
        Screen.Measurements -> actions.onCloseMeasurements()
        Screen.Parameters -> actions.onCloseParameters()
        Screen.LoadConfig -> actions.onCloseLoadConfig()
        Screen.Builtin -> actions.onCloseBuiltinProfiles()
        Screen.MergePreview -> actions.onCloseMergePreview()
        Screen.ProfileDiff -> actions.onCloseProfileDiff()
        Screen.Legacy -> actions.onCloseLegacyCleanup()
        Screen.ProfileOverride -> actions.onCloseProfileOverrides()
        Screen.AdvancedOverride -> actions.onCloseAdvancedOverrides()
    }
}

@Composable
internal fun GhostlockApp(
    state: GhostlockUiState,
    nav: NavController,
    actions: GhostlockActions,
) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val backStack = nav.backStack
        val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            NavSwipeDirection.RightToLeft
        } else {
            NavSwipeDirection.LeftToRight
        }
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    onBack = {
                        when {
                            state.executionSheetVisible -> {
                                if (state.executionSheetDismissible) actions.onCloseExecutionSheet()
                            }

                            state.overwriteDialogVisible -> actions.onOverwriteDismiss()
                            state.dialog != null -> actions.onDialogDismiss()
                            else -> {
                                if (nav.canPop) {
                                    val screen = nav.current
                                    if (screen != Screen.Main) closeScreen(screen, actions)
                                }
                            }
                        }
                    },
                ) {
                    entry<Screen.Main>(swipeDismiss = NavSwipeDirection.None) {
                        MainScreen(state = state, actions = actions)
                    }
                    entry<Screen.VrKo>(swipeDismiss = swipeBack) {
                        VrKoScreen(state = state, actions = actions)
                    }
                    entry<Screen.Advanced>(swipeDismiss = swipeBack) {
                        AdvancedScreen(state = state, actions = actions)
                    }
                    entry<Screen.RunSettings>(swipeDismiss = swipeBack) {
                        RunSettingsScreen(state = state, actions = actions)
                    }
                    entry<Screen.Generation>(swipeDismiss = swipeBack) {
                        GenerationScreen(state = state, actions = actions)
                    }
                    entry<Screen.About>(swipeDismiss = swipeBack) {
                        AboutScreen(onBack = actions::onCloseAbout)
                    }
                    entry<Screen.Measurements>(swipeDismiss = swipeBack) {
                        MeasurementsScreen(state = state, actions = actions)
                    }
                    entry<Screen.Parameters>(swipeDismiss = swipeBack) {
                        ParameterScreen(state = state, actions = actions)
                    }
                    entry<Screen.LoadConfig>(swipeDismiss = swipeBack) {
                        LoadConfigScreen(state = state, actions = actions)
                    }
                    entry<Screen.Builtin>(swipeDismiss = swipeBack) {
                        BuiltinProfileScreen(state = state, actions = actions)
                    }
                    entry<Screen.MergePreview>(swipeDismiss = swipeBack) {
                        MergePreviewScreen(state = state, actions = actions)
                    }
                    entry<Screen.ProfileDiff>(swipeDismiss = swipeBack) {
                        ProfileDiffScreen(state = state, actions = actions)
                    }
                    entry<Screen.Legacy>(swipeDismiss = swipeBack) {
                        LegacyCleanupScreen(state = state, actions = actions)
                    }
                    entry<Screen.ProfileOverride>(swipeDismiss = swipeBack) {
                        ProfileOverrideScreen(state = state, actions = actions)
                    }
                    entry<Screen.AdvancedOverride>(swipeDismiss = swipeBack) {
                        AdvancedOverrideScreen(state = state, actions = actions)
                    }
                }
                GhostlockDialog(state = state, actions = actions)
                GhostlockOverwriteDialog(state = state, actions = actions)
                RunLogPickerDialog(state = state, actions = actions)
                StridePickerDialog(state = state, actions = actions)
                GhostlockExecutionSheet(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    HomeScreen(state = state, actions = actions)
}

@Composable
internal fun pageContentPadding(
    scaffoldPadding: PaddingValues,
    top: Dp = 8.dp,
    bottom: Dp = 12.dp,
    horizontalMin: Dp = 12.dp,
): PaddingValues {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val horizontal = ((windowWidth - 800.dp) / 2).coerceAtLeast(horizontalMin)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom,
    )
}

@Composable
private fun GhostlockExecutionSheet(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayBottomSheet(
        show = state.executionSheetVisible,
        title = stringResource(R.string.log_title),
        allowDismiss = state.executionSheetDismissible,
        onDismissRequest = actions::onCloseExecutionSheet,
        startAction = {
            IconButton(onClick = actions::onCopyLogs) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(
                enabled = state.executionSheetDismissible,
                onClick = actions::onCloseExecutionSheet,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                )
            }
        },
        content = {
            LogPanel(
                lines = state.logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp)
                    .navigationBarsPadding(),
            )
            /* The run's visible verdict, and the explicit way into the
             * KernelSU manager once the run is over. The manager itself is
             * never auto-launched by the app: on a late-load ROM its own
             * restart is what takes the foreground mid-handoff. */
            if (state.executionSheetDismissible && state.runStatus != RunStatus.Idle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (state.runStatus == RunStatus.Success) {
                                R.string.run_status_root_active
                            } else {
                                R.string.run_status_failed
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.run_notif_open_manager),
                        onClick = actions::onOpenManager,
                    )
                }
            }
        },
    )
}

@Composable
private fun GhostlockDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val current = state.dialog
    /* Keep the last dialog rendered while the overlay plays its exit
     * animation; `show` still follows the single source of truth. */
    var lastDialog by remember { mutableStateOf<AppDialog?>(null) }
    if (current != null) lastDialog = current
    val dialog = current ?: lastDialog
    OverlayDialog(
        show = current != null,
        title = dialog?.let { stringResource(it.titleRes) },
        onDismissRequest = actions::onDialogDismiss,
        content = {
            when (dialog) {
                is AppDialog.ListDialog -> {
                    val items = dialog.items.ifEmpty { dialog.itemResIds.map { stringResource(it) } }
                    val leading = dialog.leadingResId
                    val offset = if (leading != null) 1 else 0
                    if (leading != null) {
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = stringResource(leading),
                            onClick = { actions.onDialogItemSelected(0) },
                        )
                    }
                    items.forEachIndexed { index, item ->
                        val grade = dialog.grades.getOrNull(index)
                        val label = if (index == dialog.currentIndex) {
                            stringResource(R.string.export_current_marker, item)
                        } else {
                            item
                        }
                        if (grade == null) {
                            /* No chip (image sources, phys choices): keep the
                             * miuix TextButton's centred label and container. */
                            TextButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                text = label,
                                onClick = { actions.onDialogItemSelected(index + offset) },
                            )
                        } else {
                            val chipColors = ButtonDefaults.textButtonColors()
                            Row(
                                /* A chip row: the same TextButton container,
                                 * with the label left-aligned so the chip sits
                                 * right after it. The miuix ButtonDefaults
                                 * values (corner 16.dp, inside margin 16x13.dp)
                                 * are not exposed to app code; they are read
                                 * from the library's constants. */
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(chipColors.color)
                                    .clickable { actions.onDialogItemSelected(index + offset) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    color = chipColors.textColor,
                                    /* The text fills the row so the chip is
                                     * always right-aligned inside the box. */
                                    modifier = Modifier.weight(1f),
                                )
                                DialogBadge(grade)
                            }
                        }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.cancel),
                        onClick = actions::onDialogDismiss,
                    )
                }

                is AppDialog.Input -> {
                    TextField(
                        value = dialog.value,
                        onValueChange = actions::onDialogInputChange,
                        label = stringResource(dialog.labelRes),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.cancel),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.confirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm(dialog.value) },
                        )
                    }
                }

                is AppDialog.Confirm -> {
                    Text(
                        text = if (dialog.message.isNotEmpty()) {
                            dialog.message
                        } else {
                            stringResource(dialog.messageRes)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (dialog.exportLog) {
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            text = stringResource(R.string.run_log_export),
                            onClick = actions::onDialogExportLog,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.dismissLabelRes),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.confirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm("") },
                        )
                    }
                }

                is AppDialog.Notice -> {
                    val layerNotice = dialog.layerNotice
                    Text(
                        text = when {
                            layerNotice != null -> layerNoticeBody(layerNotice)
                            dialog.message.isNotEmpty() -> dialog.message
                            else -> stringResource(dialog.messageRes)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        if (dialog.exportLog) {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.run_log_export),
                                onClick = actions::onDialogExportLog,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        dialog.docUrl?.let { docUrl ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.dialog_open_guide),
                                onClick = { uriHandler.openUri(docUrl) },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(dialog.dismissLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions::onDialogDismiss,
                        )
                    }
                }

                is AppDialog.NotificationPermission -> {
                    Text(
                        text = stringResource(dialog.messageRes),
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.run_notif_permission_later),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.run_notif_permission_allow),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm("") },
                        )
                    }
                }

                null -> Unit
            }
        },
    )
}

@Composable
private fun RunLogPickerDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val step = state.reportWizardStep
    val titleRes = when (step) {
        ReportWizardStep.Choice -> R.string.report_choice_title
        ReportWizardStep.Developer -> R.string.report_developer_title
        ReportWizardStep.Generate -> R.string.report_step_generate_title
        ReportWizardStep.Import -> R.string.report_step_import_title
        ReportWizardStep.Export -> R.string.report_step_export_title
    }
    val summaryRes = when (step) {
        ReportWizardStep.Choice -> R.string.report_choice_summary
        ReportWizardStep.Developer -> R.string.report_developer_summary
        ReportWizardStep.Generate -> R.string.report_step_generate_summary
        ReportWizardStep.Import -> R.string.report_step_import_summary
        ReportWizardStep.Export ->
            if (state.reportExportDone) {
                R.string.report_step_export_done_summary
            } else {
                R.string.report_step_export_summary
            }
    }
    OverlayDialog(
        show = state.runLogPickerVisible,
        title = stringResource(titleRes),
        summary = stringResource(summaryRes),
        onDismissRequest = actions::onRunLogPickerDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val plan = state.reportFlowSteps
                val index = plan.indexOf(step)
                if (plan.size > 1 && index >= 0) {
                    Text(
                        text = stringResource(
                            R.string.report_step_indicator,
                            index + 1,
                            plan.size,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when (step) {
                    ReportWizardStep.Choice -> ReportChoiceStep(state, actions)
                    ReportWizardStep.Developer -> ReportDeveloperStep(actions)
                    ReportWizardStep.Generate -> ReportGenerateStep(state, actions)
                    ReportWizardStep.Import -> ReportImportStep(state, actions)
                    ReportWizardStep.Export -> ReportExportStep(state, actions)
                }
                Spacer(modifier = Modifier.height(12.dp))
                ReportWizardNav(step, state, actions)
            }
        },
    )
}

/** The first screen when no kernel log is in hand: attach a system report or not. */
@Composable
private fun ReportChoiceStep(state: GhostlockUiState, actions: GhostlockActions) {
    CrashEvidenceCard(state)
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_append),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onReportAppend,
    )
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_export_only),
        onClick = actions::onReportExportOnly,
    )
    ReportTextLink(
        text = stringResource(R.string.report_have_bugreport),
        onClick = actions::onReportGoToImport,
    )
}

/** Developer options is off; About phone is the way in. */
@Composable
private fun ReportDeveloperStep(actions: GhostlockActions) {
    Text(
        text = stringResource(R.string.report_developer_body),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_open_about),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onOpenAboutPhone,
    )
    ReportTextLink(
        text = stringResource(R.string.report_developer_recheck),
        onClick = actions::onReportRecheckDeveloper,
    )
}

/** Developer options is on: the system generates the report from its own UI. */
@Composable
private fun ReportGenerateStep(state: GhostlockUiState, actions: GhostlockActions) {
    /* One primary card, then the manual routes as rows: the shell generator is
     * the fast path (no computer, auto-import), and the two fallbacks carry
     * their own how-to instead of three stacked paragraphs. The shell verdict
     * is polled while the sheet is open, so the card's state is live. */
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.report_generate_card_title),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                text = stringResource(
                    if (state.bugreportGenerating) {
                        R.string.report_generate_progress
                    } else {
                        R.string.report_generate_action
                    },
                ),
                enabled = state.crashShellAvailable && !state.bugreportGenerating,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = actions::onReportGenerateBugreport,
            )
            Text(
                text = stringResource(
                    if (!state.crashShellAvailable) {
                        R.string.report_generate_unavailable
                    } else {
                        R.string.report_generate_card_summary
                    },
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    Text(
        text = stringResource(R.string.report_other_ways),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.report_dev_row_title),
            summary = stringResource(R.string.report_dev_row_summary),
            onClick = actions::onOpenDeveloperOptions,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.secondaryContainer),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.report_adb_row_title),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.report_adb_row_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.report_adb_row_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                text = stringResource(R.string.report_adb_copy),
                onClick = actions::onCopyBugreportCommand,
            )
        }
    }
}

/** Pick the report the user just generated. */
@Composable
private fun ReportImportStep(state: GhostlockUiState, actions: GhostlockActions) {
    CrashEvidenceCard(state)
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_add_bugreport),
        enabled = !state.bugreportImporting,
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onAddBugreport,
    )
    if (state.bugreportImporting) {
        Text(
            text = stringResource(R.string.report_importing),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The destination is chosen for the user straight away; this screen is the fallback. */
@Composable
private fun ReportExportStep(state: GhostlockUiState, actions: GhostlockActions) {
    CrashEvidenceCard(state)
    val evidence = state.crashEvidence
    if (evidence?.crashEvidence != true) {
        ReportWarningCard(
            stringResource(
                if (evidence?.hasKernelLog == true) {
                    R.string.report_no_crash_warning
                } else {
                    R.string.report_no_kernel_warning
                },
            ),
        )
        ReportTextLink(
            text = stringResource(R.string.report_back_to_import),
            onClick = actions::onReportGoToImport,
        )
    }
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.report_choose_location_action),
        colors = ButtonDefaults.textButtonColorsPrimary(),
        onClick = actions::onExportReportBundle,
    )
    if (state.reportExportDone) {
        Text(
            text = stringResource(
                R.string.report_saved,
                state.lastReportName ?: state.lastReportUri.orEmpty(),
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ReportTextLink(
            text = stringResource(R.string.report_share),
            onClick = actions::onReportShare,
        )
    }
    if (state.runLogs.isNotEmpty()) {
        ReportTextLink(
            text = stringResource(R.string.run_log_clear),
            onClick = actions::onClearRunLogs,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ReportWizardNav(
    step: ReportWizardStep,
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    /* Choice is the decision screen; Export ends the flow. The screens in
     * between go back or continue; Back reaches Choice for the export-only
     * branch. While a report is being parsed no transition is allowed: both
     * directions stay visible but disabled, and the dialog's dismiss is
     * ignored (see the ViewModel). */
    val busy = state.bugreportImporting
    val bugreportStep = when (step) {
        ReportWizardStep.Developer,
        ReportWizardStep.Generate,
        ReportWizardStep.Import,
        -> true

        ReportWizardStep.Choice,
        ReportWizardStep.Export,
        -> false
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (step != ReportWizardStep.Choice) {
                TextButton(
                    text = stringResource(R.string.report_back),
                    enabled = !busy,
                    onClick = actions::onReportBack,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (bugreportStep) {
                TextButton(
                    text = stringResource(
                        if (step == ReportWizardStep.Generate) {
                            R.string.report_import_existing
                        } else {
                            R.string.report_next
                        },
                    ),
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions::onReportNext,
                )
            }
        }
    }
}

/** A text-link style action; lighter than the filled dialog buttons. */
@Composable
private fun ReportTextLink(
    text: String,
    onClick: () -> Unit,
    color: Color = MiuixTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ReportWarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onErrorContainer,
        )
    }
}

/** What the app already holds about the last crash, and where it came from. */
@Composable
private fun CrashEvidenceCard(state: GhostlockUiState) {
    val evidence = state.crashEvidence
    var details by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.report_evidence_title),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = evidenceStatus(evidence),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (evidence != null && evidence.source != "none") {
                ReportTextLink(
                    text = stringResource(R.string.report_details),
                    onClick = { details = !details },
                )
                if (details) {
                    Text(
                        text = stringResource(R.string.report_source, evidence.source),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.report_boot_id, evidence.captureBootId),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (evidence.bootReason.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.report_boot_reason,
                                evidence.bootReason,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (evidence.files.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.report_files,
                                evidence.files.joinToString(", "),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun evidenceStatus(evidence: CrashEvidenceInfo?): String = when {
    evidence == null -> stringResource(R.string.report_status_none)
    evidence.source == "bugreport" ->
        if (evidence.crashEvidence) {
            stringResource(
                R.string.report_status_imported,
                evidence.bugreportName.ifBlank { "bugreport.zip" },
            )
        } else {
            stringResource(
                R.string.report_status_imported_no_crash,
                evidence.bugreportName.ifBlank { "bugreport.zip" },
            )
        }

    evidence.hasKernelLog -> stringResource(R.string.report_status_collected)
    evidence.source == "none" -> stringResource(R.string.report_status_none)
    else -> stringResource(R.string.report_status_partial)
}

/**
 * The slab-stride decision sheet: one recommendation with an evidence level in
 * plain language, the alternatives as radio rows, and the raw table behind a
 * "technical details" toggle. Dismissing it is the same as Skip.
 */
@Composable
private fun StridePickerDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val picker = state.stridePicker ?: return
    val recommendation = picker.recommendation
    var detailsExpanded by remember { mutableStateOf(false) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.stride_title),
        summary = stringResource(R.string.stride_subtitle, recommendation.samples),
        onDismissRequest = actions::onStridePickerDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = recommendation.recommendedValue
                                    ?.let { stringResource(R.string.stride_bytes, it) }
                                    ?: stringResource(R.string.stride_none),
                                modifier = Modifier.weight(1f),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(strideLevelRes(recommendation.level)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (recommendation.level == StrideEvidenceLevel.CONFIRMED ||
                                    recommendation.level == StrideEvidenceLevel.CONSISTENT
                                ) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        Text(
                            text = strideReasonText(recommendation.reason),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        recommendation.confirmedValue?.let { confirmed ->
                            Text(
                                text = stringResource(R.string.stride_confirmed_line, confirmed),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    recommendation.options.forEach { option ->
                        RadioButtonPreference(
                            title = stringResource(
                                R.string.stride_option,
                                option.value,
                                option.matches,
                                option.samples,
                            ),
                            selected = picker.selectedValue == option.value,
                            onClick = { actions.onStrideOptionSelected(option.value) },
                            radioButtonLocation = RadioButtonLocation.End,
                        )
                    }
                }
                TextButton(
                    text = if (detailsExpanded) {
                        "▾ " + stringResource(R.string.stride_details)
                    } else {
                        "▸ " + stringResource(R.string.stride_details)
                    },
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (detailsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.stride_details_measurements,
                                recommendation.samples,
                                recommendation.rounds,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(
                                R.string.stride_details_gcd,
                                recommendation.gcd.toString(16),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        recommendation.options.forEach { option ->
                            Text(
                                text = stringResource(
                                    R.string.stride_details_candidate,
                                    option.value,
                                    option.matches,
                                    option.samples,
                                    option.objectsPerSlab,
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = picker.selectedValue
                            ?.let { stringResource(R.string.stride_use, it) }
                            ?: stringResource(R.string.stride_use_none),
                        enabled = picker.selectedValue != null,
                        onClick = actions::onStrideUse,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.stride_skip),
                        onClick = actions::onStrideSkip,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(picker.footerRes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    )
}

private fun strideLevelRes(level: StrideEvidenceLevel): Int = when (level) {
    StrideEvidenceLevel.CONFIRMED -> R.string.stride_level_confirmed
    StrideEvidenceLevel.CONSISTENT -> R.string.stride_level_consistent
    StrideEvidenceLevel.MIXED -> R.string.stride_level_mixed
    StrideEvidenceLevel.INSUFFICIENT -> R.string.stride_level_insufficient
}

@Composable
private fun strideReasonText(reason: StrideReason): String = when (reason) {
    is StrideReason.ConfirmedAgree -> stringResource(
        R.string.stride_reason_confirmed_agree,
        reason.samples,
    )

    is StrideReason.ConfirmedOnly -> stringResource(R.string.stride_reason_confirmed)
    is StrideReason.Agree -> stringResource(R.string.stride_reason_agree, reason.samples)
    is StrideReason.Partial -> stringResource(
        R.string.stride_reason_partial,
        reason.matches,
        reason.samples,
        reason.runnerUp,
    )

    StrideReason.Tie -> stringResource(R.string.stride_reason_tie)
    is StrideReason.Single -> stringResource(
        if (reason.samples > 0) R.string.stride_reason_single else R.string.stride_reason_none,
    )
}

/** `20261009-212803 · Shizuku · interrupted · 42 KB` */

@Composable
private fun GhostlockOverwriteDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayDialog(
        show = state.overwriteDialogVisible,
        title = stringResource(R.string.overwrite_title),
        summary = stringResource(R.string.overwrite_message, state.overwriteMessage),
        onDismissRequest = actions::onOverwriteDismiss,
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = actions::onOverwriteDismiss,
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.overwrite_yes),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions::onOverwriteConfirm,
                )
            }
        },
    )
}

@Composable
private fun MainContent(
    state: GhostlockUiState,
    actions: GhostlockActions,
    scrollBehavior: ScrollBehavior,
    scaffoldPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .scrollEndHaptic()
            .fillMaxHeight()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        contentPadding = pageContentPadding(scaffoldPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "controls") {
            ControlPanel(
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.mergeRows.isNotEmpty()) {
            item(key = "merge") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.merge_preview_title),
                        summary = stringResource(R.string.merge_summary),
                        onClick = actions::onOpenMergePreview,
                    )
                }
            }
        }
        item(key = "export-run-log") {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.run_log_export),
                onClick = actions::onExportRunLog,
            )
        }
        item(key = "run") {
            RunButton(
                running = state.running,
                supported = state.kernelSupported &&
                        state.executionHasProfile &&
                        (!state.shizukuEnabled ||
                                state.shizukuStatus == ShizukuStatus.READY),
                profileValid = state.profileInvalidPaths.isEmpty(),
                labelRes = R.string.action_run,
                onClick = actions::onRun,
                onBlockedClick = actions::onProfileInvalid,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        /* The one-time legacy cleanup sits below the primary action, so it can
         * never push the run button off a fresh screen. */
        if (state.legacyPresent) {
            item(key = "legacy") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.legacy_present_title),
                        summary = stringResource(R.string.legacy_present_summary),
                        onClick = actions::onOpenLegacyCleanup,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ActivationStatusCard(
            supported = state.kernelSupported,
            externalUnreadable = state.externalProfileUnreadable,
            externalBlocked = state.importedProfilePresent &&
                state.profileLayers?.externalError != null,
            profileAvailable = state.executionHasProfile,
            profileValid = state.profileInvalidPaths.isEmpty(),
            shizukuEnabled = state.shizukuEnabled,
            shizukuStatus = state.shizukuStatus,
            onParametersClick = actions::onOpenParameters,
            onShizukuClick = actions::onStatusClick,
            modifier = Modifier.fillMaxWidth(),
        )
        DeviceInfoCard(
            deviceName = state.deviceName,
            socName = state.socName,
            kernelRelease = state.kernelRelease,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        /* The profile-generation entry sits on the home: one sheet, OTA link
         * or an on-device image, instead of the deep advanced page. */
        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            ArrowPreference(
                title = stringResource(R.string.home_generate_profile),
                summary = stringResource(R.string.home_generate_profile_summary),
                onClick = actions::onGenerateProfile,
            )
        }
        Card(modifier = modifier.padding(top = 12.dp)) {
            if (state.cpuPairLabels.isNotEmpty()) {
                val customPair = state.customCpuPair
                val customSummary = customPair?.let {
                    stringResource(
                        R.string.cpu_pair_custom,
                        "${it.primary}, ${it.consumer}",
                    )
                }
                OverlaySpinnerPreference(
                    title = stringResource(R.string.cpu_pair_label),
                    items = state.cpuPairLabels.map { DropdownItem(icon = null, title = it) },
                    selectedIndex = if (customPair == null) state.cpuPairIndex else -1,
                    summary = customSummary,
                    showValue = customPair == null,
                    onSelectedIndexChange = actions::onCpuPairSelected,
                )
            }
            SwitchPreference(
                checked = state.safeModeEnabled,
                onCheckedChange = actions::onSafeModeChanged,
                title = stringResource(R.string.safe_mode_label),
                summary = stringResource(R.string.safe_mode_summary),
            )
            /* PROFILE-SUGGEST-01: the profile suggestion seeds the toggle but no
             * longer hides it; an explicit user choice overrides either way. */
            SwitchPreference(
                checked = state.shizukuEnabled,
                onCheckedChange = actions::onShizukuChanged,
                title = stringResource(R.string.shizuku_label),
                summary = stringResource(
                    when {
                        !state.shizukuEnabled -> R.string.shizuku_summary
                        state.shizukuStatus == ShizukuStatus.READY -> R.string.shizuku_status_ready
                        state.shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
                            R.string.shizuku_status_permission_required

                        state.shizukuStatus == ShizukuStatus.NOT_RUNNING ->
                            R.string.shizuku_status_not_running

                        else -> R.string.shizuku_status_checking
                    },
                ),
            )
            val tagMode = stringResource(vrKoModeLabelRes(state.vrKoTagClear))
            val selinuxMode = stringResource(vrKoModeLabelRes(state.vrKoSelinuxBlock))
            ArrowPreference(
                title = stringResource(R.string.vr_ko_entry_label),
                summary = stringResource(R.string.vr_ko_entry_summary, tagMode, selinuxMode),
                onClick = actions::onOpenVrKo,
            )
        }
        Card(modifier = modifier.padding(top = 12.dp)) {
            ArrowPreference(
                title = stringResource(R.string.advanced_settings),
                summary = stringResource(R.string.advanced_settings_summary),
                onClick = actions::onOpenAdvanced,
            )
        }
    }
}

@Composable
private fun ActivationStatusCard(
    supported: Boolean,
    externalUnreadable: Boolean,
    externalBlocked: Boolean,
    profileAvailable: Boolean,
    profileValid: Boolean,
    shizukuEnabled: Boolean,
    shizukuStatus: ShizukuStatus,
    onParametersClick: () -> Unit,
    onShizukuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessReady = !shizukuEnabled || shizukuStatus == ShizukuStatus.READY
    val ready = supported && profileAvailable && profileValid && accessReady
    val missing = !supported || !profileAvailable
    val (backgroundColor, title, icon) = when {
        ready -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                isSystemInDarkTheme() -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            },
            R.string.kernel_supported,
            Icons.Rounded.CheckCircleOutline,
        )

        /* The selected document itself cannot be read: the resolver has no
         * profile, and the fault is the selection, not the kernel. Checked
         * before `missing` so the card never blames the kernel match. */
        externalUnreadable -> Triple(
            MiuixTheme.colorScheme.errorContainer,
            R.string.kernel_external_unreadable,
            Icons.Rounded.ErrorOutline,
        )

        /* A readable copy that produced no profile for this kernel (no entry
         * for the release, schema too new, a merge refusal): the fault is the
         * copy, not the kernel match. Invalid fields resolve a profile and go
         * to the `else` branch with their red fields. */
        externalBlocked -> Triple(
            MiuixTheme.colorScheme.errorContainer,
            R.string.kernel_profile_invalid,
            Icons.Rounded.ErrorOutline,
        )

        missing -> Triple(
            MiuixTheme.colorScheme.errorContainer,
            if (!supported) R.string.kernel_unsupported else R.string.kernel_profile_required,
            Icons.Rounded.RemoveCircleOutline,
        )

        else -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.tertiaryContainer
                isSystemInDarkTheme() -> Color(0xFF3E2F1B)
                else -> Color(0xFFFFF0DB)
            },
            if (!profileValid) R.string.kernel_profile_invalid else R.string.shizuku_label,
            Icons.Rounded.ErrorOutline,
        )
    }
    val summary = when {
        ready && shizukuEnabled -> R.string.kernel_profile_ready_shizuku
        ready -> R.string.kernel_profile_ready
        externalUnreadable -> R.string.kernel_external_unreadable_summary
        externalBlocked -> R.string.imported_profile_not_applicable_summary
        !supported -> R.string.kernel_unsupported_summary
        !profileAvailable -> R.string.kernel_profile_required_summary
        !profileValid -> R.string.kernel_profile_invalid_summary
        shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
            R.string.shizuku_status_permission_required

        else -> R.string.shizuku_status_not_running
    }
    val action = if (!externalUnreadable && !externalBlocked && supported &&
        profileAvailable && profileValid && !accessReady
    ) {
        onShizukuClick
    } else {
        onParametersClick
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = backgroundColor),
        onClick = action,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (MiuixTheme.isDynamicColor) {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        Icons.Rounded.RemoveCircleOutline ->
                            MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)

                        else -> MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                } else {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> Color(0xFF36D167)
                        Icons.Rounded.RemoveCircleOutline -> Color(0xFFF5A623)
                        else -> Color(0xFFF72727)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = stringResource(summary),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    deviceName: String,
    socName: String,
    kernelRelease: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DeviceInfoItem(
                    title = stringResource(R.string.device_label),
                    value = deviceName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.soc_label),
                    value = socName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.kernel_label),
                    value = kernelRelease,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoItem(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

/* profile-ui: resolved execution view with auto-saved sparse overrides. */
@Composable
internal fun ExecutionEditor(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (field in state.executionFields) {
            val text = state.executionEditing[field.path] ?: field.value.toString()
            val invalid = isFieldInputInvalid(text) || field.path in state.profileInvalidPaths
            TextField(
                value = text,
                onValueChange = { value -> actions.onExecutionFieldChanged(field.path, value) },
                label = fieldLabel(field.path, field.path.substringAfterLast('.')),
                colors = when {
                    invalid ->
                        TextFieldDefaults.textFieldColors(labelColor = FieldErrorHighlight)

                    field.overridden ->
                        TextFieldDefaults.textFieldColors(labelColor = OverrideHighlight)

                    else -> TextFieldDefaults.textFieldColors()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

internal val OverrideHighlight = Color(0xFFF5A623)

/** Red marks an unfilled or non-numeric field in the parameter editors. */
internal val FieldErrorHighlight = Color(0xFFE53935)

internal fun isFieldInputInvalid(text: String): Boolean = text.trim().toLongOrNull() == null

@Composable
private fun RunButton(
    running: Boolean,
    supported: Boolean,
    profileValid: Boolean,
    labelRes: Int,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TextButton(
            text = stringResource(if (running) R.string.action_running else labelRes),
            enabled = supported && profileValid && !running,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
        /* A disabled TextButton consumes no pointer input, so this overlay
         * explains why the run is blocked. */
        if (!running && (!supported || !profileValid)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { onBlockedClick() },
            )
        }
    }
}

@Composable
private fun LogPanel(
    lines: List<GhostlockLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.trimEnd('\r', '\n'),
                        color = lineColor(line.color),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/**
 * A dialog choice's chip, rendered inline after the option (same row, trailing)
 * rather than on its own line above it: a colored dot + the short label.
 */
@Composable
private fun DialogBadge(chip: DialogChip) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(chip.color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(chip.labelRes),
            fontSize = 11.sp,
        )
    }
}

private fun lineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFD1D5DB)
}
