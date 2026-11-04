package com.example.sostavslova.ui.game

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameViewModelTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var progress: ProgressRepository
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val words = WordRepository({ "лот\nпар\nпортал\nрот\nтор\nторт\nтот" })
    private val level = Level(1, "торт", 2, 3, 'т', 2)
    private val levels = LevelsRepository({ """[{"index":1,"word":"торт","answers":2,"words":3,"letter":"т","letterCount":2}]""" })
    private val noDefinitions = object : DefinitionsRepository {
        override suspend fun isAvailable() = false
        override suspend fun definitionsFor(word: String) = emptyList<Definition>()
    }

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "game-test-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.preferences_pb") }
        progress = ProgressRepository(store)
    }

    @After fun tearDown() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope.cancel(); scope.coroutineContext.job.join()
        directory.deleteRecursively()
    }

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle(),
        repository: ProgressRepository = progress, meanings: DefinitionsRepository = noDefinitions,
        source: String? = null, wordRepository: WordRepository = words): GameViewModel = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory { initializer {
            GameViewModel(handle, wordRepository, levels, repository, meanings, Random(42), word = source)
        } })[GameViewModel::class.java]
    }

    private suspend fun GameViewModel.await(predicate: (GameUiState) -> Boolean): GameUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }
    private suspend fun GameViewModel.ready() = await { it.canPlay }
    private suspend fun GameViewModel.answer(raw: String): GameUiState {
        withContext(Dispatchers.Main) { onInputChanged(raw); onSubmit() }
        return await { !it.busy }
    }
    private suspend fun GameViewModel.finish() {
        withContext(Dispatchers.Main) { onDialog(GameDialog.Finish); onFinish() }
        await { it.returnHome }
    }
    private suspend fun GameViewModel.event() = withContext(Dispatchers.Default) {
        withTimeout(10_000) { events.first() }
    }

    @Test fun correctRepeatIncorrectAndLatinLetterFollowCoreAndPersistOnlyCorrectAnswers() = runTest {
        progress.saveGame(words.startGame("портал"))
        val model = model(); model.ready()
        assertEquals(R.string.correct_answer, model.answer("пар").message?.text)
        assertEquals(CurrentGame("портал", listOf("пар")), progress.observeCurrentGame().first())
        assertEquals(R.string.answer_already_found, model.answer("ПАР").message?.text)
        assertEquals("пар", model.uiState.value.message?.word)
        assertEquals(R.string.word_too_short, model.answer("пaр").message?.text)
        assertEquals(R.string.answer_not_found, model.answer("нос").message?.text)
        assertEquals(R.string.answer_same_as_source, model.answer("ПОРТАЛ").message?.text)
        assertEquals(1, model.uiState.value.game?.foundCount)
        assertEquals("", model.uiState.value.input)
    }

    @Test fun emptySubmissionDoesNothingAndInputIsLimitedTo30() = runTest {
        progress.saveGame(words.startGame("портал"))
        val model = model(); model.ready()
        val before = progress.observeCurrentGame().first()
        val state = model.answer("123--")
        assertNull(state.message)
        assertEquals("123--", state.input)
        assertEquals(before, progress.observeCurrentGame().first())
        withContext(Dispatchers.Main) { model.onInputChanged("а".repeat(31)) }
        assertEquals(30, model.uiState.value.input.length)
    }

    @Test fun firstStarsEmitEventsAndFinishedIsAnEventOnlyOnce() = runTest {
        progress.saveGame(words.startGame("торт"))
        val model = model(); model.ready()
        model.answer("рот")
        assertEquals(LevelProgress(), model.uiState.value.levelProgress)
        model.answer("тор")
        assertEquals(LevelProgress(completed = true), model.uiState.value.levelProgress)
        assertEquals(R.string.star_received, model.event().text)
        model.answer("тот")
        assertEquals(LevelProgress(true, true, true), model.uiState.value.levelProgress)
        assertEquals(R.string.star_received, model.event().text)
        assertEquals(R.string.all_words_found, model.event().text)
        assertTrue(checkNotNull(model.uiState.value.game).isFinished)
        model.answer("ТОТ")
        withContext(Dispatchers.Main) { model.onGameOpened() }; model.ready()
        assertNull(withContext(Dispatchers.Default) { withTimeoutOrNull(200) { model.events.first() } })
        val recreated = model(); recreated.ready()
        assertNull(withContext(Dispatchers.Default) { withTimeoutOrNull(200) { recreated.events.first() } })
    }

    @Test fun finishLevelPreservesStarsAndAnswersBeforeClearingCurrentGame() = runTest {
        progress.saveGame(words.startGame("торт"))
        val model = model(); model.ready()
        model.answer("тор"); model.answer("тот")
        model.finish()
        assertNull(progress.observeCurrentGame().first())
        assertEquals(listOf("тор", "тот"), progress.levelAnswers("торт"))
        assertEquals(LevelProgress(true, false, true), progress.observeLevelProgress().first()["торт"])
        val reopened = model(source = "ТОРТ"); reopened.ready()
        assertEquals(listOf("тор", "тот"), reopened.uiState.value.game?.found)
        assertEquals(LevelProgress(true, false, true), reopened.uiState.value.levelProgress)
    }

    @Test fun manuallyEnteredLevelMergesSavedAndCurrentAnswersThroughCoreAndFiltersInvalid() = runTest {
        progress.saveLevelResult(level, listOf("тор", "тот", "нос"))
        progress.saveGame(words.startGame("торт", listOf("рот", "тор")))
        val model = model(); val state = model.ready()
        assertEquals(listOf("рот", "тор", "тот"), state.game?.found)
        assertEquals(level, state.level)
        assertEquals(3, state.game?.total)
        assertEquals(3, state.statistics.totalFound)
        assertEquals(LevelProgress(true, true, true), state.levelProgress)
        assertEquals(state.game?.found, progress.observeCurrentGame().first()?.found)
    }

    @Test fun ordinaryGameFinishClearsAnswersAndCancelKeepsThem() = runTest {
        progress.saveGame(words.startGame("портал"))
        val model = model(); model.ready(); model.answer("пар")
        withContext(Dispatchers.Main) { model.onDialog(GameDialog.Finish); model.onDismissDialog() }
        assertEquals(listOf("пар"), progress.observeCurrentGame().first()?.found)
        model.finish()
        assertNull(progress.observeCurrentGame().first())
        assertTrue(progress.observeLevelProgress().first().isEmpty())
        val newGame = model(source = "портал"); newGame.ready()
        assertEquals(emptyList<String>(), newGame.uiState.value.game?.found)
    }

    @Test fun hintNeverUsesFoundWordAndSuppressesWordAndUrlInDefinitions() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар", "рот", "лот")))
        var requested = ""
        val meanings = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String): List<Definition> {
                requested = word
                return listOf(Definition(word, "Безопасное <толкование>\nВторая строка", source = "https://example.org/$word"),
                    Definition(word, "Ответ — ${word.uppercase()}."), Definition(word, "Текст", example = word))
            }
        }
        val model = model(meanings = meanings); model.ready()
        withContext(Dispatchers.Main) { model.onHint() }
        val hint = checkNotNull(model.await { it.hint != null && !it.hintLoading }.hint)
        assertEquals("тор", requested)
        assertEquals(3, hint.length); assertEquals("Т", hint.firstLetter)
        assertEquals(1, hint.definitions.size)
        assertEquals("", hint.definitions.single().display)
        assertEquals("", hint.definitions.single().source)
        model.answer("тор")
        assertNull(model.uiState.value.hint)
        withContext(Dispatchers.Main) { model.onHint() }
        model.await { !it.hintLoading && it.message?.text == R.string.hint_all_found }
    }

    @Test fun hintFallsBackWhenUnavailableMissingOrLeaking() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар", "рот", "лот")))
        val leaking = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String) = listOf(Definition(word, word))
        }
        for (definitions in listOf(noDefinitions, leaking)) {
            val model = model(meanings = definitions); model.ready()
            withContext(Dispatchers.Main) { model.onHint() }
            assertTrue(checkNotNull(model.await { !it.hintLoading && it.hint != null }.hint).definitions.isEmpty())
        }
    }

    @Test fun definitionDialogLoadsOnlyFoundWordAndWorksWithoutDefinitions() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val model = model(); model.ready()
        withContext(Dispatchers.Main) { model.onDefinition("тор") }
        assertNull(model.uiState.value.dialog)
        withContext(Dispatchers.Main) { model.onDefinition("пар") }
        val state = model.await { it.dialog == GameDialog.Definition && !it.definitionLoading }
        assertFalse(state.definitionsAvailable)
        assertEquals("пар", state.definitionWord)
        withContext(Dispatchers.Main) { model.onDismissDialog() }
        assertNull(model.uiState.value.dialog)
    }

    @Test fun savedStateRestoresDraftAndCurrentGameAndReloadsWhenSourceChanges() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val handle = SavedStateHandle()
        val model = model(handle); model.ready()
        withContext(Dispatchers.Main) { model.onInputChanged("ЧеРнОвИк") }
        val snapshot = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any?>(it) } }
        val restored = model(SavedStateHandle(snapshot)); restored.ready()
        assertEquals("ЧеРнОвИк", restored.uiState.value.input)
        assertEquals(listOf("пар"), restored.uiState.value.game?.found)
        progress.saveGame(words.startGame("торт", listOf("рот")))
        withContext(Dispatchers.Main) { restored.onGameOpened() }
        assertEquals("торт", restored.ready().game?.word)
        assertEquals("", restored.uiState.value.input)
        assertEquals(level, restored.uiState.value.level)
    }

    @Test fun noCurrentGameAndNoCombinationsReturnHomeWithExactMessages() = runTest {
        val noGame = model(); noGame.await { it.returnHome }
        assertEquals(R.string.enter_valid_word, noGame.uiState.value.homeMessage)
        val empty = model(source = "ааа"); empty.await { it.returnHome }
        assertEquals(R.string.no_combinations, empty.uiState.value.homeMessage)
        assertNull(progress.observeCurrentGame().first())
    }

    @Test fun saveFailureKeepsAnswerRetryableAndDoesNotFinishOrDoubleWrite() = runTest {
        var fail = false
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail) { writes++; gate.await(); throw IOException("Synthetic write failure") }
                return store.updateData(transform)
            }
        }
        progress.saveGame(words.startGame("портал"))
        val model = model(repository = ProgressRepository(wrapped)); model.ready()
        fail = true
        withContext(Dispatchers.Main) { model.onInputChanged("пар"); model.onSubmit(); model.onSubmit(); model.onFinish() }
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (writes == 0) delay(10) } }
        assertTrue(model.uiState.value.busy)
        gate.complete(Unit)
        model.await { !it.busy }
        assertEquals(1, writes)
        assertEquals("пар", model.uiState.value.input)
        assertEquals(0, model.uiState.value.game?.foundCount)
        assertEquals(R.string.game_save_error, model.uiState.value.message?.text)
        assertFalse(model.uiState.value.returnHome)
        fail = false
        model.answer("пар")
        assertEquals(listOf("пар"), progress.observeCurrentGame().first()?.found)
    }
}
