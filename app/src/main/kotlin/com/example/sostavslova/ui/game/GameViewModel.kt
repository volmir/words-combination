package com.example.sostavslova.ui.game

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignSummary
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.TaskRules
import com.example.sostavslova.core.TaskProgress
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.HintStep
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.HintStatus
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.LevelRouteRules
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.Submission
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.data.RiddlesRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.time.PlayTimeTarget
import com.example.sostavslova.time.PlayTimeTracker
import com.example.sostavslova.ui.components.AlertType
import kotlin.random.Random
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

data class GameMessage(@param:StringRes val text: Int, val word: String? = null,
    val missingLetter: com.example.sostavslova.core.MissingLetter? = null,
    val type: AlertType = AlertType.Info)

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

enum class GameDialog { Statistics, Tasks }

data class GameHint(val length: Int, val firstLetter: String)

// Только безопасный текст и открытая часть слова; полного ответа в UI-модели загадки нет.
data class RiddleUiState(val text: String, val solved: Boolean = false)

data class GameUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val game: GameState? = null,
    val level: Level? = null,
    val campaignLevel: CurrentCampaignLevel? = null,
    val totalLevels: Int = 0,
    val finaleSummary: CampaignSummary? = null,
    val finaleClosing: Boolean = false,
    val taskProgress: List<TaskProgress> = emptyList(),
    val riddles: Map<String, List<RiddleUiState>> = emptyMap(),
    val explanations: List<TaskType> = emptyList(),
    val levelProgress: LevelProgress = LevelProgress(),
    val statistics: Statistics = Statistics(emptyList()),
    val input: String = "",
    val busy: Boolean = false,
    val message: GameMessage? = null,
    val hint: GameHint? = null,
    val campaignHint: HintStep? = null,
    val campaignHints: List<HintStep> = emptyList(),
    val hintCost: Int? = null,
    val hintRemaining: Int = 0,
    val hintLoading: Boolean = false,
    val dialog: GameDialog? = null,
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
    private val random: Random = Random.Default,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val playTimeTracker: PlayTimeTracker? = null,
    private val playTimeOwner: String? = null,
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
    private var hintSequence = 0L

    private var campaign: Campaign? = null

    init { onGameOpened() }

    fun onRetry() { if (uiState.value.loadError) onGameOpened() }

    fun onGameOpened() {
        if (sessionJob?.isActive == true || uiState.value.returnLevels ||
            (uiState.value.game != null && !uiState.value.loadError)) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        syncPlayTime()
        sessionJob = viewModelScope.launch {
            try {
                val number = levelNumber
                val loadedCampaign = levels.getCampaign()
                val legacy = if (loadedCampaign == null) levels.getLevels() else emptyList()
                if (!LevelRouteRules.exists(number, loadedCampaign, legacy)) {
                    leave(R.string.level_invalid)
                    return@launch
                }
                val index = words.requireIndex()
                val saved = if (loadedCampaign != null) progress.migrateCampaign(loadedCampaign, index)
                    else { progress.migrateLegacyLevels(legacy); CampaignProgress() }
                val stars = if (loadedCampaign == null) progress.observeLevelProgress().first() else emptyMap()
                if (!LevelRouteRules.isUnlocked(number, loadedCampaign, saved, legacy, stars)) {
                    leave(R.string.level_closed)
                    return@launch
                }
                campaign = loadedCampaign
                val current = if (loadedCampaign == null) null else withContext(computationDispatcher) {
                    CampaignRules.openLevel(loadedCampaign, saved, requireNotNull(number), index)
                }
                val level = legacy.firstOrNull { it.index == number }
                val game = current?.game ?: words.startGame(requireNotNull(level).word, progress.levelAnswers(level.word))
                if (game.hasNoWords) {
                    leave(R.string.no_combinations)
                    return@launch
                }
                if (loadedCampaign != null) {
                    progress.visitCampaignLevel(loadedCampaign, requireNotNull(number))
                    val active = requireNotNull(current)
                    // Поясняем тип ровно при его первом фактическом появлении в кампании.
                    // Ротация внутри этапа не должна показывать урок раньше нужного уровня.
                    val introduced = active.level.tasks.map { it.type }.distinct().filter { type ->
                        loadedCampaign.levels.none { earlier ->
                            earlier.number < active.level.number && earlier.tasks.any { task -> task.type == type }
                        }
                    }
                    val explanations = introduced.filter { progress.markCampaignExplanationSeen(it) }
                    val riddles = loadRiddles(active)
                    mutableState.update { it.copy(totalLevels = loadedCampaign.levels.size,
                        riddles = riddles, explanations = explanations) }
                } else mutableState.update { it.copy(totalLevels = legacy.size) }
                currentCoroutineContext().ensureActive()
                // Поток — единственный источник игрового снимка. Результат записи не
                // публикуется отдельно: он мог уже уступить место более свежей транзакции.
                if (current != null) {
                    progress.observeCampaignProgress()
                        .collect { savedCampaign ->
                            val snapshot = savedCampaign.levels[game.word] ?: CampaignLevelProgress()
                            val restored = withContext(computationDispatcher) {
                                current.copy(game = game.restoreFound(snapshot.found.map(Alphabet::normalize)),
                                    completedTaskIds = snapshot.completedTaskIds.intersect(current.level.tasks.map { it.id }.toSet()),
                                    hints = snapshot.hints).let { HintRules.restore(it) }
                            }
                            publishSession(restored.game, level, restored, LevelProgress(),
                                if (savedCampaign.finaleReached && !savedCampaign.finaleShown) {
                                    CampaignRules.summary(requireNotNull(loadedCampaign), savedCampaign)
                                } else null,
                                nextLevelNumber = (requireNotNull(number) + 1).takeIf {
                                    LevelRouteRules.isUnlocked(it, loadedCampaign, savedCampaign, legacy, emptyMap())
                                })
                        }
                } else {
                    progress.observeLevelSession(game.word).collect { snapshot ->
                        val allStars = progress.observeLevelProgress().first()
                        val restored = withContext(computationDispatcher) { game.restoreFound(snapshot.found) }
                        val next = legacy.getOrNull(legacy.indexOfFirst { it.index == number } + 1)?.index
                        publishSession(restored, level, null, snapshot.progress,
                            nextLevelNumber = next?.takeIf {
                                LevelRouteRules.isUnlocked(it, null, CampaignProgress(), legacy, allStars)
                            })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadError = true) }
                syncPlayTime()
            }
        }
    }

    private suspend fun publishSession(game: GameState, level: Level?, current: CurrentCampaignLevel?, stars: LevelProgress,
        finaleSummary: CampaignSummary? = null, nextLevelNumber: Int? = null) {
        val (shownHints, cost) = withContext(computationDispatcher) {
            val shown = current?.takeIf { HintRules.currentStep(it) != null }?.hints?.ladder?.shown.orEmpty()
            shown to current?.let { HintRules.nextCost(it, campaign?.countMinLength ?: 4) }
        }
        val (stats, tasks) = withContext(computationDispatcher) {
            game.statistics() to current?.let { TaskRules.evaluate(it, campaign?.countMinLength ?: 4).progress }.orEmpty()
        }
        val solved = current?.level?.tasks?.filterIsInstance<CampaignTask.T4>()?.associate { task ->
            task.id to task.targets.map { it in game.found }
        }.orEmpty()
        currentCoroutineContext().ensureActive()
        val changed = uiState.value.game?.found != game.found
        if (changed && campaign == null) {
            hintJob?.cancel()
            hintSequence++
        }
        mutableState.update { it.copy(loading = false, loadError = false, game = game, level = level,
            nextLevelNumber = nextLevelNumber,
            finaleSummary = finaleSummary,
            campaignLevel = current, campaignHint = shownHints.lastOrNull(), campaignHints = shownHints,
            hintCost = cost, hintRemaining = current?.let { HintRules.budget(it).remaining } ?: 0,
            levelProgress = stars, statistics = stats, taskProgress = tasks,
            riddles = it.riddles.mapValues { (id, items) -> items.mapIndexed { index, riddle ->
                riddle.copy(solved = solved[id]?.getOrNull(index) == true)
            } },
            hint = if (changed) null else it.hint,
            hintLoading = if (changed && campaign == null) false else it.hintLoading) }
        syncPlayTime()
    }

    // Вызывается после подтверждённого снимка ProgressRepository, даже если UI уже ушёл.
    // busy/hintLoading/игровые диалоги не останавливают время текущего уровня.
    internal fun syncPlayTime() {
        val tracker = playTimeTracker ?: return
        val owner = playTimeOwner ?: return
        val state = uiState.value
        val current = state.campaignLevel
        val activeCampaign = campaign
        val target = if (activeCampaign != null && current != null && state.game != null &&
            !state.loading && !state.loadError && !state.returnLevels) {
            PlayTimeTarget(activeCampaign, current.level.number, current.completedTaskIds.size)
        } else null
        tracker.updateLevel(owner, target, finished = state.finaleSummary != null)
    }

    override fun onCleared() {
        playTimeOwner?.let { playTimeTracker?.detachLevel(it) }
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
                val activeCampaign = campaign
                val legacy = if (activeCampaign == null) levels.getLevels() else emptyList()
                val saved = if (activeCampaign == null) CampaignProgress() else progress.observeCampaignProgress().first()
                val stars = if (activeCampaign == null) progress.observeLevelProgress().first() else emptyMap()
                if (LevelRouteRules.isUnlocked(target, activeCampaign, saved, legacy, stars)) {
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

    fun onSubmit() {
        val before = uiState.value
        val game = before.game ?: return
        if (!before.canPlay) return
        val raw = before.input
        // Латиница, цифры и знаки после нормализации дают пустую строку. Раньше
        // такая отправка молча ничего не делала, и поле казалось сломанным.
        if (Alphabet.normalize(raw).isEmpty()) {
            mutableState.update { it.copy(message = GameMessage(R.string.answer_empty, type = AlertType.Danger)) }
            return
        }
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try {
                val activeCampaign = campaign
                val campaignAnswer = if (activeCampaign == null) null else progress.submitCampaignAnswerWithTransitions(
                    activeCampaign, requireNotNull(before.campaignLevel).level.number, raw, words.requireIndex())
                val submitted = campaignAnswer?.submission
                val submission = submitted?.let { Submission(it.evaluation.state.game, it.result, it.missingLetter, it.probabilistic) }
                    ?: withContext(computationDispatcher) { game.submit(raw, words.requireIndex()) }
                if (submission.result == AnswerResult.EMPTY) {
                    mutableState.update { it.copy(message = GameMessage(R.string.answer_empty, type = AlertType.Danger)) }
                    return@launch
                }
                val next = submission.state
                var stars = before.levelProgress
                if (submission.result == AnswerResult.CORRECT) {
                    before.level?.let { level ->
                        progress.saveLevelResult(level, next.found)
                        stars = progress.observeLevelProgress().first()[level.word] ?: stars
                    }
                }
                currentCoroutineContext().ensureActive()
                // Дождаться подтверждённого снимка перед следующим вводом.
                if (submission.result == AnswerResult.CORRECT) uiState.first { state ->
                    next.found.all { it in state.game?.found.orEmpty() }
                }
                val answer = Alphabet.normalize(raw)
                val message = submissionMessage(submission, answer,
                    threeLetterCampaignAnswer = activeCampaign != null && answer.length == 3)
                // Отказ оставляет набранный текст: опечатку можно исправить без повторного ввода.
                if (uiState.value.input == raw && submissionClearsInput(submission.result)) {
                    mutableState.update { it.copy(input = "") }
                }
                mutableState.update { it.copy(message = message) }
                if (submitted != null) {
                    val evaluation = submitted.evaluation
                    repeat(evaluation.newlyCompletedTaskIds.size) {
                        notices.send(GameMessage(R.string.star_received, type = AlertType.Success))
                    }
                    if (campaignAnswer?.openedNextLevel == true) notices.send(GameMessage(R.string.game_next_opened, type = AlertType.Success))
                } else if (stars.stars > before.levelProgress.stars) {
                    notices.send(GameMessage(R.string.star_received, type = AlertType.Success))
                }
                if (!game.isFinished && next.isFinished) notices.send(GameMessage(R.string.all_words_found, type = AlertType.Success))
                if (next != game) {
                    // Подсказка не должна продолжать показывать уже отгаданный ответ.
                    hintJob?.cancel()
                    hintSequence++
                    mutableState.update { it.copy(hint = null, hintLoading = false) }
                }
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
        val game = state.game ?: return
        if (!state.canPlay || hintJob?.isActive == true) return
        val request = ++hintSequence
        mutableState.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            try {
                val activeCampaign = campaign
                if (activeCampaign != null) {
                    val current = requireNotNull(state.campaignLevel)
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
                    return@launch
                }
                val candidates = withContext(computationDispatcher) { game.hintCandidates(random) }
                val fallback = candidates.firstOrNull()
                if (fallback == null) {
                    mutableState.update { it.copy(message = GameMessage(R.string.hint_all_found), hint = null) }
                } else {
                    val result = GameHint(fallback.length, fallback.first().uppercaseChar().toString())
                    if (hintSequence == request && uiState.value.game == game) mutableState.update {
                        it.copy(hint = result)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                if (hintSequence == request) mutableState.update { it.copy(hintLoading = false) }
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

    fun onDialog(dialog: GameDialog) {
        if (!uiState.value.canPlay) return
        if (dialog == GameDialog.Tasks && uiState.value.level == null && uiState.value.campaignLevel == null) return
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun onDismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    private fun leave(@StringRes message: Int = 0) {
        mutableState.update { it.copy(loading = false, returnLevels = true, levelsMessage = message) }
        syncPlayTime()
    }
}
