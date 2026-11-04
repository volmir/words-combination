package com.example.sostavslova.ui.search

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.WordInput
import com.example.sostavslova.core.parseWordInput
import com.example.sostavslova.data.Definition
import com.example.sostavslova.data.DefinitionsRepository
import com.example.sostavslova.data.WordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WordSearchGroup(
    val length: Int,
    val words: List<String>,
)

data class WordSearchResultUi(
    val source: String,
    val total: Int,
    val lengthKeysSummary: String,
    val groups: List<WordSearchGroup>,
)

data class WordSearchUiState(
    val input: String = "",
    @param:StringRes val message: Int? = null,
    val result: WordSearchResultUi? = null,
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val dialogWord: String = "",
    val dialogDefinitions: List<Definition> = emptyList(),
    val dialogDefinitionsAvailable: Boolean = false,
    val showDialog: Boolean = false,
)

class WordSearchViewModel(
    private val savedState: SavedStateHandle,
    private val words: WordRepository,
    private val definitions: DefinitionsRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        WordSearchUiState(
            input = savedState.get<String>(KEY_INPUT).orEmpty().take(Alphabet.MAX_WORD_LENGTH),
            message = if (savedState.get<Boolean>(KEY_TOO_LONG) == true) R.string.word_too_long else null,
        )
    )
    val uiState = mutableState.asStateFlow()

    private var searchJob: Job? = null
    private var dialogJob: Job? = null

    init {
        val savedWord = savedState.get<String>(KEY_SEARCHED_WORD)
        if (!savedWord.isNullOrEmpty()) {
            executeSearch(savedWord)
        }
    }

    fun onInputChanged(raw: String) {
        val tooLong = parseWordInput(raw) is WordInput.TooLong
        val displayed = if (raw.length > Alphabet.MAX_WORD_LENGTH) Alphabet.normalize(raw) else raw
        val input = displayed.take(Alphabet.MAX_WORD_LENGTH)
        savedState[KEY_INPUT] = input
        savedState[KEY_TOO_LONG] = tooLong
        mutableState.update {
            it.copy(
                input = input,
                message = if (tooLong) R.string.word_too_long else null,
            )
        }
    }

    fun onSearch() {
        val input = if (savedState.get<Boolean>(KEY_TOO_LONG) == true) {
            WordInput.TooLong
        } else {
            parseWordInput(uiState.value.input)
        }

        val error = when (input) {
            WordInput.Empty -> R.string.enter_valid_word
            WordInput.TooShort -> R.string.word_too_short
            WordInput.TooLong -> R.string.word_too_long
            is WordInput.Ok -> null
        }

        if (error != null) {
            mutableState.update { it.copy(message = error) }
            return
        }

        if (input !is WordInput.Ok) return
        savedState[KEY_SEARCHED_WORD] = input.word
        executeSearch(input.word)
    }

    private fun executeSearch(word: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(isSearching = true, message = null) }
        searchJob = viewModelScope.launch {
            try {
                val searchResult = words.search(word)
                if (searchResult == null || searchResult.total == 0) {
                    mutableState.update {
                        it.copy(
                            result = null,
                            hasSearched = true,
                            isSearching = false,
                        )
                    }
                } else {
                    val groups = searchResult.byLength.map { (len, list) ->
                        WordSearchGroup(length = len, words = list)
                    }
                    val lengthSummary = searchResult.byLength.keys.joinToString(",")
                    val resultUi = WordSearchResultUi(
                        source = searchResult.source,
                        total = searchResult.total,
                        lengthKeysSummary = lengthSummary,
                        groups = groups,
                    )
                    mutableState.update {
                        it.copy(
                            result = resultUi,
                            hasSearched = true,
                            isSearching = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun onWordClicked(word: String) {
        dialogJob?.cancel()
        dialogJob = viewModelScope.launch {
            val available = try {
                definitions.isAvailable()
            } catch (_: Exception) {
                false
            }
            val list = if (available) {
                try {
                    definitions.definitionsFor(word)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            mutableState.update {
                it.copy(
                    dialogWord = word,
                    dialogDefinitions = list,
                    dialogDefinitionsAvailable = available,
                    showDialog = true,
                )
            }
        }
    }

    fun onDismissDialog() {
        dialogJob?.cancel()
        mutableState.update { it.copy(showDialog = false) }
    }

    companion object {
        private const val KEY_INPUT = "word_search.input"
        private const val KEY_TOO_LONG = "word_search.too_long"
        private const val KEY_SEARCHED_WORD = "word_search.searched_word"
    }
}
