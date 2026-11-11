package com.example.sostavslova.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.core.LevelRules
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.DefinitionBlock
import com.example.sostavslova.ui.components.DefinitionDialog
import com.example.sostavslova.ui.components.LetterTiles
import com.example.sostavslova.ui.components.StarsRow
import com.example.sostavslova.ui.components.StatsTable
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.WordInputRow
import com.example.sostavslova.ui.components.WordLinks
import com.example.sostavslova.ui.components.WordsDialog
import com.example.sostavslova.ui.theme.WordsColors
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun GameRoute(onReturnLevels: (Int) -> Unit) {
    BackHandler { onReturnLevels(0) }
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory { initializer {
            GameViewModel(createSavedStateHandle(), container.wordRepository, container.levelsRepository,
                container.progressRepository, container.definitionsRepository, container.random,
                categoryText = { key -> com.example.sostavslova.data.CategoryNames.resource(key)?.let {
                    container.categoryName(it)
                } })
        } }
    }
    val model: GameViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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
    GameScreen(state, model::onInputChanged, model::onSubmit, model::onHint, model::onDefinition,
        model::onDialog, model::onDismissDialog, model::onRetry, notice, model::onDismissExplanations, model::onCloseFinale)
}

@Composable
fun GameScreen(
    state: GameUiState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onHint: () -> Unit,
    onDefinition: (String) -> Unit,
    onDialog: (GameDialog) -> Unit,
    onDismissDialog: () -> Unit,
    onRetry: () -> Unit,
    notice: GameMessage? = null,
    onDismissExplanations: () -> Unit = {},
    onCloseFinale: (Boolean) -> Unit = {},
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
            if (game != null) {
                state.campaignLevel?.let { current ->
                    Text(stringResource(R.string.game_level_header, gameStageName(current.level.stage),
                        current.level.number, state.totalLevels), Modifier.testTag("level_header"),
                        style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (current.level.number == state.totalLevels) R.string.game_level_final_threshold
                        else R.string.game_level_threshold, current.level.requiredStars, current.level.maxStars),
                        Modifier.testTag("level_difficulty"))
                    Text(stringResource(R.string.level_stars_progress, current.completedTaskIds.size, current.level.maxStars))
                    CampaignTasks(state)
                }
                LetterTiles(game.word)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.game_counts, game.total, RussianPlural.words(game.total), game.foundCount),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
                    if (state.level != null) StarsRow(state.levelProgress, onClick = { onDialog(GameDialog.Tasks) })
                }
                state.message?.let { GameBanner(it) }
                notice?.let { GameBanner(it) }
                Well {
                    WordInputRow(stringResource(R.string.your_answer), state.input, onInputChanged,
                        stringResource(R.string.check_answer), {
                            onSubmit()
                            focus.requestFocus()
                            keyboard?.show()
                        }, enabled = state.canPlay, inputEnabled = !state.loading && !state.returnLevels,
                        focusRequester = focus)
                    if (state.busy) GameLoading(stringResource(R.string.game_saving))
                }
                Text(stringResource(R.string.found_words), style = MaterialTheme.typography.titleMedium)
                for ((length, found) in game.found.groupBy { it.length }.toSortedMap()) {
                    var expanded by rememberSaveable(game.word, length) { mutableStateOf(false) }
                    Column {
                        TextButton({ expanded = !expanded }, Modifier.testTag("level_found_group_$length").heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.game_found_group, length,
                                RussianPlural.form(length, stringResource(R.string.game_letter_one),
                                    stringResource(R.string.game_letter_few), stringResource(R.string.hint_letter_genitive_many)),
                                found.size, stringResource(if (expanded) R.string.game_group_close else R.string.game_group_open)))
                        }
                        if (expanded) WordLinks(found, onDefinition)
                    }
                }
                if (state.hintLoading) GameLoading(stringResource(R.string.hint_loading))
                state.hint?.let { hint ->
                    Column(Modifier.testTag("game_hint"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.hint_title), style = MaterialTheme.typography.titleMedium)
                        if (hint.definitions.isEmpty()) Text(stringResource(R.string.hint_fallback,
                            hint.length, RussianPlural.form(hint.length,
                                stringResource(R.string.hint_letter_genitive_one),
                                stringResource(R.string.hint_letter_genitive_many),
                                stringResource(R.string.hint_letter_genitive_many)), hint.firstLetter))
                        else {
                            hint.definitions.forEach { DefinitionBlock(it, labelWord = null) }
                            if (hint.definitions.any { it.origin == "wiktionary" }) {
                                Text(stringResource(R.string.hint_definition_source), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.campaignHints.isNotEmpty()) {
                    Column(Modifier.testTag("game_hint"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.hint_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.hint_stage, state.campaignHints.size,
                            state.campaignLevel?.hints?.ladder?.steps?.size ?: 0), Modifier.testTag("game_hint_stage"))
                        state.campaignHints.forEach { step ->
                            step.text?.let { Text(it) }
                            step.length?.let { length -> Text(stringResource(R.string.hint_length, length,
                                RussianPlural.form(length, stringResource(R.string.hint_letter_genitive_one),
                                    stringResource(R.string.hint_letter_genitive_many), stringResource(R.string.hint_letter_genitive_many)))) }
                            if (step.wiktionary) Text(stringResource(R.string.hint_definition_source), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.hintFallback) {
                            Text(stringResource(R.string.hint_letters_available), style = MaterialTheme.typography.bodySmall)
                            if (com.example.sostavslova.BuildConfig.DEBUG) Text(stringResource(R.string.hint_metadata_unavailable),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.campaignLevel != null && state.hintRemaining == 0 && state.hintCost != 0) {
                    Text(stringResource(R.string.hint_budget_exhausted), Modifier.testTag("game_hint_budget"))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameAction(if (state.campaignLevel == null) stringResource(R.string.show_hint)
                        else stringResource(R.string.hint_action, state.hintCost ?: 0, state.hintRemaining),
                        state.canPlay && !state.hintLoading && (state.campaignLevel == null ||
                            (state.hintCost != null && (state.hintCost == 0 || state.hintRemaining > 0))), onHint, R.drawable.ic_help, "game_hint_button")
                    GameAction(stringResource(R.string.statistics), state.canPlay, { onDialog(GameDialog.Statistics) }, R.drawable.ic_statistics)
                    if (state.level != null || state.campaignLevel != null) GameAction(stringResource(R.string.tasks), state.canPlay,
                        { onDialog(GameDialog.Tasks) }, R.drawable.ic_star_outline)
                }
            }
        }
    }
    state.finaleSummary?.let { summary ->
        WordsDialog(stringResource(R.string.game_finale_title), { onCloseFinale(false) }) {
            Column(Modifier.testTag("level_finale_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.game_finale_stars, summary.stars, summary.maxStars))
                Text(stringResource(R.string.game_finale_words, summary.foundWords, RussianPlural.words(summary.foundWords)))
                Text(stringResource(R.string.game_finale_hints, summary.shownHintSteps))
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
            state.explanations.forEach { Text(stringResource(explanationText(it))) }
            CloseDialogButton(onDismissExplanations)
        }
    }
    when (state.dialog.takeIf { state.finaleSummary == null }) {
        GameDialog.Statistics -> {
            val word = state.game?.word.orEmpty().uppercase(Locale.ROOT)
            val title = stringResource(R.string.game_statistics_title, word)
            val styled = buildAnnotatedString {
                append(title)
                val start = title.indexOf(word)
                if (word.isNotEmpty() && start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + word.length)
            }
            WordsDialog(title, onDismissDialog, titleText = styled) {
                Column(Modifier.testTag("level_stats")) {
                    StatsTable(state.statistics, showRemaining = true)
                    state.campaignLevel?.hints?.let { hints ->
                        Text(stringResource(R.string.game_stats_hints, hints.shownSteps, hints.paidUnits))
                    }
                }
                CloseDialogButton(onDismissDialog)
            }
        }
        GameDialog.Tasks -> if (state.campaignLevel != null) {
            WordsDialog(stringResource(R.string.tasks), onDismissDialog) {
                CampaignTasks(state)
                CloseDialogButton(onDismissDialog)
            }
        } else state.level?.let { level ->
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
        GameDialog.Definition -> if (state.definitionLoading) {
            WordsDialog(stringResource(R.string.nav_word_meaning), onDismissDialog) {
                GameLoading(stringResource(R.string.definition_loading))
                CloseDialogButton(onDismissDialog)
            }
        } else DefinitionDialog(state.definitionWord, state.definitions, onDismissDialog, state.definitionsAvailable)
        null -> Unit
    }
}

@Composable
private fun GameBanner(message: GameMessage) {
    AlertBanner(message.type, if (message.word == null) stringResource(message.text) else stringResource(message.text, message.word))
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
private fun GameAction(text: String, enabled: Boolean, onClick: () -> Unit, icon: Int? = null, tag: String = "") {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)), shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, WordsColors.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WordsColors.background, contentColor = WordsColors.text)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
