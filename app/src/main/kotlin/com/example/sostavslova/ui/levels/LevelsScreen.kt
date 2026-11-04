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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.example.sostavslova.R
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsColors
import java.util.Locale

@Composable
fun LevelsRoute(onOpenGame: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory { initializer {
            LevelsViewModel(container.levelsRepository, container.progressRepository, container.wordRepository)
        } }
    }
    val model: LevelsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.openGame) {
        if (state.openGame) {
            model.onGameOpened()
            onOpenGame()
        }
    }
    LevelsScreen(state, model::onSelectLevel, model::load)
}

@Composable
fun LevelsScreen(state: LevelsUiState, onSelectLevel: (String) -> Unit, onRetry: () -> Unit) {
    val scroll = rememberLazyGridState()
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
            item(key = "levels.title", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.levels_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            item(key = "levels.prompt", span = { GridItemSpan(maxLineSpan) }) {
                Well { Text(stringResource(R.string.choose_level), style = MaterialTheme.typography.bodyLarge) }
            }
            if (state.loading || state.startingWord != null) item(key = "levels.loading", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (state.loading) R.string.levels_loading else R.string.home_starting))
                }
            }
            if (state.loadError) item(key = "levels.error", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertBanner(AlertType.Danger, stringResource(R.string.levels_load_error))
                    OutlinedButton(onRetry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
                }
            }
            if (state.message != 0) item(key = "levels.message", span = { GridItemSpan(maxLineSpan) }) {
                AlertBanner(if (state.message == R.string.no_combinations) AlertType.Warning else AlertType.Danger,
                    stringResource(state.message))
            }
            items(state.levels, key = { it.level.word }) { item ->
                LevelButton(item, enabled = item.unlocked && state.canSelect, onClick = { onSelectLevel(item.level.word) })
            }
        }
    }
}

@Composable
private fun LevelButton(item: LevelUiState, enabled: Boolean, onClick: () -> Unit) {
    val stars = stringResource(R.string.stars_progress, item.progress.stars)
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("level_${item.level.index}")
        .semantics { stateDescription = stars },
        enabled = enabled, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, WordsColors.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WordsColors.background, contentColor = WordsColors.text),
        contentPadding = PaddingValues(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val label = stringResource(R.string.level_button, item.level.index, item.level.word.uppercase(Locale.ROOT))
            if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (!item.unlocked) LockIcon()
                    }
                    StarsRow(item.progress, Modifier.align(Alignment.End))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (!item.unlocked) LockIcon()
                    StarsRow(item.progress)
                }
            }
        }
    }
}

@Composable
private fun LockIcon() {
    Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.level_locked), Modifier.size(24.dp))
}
