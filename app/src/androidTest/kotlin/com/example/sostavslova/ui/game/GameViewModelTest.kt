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
    private var route = "1"
    private val levels = LevelsRepository({ """[{"index":1,"word":"торт","answers":2,"words":3,"letter":"т","letterCount":2},{"index":2,"word":"портал","answers":2,"words":3,"letter":"п","letterCount":1}]""" })
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

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle(mapOf("index" to route)),
        repository: ProgressRepository = progress, meanings: DefinitionsRepository = noDefinitions,
        wordRepository: WordRepository = words, levelRepository: LevelsRepository = levels): GameViewModel = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory { initializer {
            GameViewModel(handle, wordRepository, levelRepository, repository, meanings, Random(42))
        } })[GameViewModel::class.java]
    }

    private suspend fun GameViewModel.await(predicate: (GameUiState) -> Boolean): GameUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }
    private suspend fun GameViewModel.ready() = await { it.canPlay }
    private suspend fun GameViewModel.answer(raw: String): GameUiState {
        withContext(Dispatchers.Main) { onInputChanged(raw); onSubmit() }
        return await { !it.busy }
    }
    private suspend fun seed(source: String, found: List<String> = emptyList()) {
        progress.migrateLegacyLevels(levels.getLevels())
        route = if (source == "торт") "1" else "2"
        if (route == "2") progress.saveLevelResult(level, listOf("тор", "рот"))
        progress.saveLevelResult(levels.getLevels().first { it.word == source }, found)
    }
    private suspend fun saved() = progress.levelAnswers(if (route == "1") "торт" else "портал")
    private suspend fun GameViewModel.event() = withContext(Dispatchers.Default) {
        withTimeout(10_000) { events.first() }
    }

    @Test fun correctRepeatIncorrectAndLatinLetterFollowCoreAndPersistOnlyCorrectAnswers() = runTest {
        seed("портал")
        val model = model(); model.ready()
        assertEquals(R.string.correct_answer, model.answer("пар").message?.text)
        assertEquals(listOf("пар"), saved())
        assertEquals(R.string.answer_already_found, model.answer("ПАР").message?.text)
        assertEquals("пар", model.uiState.value.message?.word)
        assertEquals(R.string.word_too_short, model.answer("пaр").message?.text)
        assertEquals(R.string.answer_not_found, model.answer("нос").message?.text)
        assertEquals(R.string.answer_same_as_source, model.answer("ПОРТАЛ").message?.text)
        assertEquals(1, model.uiState.value.game?.foundCount)
        assertEquals("", model.uiState.value.input)
    }

    @Test fun emptySubmissionDoesNothingAndInputIsLimitedTo30() = runTest {
        seed("портал")
        val model = model(); model.ready()
        val before = saved()
        val state = model.answer("123--")
        assertNull(state.message)
        assertEquals("123--", state.input)
        assertEquals(before, saved())
        withContext(Dispatchers.Main) { model.onInputChanged("а".repeat(31)) }
        assertEquals(30, model.uiState.value.input.length)
    }

    @Test fun firstStarsEmitEventsAndFinishedIsAnEventOnlyOnce() = runTest {
        seed("торт")
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

    @Test fun levelRestoresSavedAnswersThroughCoreAndFiltersInvalid() = runTest {
        progress.saveLevelResult(level, listOf("тор", "тот", "нос"))
        seed("торт", listOf("рот", "тор"))
        val model = model(); val state = model.ready()
        assertEquals(listOf("тор", "тот", "рот"), state.game?.found)
        assertEquals(level, state.level)
        assertEquals(3, state.game?.total)
        assertEquals(3, state.statistics.totalFound)
        assertEquals(LevelProgress(true, true, true), state.levelProgress)
        assertEquals(state.game?.found, saved().filterNot { it == "нос" })
    }

    @Test fun hintNeverUsesFoundWordAndSuppressesWordAndUrlInDefinitions() = runTest {
        seed("портал", listOf("пар", "рот", "лот"))
        var requested = ""
        val meanings = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String): List<Definition> {
                requested = word
                return listOf(Definition(word, "Безопасное толкование для условного примера", source = "https://example.org/$word"),
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
        assertEquals("", hint.definitions.single().notes)
        assertEquals("", hint.definitions.single().example)
        model.answer("тор")
        assertNull(model.uiState.value.hint)
        withContext(Dispatchers.Main) { model.onHint() }
        model.await { !it.hintLoading && it.message?.text == R.string.hint_all_found }
    }

    @Test fun hintMasksRootsAndFormsInsteadOfDiscardingUsefulDefinition() = runTest {
        seed("портал", listOf("пар", "рот", "лот"))
        val meanings = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String) = listOf(Definition(word,
                "${word.uppercase()} и ${word}овый предмет служат условным примером.",
                notes = word, example = word, source = "https://example.org/$word", forms = listOf(word), origin = "wiktionary"))
        }
        val model = model(meanings = meanings); model.ready()
        withContext(Dispatchers.Main) { model.onHint() }
        val hint = checkNotNull(model.await { it.hint != null && !it.hintLoading }.hint)
        val definition = hint.definitions.single()
        assertEquals("*** и *** предмет служат условным примером.", definition.text)
        assertEquals("", definition.display)
        assertEquals("", definition.source)
        assertEquals("", definition.example)
        assertTrue(definition.forms.isEmpty())
        assertTrue(definition.related.isEmpty())
    }

    @Test fun hintFallsBackWhenUnavailableMissingOrLeaking() = runTest {
        seed("портал", listOf("пар", "рот", "лот"))
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

    @Test fun hintTriesAnotherRemainingWordWhenFirstMeaningIsUnusable() = runTest {
        seed("портал", listOf("пар", "лот"))
        val requested = mutableListOf<String>()
        val meanings = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String): List<Definition> {
                requested.add(word)
                return listOf(Definition(word, if (requested.size == 1) word else "Синтетический предмет служит условным примером."))
            }
        }
        val model = model(meanings = meanings); model.ready()
        withContext(Dispatchers.Main) { model.onHint() }
        val hint = checkNotNull(model.await { it.hint != null && !it.hintLoading }.hint)
        assertEquals(setOf("рот", "тор"), requested.toSet())
        assertEquals(2, requested.size)
        assertEquals(1, hint.definitions.size)
    }

    @Test fun definitionDialogLoadsOnlyFoundWordAndWorksWithoutDefinitions() = runTest {
        seed("портал", listOf("пар"))
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
        seed("портал")
        val model = model(repository = ProgressRepository(wrapped)); model.ready()
        fail = true
        withContext(Dispatchers.Main) { model.onInputChanged("пар"); model.onSubmit(); model.onSubmit() }
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (writes == 0) delay(10) } }
        assertTrue(model.uiState.value.busy)
        gate.complete(Unit)
        model.await { !it.busy }
        assertEquals(1, writes)
        assertEquals("пар", model.uiState.value.input)
        assertEquals(0, model.uiState.value.game?.foundCount)
        assertEquals(R.string.game_save_error, model.uiState.value.message?.text)
        assertFalse(model.uiState.value.returnLevels)
        fail = false
        model.answer("пар")
        assertEquals(listOf("пар"), saved())
    }
    @Test fun invalidAndClosedRouteReturnToMapWithoutWritingGame() = runTest {
        for (index in listOf("bad", "0", "-1", "3", "999999999999")) {
            val model = model(SavedStateHandle(mapOf("index" to index)))
            assertEquals(R.string.level_invalid, model.await { it.returnLevels }.levelsMessage)
        }
        val closed = model(SavedStateHandle(mapOf("index" to "2")))
        assertEquals(R.string.level_closed, closed.await { it.returnLevels }.levelsMessage)
        assertTrue(progress.levelAnswers("портал").isEmpty())
        assertNull(store.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("currentGame")])
    }

    @Test fun routeAndDraftRestoreAfterViewModelRecreation() = runTest {
        seed("портал", listOf("пар"))
        val handle = SavedStateHandle(mapOf("index" to "2"))
        val model = model(handle); model.ready()
        withContext(Dispatchers.Main) { model.onInputChanged("ЧеРнОвИк") }
        val snapshot = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any?>(it) } }
        val restored = model(SavedStateHandle(snapshot)); val state = restored.ready()
        assertEquals("портал", state.game?.word)
        assertEquals(listOf("пар"), state.game?.found)
        assertEquals("ЧеРнОвИк", state.input)
    }

    @Test fun v2RouteRestoresSnapshotAndSavesEachAnswerAndHintThroughSave01() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1 fallback") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val handle = SavedStateHandle(mapOf("index" to "1"))
        val model = model(handle, wordRepository = campaignWords, levelRepository = campaignLevels)
        assertEquals("картотека", model.ready().game?.word)
        assertEquals(R.string.correct_answer, model.answer("карта").message?.text)
        val snapshot = progress.observeCampaignProgress().first()
        assertEquals(listOf("карта"), snapshot.levels.getValue("картотека").found)
        assertEquals(setOf("initial", "long"), snapshot.levels.getValue("картотека").completedTaskIds)
        assertEquals(2, snapshot.highestUnlockedLevel)
        withContext(Dispatchers.Main) { model.onHint() }
        model.await { !it.hintLoading && it.campaignHint != null }
        val hinted = progress.observeCampaignProgress().first()
        assertEquals(1, hinted.levels.getValue("картотека").hints.shownSteps)
        val recreated = model(SavedStateHandle(mapOf("index" to "1")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        val state = recreated.ready()
        assertEquals(listOf("карта"), state.game?.found)
        assertEquals(model.uiState.value.campaignHint, state.campaignHint)
        assertEquals(hinted.levels, progress.observeCampaignProgress().first().levels)
        val second = model(SavedStateHandle(mapOf("index" to "2")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        assertEquals("трактор", second.ready().game?.word)
        assertNull(store.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("currentGame")])
    }

    @Test fun v2ClosedRouteIsRejectedAndCorruptCampaignNeverFallsBack() = runTest {
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaignLevels = LevelsRepository({ error("No v1 fallback") }, readCampaign = { miniCampaignJson })
        val closed = model(SavedStateHandle(mapOf("index" to "2")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        assertEquals(R.string.level_closed, closed.await { it.returnLevels }.levelsMessage)
        val corrupt = model(SavedStateHandle(mapOf("index" to "1")),
            levelRepository = LevelsRepository({ error("No v1 fallback") }, readCampaign = { "broken" }))
        assertTrue(corrupt.await { it.loadError }.loadError)
        assertNull(corrupt.uiState.value.game)
    }

}
