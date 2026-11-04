package com.example.sostavslova.ui.levels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
import com.example.sostavslova.ui.game.GameDialog
import com.example.sostavslova.ui.game.GameViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LevelsViewModelTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var progress: ProgressRepository
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val fixture = """[{"index":1,"word":"торт","answers":2,"words":3,"letter":"т","letterCount":1},
        {"index":2,"word":"портал","answers":2,"words":3,"letter":"п","letterCount":1}]"""
    private val first = Level(1, "торт", 2, 3, 'т', 1)
    private val levels = LevelsRepository({ fixture })
    private val words = WordRepository({ "лот\nпар\nпортал\nрот\nтор\nторт\nтот" })
    private val noDefinitions = object : DefinitionsRepository {
        override suspend fun isAvailable() = false
        override suspend fun definitionsFor(word: String) = emptyList<Definition>()
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        directory = File(context.cacheDir, "levels-test-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.preferences_pb") }
        progress = ProgressRepository(store)
    }
    @After fun tearDown() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope.cancel(); scope.coroutineContext.job.join(); directory.deleteRecursively()
    }
    private suspend inline fun <reified T : ViewModel> create(crossinline factory: () -> T): T = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory { initializer { factory() } })[T::class.java]
    }
    private suspend fun model(saved: ProgressRepository = progress, source: LevelsRepository = levels,
        dictionary: WordRepository = words) = create { LevelsViewModel(source, saved, dictionary) }
    private suspend fun LevelsViewModel.await(predicate: (LevelsUiState) -> Boolean): LevelsUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }
    private suspend fun LevelsViewModel.ready() = await { !it.loading && !it.loadError }
    private suspend fun GameViewModel.ready() = withContext(Dispatchers.Default) {
        withTimeout(10_000) { uiState.first { it.canPlay } }
    }
    private suspend fun GameViewModel.answer(word: String) {
        withContext(Dispatchers.Main) { onInputChanged(word); onSubmit() }
        val state = ready()
        assertEquals("Ответ $word", R.string.correct_answer, state.message?.text)
    }
    private suspend fun select(model: LevelsViewModel, word: String) = withContext(Dispatchers.Main) { model.onSelectLevel(word) }

    @Test fun freshProgressUnlocksOnlyFirstAndLockedOrUnknownSelectionsDoNothing() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val original = progress.observeCurrentGame().first()
        val model = model(); val state = model.ready()
        assertEquals(listOf(true, false), state.levels.map { it.unlocked })
        select(model, "портал"); select(model, "нет")
        assertFalse(model.uiState.value.openGame)
        assertEquals(original, progress.observeCurrentGame().first())
    }

    @Test fun onlyCompletionStarUnlocksNextAndStarsReactToRepositoryUpdates() = runTest {
        val model = model(); model.ready()
        progress.saveLevelResult(first, listOf("тор"))
        var state = model.await { it.levels.first().progress.lettersTask }
        assertEquals(LevelProgress(lettersTask = true), state.levels.first().progress)
        assertFalse(state.levels[1].unlocked)
        progress.saveLevelResult(first, listOf("рот"))
        state = model.await { it.levels[1].unlocked }
        assertEquals(LevelProgress(completed = true, lettersTask = true), state.levels.first().progress)
        progress.saveLevelResult(first, listOf("тот"))
        assertEquals(3, model.await { it.levels.first().progress.wordsTask }.levels.first().progress.stars)
        progress.clearCurrentGame()
        val recreated = model(); assertEquals(3, recreated.ready().levels.first().progress.stars)
    }

    @Test fun selectionMergesCurrentAndSavedLevelAnswersAndFiltersThroughCore() = runTest {
        progress.saveLevelResult(first, listOf("тор", "нос"))
        progress.saveGame(words.startGame("торт", listOf("рот", "тор")))
        val model = model(); model.ready(); select(model, "торт")
        model.await { it.openGame && it.startingWord == null }
        assertEquals(CurrentGame("торт", listOf("рот", "тор")), progress.observeCurrentGame().first())
        assertFalse(model.uiState.value.canSelect)
        withContext(Dispatchers.Main) { model.onGameOpened() }
        assertTrue(model.uiState.value.canSelect)
    }

    @Test fun selectionOfAnotherLevelDoesNotImportCurrentGameAnswers() = runTest {
        progress.saveLevelResult(first, listOf("тор"))
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val model = model(); model.ready(); select(model, "торт"); model.await { it.openGame }
        assertEquals(CurrentGame("торт", listOf("тор")), progress.observeCurrentGame().first())
    }

    @Test fun navigationWaitsForWriteAndDoubleTapStartsOnlyOneGame() = runTest {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                calls.incrementAndGet(); started.complete(Unit); gate.await()
                return store.updateData(transform)
            }
        }
        val model = model(ProgressRepository(wrapped)); model.ready()
        withContext(Dispatchers.Main) { model.onSelectLevel("торт"); model.onSelectLevel("торт") }
        withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
        assertFalse(model.uiState.value.openGame); assertFalse(model.uiState.value.canSelect)
        assertNull(progress.observeCurrentGame().first())
        gate.complete(Unit); model.await { it.openGame }
        assertEquals(1, calls.get())
    }

    @Test fun saveFailureKeepsPreviousGameAndAllowsRetry() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val fail = AtomicBoolean(true)
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail.get()) throw IOException("Synthetic save failure")
                return store.updateData(transform)
            }
        }
        val model = model(ProgressRepository(wrapped)); model.ready(); select(model, "торт")
        model.await { it.message == R.string.level_start_error && it.canSelect }
        assertFalse(model.uiState.value.openGame)
        assertEquals("портал", progress.observeCurrentGame().first()?.word)
        fail.set(false); select(model, "торт"); model.await { it.openGame }
        assertEquals("торт", progress.observeCurrentGame().first()?.word)
    }

    @Test fun loadingErrorCanBeRetried() = runTest {
        val calls = AtomicInteger()
        val source = LevelsRepository({ if (calls.incrementAndGet() == 1) throw IOException("Synthetic level failure"); fixture })
        val model = model(source = source); model.await { it.loadError }
        assertFalse(model.uiState.value.canSelect)
        withContext(Dispatchers.Main) { model.load() }
        assertEquals(2, model.ready().levels.size); assertEquals(2, calls.get())
    }

    @Test fun sourceWithoutCombinationsDoesNotOverwriteCurrentGame() = runTest {
        progress.saveGame(words.startGame("портал", listOf("пар")))
        val source = LevelsRepository({ fixture.replace("торт", "ааа") })
        val model = model(source = source); model.ready(); select(model, "ааа")
        model.await { it.message == R.string.no_combinations && it.canSelect }
        assertFalse(model.uiState.value.openGame)
        assertEquals("портал", progress.observeCurrentGame().first()?.word)
    }

    @Test fun realLevelOneUnlocksSecondAfterEightCorrectAnswersAndRestoresOnReentry() = runTest {
        val source = LevelsRepository({ context.assets.open("levels.json").bufferedReader().use { it.readText() } })
        val dictionary = WordRepository({ context.assets.open("words.txt").bufferedReader().use { it.readText() } })
        val model = model(source = source, dictionary = dictionary)
        val initial = model.ready()
        assertEquals(30, initial.levels.size)
        assertEquals(listOf(1), initial.levels.filter { it.unlocked }.map { it.level.index })
        select(model, "рекуперация"); model.await { it.openGame }
        val game = create { GameViewModel(SavedStateHandle(), dictionary, source, progress, noDefinitions) }
        game.ready()
        val answers = listOf("рак", "пар", "пир", "река", "рука", "икра", "цирк", "акция")
        for ((i, word) in answers.withIndex()) {
            game.answer(word)
            if (i == 6) assertFalse(model.uiState.value.levels[1].unlocked)
        }
        val unlocked = model.await { it.levels[1].unlocked }
        assertEquals(listOf(1, 2), unlocked.levels.filter { it.unlocked }.map { it.level.index })
        assertEquals(LevelProgress(completed = true), unlocked.levels.first().progress)
        withContext(Dispatchers.Main) { game.onDialog(GameDialog.Finish); game.onFinish() }
        withContext(Dispatchers.Default) { withTimeout(10_000) { game.uiState.first { it.returnHome } } }
        assertNull(progress.observeCurrentGame().first())
        withContext(Dispatchers.Main) { model.onGameOpened() }
        select(model, "рекуперация"); model.await { it.openGame }
        assertEquals(answers, progress.observeCurrentGame().first()?.found)
    }

    @Test fun realLevel19EarnsCyrillicLetterStarAfterFiveAnswersAndDisplaysSavedStar() = runTest {
        val source = LevelsRepository({ context.assets.open("levels.json").bufferedReader().use { it.readText() } })
        val dictionary = WordRepository({ context.assets.open("words.txt").bufferedReader().use { it.readText() } })
        val model = model(source = source, dictionary = dictionary); model.ready()
        val game = create { GameViewModel(SavedStateHandle(), dictionary, source, progress, noDefinitions, word = "реввоенсовет") }
        val initial = game.ready()
        assertEquals('с', initial.level?.letter); assertEquals(5, initial.level?.letterCount)
        for (answer in listOf("сев", "север", "сено", "совет", "сорт")) game.answer(answer)
        val state = model.await { it.levels[18].progress.lettersTask }
        assertEquals(LevelProgress(lettersTask = true), state.levels[18].progress)
        assertFalse(state.levels[18].unlocked) // Звезда задания не заменяет прохождение уровня 18.
        assertEquals(5, progress.levelAnswers("реввоенсовет").size)
    }
}
