package com.example.sostavslova.ui.game

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
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
        wordRepository: WordRepository = words, levelRepository: LevelsRepository = levels,
        retainedOwner: ViewModelStoreOwner? = null): GameViewModel = withContext(Dispatchers.Main) {
        val owner = retainedOwner ?: object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        if (owner !in owners) owners.add(owner)
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

    /** 30 синтетических уровней, суммарно 100 звёзд; последний имеет порог 4/5. */
    private fun finaleCampaignJson(): String {
        val levels = (1..30).joinToString(",") { number ->
            val suffix = "а" + ('а' + number)
            val source = if (number == 30) "картотека" else "картотека$suffix"
            val count = minOf((number - 1) / 5 + 1, 5)
            val tasks = listOf(
                """{"id":"count$number","type":"T1","n":2}""",
                """{"id":"initial$number","type":"T2","letter":"к","n":1}""",
                """{"id":"ending$number","type":"T5","ending":"${if (number == 30) "от" else suffix}","minLength":3}""",
                """{"id":"theme$number","type":"T6","category":"test$number","n":1,"words":["кот"]}""",
                """{"id":"contains$number","type":"T7","letter":"р","n":1}""",
            ).take(count).joinToString(",")
            """{"number":$number,"word":"$source","stage":1,"requiredStars":${if (number == 30) 4 else 1},"tasks":[$tasks]}"""
        }
        return """{"version":2,"countMinLength":4,"stages":[{"number":1,"firstLevel":1,"lastLevel":30}],"levels":[$levels]}"""
    }

    @Test fun finaleAtFourOfFiveSurvivesRecreationUntilClosedAndNeverRepeatsAtHundred() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { finaleCampaignJson() })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт" })
        val seeded = org.json.JSONObject().put("version", 2).put("highestUnlockedLevel", 30)
        val savedLevels = org.json.JSONObject()
        campaign.levels.dropLast(1).forEach { level ->
            savedLevels.put(level.word, org.json.JSONObject().put("completedTaskIds",
                org.json.JSONArray(level.tasks.map { it.id })))
        }
        seeded.put("levels", savedLevels)
        store.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("campaignProgressV2")] = seeded.toString() }
        suspend fun open(number: String = "30", repository: ProgressRepository = progress) = model(
            SavedStateHandle(mapOf("index" to number)), repository = repository,
            wordRepository = campaignWords, levelRepository = campaignLevels)
        val model = open(); model.ready()
        assertNull(model.uiState.value.finaleSummary)
        model.answer("крот") // T2, T5, T7: ровно 3/5, финала ещё нет.
        assertEquals(3, model.uiState.value.campaignLevel?.completedTaskIds?.size)
        assertNull(model.uiState.value.finaleSummary)
        withContext(Dispatchers.Main) { model.onHint() }
        model.await { !it.hintLoading }
        model.answer("кот") // T6: ровно 4/5.
        val finale = requireNotNull(model.await { it.finaleSummary != null }.finaleSummary)
        assertEquals(99, finale.stars)
        assertEquals(100, finale.maxStars)
        assertEquals(2, finale.foundWords)
        assertEquals(1, finale.shownHintSteps)
        assertFalse(progress.observeCampaignProgress().first().finaleShown)
        withContext(Dispatchers.Main) { model.onGameOpened() }
        assertEquals(finale, model.uiState.value.finaleSummary)
        // Новая ViewModel и новый репозиторий читают только сохранённое событие.
        val restored = open(repository = ProgressRepository(store))
        assertEquals(finale, restored.await { it.finaleSummary != null }.finaleSummary)
        withContext(Dispatchers.Main) { restored.onCloseFinale(false) }
        restored.await { it.canPlay && !it.finaleClosing }
        assertFalse(restored.uiState.value.returnLevels)
        assertTrue(progress.observeCampaignProgress().first().finaleShown)
        val restarted = open(); restarted.ready()
        assertNull(restarted.uiState.value.finaleSummary)
        val previous = open("1"); previous.ready()
        previous.answer("кот")
        assertNull(previous.uiState.value.finaleSummary)
        assertEquals(3, com.example.sostavslova.core.CampaignRules.summary(campaign,
            progress.observeCampaignProgress().first()).foundWords)
        restarted.answer("карта") // T1: последняя звезда всей кампании.
        val summary = com.example.sostavslova.core.CampaignRules.summary(campaign, progress.observeCampaignProgress().first())
        assertEquals(100, summary.stars)
        assertEquals(4, summary.foundWords)
        assertTrue(summary.isMaster)
        assertNull(restarted.uiState.value.finaleSummary)
        assertNull(open().ready().finaleSummary)
    }

    @Test fun finaleLevelsActionAcknowledgesBeforeNavigationAndDoesNotRepeat() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        val index = campaignWords.requireIndex()
        progress.submitCampaignAnswer(campaign, 1, "карта", index)
        progress.submitCampaignAnswer(campaign, 2, "торт", index)
        val model = model(SavedStateHandle(mapOf("index" to "2")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        model.await { it.finaleSummary != null }
        withContext(Dispatchers.Main) { model.onCloseFinale(true) }
        model.await { it.returnLevels }
        assertTrue(progress.observeCampaignProgress().first().finaleShown)
        val reopened = model(SavedStateHandle(mapOf("index" to "1")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        assertNull(reopened.ready().finaleSummary)
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

    @Test fun repeatedOpenDuringLoadAndRetainedOwnerDoNotSearchAgainAndObserveFreshV1() = runTest {
        seed("торт")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val computations = AtomicInteger()
        val countingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                computations.incrementAndGet()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val countedWords = WordRepository({
            reads.incrementAndGet()
            entered.complete(Unit)
            release.await()
            "рот\nтор\nтот\nторт"
        }, computationDispatcher = countingDispatcher)
        val handle = SavedStateHandle(mapOf("index" to "1"))
        val first = model(handle, wordRepository = countedWords)
        val owner = owners.last()
        entered.await()
        withContext(Dispatchers.Main) { repeat(5) { first.onGameOpened() } }
        release.complete(Unit)
        val initial = first.ready()
        val count = computations.get()
        withContext(Dispatchers.Main) {
            first.onInputChanged("черновик")
            repeat(5) { first.onGameOpened() }
        }
        // Смена конфигурации сохраняет NavBackStackEntry и его ViewModelStore.
        val retained = model(handle, wordRepository = countedWords, retainedOwner = owner)
        assertSame(first, retained)
        progress.saveLevelResult(level, listOf("рот", "тор"))
        val updated = first.await { it.game?.foundCount == 2 }
        assertSame(initial.game?.allWords, updated.game?.allWords)
        assertEquals(LevelProgress(completed = true), updated.levelProgress)
        assertEquals(2, updated.statistics.totalFound)
        assertEquals("черновик", updated.input)
        assertEquals(1, reads.get())
        assertEquals(count, computations.get())
    }

    @Test fun lateHintResultCannotOverwriteNewerCampaignAnswerOrSpendOnResume() = runTest {
        val pauseNext = AtomicBoolean(false)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val result = store.updateData(transform)
                if (pauseNext.compareAndSet(true, false)) {
                    committed.complete(Unit)
                    release.await()
                }
                return result
            }
        }
        val campaignLevels = LevelsRepository({ error("No v1 fallback") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaign = checkNotNull(campaignLevels.getCampaign())
        val model = model(repository = ProgressRepository(wrapped), wordRepository = campaignWords,
            levelRepository = campaignLevels)
        val initial = model.ready()
        pauseNext.set(true)
        withContext(Dispatchers.Main) { model.onHint() }
        committed.await()
        progress.submitCampaignAnswer(campaign, 1, "карта", campaignWords.requireIndex())
        val newer = model.await { "карта" in it.game?.found.orEmpty() }
        val snapshot = progress.observeCampaignProgress().first()
        withContext(Dispatchers.Main) {
            model.onInputChanged("новый ввод")
            repeat(5) { model.onGameOpened() }
        }
        release.complete(Unit)
        val state = model.await { !it.hintLoading }
        assertEquals(newer.campaignLevel, state.campaignLevel)
        assertEquals("новый ввод", state.input)
        assertSame(initial.game?.allWords, state.game?.allWords)
        assertEquals(snapshot, progress.observeCampaignProgress().first())
        assertEquals(1, snapshot.levels.getValue("картотека").hints.shownSteps)
    }

    @Test fun delayedLoadFromRemovedRouteCannotChangeNextLevelOrReturn() = runTest {
        seed("портал")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val readFinished = CompletableDeferred<Unit>()
        val delayedWords = WordRepository({
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                readFinished.complete(Unit)
                "рот\nтор\nтот\nторт"
            }
        })
        val first = model(SavedStateHandle(mapOf("index" to "1")), wordRepository = delayedWords)
        val firstOwner = owners.last()
        entered.await()
        withContext(Dispatchers.Main) { firstOwner.viewModelStore.clear() }
        val second = model(SavedStateHandle(mapOf("index" to "2")))
        second.ready()
        withContext(Dispatchers.Main) { second.onInputChanged("пар") }
        val before = second.uiState.value
        release.complete(Unit)
        readFinished.await()
        withContext(Dispatchers.Main) { first.onGameOpened() }
        assertEquals(before, second.uiState.value)
        assertEquals("портал", second.uiState.value.game?.word)
        val returned = model(SavedStateHandle(mapOf("index" to "1")))
        assertEquals(listOf("тор", "рот").toSet(), returned.ready().game?.found?.toSet())
        assertEquals("", returned.uiState.value.input)
    }

    @Test fun answerInFlightWhenNavigatingSavesOnlyItsLevelAndPreservesNewDraft() = runTest {
        seed("портал")
        val pauseNext = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val paused = pauseNext.compareAndSet(true, false)
                if (paused) { entered.complete(Unit); release.await() }
                return store.updateData(transform).also { if (paused) committed.complete(Unit) }
            }
        }
        val first = model(SavedStateHandle(mapOf("index" to "1")), repository = ProgressRepository(wrapped))
        val owner = owners.last()
        first.ready()
        pauseNext.set(true)
        withContext(Dispatchers.Main) { first.onInputChanged("тот"); first.onSubmit() }
        entered.await()
        withContext(Dispatchers.Main) { owner.viewModelStore.clear() }
        val second = model(SavedStateHandle(mapOf("index" to "2")))
        second.ready()
        withContext(Dispatchers.Main) { second.onInputChanged("тот") }
        release.complete(Unit)
        committed.await()
        val returned = model(SavedStateHandle(mapOf("index" to "1")))
        assertTrue("тот" in returned.ready().game?.found.orEmpty())
        assertEquals("тот", second.uiState.value.input)
        assertEquals("портал", second.uiState.value.game?.word)
        assertTrue(second.uiState.value.game?.found.orEmpty().isEmpty())
        assertNull(second.uiState.value.message)
    }

    @Test fun campaignRiddlesKeepAssignedAnswersWithMissingCorruptAndUnusableDefinitions() = runTest {
        val repositories = listOf(noDefinitions,
            object : DefinitionsRepository {
                override suspend fun isAvailable(): Boolean = throw IOException("Broken database")
                override suspend fun definitionsFor(word: String): List<Definition> = error("Must not read")
            },
            object : DefinitionsRepository {
                override suspend fun isAvailable() = true
                override suspend fun definitionsFor(word: String): List<Definition> = throw IOException("Broken table")
            },
            object : DefinitionsRepository {
                override suspend fun isAvailable() = true
                override suspend fun definitionsFor(word: String) = listOf(Definition(word, word, source = "https://example.org/$word"))
            })
        val json = miniCampaignJson.replace("[\"кот\"]", "[\"кот\", \"крот\"]")
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { json })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        for (meanings in repositories) {
            progress.clearAll()
            val model = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels)
            val initial = model.ready()
            val riddles = initial.riddles.getValue("riddle")
            assertEquals(listOf(3, 4), riddles.map { it.length })
            assertTrue(riddles.all { it.text == null && it.firstLetter == "К" && !it.solved })
            val first = model.answer("кот")
            assertEquals(R.string.correct_three_letter_answer, first.message?.text)
            assertEquals(listOf(true, false), first.riddles.getValue("riddle").map { it.solved })
            assertFalse("riddle" in first.campaignLevel?.completedTaskIds.orEmpty())
            assertTrue(first.taskProgress.filter { it.id == "count" || it.id == "initial" }.all { it.current == 0 })
            val second = model.answer("крот")
            assertTrue(second.riddles.getValue("riddle").all { it.solved })
            assertTrue("riddle" in second.campaignLevel?.completedTaskIds.orEmpty())
            assertEquals(setOf("кот", "крот"), second.game?.found?.toSet())
            withContext(Dispatchers.Main) { owners.last().viewModelStore.clear() }
        }
    }

    @Test fun campaignSafeRiddleTextHasNoMetadataAndProgressSurvivesRecreation() = runTest {
        val meanings = object : DefinitionsRepository {
            override suspend fun isAvailable() = true
            override suspend fun definitionsFor(word: String) = listOf(Definition(word,
                "Условное безопасное описание для синтетического теста", source = "https://example.org/$word", origin = "wiktionary"))
        }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels)
        val riddle = model.ready().riddles.getValue("riddle").single()
        assertNotNull(riddle.text)
        assertTrue(riddle.wiktionary)
        assertFalse(riddle.toString().contains("кот"))
        assertFalse(riddle.toString().contains("https"))
        model.answer("кот")
        val restored = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels).ready()
        assertTrue(restored.riddles.getValue("riddle").single().solved)
    }

    @Test fun oneCampaignAnswerWritesOnceAndEmitsEveryNewStarAndUnlockOnlyOnce() = runTest {
        val writes = AtomicInteger()
        val counted = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes.incrementAndGet()
                return store.updateData(transform)
            }
        }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(repository = ProgressRepository(counted), wordRepository = campaignWords, levelRepository = campaignLevels)
        model.ready()
        writes.set(0)
        val answered = model.answer("карта")
        assertEquals(1, writes.get())
        assertEquals(2, answered.campaignLevel?.completedTaskIds?.size)
        assertEquals(R.string.star_received, model.event().text)
        assertEquals(R.string.star_received, model.event().text)
        assertEquals(R.string.game_next_opened, model.event().text)
        assertEquals(2, progress.observeCampaignProgress().first().highestUnlockedLevel)
        model.answer("КАРТА")
        withContext(Dispatchers.Main) { repeat(3) { model.onGameOpened() } }
        assertNull(withTimeoutOrNull(200) { model.events.first() })
    }

    @Test fun firstLevelOfMasterStageExplainsBothRotatingNewTypesOnce() = runTest {
        val json = miniCampaignJson.replace("\"number\": 1, \"firstLevel\"", "\"number\": 6, \"firstLevel\"")
            .replace("\"stage\": 1", "\"stage\": 6")
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { json })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val first = model(wordRepository = campaignWords, levelRepository = campaignLevels)
        val state = first.ready()
        assertTrue(com.example.sostavslova.core.TaskType.T6 in state.explanations)
        assertTrue(com.example.sostavslova.core.TaskType.T7 in state.explanations)
        withContext(Dispatchers.Main) { first.onDismissExplanations() }
        assertTrue(first.uiState.value.explanations.isEmpty())
        val recreated = model(wordRepository = campaignWords, levelRepository = campaignLevels).ready()
        assertTrue(recreated.explanations.isEmpty())
    }

}
