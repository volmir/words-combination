package com.example.sostavslova.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.WordInput
import com.example.sostavslova.core.parseWordInput
import com.example.sostavslova.data.LoadState
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.ui.components.AlertType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeMessage(@param:StringRes val text: Int, val type: AlertType = AlertType.Danger)

data class HomeUiState(
    val input: String = "",
    val dictionaryReady: Boolean = false,
    val dictionaryLoading: Boolean = true,
    val dictionaryError: Boolean = false,
    val randomWords: List<String> = emptyList(),
    val randomWordsLoading: Boolean = false,
    val randomWordsError: Boolean = false,
    val currentGame: GameState? = null,
    val isStarting: Boolean = false,
    val message: HomeMessage? = null,
    val openGame: Boolean = false,
) {
    val canStart: Boolean get() = dictionaryReady && !isStarting && !openGame
}

class HomeViewModel(
    private val savedState: SavedStateHandle,
    private val words: WordRepository,
    private val progress: ProgressRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(
        input = savedState.get<String>(INPUT).orEmpty().take(Alphabet.MAX_WORD_LENGTH),
        message = if (savedState.get<Boolean>(TOO_LONG) == true) HomeMessage(R.string.word_too_long) else null,
    ))
    val uiState = mutableState.asStateFlow()
    private var randomJob: Job? = null

    init {
        viewModelScope.launch {
            words.state.collect { load ->
                mutableState.update { it.copy(
                    dictionaryReady = load is LoadState.Ready,
                    dictionaryLoading = load is LoadState.Loading,
                    dictionaryError = load is LoadState.Error,
                ) }
            }
        }
        viewModelScope.launch {
            combine(words.state, progress.observeCurrentGame()) { load, saved -> load to saved }
                .catch { error ->
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(message = HomeMessage(R.string.home_progress_error)) }
                }
                .collectLatest { (load, saved) ->
                    if (load !is LoadState.Ready || saved == null) {
                        mutableState.update { it.copy(currentGame = null) }
                    } else {
                        try {
                            val game = words.startGame(saved.word, saved.found)
                            mutableState.update { it.copy(currentGame = game.takeUnless { candidate -> candidate.hasNoWords }) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.update { it.copy(currentGame = null, message = HomeMessage(R.string.home_progress_error)) }
                        }
                    }
                }
        }
        viewModelScope.launch { words.ensureLoaded() }
    }

    fun onInputChanged(raw: String) {
        val tooLong = parseWordInput(raw) is WordInput.TooLong
        val displayed = if (raw.length > Alphabet.MAX_WORD_LENGTH) Alphabet.normalize(raw) else raw
        val input = displayed.take(Alphabet.MAX_WORD_LENGTH)
        savedState[INPUT] = input
        savedState[TOO_LONG] = tooLong
        mutableState.update { it.copy(
            input = input,
            message = if (tooLong) HomeMessage(R.string.word_too_long) else null,
        ) }
    }

    fun onStart() {
        val input = if (savedState.get<Boolean>(TOO_LONG) == true) WordInput.TooLong
            else parseWordInput(uiState.value.input)
        start(input)
    }

    fun onRandomWord(word: String) = start(parseWordInput(word))

    private fun start(input: WordInput) {
        if (!uiState.value.canStart) return
        val error = when (input) {
            WordInput.Empty -> R.string.enter_valid_word
            WordInput.TooShort -> R.string.word_too_short
            WordInput.TooLong -> R.string.word_too_long
            is WordInput.Ok -> null
        }
        if (error != null) {
            mutableState.update { it.copy(message = HomeMessage(error)) }
            return
        }
        if (input !is WordInput.Ok) return
        mutableState.update { it.copy(isStarting = true, message = null) }
        viewModelScope.launch {
            try {
                val saved = progress.observeCurrentGame().first()
                val restored = saved?.takeIf { Alphabet.normalize(it.word) == input.word }?.found.orEmpty()
                val game = words.startGame(input.word, restored)
                if (game.hasNoWords) {
                    mutableState.update { it.copy(message = HomeMessage(R.string.no_combinations, AlertType.Warning)) }
                } else {
                    // Навигация разрешена только после завершения записи в DataStore.
                    progress.saveGame(game)
                    mutableState.update { it.copy(currentGame = game, openGame = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = HomeMessage(R.string.home_start_error)) }
            } finally {
                mutableState.update { it.copy(isStarting = false) }
            }
        }
    }

    fun onContinue() {
        if (uiState.value.canStart && uiState.value.currentGame != null) {
            mutableState.update { it.copy(openGame = true) }
        }
    }

    fun onGameOpened() {
        mutableState.update { it.copy(openGame = false) }
    }

    fun onNavigationMessage(@StringRes message: Int) {
        mutableState.update { it.copy(message = HomeMessage(message,
            if (message == R.string.no_combinations) AlertType.Warning else AlertType.Danger)) }
    }

    fun onHomeOpened() = refreshRandomWords()

    fun refreshRandomWords() {
        if (randomJob?.isActive == true) return
        mutableState.update { it.copy(randomWordsLoading = true, randomWordsError = false) }
        randomJob = viewModelScope.launch {
            try {
                words.ensureLoaded()
                if (words.state.value !is LoadState.Ready) return@launch
                val randomWords = words.randomSourceWords()
                mutableState.update { it.copy(randomWords = randomWords) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(randomWordsError = true) }
            } finally {
                mutableState.update { it.copy(randomWordsLoading = false) }
            }
        }
    }

    private companion object {
        const val INPUT = "home.input"
        const val TOO_LONG = "home.tooLong"
    }
}
