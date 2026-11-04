package com.example.sostavslova.ui.game

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.data.Definition
import com.example.sostavslova.data.DefinitionsRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.ui.components.AlertType
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameMessage(@param:StringRes val text: Int, val word: String? = null, val type: AlertType = AlertType.Info)

enum class GameDialog { Statistics, Tasks, Definition, Finish }

data class GameHint(val length: Int, val firstLetter: String, val definitions: List<Definition>)

data class GameUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val game: GameState? = null,
    val level: Level? = null,
    val levelProgress: LevelProgress = LevelProgress(),
    val statistics: Statistics = Statistics(emptyList()),
    val input: String = "",
    val busy: Boolean = false,
    val message: GameMessage? = null,
    val hint: GameHint? = null,
    val hintLoading: Boolean = false,
    val dialog: GameDialog? = null,
    val definitionWord: String = "",
    val definitions: List<Definition> = emptyList(),
    val definitionsAvailable: Boolean = false,
    val definitionLoading: Boolean = false,
    val returnHome: Boolean = false,
    @param:StringRes val homeMessage: Int = 0,
) {
    val canPlay: Boolean get() = game != null && !loading && !loadError && !busy && !returnHome
}

class GameViewModel(
    private val savedState: SavedStateHandle,
    private val words: WordRepository,
    private val levels: LevelsRepository,
    private val progress: ProgressRepository,
    private val definitions: DefinitionsRepository,
    private val random: Random = Random.Default,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val word: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GameUiState(input = savedState.get<String>(INPUT).orEmpty()))
    val uiState = mutableState.asStateFlow()
    // Одноразовые уведомления не выводятся из isFinished при каждой перерисовке.
    private val notices = Channel<GameMessage>(Channel.UNLIMITED)
    val events = notices.receiveAsFlow()
    private var operation: Job? = null
    private var hintJob: Job? = null
    private var definitionJob: Job? = null

    init { onGameOpened() }

    /** Повторный вход через меню может восстановить тот же NavBackStackEntry с другой игрой. */
    fun onGameOpened() {
        if (operation?.isActive == true || uiState.value.returnHome) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        operation = viewModelScope.launch {
            try {
                val current = progress.observeCurrentGame().first()
                val source = word ?: current?.word
                if (source == null) {
                    leave(R.string.enter_valid_word)
                    return@launch
                }
                val normalized = Alphabet.normalize(source)
                val level = levels.getLevels().firstOrNull { it.word == normalized }
                val restored = current?.takeIf { Alphabet.normalize(it.word) == normalized }?.found.orEmpty() +
                    if (level == null) emptyList() else progress.levelAnswers(level.word)
                val game = words.startGame(normalized, restored)
                if (game.hasNoWords) {
                    leave(R.string.no_combinations)
                    return@launch
                }
                // Запись уровня раньше currentGame: при прерывании объединение ответов восстановится.
                if (level != null) progress.saveLevelResult(level, game.found)
                progress.saveGame(game)
                val stars = if (level == null) LevelProgress() else
                    progress.observeLevelProgress().first()[level.word] ?: LevelProgress()
                val stats = withContext(computationDispatcher) { game.statistics() }
                val previousWord = savedState.get<String>(SOURCE)
                if (previousWord != normalized) {
                    onInputChanged("")
                    savedState[SOURCE] = normalized
                }
                mutableState.update { old -> old.copy(
                    loading = false, game = game, level = level, levelProgress = stars, statistics = stats,
                    hint = old.hint.takeIf { old.game == game },
                    dialog = old.dialog.takeIf { old.game?.word == normalized },
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadError = true) }
            }
        }
    }

    fun onInputChanged(raw: String) {
        val value = raw.take(Alphabet.MAX_WORD_LENGTH)
        savedState[INPUT] = value
        mutableState.update { it.copy(input = value, message = null) }
    }

    fun onSubmit() {
        val before = uiState.value
        val game = before.game ?: return
        if (!before.canPlay) return
        val raw = before.input
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try {
                val submission = withContext(computationDispatcher) { game.submit(raw) }
                if (submission.result == AnswerResult.EMPTY) return@launch
                val next = submission.state
                var stars = before.levelProgress
                if (submission.result == AnswerResult.CORRECT) {
                    before.level?.let { level ->
                        progress.saveLevelResult(level, next.found)
                        stars = progress.observeLevelProgress().first()[level.word] ?: stars
                    }
                    progress.saveGame(next)
                }
                val stats = withContext(computationDispatcher) { next.statistics() }
                val answer = Alphabet.normalize(raw)
                val message = when (submission.result) {
                    AnswerResult.CORRECT -> GameMessage(R.string.correct_answer, type = AlertType.Success)
                    AnswerResult.ALREADY_FOUND -> GameMessage(R.string.answer_already_found, answer)
                    AnswerResult.NOT_FOUND -> GameMessage(R.string.answer_not_found, answer, AlertType.Danger)
                    AnswerResult.TOO_SHORT -> GameMessage(R.string.word_too_short, type = AlertType.Danger)
                    AnswerResult.SAME_AS_SOURCE -> GameMessage(R.string.answer_same_as_source)
                    AnswerResult.EMPTY -> null
                }
                if (uiState.value.input == raw) {
                    savedState[INPUT] = ""
                    mutableState.update { it.copy(input = "") }
                }
                mutableState.update { it.copy(game = next, levelProgress = stars, statistics = stats, message = message) }
                if (stars.stars > before.levelProgress.stars) notices.send(GameMessage(R.string.star_received, type = AlertType.Success))
                if (!game.isFinished && next.isFinished) notices.send(GameMessage(R.string.all_words_found, type = AlertType.Success))
                if (next != game) {
                    // Подсказка не должна продолжать показывать уже отгаданный ответ.
                    hintJob?.cancel()
                    mutableState.update { it.copy(hint = null, hintLoading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun onHint() {
        val state = uiState.value
        val game = state.game ?: return
        if (!state.canPlay || hintJob?.isActive == true) return
        mutableState.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            try {
                val answer = withContext(computationDispatcher) { game.hintWord(random) }
                if (answer == null) {
                    mutableState.update { it.copy(message = GameMessage(R.string.hint_all_found), hint = null) }
                } else {
                    val meanings = try {
                        if (definitions.isAvailable()) definitions.definitionsFor(answer) else emptyList()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    // Даже если в источнике ответ встречается в примере/URL, подсказка его не выдаёт.
                    val safe = withContext(computationDispatcher) {
                        meanings.filter { definition ->
                            listOf(definition.text, definition.notes, definition.antonym, definition.example)
                                .none { containsAnswer(it, answer) }
                        }.map { it.copy(display = "", source = "") }
                    }
                    if (uiState.value.game == game) mutableState.update {
                        it.copy(hint = GameHint(answer.length, answer.first().uppercaseChar().toString(), safe))
                    }
                }
            } finally {
                mutableState.update { it.copy(hintLoading = false) }
            }
        }
    }

    fun onDefinition(selected: String) {
        if (selected !in uiState.value.game?.found.orEmpty()) return
        definitionJob?.cancel()
        mutableState.update { it.copy(dialog = GameDialog.Definition, definitionWord = selected,
            definitions = emptyList(), definitionLoading = true, definitionsAvailable = false) }
        definitionJob = viewModelScope.launch {
            try {
                val available = definitions.isAvailable()
                val found = if (available) definitions.definitionsFor(selected) else emptyList()
                mutableState.update { it.copy(definitionsAvailable = available, definitions = found) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(definitionsAvailable = false) } }
            finally { mutableState.update { it.copy(definitionLoading = false) } }
        }
    }

    fun onDialog(dialog: GameDialog) {
        if (!uiState.value.canPlay || dialog == GameDialog.Definition) return
        if (dialog == GameDialog.Tasks && uiState.value.level == null) return
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun onDismissDialog() {
        definitionJob?.cancel()
        mutableState.update { it.copy(dialog = null, definitionLoading = false) }
    }

    fun onFinish() {
        val state = uiState.value
        val game = state.game ?: return
        if (!state.canPlay || state.dialog != GameDialog.Finish) return
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try {
                state.level?.let { progress.saveLevelResult(it, game.found) }
                progress.clearCurrentGame()
                onDismissDialog()
                hintJob?.cancel()
                leave()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = GameMessage(R.string.game_save_error, type = AlertType.Danger), dialog = null) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun leave(@StringRes message: Int = 0) {
        mutableState.update { it.copy(loading = false, returnHome = true, homeMessage = message) }
    }

    private fun containsAnswer(text: String, answer: String): Boolean =
        Regex("[А-Яа-яЁё]+").findAll(text).any { Alphabet.normalize(it.value) == answer }

    private companion object {
        const val INPUT = "game.input"
        const val SOURCE = "game.source"
    }
}
