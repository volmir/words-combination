package com.example.sostavslova.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelTimeStatistics
import com.example.sostavslova.di.appContainer
import java.math.BigDecimal
import java.math.BigInteger

/** Total hours, never a wall-clock time: no wrapping at 24 h or Long overflow. */
internal fun durationParts(milliseconds: BigInteger): List<String> {
    require(milliseconds.signum() >= 0)
    val seconds = milliseconds / BigInteger.valueOf(1000)
    return listOf((seconds / BigInteger.valueOf(3600)).toString(),
        ((seconds / BigInteger.valueOf(60)) % BigInteger.valueOf(60)).toString(),
        (seconds % BigInteger.valueOf(60)).toString())
}

@Composable
private fun duration(ms: BigInteger): String {
    val parts = durationParts(ms)
    return stringResource(R.string.time_duration, parts[0], parts[1], parts[2])
}

@Composable
fun TimeRoute() {
    val container = appContainer()
    val factory = remember(container) { viewModelFactory { initializer {
        TimeViewModel(container.levelsRepository, container.playTimeRepository, container.progressRepository)
    } } }
    val model: TimeViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    TimeScreen(state)
}

@Composable
fun TimeScreen(state: TimeUiState, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("time_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TimeHeading(stringResource(R.string.nav_time)) }
        when {
            state.loading -> item { TimeText(stringResource(R.string.time_loading)) }
            state.error -> item { TimeText(stringResource(R.string.time_error)) }
            state.campaign == null || state.summary == null -> item { TimeText(stringResource(R.string.time_unavailable)) }
            else -> {
                val campaign = state.campaign
                val summary = state.summary
                item { TimeText(stringResource(R.string.time_total, duration(summary.screenActiveMs)), "time_total") }
                item { TimeText(stringResource(R.string.time_note)) }
                item {
                    TimeText(stringResource(R.string.time_mean, average(summary.meanLevelActiveMs)))
                    TimeText(stringResource(R.string.time_median, average(summary.medianLevelActiveMs)))
                    TimeText(stringResource(R.string.time_fastest, extreme(summary.fastestLevel, state)))
                    TimeText(stringResource(R.string.time_slowest, extreme(summary.slowestLevel, state)))
                }
                item { TimeHeading(stringResource(R.string.time_stages)) }
                summary.stages.forEach { stage -> item(key = "stage_${stage.stageNumber}") {
                    TimeText(stringResource(R.string.time_stage, stage.stageNumber, duration(stage.screenActiveMs)),
                        "time_stage_${stage.stageNumber}")
                } }
                item { TimeHeading(stringResource(R.string.time_levels)) }
                summary.levels.forEach { time -> item(key = "level_${time.levelNumber}") {
                    val level = campaign.levels.single { it.number == time.levelNumber }
                    // Stacked table row keeps every field readable even at 320 dp / 200% font.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("time_level_${level.number}").semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.time_level, level.number, level.word), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.time_level_stage, level.stage))
                        Text(stringResource(R.string.time_active, when {
                            time.screenActiveMs != null -> duration(BigInteger.valueOf(requireNotNull(time.screenActiveMs)))
                            level.number in state.historicalLevels -> stringResource(R.string.time_historical)
                            level.number in state.pendingLevels -> stringResource(R.string.time_pending)
                            else -> stringResource(R.string.time_unplayed)
                        }))
                        if (time.screenActiveMs != null && level.number in state.historicalLevels) {
                            Text(stringResource(R.string.time_historical))
                        }
                        Text(stringResource(R.string.time_to_r, level.requiredStars, milestone(time.timeToRMs)))
                        Text(stringResource(R.string.time_to_m, level.maxStars, milestone(time.timeToFullMs)))
                    }
                    HorizontalDivider()
                } }
            }
        }
    }
}

@Composable
private fun TimeHeading(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun TimeText(text: String, tag: String = "time_text") {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag))
}

@Composable
private fun milestone(ms: Long?): String = ms?.let { duration(BigInteger.valueOf(it)) }
    ?: stringResource(R.string.time_missing)

@Composable
private fun average(ms: Double?): String = ms?.let { duration(BigDecimal.valueOf(it).toBigInteger()) }
    ?: stringResource(R.string.time_no_measurements)

@Composable
private fun extreme(time: LevelTimeStatistics?, state: TimeUiState): String {
    if (time == null) return stringResource(R.string.time_no_measurements)
    val level = state.campaign?.levels?.singleOrNull { it.number == time.levelNumber }
        ?: return stringResource(R.string.time_no_measurements)
    return stringResource(R.string.time_extreme, level.number, level.word,
        duration(BigInteger.valueOf(requireNotNull(time.screenActiveMs))))
}
