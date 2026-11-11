package com.example.sostavslova.ui.levels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.R
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsColors
import java.util.Locale

@Composable
fun LevelsRoute(notice: Int = 0, onOpenGame: (Int) -> Unit) {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory { initializer {
            LevelsViewModel(container.levelsRepository, container.progressRepository, container.wordRepository)
        } }
    }
    val model: LevelsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.openLevel) {
        state.openLevel?.let { index ->
            model.onGameOpened()
            onOpenGame(index)
        }
    }
    LevelsScreen(state.copy(message = if (notice != 0) notice else state.message), model::onSelectLevel, model::load, model::dismissLockedRequirement)
}

@Composable
fun LevelsScreen(state: LevelsUiState, onSelectLevel: (String) -> Unit, onRetry: () -> Unit, onDismissLocked: () -> Unit = {}) {
    val scroll = rememberLazyGridState()
    var positioned by rememberSaveable { mutableStateOf(false) }
    // Only the first loaded snapshot positions the map. Progress emissions keep the user's scroll.
    LaunchedEffect(state.loading) {
        if (!state.loading && !state.loadError && state.levels.isNotEmpty() && !positioned) {
            positioned = true
            val target = state.levels.firstOrNull { it.index == state.recommendedLevel }
            if (target != null && target.index != state.levels.first().index) {
                val position = 1 + state.levels.indexOf(target) + state.stages.count { it.firstLevel <= target.index }
                scroll.scrollToItem(position)
            }
        }
    }
    state.lockedRequirement?.let { (previous, required) ->
        AlertDialog(onDismissRequest = onDismissLocked,
            title = { Text(stringResource(R.string.level_locked)) },
            text = { Text(stringResource(R.string.level_unlock_requirement, previous, required, starForm(required))) },
            confirmButton = { TextButton(onDismissLocked) { Text(stringResource(R.string.close_dialog)) } })
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("levels_screen"), contentAlignment = Alignment.TopCenter) {
        // До загрузки данных не измеряем пустую сетку: она сбросила бы восстановленную позицию прокрутки.
        if (state.loading && state.levels.isEmpty()) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.levels_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Well { Text(stringResource(R.string.choose_level), style = MaterialTheme.typography.bodyLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.levels_loading))
                }
            }
            return@BoxWithConstraints
        }
        val columns = if (maxWidth < 600.dp) GridCells.Fixed(1) else GridCells.Adaptive(220.dp)
        LazyVerticalGrid(columns, Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("levels_grid"),
            state = scroll, contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "levels.header", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.levels_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    state.summary?.let { summary ->
                        Text(stringResource(R.string.levels_campaign_progress, summary.stars, summary.maxStars,
                            summary.passedLevels, summary.totalLevels), Modifier.testTag("levels_progress"))
                        OutlinedButton(onClick = {
                            state.levels.firstOrNull { it.index == state.recommendedLevel }?.let { onSelectLevel(it.word) }
                        }, enabled = state.canSelect, modifier = Modifier.testTag("levels_continue").heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.levels_continue))
                        }
                    }
                    if (state.finaleReached) {
                        Well {
                            Text(stringResource(if (state.summary?.isMaster == true) R.string.levels_master else R.string.levels_finale),
                                Modifier.testTag("levels_finale_banner").semantics { heading() })
                        }
                    }
                    if (state.loadError) {
                        AlertBanner(AlertType.Danger, stringResource(R.string.levels_load_error))
                        OutlinedButton(onRetry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
                    }
                    if (state.message != 0) AlertBanner(AlertType.Warning, stringResource(state.message))
                }
            }
            if (state.stages.isEmpty()) {
                items(state.levels, key = { it.word }) { item ->
                    LevelButton(item, state.canSelect && item.unlocked) { onSelectLevel(item.word) }
                }
            } else {
                state.stages.forEach { stage ->
                    item(key = "stage_${stage.number}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.levels_stage, stageName(stage.number), stage.firstLevel, stage.lastLevel),
                            Modifier.testTag("levels_stage_${stage.number}").semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.levels.filter { it.stage == stage.number }, key = { it.word }) { item ->
                        LevelButton(item, state.canSelect) { onSelectLevel(item.word) }
                    }
                }
            }
        }
    }
}

@Composable
private fun starForm(count: Int): String = RussianPlural.form(count,
    stringResource(R.string.star_one), stringResource(R.string.star_few), stringResource(R.string.star_many))

@Composable
private fun stageName(number: Int): String = stringResource(when (number) {
    1 -> R.string.stage_warmup
    2 -> R.string.stage_initial
    3 -> R.string.stage_long
    4 -> R.string.stage_riddles
    5 -> R.string.stage_endings
    else -> R.string.stage_master
})

@Composable
private fun LevelButton(item: LevelUiState, enabled: Boolean, onClick: () -> Unit) {
    val stars = stringResource(R.string.level_stars_progress, item.stars, item.maxStars)
    val status = stringResource(when {
        !item.unlocked -> R.string.level_locked
        item.stars == item.maxStars -> R.string.level_all_stars
        item.stars >= item.requiredStars -> R.string.level_passed
        else -> R.string.level_open
    })
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("level_card_${item.index}")
        .semantics { stateDescription = stars },
        enabled = enabled, shape = RoundedCornerShape(4.dp),
        border = BorderStroke(if (item.recommended) 3.dp else 1.dp, WordsColors.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WordsColors.background, contentColor = WordsColors.text),
        contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.level_button, item.index, item.word.uppercase(Locale.ROOT)),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (!item.unlocked) LockIcon()
                if (item.crowned) Icon(painterResource(R.drawable.ic_crown), stringResource(R.string.level_crown), Modifier.size(24.dp))
            }
            if (item.campaignStars == null) StarsRow(item.progress) else StarsRow(item.stars, item.maxStars)
            if (item.campaignStars != null) {
                Text(status)
                Text(stringResource(R.string.level_threshold, item.requiredStars, starForm(item.requiredStars)))
                val remaining = item.maxStars - item.stars
                if (remaining > 0) Text(stringResource(R.string.level_available_stars, remaining, starForm(remaining)))
                if (item.recommended) Text(stringResource(R.string.level_recommended))
            }
        }
    }
}

@Composable
private fun LockIcon() {
    Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.level_locked), Modifier.size(24.dp))
}
