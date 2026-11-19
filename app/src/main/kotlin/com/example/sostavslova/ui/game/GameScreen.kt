package com.example.sostavslova.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.di.appContainer
import com.example.sostavslova.time.observePlayTimeLifecycle
import com.example.sostavslova.core.LevelRules
import com.example.sostavslova.core.HintLadder
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.LetterSlots
import com.example.sostavslova.ui.components.LetterTiles
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.WordInputRow
import com.example.sostavslova.ui.components.FoundWords
import com.example.sostavslova.ui.components.WordsDialog
import com.example.sostavslova.ui.theme.WordsColors
import com.example.sostavslova.ui.theme.WordsControlShape
import kotlinx.coroutines.delay

@Composable
fun GameRoute(playTimeOwner: String, onOpenLevel: (Int) -> Unit, onReturnLevels: (Int) -> Unit) {
    BackHandler { onReturnLevels(0) }
    val container = appContainer()
    val factory = remember(container, playTimeOwner) {
        viewModelFactory { initializer {
            GameViewModel(createSavedStateHandle(), container.wordRepository, container.levelsRepository,
                container.progressRepository, container.riddlesRepository, container.random,
                playTimeTracker = container.playTimeTracker, playTimeOwner = playTimeOwner)
        } }
    }
    val model: GameViewModel = viewModel(factory = factory)
    DisposableEffect(model) {
        onDispose { model.onScreenClosed() }
    }
    val state by model.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle, playTimeOwner) {
        model.syncPlayTime()
        val dispose = observePlayTimeLifecycle(lifecycle) {
            container.playTimeTracker.setLevelResumed(playTimeOwner, it)
        }
        onDispose {
            dispose()
            container.playTimeTracker.detachLevel(playTimeOwner)
        }
    }
    var notice by remember { mutableStateOf<GameMessage?>(null) }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                model.events.collect {
                    notice = it
                    delay(3500)
                    notice = null
                }
            } finally { notice = null }
        }
    }
    LaunchedEffect(state.returnLevels) { if (state.returnLevels) onReturnLevels(state.levelsMessage) }
    LaunchedEffect(state.nextLevelNavigation) { state.nextLevelNavigation?.let(onOpenLevel) }
    GameScreen(state, model::onInputChanged, model::onSubmit, model::onHint,
        model::onDialog, model::onDismissDialog, model::onRetry, notice, model::onDismissExplanations,
        model::onCloseFinale, model::onNextLevel)
}

@Composable
fun GameScreen(
    state: GameUiState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onHint: () -> Unit,
    onDialog: (GameDialog) -> Unit,
    onDismissDialog: () -> Unit,
    onRetry: () -> Unit,
    notice: GameMessage? = null,
    onDismissExplanations: () -> Unit = {},
    onCloseFinale: (Boolean) -> Unit = {},
    onNextLevel: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxSize().testTag("game_screen").imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) GameLoading(stringResource(R.string.game_loading))
            if (state.loadError) {
                AlertBanner(com.example.sostavslova.ui.components.AlertType.Danger, stringResource(R.string.game_load_error))
                GameAction(stringResource(R.string.retry), true, onRetry)
            }
            val game = state.game
            val current = state.campaignLevel
            if (game != null) {
                if (current != null) {
                    Text(stringResource(R.string.game_level_header, gameStageName(current.level.stage),
                        current.level.number, state.totalLevels), Modifier.testTag("level_header"),
                        style = MaterialTheme.typography.titleLarge)
                    LetterTiles(game.word)
                    val starsDescription = pluralStringResource(R.plurals.level_stars_progress,
                        current.level.maxStars, current.completedTaskIds.size, current.level.maxStars)
                    val required = current.level.requiredStars
                    val requiredWord = RussianPlural.form(required,
                        stringResource(R.string.star_one), stringResource(R.string.star_few),
                        stringResource(R.string.star_many))
                    Text(stringResource(if (current.level.number == state.totalLevels) R.string.game_level_final_threshold
                        else R.string.game_level_threshold, required, requiredWord),
                        Modifier.testTag("level_difficulty").semantics { stateDescription = starsDescription },
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    LetterTiles(game.word)
                    if (state.level != null) StarsRow(state.levelProgress, onClick = { onDialog(GameDialog.Tasks) })
                }
                Well {
                    WordInputRow(stringResource(R.string.your_answer), state.input, onInputChanged,
                        stringResource(R.string.check_answer), {
                            onSubmit()
                            focus.requestFocus()
                            keyboard?.show()
                        }, enabled = state.canPlay, inputEnabled = !state.loading && !state.returnLevels && !state.navigatingNext,
                        maxLength = null, focusRequester = focus)
                    if (state.busy) GameLoading(stringResource(R.string.game_saving))
                }
                // Feedback stays in flow directly under the input: heightIn reserves
                // space for a single short banner (no jump on first message) while
                // still growing for long/multi-line text, 200% font scale, or
                // message + notice shown together, without covering input/buttons.
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game_feedback"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.message?.let { GameBanner(it) }
                    notice?.let { GameBanner(it) }
                }
                state.nextLevelNumber?.let { number ->
                    Well(Modifier.testTag("game_next_level").semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.game_next_level_title),
                            Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.game_next_level_message), style = MaterialTheme.typography.bodyMedium)
                        GamePrimaryAction(stringResource(R.string.game_next_level_action, number),
                            state.canPlay, onNextLevel, "game_next_level_button")
                        if (state.navigatingNext) GameLoading(stringResource(R.string.game_saving))
                    }
                }
                if (current != null) {
                    CampaignTasks(state)
                    CampaignHint(state, onHint)
                } else {
                    if (state.hintLoading) GameLoading(stringResource(R.string.hint_loading))
                    state.hint?.let { hint ->
                        Column(Modifier.testTag("game_hint").semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.hint_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.hint_fallback,
                                hint.length, RussianPlural.form(hint.length,
                                    stringResource(R.string.hint_letter_genitive_one),
                                    stringResource(R.string.hint_letter_genitive_many),
                                    stringResource(R.string.hint_letter_genitive_many)), hint.firstLetter))

                        }
                    }
                    GamePrimaryAction(stringResource(R.string.show_hint), state.canPlay && !state.hintLoading,
                        onHint, "game_hint_button")
                }
                Column(Modifier.fillMaxWidth().testTag("game_found_words"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(stringResource(R.string.game_found_summary, game.foundCount, game.total),
                        Modifier.testTag("game_found_summary").semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    val lastFound = game.found.lastOrNull()
                    for (row in state.statistics.rows) {
                        val found = game.found.filter { it.length == row.length }.sorted()
                        var handledLastFound by rememberSaveable(game.word) { mutableStateOf(lastFound) }
                        var expanded by rememberSaveable(game.word, row.length) {
                            mutableStateOf(row.length == lastFound?.length)
                        }
                        LaunchedEffect(lastFound) {
                            if (lastFound != handledLastFound) {
                                if (row.length == lastFound?.length) expanded = true
                                handledLastFound = lastFound
                            }
                        }
                        val groupState = stringResource(if (expanded) R.string.game_group_expanded else R.string.game_group_collapsed)
                        Column(Modifier.fillMaxWidth()) {
                            TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()
                                .testTag("level_found_group_${row.length}").heightIn(min = 48.dp)
                                .semantics { stateDescription = groupState }, enabled = found.isNotEmpty()) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.game_found_group, row.length,
                                        RussianPlural.form(row.length, stringResource(R.string.game_letter_one),
                                            stringResource(R.string.game_letter_few), stringResource(R.string.hint_letter_genitive_many)),
                                        row.found, row.total),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(if (expanded) R.string.game_group_close else R.string.game_group_open),
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (expanded && found.isNotEmpty()) {
                                FoundWords(found, Modifier.fillMaxWidth().testTag("game_found_words_${row.length}"),
                                    highlighted = lastFound)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    state.finaleSummary?.let { summary ->
        WordsDialog(stringResource(R.string.game_finale_title), { onCloseFinale(false) }) {
            Column(Modifier.testTag("level_finale_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.game_finale_stars, summary.stars, summary.maxStars), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.game_finale_words, summary.foundWords, RussianPlural.words(summary.foundWords)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.game_finale_hints, summary.shownHintSteps), style = MaterialTheme.typography.bodyLarge)
                TextButton({ onCloseFinale(false) }, enabled = !state.finaleClosing) {
                    Text(stringResource(R.string.game_finale_continue))
                }
                TextButton({ onCloseFinale(true) }, enabled = !state.finaleClosing) {
                    Text(stringResource(R.string.game_finale_levels))
                }
                if (state.finaleClosing) GameLoading(stringResource(R.string.game_saving))
                state.message?.takeIf { it.text == R.string.game_save_error }?.let { GameBanner(it) }
            }
        }
    }
    if (state.explanations.isNotEmpty() && !state.loading && state.finaleSummary == null) {
        WordsDialog(stringResource(R.string.game_explanations), onDismissExplanations) {
            state.explanations.forEach { Text(stringResource(explanationText(it)), style = MaterialTheme.typography.bodyLarge) }
            CloseDialogButton(onDismissExplanations)
        }
    }
    when (state.dialog.takeIf { state.finaleSummary == null }) {
        GameDialog.Tasks -> if (state.campaignLevel == null) state.level?.let { level ->
            val found = state.game?.found.orEmpty()
            WordsDialog(stringResource(R.string.tasks), onDismissDialog) {
                val remaining = LevelRules.remainingToComplete(level, found)
                TaskRow(if (state.levelProgress.completed) stringResource(R.string.task_complete)
                    else stringResource(R.string.task_complete_remaining, remaining, RussianPlural.words(remaining)), state.levelProgress.completed)
                TaskRow(stringResource(R.string.task_words, level.words, RussianPlural.words(level.words), found.size), state.levelProgress.wordsTask)
                TaskRow(stringResource(R.string.task_letters, level.letterCount, RussianPlural.words(level.letterCount),
                    level.letter.toString(), LevelRules.lettersCount(level, found)), state.levelProgress.lettersTask)
                CloseDialogButton(onDismissDialog)
            }
        }
        null -> Unit
    }
}

/** Read the current snapshot only: no local accumulation or cached target across restoration. */
@Composable
private fun CampaignHint(state: GameUiState, onHint: () -> Unit) {
    val current = state.campaignLevel?.let { HintRules.restore(it) } ?: return
    val ladder = current.hints.ladder.takeUnless { it.target?.word in current.game.found } ?: HintLadder()
    val step = HintRules.currentStep(current)
    val pattern = step?.text?.takeIf { it.isNotEmpty() }
    val total = ladder.target?.word?.length ?: pattern?.length ?: 0
    val opened = pattern?.count { it != '_' } ?: 0
    val complete = pattern != null && ladder.steps.isNotEmpty() && ladder.shown.size == ladder.steps.size
    val cost = state.hintCost
    Well(Modifier.testTag("game_hint").semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(if (pattern == null) stringResource(R.string.hint_unopened)
            else stringResource(R.string.hint_stage, opened, total),
            Modifier.testTag("game_hint_stage").semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        if (pattern != null) {
            Text(pluralStringResource(R.plurals.hint_word_length, pattern.length, pattern.length),
                Modifier.testTag("game_hint_length"), style = MaterialTheme.typography.bodyLarge)
            LetterSlots(pattern, Modifier.testTag("game_hint_pattern"))
        }
        if (state.hintLoading) Text(stringResource(R.string.hint_loading), style = MaterialTheme.typography.bodyMedium)
        val explanation = when {
            complete -> R.string.hint_ladder_complete
            cost == null -> R.string.hint_all_found
            cost > state.hintRemaining -> R.string.hint_budget_exhausted
            else -> null
        }
        explanation?.let { Text(stringResource(it), Modifier.testTag("game_hint_budget"),
            style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.hint_remaining, state.hintRemaining),
            Modifier.testTag("game_hint_remaining"), style = MaterialTheme.typography.bodyMedium)
        val action = when {
            cost == null -> stringResource(R.string.hint_action_unavailable)
            cost == 0 -> stringResource(R.string.hint_action_free)
            else -> stringResource(R.string.hint_action_paid, cost, state.hintRemaining)
        }
        GamePrimaryAction(action,
            state.canPlay && !complete && cost != null && cost <= state.hintRemaining,
            onHint, "game_hint_button")
    }
}

@Composable
private fun GameBanner(message: GameMessage) {
    val missing = message.missingLetter
    val pluralText = message.pluralText
    val text = when {
        pluralText != null -> pluralStringResource(pluralText, message.count, message.count)
        missing != null -> stringResource(message.text, message.word.orEmpty(),
            missing.letter.uppercaseChar().toString(), missing.available, missing.required)
        message.word != null -> stringResource(message.text, message.word)
        else -> stringResource(message.text)
    }
    AlertBanner(message.type, text)
}

@Composable
internal fun TaskRow(text: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(if (done) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            stringResource(if (done) R.string.task_done else R.string.task_pending), Modifier.size(24.dp),
            tint = if (done) WordsColors.starDone else WordsColors.starEmpty)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CloseDialogButton(onDismiss: () -> Unit) {
    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close_dialog), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun GameLoading(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GamePrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Button(onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), shape = WordsControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = WordsColors.primary, contentColor = WordsColors.background)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GameAction(text: String, enabled: Boolean, onClick: () -> Unit, icon: Int? = null, tag: String = "") {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)), shape = WordsControlShape,
        border = BorderStroke(1.dp, WordsColors.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WordsColors.background, contentColor = WordsColors.text)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
