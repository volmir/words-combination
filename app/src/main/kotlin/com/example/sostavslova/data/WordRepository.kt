package com.example.sostavslova.data

import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.SearchResult
import com.example.sostavslova.core.WordIndex
import com.example.sostavslova.core.WordInput
import com.example.sostavslova.core.WordSearch
import com.example.sostavslova.core.parseWordInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val wordCount: Int) : LoadState
    data class Error(val cause: Throwable) : LoadState
}

/** Единственный экземпляр в AppContainer; публичные операции безопасны для главного потока. */
class WordRepository(
    private val readWords: suspend () -> String,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val loadMutex = Mutex()
    private var index: WordIndex? = null
    private val mutableState = MutableStateFlow<LoadState>(LoadState.Loading)
    val state: StateFlow<LoadState> = mutableState.asStateFlow()

    suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (index != null) return
            mutableState.value = LoadState.Loading
            try {
                val text = withContext(ioDispatcher) { readWords() }
                val loaded = withContext(computationDispatcher) { WordIndex.fromText(text) }
                index = loaded
                mutableState.value = LoadState.Ready(loaded.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = LoadState.Error(error)
            }
        }
    }

    suspend fun requireIndex(): WordIndex {
        ensureLoaded()
        return checkNotNull(index) { "Словарь не загружен: ${state.value}" }
    }

    suspend fun search(raw: String): SearchResult? {
        val loaded = requireIndex()
        return withContext(computationDispatcher) { WordSearch(loaded).search(raw) }
    }

    suspend fun startGame(word: String, restoredFound: List<String> = emptyList()): GameState {
        val loaded = requireIndex()
        return withContext(computationDispatcher) {
            val input = parseWordInput(word)
            require(input is WordInput.Ok) { "Некорректное исходное слово" }
            GameState.start(input.word, loaded, restoredFound)
        }
    }

}
