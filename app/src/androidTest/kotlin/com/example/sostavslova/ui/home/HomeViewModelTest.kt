package com.example.sostavslova.ui.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.data.CurrentGame
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.ui.components.AlertType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    private lateinit var directory: File
    private lateinit var storeScope: CoroutineScope
    private lateinit var progress: ProgressRepository
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val letters = Alphabet.LETTERS.take(11)
    private val fixture = buildSet {
        addAll(listOf("портал", "пар", "рот", "тор", "лот"))
        repeat(8) { add(letters.drop(it) + letters.take(it)) }
        for (i in letters.indices) for (j in i + 1 until letters.length) for (k in j + 1 until letters.length) {
            add("${letters[i]}${letters[j]}${letters[k]}")
        }
    }.sorted().joinToString("\n")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "home-test-${UUID.randomUUID()}").apply { mkdirs() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        progress = ProgressRepository(PreferenceDataStoreFactory.create(scope = storeScope) {
            File(directory, "progress.preferences_pb")
        })
    }

    @After
    fun tearDown() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        storeScope.cancel()
        storeScope.coroutineContext.job.join()
        directory.deleteRecursively()
    }

    private fun repository(read: suspend () -> String = { fixture }) =
        WordRepository(readWords = read, random = Random(42))

    private suspend fun model(
        words: WordRepository = repository(),
        handle: SavedStateHandle = SavedStateHandle(),
        saved: ProgressRepository = progress,
    ): HomeViewModel = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory {
            initializer { HomeViewModel(handle, words, saved) }
        })[HomeViewModel::class.java]
    }

    private suspend fun HomeViewModel.await(predicate: (HomeUiState) -> Boolean): HomeUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }

    private suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }

    @Test
    fun validatesEmptyShortAnd31CharactersUsingCoreWithoutCreatingGame() = runTest {
        val model = model()
        model.await { it.dictionaryReady }
        for ((raw, message) in listOf("" to R.string.enter_valid_word, "123--" to R.string.enter_valid_word,
            "до" to R.string.word_too_short, "ёж" to R.string.word_too_short, "а".repeat(31) to R.string.word_too_long)) {
            onMain { model.onInputChanged(raw); model.onStart() }
            assertEquals(message, model.uiState.value.message?.text)
            assertFalse(model.uiState.value.openGame)
            assertNull(progress.observeCurrentGame().first())
            assertTrue(model.uiState.value.input.length <= Alphabet.MAX_WORD_LENGTH)
        }
    }

    @Test
    fun upperAndLowerCaseStartSameCanonicalGameAndPreserveItsAnswers() = runTest {
        val words = repository()
        val model = model(words)
        model.await { it.dictionaryReady }
        onMain { model.onInputChanged("ПОРТАЛ"); model.onStart() }
        model.await { it.openGame }
        assertEquals(CurrentGame("портал", emptyList()), progress.observeCurrentGame().first())
        onMain { model.onGameOpened() }
        progress.saveGame(words.startGame("портал", listOf("пар")))
        model.await { it.currentGame?.foundCount == 1 }
        onMain { model.onInputChanged("портал"); model.onStart() }
        model.await { it.openGame }
        assertEquals(CurrentGame("портал", listOf("пар")), progress.observeCurrentGame().first())
    }

    @Test
    fun noCombinationsNeitherCreatesNorOverwritesCurrentGame() = runTest {
        val words = repository()
        val model = model(words)
        model.await { it.dictionaryReady }
        onMain { model.onInputChanged("ааа"); model.onStart() }
        val warning = model.await { it.message?.text == R.string.no_combinations && !it.isStarting }
        assertEquals(AlertType.Warning, warning.message?.type)
        assertFalse(warning.openGame)
        assertNull(progress.observeCurrentGame().first())
        val previous = words.startGame("портал", listOf("пар"))
        progress.saveGame(previous)
        model.await { it.currentGame == previous }
        onMain { model.onStart() }
        model.await { it.message?.text == R.string.no_combinations && !it.isStarting }
        assertEquals(CurrentGame("портал", listOf("пар")), progress.observeCurrentGame().first())
        assertEquals(previous, model.uiState.value.currentGame)
        assertFalse(model.uiState.value.openGame)
    }

    @Test
    fun dictionaryLoadingDisablesStartButRetainsTypedInput() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = model(repository { gate.await(); fixture })
        assertTrue(model.uiState.value.dictionaryLoading)
        onMain { model.onInputChanged("портал"); model.onStart() }
        assertFalse(model.uiState.value.canStart)
        assertFalse(model.uiState.value.openGame)
        assertNull(progress.observeCurrentGame().first())
        gate.complete(Unit)
        model.await { it.canStart }
        assertEquals("портал", model.uiState.value.input)
        onMain { model.onStart() }
        model.await { it.openGame }
    }

    @Test
    fun savedStateRestoresInputAndPendingLengthError() = runTest {
        val handle = SavedStateHandle()
        val first = model(handle = handle)
        first.await { it.dictionaryReady }
        onMain { first.onInputChanged("а".repeat(31)) }
        val snapshot = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any?>(it) } }
        val restored = model(handle = SavedStateHandle(snapshot))
        restored.await { it.dictionaryReady }
        assertEquals("а".repeat(30), restored.uiState.value.input)
        onMain { restored.onStart() }
        assertEquals(R.string.word_too_long, restored.uiState.value.message?.text)
        assertFalse(restored.uiState.value.openGame)
        onMain { restored.onInputChanged(" ПортАл "); restored.onStart() }
        restored.await { it.openGame }
        assertEquals("портал", progress.observeCurrentGame().first()?.word)
    }

    @Test
    fun homeOpenAndRefreshChooseFourDistinctPlayableSourceWords() = runTest {
        val words = repository()
        val model = model(words)
        onMain { model.onHomeOpened() }
        val first = model.await { it.randomWords.size == 4 && !it.randomWordsLoading }.randomWords
        assertEquals(4, first.toSet().size)
        for (word in first) {
            assertTrue(word.length in 11..12)
            assertTrue(checkNotNull(words.search(word)).total >= 20)
        }
        onMain { model.refreshRandomWords() }
        val second = model.await { !it.randomWordsLoading }.randomWords
        assertEquals(4, second.toSet().size)
        assertNotEquals(first, second)
        onMain { model.onHomeOpened() }
        val third = model.await { !it.randomWordsLoading }.randomWords
        assertNotEquals(second, third)
        onMain { model.onRandomWord(third.first()) }
        model.await { it.openGame }
        assertEquals(third.first(), progress.observeCurrentGame().first()?.word)
    }

    @Test
    fun continueUsesCountsFromCoreAndDoesNotResetSavedGame() = runTest {
        val words = repository()
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val before = progress.observeCurrentGame().first()
        val model = model(words)
        val state = model.await { it.currentGame != null }
        assertEquals(1, state.currentGame?.foundCount)
        assertEquals(words.startGame("портал").total, state.currentGame?.total)
        onMain { model.onContinue() }
        assertTrue(model.uiState.value.openGame)
        assertEquals(before, progress.observeCurrentGame().first())
    }

    @Test
    fun navigationWaitsForPersistenceAndDoubleSubmitSavesOnce() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val stored = MutableStateFlow(emptyPreferences())
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = stored
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                calls.incrementAndGet()
                writeStarted.complete(Unit)
                allowWrite.await()
                return transform(stored.value).also { stored.value = it }
            }
        }
        val model = model(saved = ProgressRepository(dataStore))
        model.await { it.canStart }
        onMain { model.onInputChanged("портал"); model.onStart(); model.onStart() }
        withContext(Dispatchers.Default) { withTimeout(10_000) { writeStarted.await() } }
        assertFalse(model.uiState.value.openGame)
        assertTrue(model.uiState.value.isStarting)
        allowWrite.complete(Unit)
        model.await { it.openGame }
        assertEquals(1, calls.get())
    }

    @Test
    fun failedDictionaryCanBeRetriedWithoutLosingInput() = runTest {
        val calls = AtomicInteger()
        val model = model(repository {
            if (calls.incrementAndGet() == 1) throw IOException("Synthetic missing dictionary")
            fixture
        })
        model.await { it.dictionaryError }
        onMain { model.onInputChanged("портал"); model.onHomeOpened() }
        model.await { it.canStart && !it.randomWordsLoading }
        assertEquals(2, calls.get())
        assertEquals("портал", model.uiState.value.input)
    }

    @Test
    fun failedSaveStaysOnHomeAndAllowsRetry() = runTest {
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Synthetic write failure")
        }
        val model = model(saved = ProgressRepository(dataStore))
        model.await { it.canStart }
        onMain { model.onInputChanged("портал"); model.onStart() }
        model.await { it.message?.text == R.string.home_start_error && !it.isStarting }
        assertFalse(model.uiState.value.openGame)
        assertTrue(model.uiState.value.canStart)
    }
}
