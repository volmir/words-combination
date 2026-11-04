package com.example.sostavslova.ui.levels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.LevelRules
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LevelUiState(val level: Level, val progress: LevelProgress, val unlocked: Boolean)

data class LevelsUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val levels: List<LevelUiState> = emptyList(),
    val startingWord: String? = null,
    val openGame: Boolean = false,
    @param:StringRes val message: Int = 0,
) {
    val canSelect: Boolean get() = !loading && !loadError && startingWord == null && !openGame
}

class LevelsViewModel(
    private val levels: LevelsRepository,
    private val progress: ProgressRepository,
    private val words: WordRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LevelsUiState())
    val uiState = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        loadJob = viewModelScope.launch {
            try {
                val all = levels.getLevels()
                progress.observeLevelProgress().collect { saved ->
                    val items = withContext(computationDispatcher) {
                        all.mapIndexed { position, level ->
                            LevelUiState(level, saved[level.word] ?: LevelProgress(), LevelRules.isUnlocked(all, saved, position))
                        }
                    }
                    mutableState.update { it.copy(loading = false, levels = items) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, loadError = true) } }
        }
    }

    fun onSelectLevel(word: String) {
        val state = uiState.value
        if (!state.canSelect || state.levels.none { it.level.word == word && it.unlocked }) return
        mutableState.update { it.copy(startingWord = word, message = 0) }
        viewModelScope.launch {
            try {
                // Перепроверяем по сохранённым звёздам, не полагаясь только на состояние кнопки.
                val all = levels.getLevels()
                val position = all.indexOfFirst { it.word == word }
                val savedProgress = progress.observeLevelProgress().first()
                if (position < 0 || !LevelRules.isUnlocked(all, savedProgress, position)) return@launch
                val level = all[position]
                val current = progress.observeCurrentGame().first()
                val restored = current?.takeIf { Alphabet.normalize(it.word) == level.word }?.found.orEmpty() +
                    progress.levelAnswers(level.word)
                val game = words.startGame(level.word, restored)
                if (game.hasNoWords) {
                    mutableState.update { it.copy(message = R.string.no_combinations) }
                } else {
                    // Переход в игру разрешается только после записи; правила восстановления — в :core.
                    progress.saveGame(game)
                    mutableState.update { it.copy(openGame = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(message = R.string.level_start_error) } }
            finally { mutableState.update { it.copy(startingWord = null) } }
        }
    }

    fun onGameOpened() {
        mutableState.update { it.copy(openGame = false) }
    }
}
