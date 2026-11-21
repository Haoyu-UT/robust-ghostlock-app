package com.ghostlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.BuildConfig
import com.ghostlock.app.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Field reference and validation rules live in the repository docs; pick the
 * page matching the system language. */
private const val ProfileDocsBase =
    "https://github.com/YuKongA/ghostlock-app/blob/main/docs/kernel_profiles/"

private fun profileDocsUrl(): String =
    ProfileDocsBase + if (Locale.getDefault().language == "zh") {
        "PROFILE_SCHEMA_ZH.md"
    } else {
        "PROFILE_SCHEMA.md"
    }

private fun Modifier.preferencePageItem(): Modifier =
    this.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)

@Composable
private fun ProfileDocsCard(onOpen: () -> Unit) {
    Card {
        ArrowPreference(
            title = stringResource(R.string.profile_docs),
            summary = stringResource(R.string.profile_docs_summary),
            onClick = onOpen,
        )
    }
}

@Composable
private fun ProfileHintBanner(
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    title: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = if (warning) {
            CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.errorContainer,
                contentColor = MiuixTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.defaultColors()
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (warning) MiuixTheme.colorScheme.onErrorContainer
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = if (warning) MiuixTheme.colorScheme.onErrorContainer
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Advanced screen: offsets tooling, parameter overrides and the debug-only
 * log export switches. Debug preferences are hidden in release builds.
 */
@Composable
internal fun AdvancedScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.advanced_settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseAdvanced) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "runSettings") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.run_settings_title),
                        summary = stringResource(R.string.run_settings_summary),
                        onClick = actions::onOpenRunSettings,
                    )
                }
            }
            item(key = "forceAttack") {
                Card {
                    SwitchPreference(
                        checked = state.forceAttackTestEnabled,
                        onCheckedChange = actions::onForceAttackTestChanged,
                        title = stringResource(R.string.force_attack_test_label),
                        summary = stringResource(R.string.force_attack_test_summary),
                    )
                }
            }
            /* The profile's `pipeline` is the only product selector; the
             * debug-only `second_way` intent remains a lab hook for the
             * harness and has no UI (see profile-pipeline-selection-plan). */
            item(key = "rootManager") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.manager_label),
                        summary = state.selectedManagerPackage
                            ?: stringResource(R.string.manager_summary_auto),
                        onClick = actions::onOpenManagerPicker,
                    )
                }
            }
            item(key = "override") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.parameters),
                        summary = stringResource(R.string.parameters_summary),
                        onClick = actions::onOpenParameters,
                    )
                }
            }
            item(key = "measurements") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.measurements_title),
                        summary = stringResource(R.string.measurements_summary),
                        onClick = actions::onOpenMeasurements,
                    )
                }
            }
            item(key = "export") {
                Card {
                    Column {
                        /* The run log itself has no switch any more: it is always
                         * kept. This one still gates the kernel side, which the
                         * root script only writes from its exit trap. */
                        SwitchPreference(
                            checked = state.debugKernelLogEnabled,
                            onCheckedChange = actions::onDebugKernelLogChanged,
                            title = stringResource(R.string.debug_kernel_log),
                            summary = stringResource(R.string.debug_kernel_log_summary),
                        )
                        if (!BuildConfig.DEBUG) {
                            Text(
                                text = stringResource(R.string.debug_build_hint),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            item(key = "about") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.about),
                        summary = stringResource(R.string.about_summary),
                        onClick = actions::onShowAbout,
                    )
                }
            }
        }
    }
}

/** Parameters screen: configuration loading and the parameter-override submenu. */
@Composable
internal fun ParameterScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.parameters),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseParameters) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "loaded") {
                ProfileHintBanner(
                    text = if (state.importedProfilePresent) {
                        stringResource(R.string.profile_kind_external)
                    } else if (!state.executionHasProfile) {
                        stringResource(R.string.profile_not_loaded)
                    } else {
                        stringResource(
                            R.string.builtin_profile_label,
                            state.activeBuiltinProfile ?: stringResource(R.string.load_builtin_auto),
                        )
                    },
                )
            }
            item(key = "load") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.load_config_title),
                        summary = stringResource(R.string.load_config_summary),
                        onClick = actions::onOpenLoadConfig,
                    )
                }
            }
            item(key = "docs") {
                ProfileDocsCard { uriHandler.openUri(profileDocsUrl()) }
            }
            if (state.executionHasProfile) {
                item(key = "actions") {
                    TextButton(
                        text = stringResource(R.string.override_export),
                        onClick = actions::onExportProfile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Configuration loading: the import/parse buttons, the builtin picker and the
 * list of verbatim user-imported documents (export, rename, delete).
 */
@Composable
internal fun LoadConfigScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.load_config_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseLoadConfig) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(
                paddingValues, top = 0.dp, bottom = 0.dp, horizontalMin = 0.dp,
            ),
        ) {
            item(key = "top-space") { Spacer(Modifier.height(12.dp)) }
            item(key = "source") {
                val details = when {
                    state.importedProfilePresent -> stringResource(R.string.profile_kind_external)
                    state.activeBuiltinProfile != null || state.executionHasProfile ->
                        stringResource(R.string.profile_kind_builtin)

                    else -> stringResource(R.string.profile_not_loaded)
                }
                ProfileHintBanner(
                    title = stringResource(R.string.profile_source_title),
                    text = details,
                    modifier = Modifier.preferencePageItem(),
                )
            }
            item(key = "select-profile") {
                Card(modifier = Modifier.preferencePageItem()) {
                    ArrowPreference(
                        title = stringResource(R.string.select_profile_file),
                        summary = stringResource(R.string.select_profile_summary),
                        onClick = actions::onSelectProfileFile,
                    )
                }
            }
            if (state.importedProfilePresent) {
                item(key = "clear-external") {
                    TextButton(
                        text = stringResource(R.string.clear_external_profile),
                        onClick = actions::onClearExternalProfile,
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            item(key = "parse-ota") {
                Card(modifier = Modifier.preferencePageItem()) {
                    ArrowPreference(
                        title = stringResource(R.string.action_parse_ota),
                        onClick = actions::onParseOta,
                    )
                }
            }
            item(key = "parse-image") {
                Card(modifier = Modifier.preferencePageItem()) {
                    ArrowPreference(
                        title = stringResource(R.string.action_parse),
                        onClick = actions::onParseImage,
                    )
                }
            }
            item(key = "builtin") {
                Card(modifier = Modifier.preferencePageItem()) {
                    ArrowPreference(
                        title = stringResource(R.string.load_builtin_profile),
                        summary = stringResource(R.string.load_builtin_summary),
                        onClick = actions::onOpenBuiltinProfiles,
                        endActions = {
                            if (state.executionHasProfile && !state.importedProfilePresent &&
                                state.activeBuiltinProfile == null
                            ) {
                                Text(
                                    text = stringResource(R.string.user_profile_loaded_badge),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OverrideHighlight,
                                )
                            }
                        },
                    )
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}


private fun formatProfileTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Builtin picker: the device kernel stays pinned on top, every bundled
 * release follows, ordered by how close it is to the device kernel.
 */
@Composable
internal fun BuiltinProfileScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.load_builtin_profile),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseBuiltinProfiles) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(
                paddingValues, top = 0.dp, bottom = 0.dp, horizontalMin = 0.dp,
            ),
        ) {
            item(key = "top-space") { Spacer(Modifier.height(12.dp)) }
            item(key = "local-kernel") {
                ProfileHintBanner(
                    text = stringResource(R.string.local_kernel, state.kernelRelease),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (state.activeBuiltinProfile == null && !state.importedProfilePresent &&
                !state.executionHasProfile
            ) {
                item(key = "auto-no-match") {
                    ProfileHintBanner(
                        text = stringResource(R.string.builtin_auto_no_match),
                        warning = true,
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            item(key = "immutable-notice") {
                ProfileHintBanner(
                    text = stringResource(R.string.builtin_immutable_notice),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            item(key = "auto") {
                BuiltinProfileRow(
                    title = stringResource(R.string.load_builtin_auto),
                    selected = !state.importedProfilePresent &&
                            state.activeBuiltinProfile == null,
                    onClick = { actions.onSelectBuiltinProfile(null) },
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (state.builtinTemplates.isNotEmpty()) {
                item(key = "templates-title") {
                    SmallTitle(text = stringResource(R.string.templates_section))
                }
                items(state.builtinTemplates, key = { "template:$it" }) { release ->
                    BuiltinProfileRow(
                        title = release,
                        selected = !state.importedProfilePresent &&
                                state.activeBuiltinProfile == release,
                        onClick = { actions.onSelectBuiltinProfile(release) },
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            if (state.builtinProfiles.isNotEmpty()) {
                item(key = "kernels-title") {
                    SmallTitle(text = stringResource(R.string.kernels_section))
                }
                items(state.builtinProfiles, key = { "kernel:$it" }) { release ->
                    BuiltinProfileRow(
                        title = release,
                        selected = !state.importedProfilePresent &&
                                state.activeBuiltinProfile == release,
                        onClick = { actions.onSelectBuiltinProfile(release) },
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun BuiltinProfileRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        RadioButtonPreference(
            title = title,
            selected = selected,
            onClick = onClick,
            radioButtonLocation = RadioButtonLocation.End,
        )
    }
}

/**
 * Read-only view of the measured database: per kernel, each root-measured fact
 * with its source, time and the raw evidence line it came from. Nothing here
 * can be edited, and nothing here is applied automatically.
 */
@Composable
internal fun MeasurementsScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.measurements_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseMeasurements) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "readonly") {
                ProfileHintBanner(
                    text = stringResource(R.string.measurements_readonly),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            if (state.measuredKernels.isEmpty()) {
                item(key = "empty") {
                    ProfileHintBanner(
                        text = stringResource(R.string.measurements_empty),
                        modifier = Modifier.preferencePageItem(),
                    )
                }
            }
            items(state.measuredKernels, key = { it.release }) { kernel ->
                Card(modifier = Modifier.preferencePageItem()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = kernel.release,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.measurement_updated,
                                formatProfileTime(kernel.updatedAt),
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        kernel.facts.forEach { fact ->
                            Text(
                                text = "${fact.key} = ${fact.value}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.measurement_source, fact.source),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(
                                    R.string.measurement_time,
                                    formatProfileTime(fact.measuredAt),
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(
                                    R.string.measurement_evidence,
                                    fact.evidence,
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

/**
 * Home merge preview: the facts the measured database would change, their raw
 * evidence, and the one action that matters — exporting a new document. The
 * source profile is never modified.
 */
@Composable
internal fun MergePreviewScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.merge_preview_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseMergePreview) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                ProfileHintBanner(
                    text = stringResource(R.string.merge_preview_summary),
                    modifier = Modifier.preferencePageItem(),
                )
            }
            items(state.mergeRows, key = { it.path }) { row ->
                Card(modifier = Modifier.preferencePageItem()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = row.path,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = buildString {
                                append(stringResource(R.string.merge_profile_value))
                                append(": ")
                                append(row.profileValue ?: "—")
                                append("    ")
                                append(stringResource(R.string.merge_measured_value))
                                append(": ")
                                append(row.measuredValue)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.measurement_source, row.source),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.measurement_evidence, row.evidence),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "export") {
                TextButton(
                    text = stringResource(R.string.merge_export),
                    onClick = actions::onExportMergedProfile,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier
                        .preferencePageItem()
                        .fillMaxWidth(),
                )
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

