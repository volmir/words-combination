package com.example.sostavslova.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelStatsRow
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.di.appContainer
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.AchievementStrip
import com.example.sostavslova.ui.components.StatsTable
import com.example.sostavslova.ui.components.WordsDialog
import com.example.sostavslova.ui.game.gameStageName
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsControlShape
import java.util.Locale

/** Разряды через пробел: «1 204». */
internal fun groupedNumber(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
fun StatsRoute(onReturnToLevels: () -> Unit = {}) {
    val container = appContainer()
    val stageNames = (1..6).associateWith { gameStageName(it) }
    val factory = remember(container, stageNames) { viewModelFactory { initializer {
        StatsViewModel(container.levelsRepository, container.progressRepository,
            container.wordRepository, stageNames)
    } } }
    val model: StatsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.resetCompleted) {
        if (state.resetCompleted) onReturnToLevels()
    }
    StatsScreen(
        state = state,
        onRequestReset = model::onRequestReset,
        onConfirmReset = model::onConfirmReset,
        onDismissReset = model::onDismissReset,
    )
}

@Composable
fun StatsScreen(
    state: StatsUiState,
    modifier: Modifier = Modifier,
    onRequestReset: () -> Unit = {},
    onConfirmReset: () -> Unit = {},
    onDismissReset: () -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize().testTag("stats_screen"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                StatsHeading(stringResource(R.string.stats_title), "stats_title")
                StatsText(stringResource(R.string.stats_subtitle), "stats_subtitle")
            }
        }
        when {
            state.loading -> item { StatsText(stringResource(R.string.stats_loading), "stats_loading") }
            state.error -> item { StatsText(stringResource(R.string.stats_error), "stats_error") }
            state.statistics == null -> item {
                StatsText(stringResource(R.string.stats_unavailable), "stats_unavailable")
            }
            else -> {
                val statistics = state.statistics
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.stats_summary, statistics.stars, statistics.maxStars,
                            statistics.passedLevels, statistics.totalLevels, groupedNumber(statistics.foundWords)),
                            Modifier.testTag("stats_summary"), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.stats_achievements_total, statistics.hintsShown,
                            statistics.achievementsEarned, statistics.achievementsTotal),
                            Modifier.testTag("stats_achievements_total"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                statistics.stages.forEach { stage ->
                    item(key = "stats_stage_${stage.number}") {
                        Text(stringResource(R.string.levels_stage, stage.name, stage.firstLevel, stage.lastLevel),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("stats_stage_${stage.number}").semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(stage.levels, key = { "stats_level_${it.number}" }) { row -> StatsLevelRow(row) }
                }
                item(key = "stats_data_section") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("stats_data_section"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.stats_data_heading),
                            Modifier.semantics { heading() }.testTag("stats_data_heading"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(
                            onClick = onRequestReset,
                            enabled = !state.resetting,
                            shape = WordsControlShape,
                            border = BorderStroke(1.dp, LocalWordsPalette.current.dangerButton),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("stats_reset_button"),
                        ) {
                            Text(stringResource(R.string.stats_reset_button))
                        }
                    }
                }
            }
        }
    }
    if (state.resetConfirmVisible) {
        WordsDialog(
            title = stringResource(R.string.stats_reset_dialog_title),
            onDismiss = { if (!state.resetting) onDismissReset() },
            modifier = Modifier.testTag("stats_reset_dialog"),
        ) {
            Text(
                stringResource(R.string.stats_reset_dialog_message),
                Modifier.testTag("stats_reset_dialog_message"),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.resetError) {
                AlertBanner(
                    AlertType.Danger,
                    stringResource(R.string.stats_reset_error),
                    Modifier.testTag("stats_reset_error"),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismissReset,
                    enabled = !state.resetting,
                    shape = WordsControlShape,
                    border = BorderStroke(1.dp, LocalWordsPalette.current.defaultButtonBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = LocalWordsPalette.current.defaultButton,
                        contentColor = LocalWordsPalette.current.defaultButtonText,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("stats_reset_cancel"),
                ) {
                    Text(stringResource(R.string.stats_reset_cancel))
                }
                Button(
                    onClick = onConfirmReset,
                    enabled = !state.resetting,
                    shape = WordsControlShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("stats_reset_confirm"),
                ) {
                    Text(stringResource(R.string.stats_reset_confirm))
                }
            }
        }
    }
}

@Composable
private fun StatsLevelRow(row: LevelStatsRow) {
    var expanded by rememberSaveable(row.number) { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.stats_expanded else R.string.stats_collapsed)
    val toggleLabel = stringResource(if (expanded) R.string.stats_collapse else R.string.stats_details)
    Column(Modifier.fillMaxWidth()
        .testTag("stats_row_${row.number}")
        .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
        .semantics(mergeDescendants = true) { stateDescription = expansionState }
        .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stats_level_header, row.number, row.word.uppercase(Locale.ROOT)),
            Modifier.testTag("stats_level_${row.number}"), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsMetric(stringResource(R.string.stats_metric_words_label),
                stringResource(R.string.stats_pair, row.found, row.total), "stats_metric_words_${row.number}")
            StatsMetric(stringResource(R.string.stats_metric_stars_label),
                stringResource(R.string.stats_pair, row.stars, row.maxStars), "stats_metric_stars_${row.number}")
        }
        Column(Modifier.fillMaxWidth().testTag("stats_achievements_${row.number}")) {
            Text(stringResource(R.string.stats_achievements_label), style = MaterialTheme.typography.bodyMedium)
            AchievementStrip(row.achievements, row.achievementsAvailable, Modifier.fillMaxWidth(),
                iconTagPrefix = "stats_achievement_${row.number}")
        }
        if (expanded) {
            Text(stringResource(R.string.stats_percent_value, row.percent),
                Modifier.testTag("stats_percent_${row.number}"), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (row.total == 0) 0f else row.found.toFloat() / row.total },
                Modifier.fillMaxWidth().height(8.dp).testTag("stats_progress_${row.number}"),
                color = LocalWordsPalette.current.primary, trackColor = LocalWordsPalette.current.wellBorder)
            Text(stringResource(R.string.stats_hints_value, row.hintsShown),
                Modifier.testTag("stats_hints_${row.number}"), style = MaterialTheme.typography.bodyMedium)
            StatsTable(Statistics(row.byLength), Modifier.testTag("stats_table_${row.number}"), showRemaining = true)
            if (!row.passed) {
                Text(stringResource(R.string.stats_required, row.requiredStars),
                    Modifier.testTag("stats_required_${row.number}"), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.stats_not_passed),
                    Modifier.testTag("stats_not_passed_${row.number}"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton({ expanded = !expanded }, Modifier.heightIn(min = 48.dp)
            .testTag("stats_toggle_${row.number}")) {
            Text(toggleLabel)
        }
    }
    HorizontalDivider()
}

@Composable
private fun StatsMetric(label: String, value: String, tag: String) {
    Column(Modifier.widthIn(min = 120.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatsHeading(text: String, tag: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag(tag).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun StatsText(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag))
}
