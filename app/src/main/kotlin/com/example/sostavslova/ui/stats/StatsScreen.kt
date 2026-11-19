package com.example.sostavslova.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.example.sostavslova.core.LevelTimeStatus
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.di.appContainer
import com.example.sostavslova.time.PlayTimeTracker
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.StatsTable
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.components.WordsDialog
import com.example.sostavslova.ui.game.gameStageName
import com.example.sostavslova.ui.theme.WordsColors
import com.example.sostavslova.ui.theme.WordsControlShape
import java.math.BigInteger
import java.util.Locale

/** Total hours, never a wall-clock time: no wrapping at 24 h or Long overflow. */
internal fun durationParts(milliseconds: BigInteger): List<String> {
    require(milliseconds.signum() >= 0)
    val seconds = milliseconds / BigInteger.valueOf(1000)
    return listOf((seconds / BigInteger.valueOf(3600)).toString(),
        ((seconds / BigInteger.valueOf(60)) % BigInteger.valueOf(60)).toString(),
        (seconds % BigInteger.valueOf(60)).toString())
}

/** Оставляет только значимые единицы: «45 с», «5 мин 3 с», «2 ч 14 мин», «0 с». */
internal fun durationText(parts: List<String>, units: List<String>): String {
    require(parts.size == 3 && units.size == 3)
    val significant = parts.indices.filter { parts[it] != "0" }
    if (significant.isEmpty()) return "0 ${units[2]}"
    return significant.joinToString(" ") { "${parts[it]} ${units[it]}" }
}

/** Разряды через пробел: «1 204». */
internal fun groupedNumber(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
private fun duration(ms: Long): String = duration(BigInteger.valueOf(ms))

@Composable
private fun duration(ms: BigInteger): String = durationText(durationParts(ms), listOf(
    stringResource(R.string.time_unit_hours),
    stringResource(R.string.time_unit_minutes),
    stringResource(R.string.time_unit_seconds),
))

@Composable
fun StatsRoute(onReturnToLevels: () -> Unit = {}) {
    val container = appContainer()
    val stageNames = (1..6).associateWith { gameStageName(it) }
    val factory = remember(container, stageNames) { viewModelFactory { initializer {
        StatsViewModel(container.levelsRepository, container.playTimeRepository,
            container.progressRepository, container.wordRepository, stageNames,
            playTimeTracker = container.playTimeTracker)
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
        item { StatsHeading(stringResource(R.string.stats_title), "stats_title") }
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
                        Text(stringResource(R.string.stats_time_summary,
                            if (statistics.measuredLevelCount == 0) stringResource(R.string.stats_dash)
                            else duration(statistics.totalTimeMs), statistics.hintsShown),
                            Modifier.testTag("stats_time_summary"), style = MaterialTheme.typography.bodyLarge)
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
                item(key = "stats_time_note") {
                    Text(stringResource(R.string.stats_time_note),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("stats_time_note"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                item(key = "stats_idle_note") {
                    val idleSeconds = (PlayTimeTracker.DEFAULT_IDLE_TIMEOUT_MS / 1_000L).toInt()
                    val secondUnit = RussianPlural.form(
                        idleSeconds,
                        stringResource(R.string.stats_second_genitive_one),
                        stringResource(R.string.stats_second_genitive_many),
                        stringResource(R.string.stats_second_genitive_many),
                    )
                    Text(stringResource(R.string.stats_idle_note, idleSeconds, secondUnit),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("stats_idle_note"),
                        style = MaterialTheme.typography.bodyMedium)
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
                            border = BorderStroke(1.dp, WordsColors.dangerButton),
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
                    border = BorderStroke(1.dp, WordsColors.defaultButtonBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = WordsColors.defaultButton,
                        contentColor = WordsColors.defaultButtonText,
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
    val starsValue = stringResource(R.string.stats_stars_value, row.stars, row.maxStars)
    val required = stringResource(R.string.stats_required, row.requiredStars)
    val words = stringResource(R.string.stats_words_progress, row.found, row.total,
        RussianPlural.words(row.total))
    val percent = stringResource(R.string.stats_percent_value, row.percent)
    val time = activeTimeText(row)
    val timeAndHints = if (row.hintsShown == 0) time
        else stringResource(R.string.stats_time_hints, time, row.hintsShown)
    val statusText = listOf(starsValue, words, timeAndHints).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(start = 16.dp)
                .testTag("stats_level_${row.number}").semantics(mergeDescendants = true) {
                    stateDescription = statusText
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stats_level_header, row.number, row.word.uppercase(Locale.ROOT)),
                    style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StarsRow(row.stars, row.maxStars, label = stringResource(R.string.level_stars_label))
                    Text(starsValue, style = MaterialTheme.typography.bodyMedium)
                    if (!row.passed) Text(required, style = MaterialTheme.typography.bodyMedium)
                }
                Text(words, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { if (row.total == 0) 0f else row.found.toFloat() / row.total },
                        Modifier.weight(1f).height(8.dp).testTag("stats_progress_${row.number}"),
                        color = WordsColors.primary, trackColor = WordsColors.wellBorder)
                    Text(percent, style = MaterialTheme.typography.bodyMedium)
                }
                Text(timeAndHints, Modifier.testTag("stats_time_${row.number}"),
                    style = MaterialTheme.typography.bodyMedium)
            }
            TextButton({ expanded = !expanded }, Modifier.padding(end = 8.dp).heightIn(min = 48.dp)
                .testTag("stats_expand_${row.number}")) {
                Text(stringResource(if (expanded) R.string.stats_collapse else R.string.stats_details))
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsTable(Statistics(row.byLength), Modifier.testTag("stats_table_${row.number}"), showRemaining = true)
                if (!row.passed) {
                    Text(stringResource(R.string.stats_not_passed),
                        Modifier.testTag("stats_not_passed_${row.number}"),
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.stats_passed_for,
                        row.toRMs?.let { duration(it) } ?: stringResource(R.string.stats_dash)),
                        Modifier.testTag("stats_passed_${row.number}"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (row.maxStars > row.requiredStars) {
                    if (row.stars >= row.maxStars) {
                        Text(stringResource(R.string.stats_all_stars_for,
                            row.toFullMs?.let { duration(it) } ?: stringResource(R.string.stats_dash)),
                            Modifier.testTag("stats_all_stars_${row.number}"),
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.stats_all_stars_pending),
                            Modifier.testTag("stats_all_stars_${row.number}"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun activeTimeText(row: LevelStatsRow): String =
    when (row.timeStatus) {
        LevelTimeStatus.Measured -> row.activeMs?.let { duration(it) } ?: stringResource(R.string.stats_dash)
        LevelTimeStatus.Historical, LevelTimeStatus.Pending, LevelTimeStatus.Unplayed ->
            stringResource(R.string.stats_dash)
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
