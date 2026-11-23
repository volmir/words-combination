package com.example.sostavslova.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.HintPanelLabel
import com.example.sostavslova.core.HintPanelModel
import com.example.sostavslova.core.HintPanelTask
import com.example.sostavslova.core.LevelThresholdKind
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.LetterSlots
import com.example.sostavslova.ui.components.LetterTiles
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.starTint
import com.example.sostavslova.ui.components.FoundWords
import com.example.sostavslova.ui.components.AchievementStrip
import com.example.sostavslova.core.LevelCompleteSummary
import com.example.sostavslova.ui.components.WordsDialog
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsControlShape
import com.example.sostavslova.ui.theme.WordsPalette
import kotlinx.coroutines.delay

@Composable
fun GameRoute(onOpenLevel: (Int) -> Unit, onReturnLevels: (Int) -> Unit) {
    BackHandler { onReturnLevels(0) }
    val container = appContainer()
    val factory = remember(container) {
        viewModelFactory { initializer {
            GameViewModel(createSavedStateHandle(), container.wordRepository, container.levelsRepository,
                container.progressRepository, container.riddlesRepository)
        } }
    }
    val model: GameViewModel = viewModel(factory = factory)
    DisposableEffect(model) {
        onDispose { model.onScreenClosed() }
    }
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
    LaunchedEffect(state.nextLevelNavigation) { state.nextLevelNavigation?.let(onOpenLevel) }
    GameScreen(state, model::onInputChanged, model::onSubmit, model::onHint,
        model::onRetry, notice, model::onDismissExplanations,
        model::onCloseFinale, model::onNextLevel, model::onHintTask,
        model::onTileTap, model::onBackspace, model::onClearInput, model::onShuffle, model::onToggleInputMode, model::onCloseLevelComplete)
}

@Composable
fun GameScreen(
    state: GameUiState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onHint: () -> Unit,
    onRetry: () -> Unit,
    notice: GameMessage? = null,
    onDismissExplanations: () -> Unit = {},
    onCloseFinale: (Boolean) -> Unit = {},
    onNextLevel: () -> Unit = {},
    onHintTask: (String?) -> Unit = {},
    onTileTap: (Char) -> Unit = {},
    onBackspace: () -> Unit = {},
    onClearInput: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onToggleInputMode: () -> Unit = {},
    onCloseLevelComplete: (Boolean) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().testTag("game_screen")
            .imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                }
                LetterTiles(game.word, order = state.tileOrder, input = state.input,
                    enabled = state.canPlay, onTileTap = onTileTap)
                if (current != null) {
                    val starsDescription = pluralStringResource(R.plurals.level_stars_progress,
                        current.level.maxStars, current.completedTaskIds.size, current.level.maxStars)
                    val required = current.level.requiredStars
                    val maxStars = current.level.maxStars
                    val thresholdText = when (CampaignRules.thresholdKind(current.level, state.totalLevels)) {
                        LevelThresholdKind.ALL_FOR_NEXT -> stringResource(R.string.game_level_threshold_all)
                        LevelThresholdKind.PARTIAL_FOR_NEXT -> stringResource(R.string.game_level_threshold, required, maxStars)
                        LevelThresholdKind.ALL_FOR_FINAL -> stringResource(R.string.game_level_final_threshold_all)
                        LevelThresholdKind.PARTIAL_FOR_FINAL -> stringResource(R.string.game_level_final_threshold, required, maxStars)
                    }
                    Text(thresholdText,
                        Modifier.testTag("level_difficulty").semantics { stateDescription = starsDescription },
                        style = MaterialTheme.typography.bodyMedium)
                }
                Well {
                    GameInput(state, onInputChanged, onSubmit, onToggleInputMode,
                        onBackspace, onClearInput, onShuffle)
                    if (state.busy) GameLoading(stringResource(R.string.game_saving))
                }
                // Feedback stays in flow directly under the input: heightIn reserves
                // space for a single short banner (no jump on first message) while
                // still growing for long/multi-line text, 200% font scale, or
                // a multi-line award notice, without covering input/buttons.
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game_feedback"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (notice ?: state.message)?.let { GameBanner(it, state.countMinLength) }
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
                    CampaignHint(state, onHint, onHintTask)
                }
                Column(Modifier.fillMaxWidth().testTag("game_found_words"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(stringResource(R.string.game_found_summary, game.foundCount, game.total),
                        Modifier.testTag("game_found_summary").semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    val countedFound = game.countedFoundCount(state.countMinLength)
                    Text(stringResource(R.string.game_found_counted, countedFound, RussianPlural.words(countedFound),
                        state.countMinLength, letterForm(state.countMinLength)),
                        Modifier.testTag("game_found_counted"),
                        style = MaterialTheme.typography.bodyMedium)
                    if (game.allWords.any { it.length < state.countMinLength }) {
                        Text(stringResource(R.string.game_found_counted_note,
                            state.countMinLength, letterForm(state.countMinLength)),
                            Modifier.fillMaxWidth().testTag("game_found_counted_note"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
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
                        val rowLetterForm = RussianPlural.form(row.length, stringResource(R.string.game_letter_one),
                            stringResource(R.string.game_letter_few), stringResource(R.string.hint_letter_genitive_many))
                        val groupTitle = stringResource(R.string.game_found_group,
                            row.length, rowLetterForm, row.found, row.total)
                        Column(Modifier.fillMaxWidth()) {
                            TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()
                                .testTag("level_found_group_${row.length}").heightIn(min = 48.dp)
                                .semantics { stateDescription = groupState }, enabled = found.isNotEmpty()) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(groupTitle, style = MaterialTheme.typography.titleSmall)
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
    state.levelComplete?.takeIf { state.finaleSummary == null }?.let { event ->
        LevelCompleteDialog(event.summary, { onCloseLevelComplete(false) }, { onCloseLevelComplete(true) })
    }
    state.finaleSummary?.let { summary ->
        WordsDialog(stringResource(R.string.game_finale_title), { onCloseFinale(false) }) {
            Column(Modifier.testTag("level_finale_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.game_finale_stars, summary.stars, summary.maxStars), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.game_finale_words, summary.foundWords, RussianPlural.words(summary.foundWords)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.game_finale_hints, summary.shownHintSteps), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.game_finale_achievements, state.finaleAchievements), style = MaterialTheme.typography.bodyLarge)
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
    if (state.explanations.isNotEmpty() && !state.loading && state.finaleSummary == null && state.levelComplete == null) {
        WordsDialog(stringResource(R.string.game_explanations), onDismissExplanations) {
            state.explanations.forEach { Text(explanationText(it, state.countMinLength), style = MaterialTheme.typography.bodyLarge) }
            CloseDialogButton(onDismissExplanations)
        }
    }
}

/** Вертикальные кнопки и прокрутка WordsDialog сохраняют доступность при большом шрифте. */
@Composable
internal fun LevelCompleteDialog(summary: LevelCompleteSummary, onStay: () -> Unit, onNext: () -> Unit) {
    WordsDialog(stringResource(R.string.level_complete_title, summary.levelNumber), onStay,
        Modifier.testTag("level_complete_dialog"), titleTag = "level_complete_title") {
        Text(summary.word, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.level_complete_stars, summary.stars, summary.maxStars),
            Modifier.testTag("level_complete_stars"), style = MaterialTheme.typography.bodyLarge)
        LevelCompleteStars(summary.stars, summary.maxStars)
        if (summary.allStars) Text(stringResource(R.string.level_complete_all_stars), style = MaterialTheme.typography.bodyLarge)
        AchievementStrip(summary.achievements, summary.achievements, Modifier.fillMaxWidth(),
            iconTagPrefix = "level_complete_achievement", highlightedAchievements = summary.newAchievements)
        Text(stringResource(R.string.level_complete_achievements, summary.achievements.size, summary.availableAchievements.size),
            Modifier.testTag("level_complete_achievements"), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.level_complete_words, summary.foundWords, summary.totalWords), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.level_complete_hints, summary.hintsUsed), style = MaterialTheme.typography.bodyLarge)
        if (summary.hasNextLevel) TextButton(onNext,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("level_complete_next")) {
            Text(stringResource(R.string.level_complete_next))
        }
        TextButton(onStay, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("level_complete_stay")) {
            Text(stringResource(R.string.level_complete_stay))
        }
    }
}

/** Композиция отображает снимок панели, подготовленный ViewModel. */
@Composable
private fun CampaignHint(state: GameUiState, onHint: () -> Unit, onHintTask: (String?) -> Unit) {
    val panel = state.hintPanel ?: return
    val cost = panel.cost
    var choosingTask by rememberSaveable(state.game?.word) { mutableStateOf(false) }
    val changeEnabled = !panel.selectionLocked && !state.hintLoading
    Well(Modifier.testTag("game_hint").semantics { liveRegion = LiveRegionMode.Polite }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val heading: @Composable () -> Unit = {
                Text(stringResource(R.string.hint_task_heading, hintTaskLabel(panel.target)),
                    Modifier.testTag("game_hint_task").semantics { heading() },
                    style = MaterialTheme.typography.titleSmall)
            }
            val change: @Composable () -> Unit = {
                if (panel.options.isNotEmpty()) {
                    val description = stringResource(R.string.hint_task_change_description)
                    TextButton(onClick = { choosingTask = true }, enabled = changeEnabled,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                            .testTag("game_hint_task_change").semantics { contentDescription = description }) {
                        Text(stringResource(R.string.hint_task_change))
                    }
                }
            }
            // На узком экране и при крупном шрифте кнопка переносится под заголовок.
            if (maxWidth >= 480.dp * LocalDensity.current.fontScale) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { heading() }
                    change()
                }
            } else {
                Column { heading(); change() }
            }
        }
        if (panel.selectionLocked) {
            Text(stringResource(R.string.hint_task_locked), Modifier.testTag("game_hint_task_locked"),
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(if (panel.pattern == null) stringResource(R.string.hint_unopened)
            else stringResource(R.string.hint_caption, panel.wordLength, letterForm(panel.wordLength), panel.openedLetters),
            Modifier.testTag("game_hint_caption"), style = MaterialTheme.typography.bodyLarge)
        panel.pattern?.let { LetterSlots(it, Modifier.testTag("game_hint_pattern")) }
        if (state.hintLoading) Text(stringResource(R.string.hint_loading), style = MaterialTheme.typography.bodyMedium)
        val explanation = when {
            panel.ladderComplete -> R.string.hint_ladder_complete
            cost == null -> R.string.hint_all_found
            cost > panel.remaining -> R.string.hint_budget_exhausted
            else -> null
        }
        explanation?.let { Text(stringResource(it), Modifier.testTag("game_hint_budget"),
            style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.hint_remaining, panel.remaining),
            Modifier.testTag("game_hint_remaining"), style = MaterialTheme.typography.bodyMedium)
        val action = when {
            cost == null -> stringResource(R.string.hint_action_unavailable)
            cost == 0 -> stringResource(R.string.hint_action_free)
            else -> stringResource(R.string.hint_action_paid, cost, (panel.remaining - cost).coerceAtLeast(0))
        }
        OutlinedButton(
            onClick = onHint,
            enabled = state.canPlay && !panel.ladderComplete && cost != null && cost <= panel.remaining,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game_hint_button"),
            shape = WordsControlShape,
            border = BorderStroke(1.dp, LocalWordsPalette.current.defaultButtonBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = LocalWordsPalette.current.background,
                contentColor = LocalWordsPalette.current.text,
            ),
        ) {
            Text(action, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (choosingTask && panel.options.isNotEmpty() && !panel.selectionLocked) {
        HintTaskDialog(panel, enabled = changeEnabled, onDismiss = { choosingTask = false }) { taskId ->
            onHintTask(taskId)
            choosingTask = false
        }
    }
}

@Composable
internal fun HintTaskDialog(panel: HintPanelModel, enabled: Boolean, onDismiss: () -> Unit,
    onSelect: (String?) -> Unit) {
    WordsDialog(title = stringResource(R.string.hint_task_change), onDismiss = onDismiss,
        modifier = Modifier.testTag("game_hint_task_dialog")) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            panel.options.forEach { option ->
                val label = hintTaskLabel(option)
                HintTaskOption(label,
                    description = if (option.taskId == null) stringResource(R.string.hint_task_auto_description)
                        else stringResource(R.string.hint_task_description, label),
                    selected = option.taskId == panel.selectedTaskId, enabled = enabled,
                    onClick = { onSelect(option.taskId) },
                    tag = "game_hint_task_option_${option.taskId ?: "auto"}")
            }
        }
    }
}

/** Цвета строки выбора зависят от состояния и активной палитры. */
internal fun hintTaskOptionColors(
    palette: WordsPalette,
    selected: Boolean,
    enabled: Boolean,
): Triple<Color, Color, Color> = Triple(
    when {
        selected -> palette.primary
        !enabled -> palette.levelLockedBackground
        else -> palette.background
    },
    when {
        selected -> palette.background
        enabled -> palette.text
        else -> palette.defaultButtonText
    },
    when {
        selected -> palette.primary
        enabled -> palette.defaultButtonBorder
        else -> palette.wellBorder
    },
)

@Composable
private fun HintTaskOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val (container, content, borderColor) = hintTaskOptionColors(LocalWordsPalette.current, selected, enabled)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clip(WordsControlShape).background(container).border(1.dp, borderColor, WordsControlShape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).semantics { contentDescription = description }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = content, unselectedColor = content,
                disabledSelectedColor = content, disabledUnselectedColor = content))
        Text(label, Modifier.weight(1f), color = content, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Только локализация готового ключа и параметров из core. */
@Composable
private fun hintTaskLabel(task: HintPanelTask): String = when (task.label) {
    HintPanelLabel.AUTO -> stringResource(R.string.hint_task_auto)
    HintPanelLabel.MIN_LENGTH -> stringResource(R.string.hint_task_min_length, task.number, letterForm(task.number))
    HintPanelLabel.START_LETTER -> stringResource(R.string.hint_task_t2, task.argument)
    HintPanelLabel.RIDDLES -> stringResource(R.string.hint_task_t4)
    HintPanelLabel.RIDDLE_NUMBER -> stringResource(R.string.game_riddle_number, task.number)
    HintPanelLabel.ENDING -> stringResource(R.string.hint_task_t5, task.argument)
    HintPanelLabel.CATEGORY -> stringResource(R.string.hint_task_t6, categoryName(task.argument))
    HintPanelLabel.CONTAINS_LETTER -> stringResource(R.string.hint_task_t7, task.argument)
}

@Composable
internal fun GameBanner(message: GameMessage, countMinLength: Int? = null) {
    val text = (listOf(message) + message.additionalLines)
        .map { gameMessageLine(it, countMinLength) }.joinToString("\n")
    key(message) {
        AlertBanner(message.type, text, Modifier.rewardReveal(message.animateReward, durationMillis = 240)
            .testTag("game_banner"))
    }
}

@Composable
private fun gameMessageLine(message: GameMessage, countMinLength: Int? = null): String {
    val missing = message.missingLetter
    val pluralText = message.pluralText
    return when {
        pluralText != null -> pluralStringResource(pluralText, message.count, message.count)
        missing != null -> stringResource(message.text, message.word.orEmpty(),
            missing.letter.uppercaseChar().toString(), missing.available, missing.required)
        message.argumentText != null -> stringResource(message.text, stringResource(message.argumentText))
        message.word != null -> stringResource(message.text, message.word)
        message.text == R.string.correct_three_letter_answer && countMinLength != null ->
            stringResource(message.text, countMinLength, letterForm(countMinLength))
        else -> stringResource(message.text)
    }
}

@Composable
internal fun TaskRow(text: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(if (done) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            stringResource(if (done) R.string.task_done else R.string.task_pending), Modifier.size(24.dp),
            tint = starTint(LocalWordsPalette.current, done))
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
        colors = ButtonDefaults.buttonColors(containerColor = LocalWordsPalette.current.primary, contentColor = LocalWordsPalette.current.background)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GameAction(text: String, enabled: Boolean, onClick: () -> Unit, icon: Int? = null, tag: String = "") {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)), shape = WordsControlShape,
        border = BorderStroke(1.dp, LocalWordsPalette.current.defaultButtonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LocalWordsPalette.current.background, contentColor = LocalWordsPalette.current.text)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
