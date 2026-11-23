package com.example.sostavslova.ui.game

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.CampaignSummary
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.TaskRules
import com.example.sostavslova.core.TaskProgress
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.core.HintStep
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.HintStatus
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.LevelRouteRules
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.LetterOrder
import com.example.sostavslova.core.TileUsage
import com.example.sostavslova.data.InputMode
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.Submission
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.data.RiddlesRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.ui.components.achievementNameRes
import com.example.sostavslova.ui.components.AlertType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class GameMessage(@param:StringRes val text: Int, val word: String? = null,
    val missingLetter: com.example.sostavslova.core.MissingLetter? = null,
    val type: AlertType = AlertType.Info,
    @param:PluralsRes val pluralText: Int? = null,
    val count: Int = 0,
    @param:StringRes val argumentText: Int? = null,
    val additionalLines: List<GameMessage> = emptyList())

/** Только представление наград: правила выдачи и порядок достижений задаёт core. */
internal fun awardMessage(
    stars: Int,
    openedNextLevel: Boolean,
    achievements: List<LevelAchievement>,
): GameMessage? {
    val ordered = LevelAchievement.entries.filter { it in achievements }
    val lines = buildList {
        if (stars > 0) add(GameMessage(R.string.star_received, type = AlertType.Success,
            pluralText = R.plurals.stars_received, count = stars))
        if (openedNextLevel) add(GameMessage(R.string.game_next_opened, type = AlertType.Success))
        ordered.take(3).forEach { achievement ->
            add(GameMessage(R.string.achievement_received, type = AlertType.Success,
                argumentText = achievementNameRes(achievement)))
        }
        if (ordered.size > 3) add(GameMessage(R.string.achievement_more, type = AlertType.Success,
            pluralText = R.plurals.achievements_more, count = ordered.size - 3))
    }
    return lines.firstOrNull()?.copy(additionalLines = lines.drop(1))
}

/** Формулировка отказа сохраняет различие точного словаря и вероятного совпадения. */
internal fun submissionMessage(submission: Submission, answer: String, threeLetterCampaignAnswer: Boolean = false): GameMessage? {
    return when (submission.result) {
        AnswerResult.CORRECT -> GameMessage(if (threeLetterCampaignAnswer)
            R.string.correct_three_letter_answer else R.string.correct_answer, type = AlertType.Success)
        AnswerResult.ALREADY_FOUND -> GameMessage(R.string.answer_already_found, answer)
        AnswerResult.NOT_FOUND -> GameMessage(R.string.answer_unknown, answer, type = AlertType.Danger)
        AnswerResult.NOT_COUNTED -> GameMessage(R.string.answer_not_counted, answer, type = AlertType.Danger)
        AnswerResult.LETTERS_MISSING -> {
            val missing = submission.missingLetter
            if (missing == null) GameMessage(R.string.answer_letters_missing_error, type = AlertType.Danger)
            else GameMessage(if (submission.probabilistic) R.string.answer_letters_missing_probable
                else R.string.answer_letters_missing, answer, missing, AlertType.Danger)
        }
        AnswerResult.TOO_SHORT -> GameMessage(R.string.word_too_short, type = AlertType.Danger)
        AnswerResult.SAME_AS_SOURCE -> GameMessage(R.string.answer_same_as_source)
        AnswerResult.EMPTY -> null
    }
}

/** Поле очищают только зачёт и повтор уже найденного; любой отказ сохраняет набранный текст. */
internal fun submissionClearsInput(result: AnswerResult): Boolean = when (result) {
    AnswerResult.CORRECT, AnswerResult.ALREADY_FOUND -> true
    AnswerResult.NOT_FOUND, AnswerResult.LETTERS_MISSING, AnswerResult.NOT_COUNTED,
    AnswerResult.SAME_AS_SOURCE, AnswerResult.EMPTY, AnswerResult.TOO_SHORT -> false
}

// Только безопасный текст и открытая часть слова; полного ответа в UI-модели загадки нет.
data class RiddleUiState(val text: String, val solved: Boolean = false)

data class GameUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val game: GameState? = null,
    val campaignLevel: CurrentCampaignLevel? = null,
    val countMinLength: Int = 4,
    val totalLevels: Int = 0,
    val finaleSummary: CampaignSummary? = null,
    val finaleClosing: Boolean = false,
    val taskProgress: List<TaskProgress> = emptyList(),
    val riddles: Map<String, List<RiddleUiState>> = emptyMap(),
    val explanations: List<TaskType> = emptyList(),
    val statistics: Statistics = Statistics(emptyList()),
    val input: String = "",
    val inputMode: InputMode = InputMode.TOUCH,
    val tileOrder: List<Int> = game?.word?.let(LetterOrder::natural).orEmpty(),
    val inputModeSaving: Boolean = false,
    val busy: Boolean = false,
    val message: GameMessage? = null,
    val campaignHint: HintStep? = null,
    val campaignHints: List<HintStep> = emptyList(),
    val hintCost: Int? = null,
    val hintRemaining: Int = 0,
    val hintLoading: Boolean = false,
    val returnLevels: Boolean = false,
    val nextLevelNumber: Int? = null,
    val navigatingNext: Boolean = false,
    val nextLevelNavigation: Int? = null,
    @param:StringRes val levelsMessage: Int = 0,
) {
    val canPlay: Boolean get() = game != null && !loading && !loadError && !busy && !hintLoading && !returnLevels && !navigatingNext && finaleSummary == null
}

class GameViewModel(
    savedState: SavedStateHandle,
    private val words: WordRepository,
    private val levels: LevelsRepository,
    private val progress: ProgressRepository,
    private val riddles: RiddlesRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val shuffleSeed: () -> Long = { Random.nextLong() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(GameUiState())
    val uiState = mutableState.asStateFlow()
    // Одноразовые уведомления не выводятся из isFinished при каждой перерисовке.
    private val notices = Channel<GameMessage>(Channel.UNLIMITED)
    val events = notices.receiveAsFlow()
    // Ключ неизменяем: каждый level/{index} владеет своей ViewModel в NavBackStackEntry.
    private val levelNumber = savedState.get<String>("index")?.toIntOrNull()
    private var sessionJob: Job? = null
    private var operation: Job? = null
    private var hintJob: Job? = null

    private var campaign: Campaign? = null

    init { onGameOpened() }

    fun onRetry() { if (uiState.value.loadError) onGameOpened() }

    fun onGameOpened() {
        if (sessionJob?.isActive == true || uiState.value.returnLevels ||
            (uiState.value.game != null && !uiState.value.loadError)) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        sessionJob = viewModelScope.launch {
            try {
                val number = levelNumber
                val loadedCampaign = requireNotNull(levels.getCampaign()) { "Campaign asset is required" }
                if (!LevelRouteRules.exists(number, loadedCampaign)) {
                    leave(R.string.level_invalid)
                    return@launch
                }
                val index = words.requireIndex()
                val saved = progress.migrateCampaign(loadedCampaign, index)
                if (!LevelRouteRules.isUnlocked(number, loadedCampaign, saved)) {
                    leave(R.string.level_closed)
                    return@launch
                }
                campaign = loadedCampaign
                val current = withContext(computationDispatcher) {
                    CampaignRules.openLevel(loadedCampaign, saved, requireNotNull(number), index)
                }
                if (current == null) {
                    leave(R.string.level_closed)
                    return@launch
                }
                val game = current.game
                if (game.hasNoWords) {
                    leave(R.string.no_combinations)
                    return@launch
                }
                progress.visitCampaignLevel(loadedCampaign, requireNotNull(number))
                // Поясняем тип ровно при его первом фактическом появлении в кампании.
                // Ротация внутри этапа не должна показывать урок раньше нужного уровня.
                val introduced = current.level.tasks.map { it.type }.distinct().filter { type ->
                    loadedCampaign.levels.none { earlier ->
                        earlier.number < current.level.number && earlier.tasks.any { task -> task.type == type }
                    }
                }
                val explanations = introduced.filter { progress.markCampaignExplanationSeen(it) }
                val riddles = loadRiddles(current)
                val inputMode = progress.observeInputMode().first()
                mutableState.update { it.copy(totalLevels = loadedCampaign.levels.size,
                    riddles = riddles, explanations = explanations, inputMode = inputMode) }
                currentCoroutineContext().ensureActive()
                // Поток — единственный источник игрового снимка. Результат записи не
                // публикуется отдельно: он мог уже уступить место более свежей транзакции.
                progress.observeCampaignProgress()
                    .collect { savedCampaign ->
                        val snapshot = savedCampaign.levels[game.word] ?: CampaignLevelProgress()
                        val restored = withContext(computationDispatcher) {
                            current.copy(game = game.restoreFound(snapshot.found.map(Alphabet::normalize)),
                                completedTaskIds = snapshot.completedTaskIds.intersect(current.level.tasks.map { it.id }.toSet()),
                                hints = snapshot.hints).let { HintRules.restore(it) }
                        }
                        publishSession(restored.game, restored,
                            if (savedCampaign.finaleReached && !savedCampaign.finaleShown) {
                                CampaignRules.summary(loadedCampaign, savedCampaign)
                            } else null,
                            nextLevelNumber = (requireNotNull(number) + 1).takeIf {
                                LevelRouteRules.isUnlocked(it, loadedCampaign, savedCampaign)
                            })
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadError = true) }
            }
        }
    }

    private suspend fun publishSession(game: GameState, current: CurrentCampaignLevel,
        finaleSummary: CampaignSummary? = null, nextLevelNumber: Int? = null) {
        val (shownHints, cost) = withContext(computationDispatcher) {
            val shown = current.takeIf { HintRules.currentStep(it) != null }?.hints?.ladder?.shown.orEmpty()
            shown to HintRules.nextCost(current, campaign?.countMinLength ?: 4)
        }
        val (stats, tasks) = withContext(computationDispatcher) {
            game.statistics() to TaskRules.evaluate(current, campaign?.countMinLength ?: 4).progress
        }
        val solved = current.level.tasks.filterIsInstance<CampaignTask.T4>().associate { task ->
            task.id to task.targets.map { it in game.found }
        }
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(loading = false, loadError = false, game = game,
            tileOrder = if (it.game?.word == game.word) it.tileOrder else LetterOrder.natural(game.word),
            nextLevelNumber = nextLevelNumber,
            finaleSummary = finaleSummary,
            campaignLevel = current, campaignHint = shownHints.lastOrNull(), campaignHints = shownHints,
            countMinLength = campaign?.countMinLength ?: 4,
            hintCost = cost, hintRemaining = HintRules.budget(current).remaining,
            statistics = stats, taskProgress = tasks,
            riddles = it.riddles.mapValues { (id, items) -> items.mapIndexed { index, riddle ->
                riddle.copy(solved = solved[id]?.getOrNull(index) == true)
            } }) }
    }

    // Черновик живёт только пока открыт экран, даже если ViewModel пережила Activity.
    fun onScreenClosed() { mutableState.update { it.copy(input = "") } }

    fun onNextLevel() {
        val before = uiState.value
        val target = before.nextLevelNumber ?: return
        if (!before.canPlay) return
        // Захватываем переход синхронно: повторное нажатие не создаёт второй маршрут.
        mutableState.update { it.copy(navigatingNext = true) }
        viewModelScope.launch {
            try {
                // Не регистрируем сам барьер через trackOperation: иначе он ждал бы себя.
                progress.awaitSaved()
                val activeCampaign = requireNotNull(campaign)
                val saved = progress.observeCampaignProgress().first()
                if (LevelRouteRules.isUnlocked(target, activeCampaign, saved)) {
                    mutableState.update { it.copy(input = "", nextLevelNavigation = target) }
                } else mutableState.update { it.copy(navigatingNext = false, nextLevelNumber = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(navigatingNext = false,
                    message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            }
        }
    }

    fun onInputChanged(raw: String) {
        // Правильный ответ не длиннее исходника (≤ MAX_WORD_LENGTH), поэтому
        // произвольный ввод не обрезаем: скрытая потеря последних символов недопустима.
        mutableState.update { it.copy(input = raw, message = null) }
    }

    fun onTileTap(char: Char) {
        mutableState.update { state ->
            val game = state.game
            if (!state.canPlay || game == null) return@update state
            val used = TileUsage.usedMask(game.word, state.tileOrder, state.input)
            val letter = Alphabet.normalize(char.toString())
            val available = state.tileOrder.indices.any { position ->
                !used[position] && Alphabet.normalize(game.word[state.tileOrder[position]].toString()) == letter
            }
            if (available) state.copy(input = state.input + char, message = null) else state
        }
    }

    fun onBackspace() {
        if (uiState.value.canPlay) onInputChanged(uiState.value.input.dropLast(1))
    }

    fun onClearInput() {
        if (uiState.value.canPlay) onInputChanged("")
    }

    fun onShuffle() {
        val before = uiState.value
        val word = before.game?.word ?: return
        if (!before.canPlay) return
        val order = LetterOrder.shuffled(word, shuffleSeed(), before.tileOrder)
        mutableState.update { it.copy(tileOrder = order) }
    }

    fun onToggleInputMode() {
        val before = uiState.value
        if (!before.canPlay || before.inputModeSaving) return
        val mode = if (before.inputMode == InputMode.TOUCH) InputMode.KEYBOARD else InputMode.TOUCH
        mutableState.update { it.copy(inputModeSaving = true) }
        val job = viewModelScope.launch {
            try {
                progress.setInputMode(mode)
                mutableState.update { it.copy(inputMode = mode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(inputModeSaving = false) }
            }
        }
        progress.trackOperation(job)
    }

    fun onSubmit() {
        val before = uiState.value
        val game = before.game ?: return
        if (!before.canPlay) return
        val raw = before.input
        // Не отбрасываем посторонние символы молча: иначе проверяется не то
        // слово, которое набрал игрок.
        if (Alphabet.hasForeignCharacters(raw)) {
            mutableState.update { it.copy(message = GameMessage(R.string.answer_invalid_characters, type = AlertType.Danger)) }
            return
        }
        // Пустой ввод после нормализации получает отдельное сообщение.
        if (Alphabet.normalize(raw).isEmpty()) {
            mutableState.update { it.copy(message = GameMessage(R.string.answer_empty, type = AlertType.Danger)) }
            return
        }
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try {
                val activeCampaign = requireNotNull(campaign)
                val submitted = progress.submitCampaignAnswerWithTransitions(
                    activeCampaign, requireNotNull(before.campaignLevel).level.number, raw, words.requireIndex())
                val submission = Submission(submitted.submission.evaluation.state.game, submitted.submission.result,
                    submitted.submission.missingLetter, submitted.submission.probabilistic)
                if (submission.result == AnswerResult.EMPTY) {
                    mutableState.update { it.copy(message = GameMessage(R.string.answer_empty, type = AlertType.Danger)) }
                    return@launch
                }
                val next = submission.state
                currentCoroutineContext().ensureActive()
                // Дождаться подтверждённого снимка перед следующим вводом.
                if (submission.result == AnswerResult.CORRECT) uiState.first { state ->
                    next.found.all { it in state.game?.found.orEmpty() }
                }
                val answer = Alphabet.normalize(raw)
                val message = submissionMessage(submission, answer,
                    threeLetterCampaignAnswer = answer.length == 3)
                // Отказ оставляет набранный текст: опечатку можно исправить без повторного ввода.
                if (uiState.value.input == raw && submissionClearsInput(submission.result)) {
                    mutableState.update { it.copy(input = "") }
                }
                mutableState.update { it.copy(message = message) }
                awardMessage(
                    stars = submitted.submission.evaluation.newlyCompletedTaskIds.size,
                    openedNextLevel = submitted.openedNextLevel,
                    achievements = submitted.newAchievements.map { it.second },
                )?.let { notices.send(it) }
                if (!game.isFinished && next.isFinished) notices.send(GameMessage(R.string.all_words_found, type = AlertType.Success))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }.also(progress::trackOperation)
    }

    fun onHint() {
        val state = uiState.value
        if (!state.canPlay || hintJob?.isActive == true) return
        val current = state.campaignLevel ?: return
        val activeCampaign = campaign ?: return
        mutableState.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            try {
                val result = progress.requestCampaignHint(activeCampaign, current.level.number,
                    current.hints.shownSteps, words.requireIndex())
                currentCoroutineContext().ensureActive()
                if (result != null) {
                    uiState.first { (it.campaignLevel?.hints?.shownSteps ?: 0) >= result.state.hints.shownSteps }
                    mutableState.update { it.copy(message = when (result.status) {
                        HintStatus.BUDGET_EXHAUSTED -> GameMessage(R.string.hint_budget_exhausted)
                        HintStatus.NO_TARGET -> GameMessage(R.string.hint_all_found)
                        HintStatus.LADDER_COMPLETE -> GameMessage(R.string.hint_ladder_complete)
                        HintStatus.SHOWN -> null
                    }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(hintLoading = false) }
            }
        }.also(progress::trackOperation)
    }

    /**
     * Выбор задания действует до первой платной ступени; купленная ступень фиксирует
     * цель, пока её слово не найдено. Повторное нажатие во время сохранения и
     * заблокированная попытка не создают транзакцию.
     */
    fun onHintTask(taskId: String?) {
        val state = uiState.value
        if (!state.canPlay || hintJob?.isActive == true) return
        val current = state.campaignLevel ?: return
        val ladder = current.hints.ladder
        // Платная ступень фиксирует цель, пока загаданное слово не найдено.
        val locked = ladder.paidSteps > 0 && ladder.target?.word?.let { it in current.game.found } == false
        if (locked) return
        val activeCampaign = campaign ?: return
        mutableState.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            try {
                val selected = progress.selectCampaignHintTask(activeCampaign, current.level.number,
                    taskId, words.requireIndex())
                currentCoroutineContext().ensureActive()
                uiState.first { it.campaignLevel?.hints?.preferredTaskId == selected.hints.preferredTaskId }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(hintLoading = false) }
            }
        }.also(progress::trackOperation)
    }

    private suspend fun loadRiddles(current: CurrentCampaignLevel): Map<String, List<RiddleUiState>> =
        current.level.tasks.filterIsInstance<CampaignTask.T4>().associate { task ->
            task.id to task.targets.map { answer -> RiddleUiState(riddles.textFor(answer)) }
        }

    // Подтверждаем только закрытие: до него событие восстанавливается из DataStore.
    fun onCloseFinale(toLevels: Boolean) {
        if (uiState.value.finaleSummary == null || uiState.value.finaleClosing) return
        mutableState.update { it.copy(finaleClosing = true) }
        viewModelScope.launch {
            try {
                progress.consumeCampaignFinaleDialog()
                uiState.first { it.finaleSummary == null }
                if (toLevels) leave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(finaleClosing = false) }
            }
        }.also(progress::trackOperation)
    }

    fun onDismissExplanations() { mutableState.update { it.copy(explanations = emptyList()) } }

    private fun leave(@StringRes message: Int = 0) {
        mutableState.update { it.copy(loading = false, returnLevels = true, levelsMessage = message) }
    }
}
