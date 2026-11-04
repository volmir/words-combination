package com.example.sostavslova.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.WordInputRow
import com.example.sostavslova.ui.theme.WordsColors
import java.util.Locale

@Composable
fun HomeRoute(notice: Int = 0, onNoticeConsumed: () -> Unit = {}, onOpenGame: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory {
            initializer { HomeViewModel(createSavedStateHandle(), container.wordRepository, container.progressRepository) }
        }
    }
    val model: HomeViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        if (notice != 0) {
            model.onNavigationMessage(notice)
            onNoticeConsumed()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onHomeOpened() }
    LaunchedEffect(state.openGame) {
        if (state.openGame) {
            model.onGameOpened()
            onOpenGame()
        }
    }
    HomeScreen(state, model::onInputChanged, model::onStart, model::onRandomWord,
        model::refreshRandomWords, model::onContinue)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChanged: (String) -> Unit,
    onStart: () -> Unit,
    onRandomWord: (String) -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier.testTag("home_screen").fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
            state.message?.let { AlertBanner(it.type, stringResource(it.text)) }
            if (state.dictionaryLoading) LoadingText(stringResource(R.string.home_dictionary_loading))
            if (state.dictionaryError) {
                AlertBanner(AlertType.Danger, stringResource(R.string.home_dictionary_error))
                HomeButton(stringResource(R.string.retry), onRefresh)
            }
            Well {
                WordInputRow(
                    label = stringResource(R.string.enter_word), value = state.input,
                    onValueChange = onInputChanged, buttonText = stringResource(R.string.start_game),
                    onSubmit = onStart, imeAction = ImeAction.Go, enabled = state.canStart,
                    inputEnabled = !state.isStarting, onLengthExceeded = onInputChanged,
                )
                if (state.isStarting) LoadingText(stringResource(R.string.home_starting))
            }
            state.currentGame?.let { game ->
                val label = stringResource(R.string.continue_game)
                Well(Modifier.clickable(enabled = state.canStart, role = Role.Button, onClickLabel = label, onClick = onContinue)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_continue_progress, game.word.uppercase(Locale.ROOT), game.foundCount, game.total),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Well {
                Text(stringResource(R.string.home_random_title), style = MaterialTheme.typography.titleMedium)
                if (state.randomWordsLoading) LoadingText(stringResource(R.string.home_random_loading))
                if (state.randomWordsError) AlertBanner(AlertType.Warning, stringResource(R.string.home_random_error))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.randomWords.forEach { word ->
                        HomeButton(word.uppercase(Locale.ROOT), { onRandomWord(word) }, enabled = state.canStart)
                    }
                }
                HomeButton(stringResource(R.string.other_words), onRefresh,
                    enabled = state.dictionaryReady && !state.randomWordsLoading && !state.isStarting)
            }
            Text(stringResource(R.string.nav_about), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LoadingText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = text }, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HomeButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, WordsColors.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WordsColors.background, contentColor = WordsColors.text),
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}
