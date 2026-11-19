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
import com.example.sostavslova.ui.game.gameStageName
import java.math.BigInteger

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

internal enum class ActiveTimeStatus { Unplayed, Historical, Pending, Measured }

internal fun activeTimeStatus(screenActiveMs: Long?, historical: Boolean, pending: Boolean): ActiveTimeStatus =
    when {
        screenActiveMs != null -> ActiveTimeStatus.Measured
        historical -> ActiveTimeStatus.Historical
        pending -> ActiveTimeStatus.Pending
        else -> ActiveTimeStatus.Unplayed
    }

internal enum class ThresholdStatus { NotPassed, NotMeasured, Measured }

/** Порог считается достигнутым по звёздам прогресса, а не по наличию сохранённого замера. */
internal fun thresholdStatus(stars: Int, thresholdStars: Int, milliseconds: Long?): ThresholdStatus = when {
    stars < thresholdStars -> ThresholdStatus.NotPassed
    milliseconds == null -> ThresholdStatus.NotMeasured
    else -> ThresholdStatus.Measured
}

@Composable
private fun duration(ms: BigInteger): String = durationText(durationParts(ms), listOf(
    stringResource(R.string.time_unit_hours),
    stringResource(R.string.time_unit_minutes),
    stringResource(R.string.time_unit_seconds),
))

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
                item { TimeHeading(stringResource(R.string.time_stages)) }
                summary.stages.forEach { stage -> item(key = "stage_${stage.stageNumber}") {
                    TimeText(stringResource(R.string.time_stage, gameStageName(stage.stageNumber),
                        duration(stage.screenActiveMs)), "time_stage_${stage.stageNumber}")
                } }
                item { TimeHeading(stringResource(R.string.time_levels)) }
                summary.levels.forEach { time -> item(key = "level_${time.levelNumber}") {
                    val level = campaign.levels.single { it.number == time.levelNumber }
                    val stars = state.levelStars[level.number] ?: 0
                    // Stacked table row keeps every field readable even at 320 dp / 200% font.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("time_level_${level.number}").semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.time_level, level.number, level.word), style = MaterialTheme.typography.titleMedium)
                        Text(gameStageName(level.stage))
                        Text(stringResource(R.string.time_active, activeTime(time, state)))
                        Text(stringResource(R.string.time_passed_for, threshold(stars, level.requiredStars, time.timeToRMs)))
                        if (level.maxStars > level.requiredStars) {
                            Text(stringResource(R.string.time_all_stars_for, threshold(stars, level.maxStars, time.timeToFullMs)))
                        }
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
private fun activeTime(time: LevelTimeStatistics, state: TimeUiState): String =
    when (activeTimeStatus(time.screenActiveMs,
        time.levelNumber in state.historicalLevels, time.levelNumber in state.pendingLevels)) {
        ActiveTimeStatus.Measured -> duration(BigInteger.valueOf(requireNotNull(time.screenActiveMs)))
        ActiveTimeStatus.Historical -> stringResource(R.string.time_historical)
        ActiveTimeStatus.Pending -> stringResource(R.string.time_pending)
        ActiveTimeStatus.Unplayed -> stringResource(R.string.time_unplayed)
    }

@Composable
private fun threshold(stars: Int, thresholdStars: Int, milliseconds: Long?): String =
    when (thresholdStatus(stars, thresholdStars, milliseconds)) {
        ThresholdStatus.NotPassed -> stringResource(R.string.time_threshold_not_passed)
        ThresholdStatus.NotMeasured -> stringResource(R.string.time_threshold_not_measured)
        ThresholdStatus.Measured -> duration(BigInteger.valueOf(requireNotNull(milliseconds)))
    }
