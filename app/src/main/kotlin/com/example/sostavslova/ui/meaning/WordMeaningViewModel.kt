package com.example.sostavslova.ui.meaning

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.WordInput
import com.example.sostavslova.core.parseWordInput
import com.example.sostavslova.data.Definition
import com.example.sostavslova.data.DefinitionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WordMeaningUiState(
    val input: String = "",
    val hasDefinitionsInBuild: Boolean = BuildConfig.HAS_DEFINITIONS,
    @param:StringRes val message: Int? = null,
    val searchedWord: String? = null,
    val definitions: List<Definition> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
)

class WordMeaningViewModel(
    private val savedState: SavedStateHandle,
    private val definitions: DefinitionsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        WordMeaningUiState(
            input = savedState.get<String>(KEY_INPUT).orEmpty().take(Alphabet.MAX_WORD_LENGTH),
        )
    )
    val uiState = mutableState.asStateFlow()

    private var searchJob: Job? = null

    init {
        val savedWord = savedState.get<String>(KEY_SEARCHED_WORD)
        if (!savedWord.isNullOrEmpty() && BuildConfig.HAS_DEFINITIONS) {
            executeSearch(savedWord)
        }
    }

    fun onInputChanged(raw: String) {
        val tooLong = parseWordInput(raw) is WordInput.TooLong
        val displayed = if (raw.length > Alphabet.MAX_WORD_LENGTH) Alphabet.normalize(raw) else raw
        val input = displayed.take(Alphabet.MAX_WORD_LENGTH)
        savedState[KEY_INPUT] = input
        mutableState.update {
            it.copy(
                input = input,
                message = if (tooLong) R.string.word_too_long else null,
            )
        }
    }

    fun onSearch() {
        val raw = uiState.value.input
        val normalized = Alphabet.normalize(raw)
        if (normalized.isEmpty()) {
            mutableState.update { it.copy(message = R.string.enter_valid_word) }
            return
        }
        savedState[KEY_SEARCHED_WORD] = normalized
        executeSearch(normalized)
    }

    private fun executeSearch(word: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(isSearching = true, message = null) }
        searchJob = viewModelScope.launch {
            try {
                val available = definitions.isAvailable()
                if (!available) {
                    mutableState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            searchedWord = word,
                            definitions = emptyList(),
                        )
                    }
                    return@launch
                }
                val result = definitions.definitionsFor(word)
                mutableState.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchedWord = word,
                        definitions = result,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(isSearching = false) }
            }
        }
    }

    companion object {
        private const val KEY_INPUT = "word_meaning.input"
        private const val KEY_SEARCHED_WORD = "word_meaning.searched_word"
    }
}
