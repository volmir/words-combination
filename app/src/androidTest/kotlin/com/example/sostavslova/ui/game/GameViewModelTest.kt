package com.example.sostavslova.ui.game

import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.AnswerResult
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
    private val noDefinitions = RiddlesRepository { "Синтетическое существо для проверки подсказок." }

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "game-test-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.json") }
        progress = ProgressRepository(store)
    }

    @After fun tearDown() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope.cancel(); scope.coroutineContext.job.join()
        directory.deleteRecursively()
    }

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle(mapOf("index" to route)),
        repository: ProgressRepository = progress, meanings: RiddlesRepository = noDefinitions,
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

    @Test fun nextLevelUsesCommittedUnlockAllowsMoreStarsAndWaitsForSaveBarrier() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(wordRepository = campaignWords, levelRepository = campaignLevels)
        assertNull(model.ready().nextLevelNumber)
        withContext(Dispatchers.Main) { model.onNextLevel() }
        assertNull(model.uiState.value.nextLevelNavigation)
        val opened = model.answer("карта")
        assertEquals(2, opened.nextLevelNumber)
        assertEquals(2, opened.campaignLevel?.completedTaskIds?.size)
        assertTrue(requireNotNull(opened.campaignLevel).completedTaskIds.size < requireNotNull(opened.campaignLevel).level.maxStars)
        val extra = model.answer("крот")
        assertEquals(3, extra.campaignLevel?.completedTaskIds?.size)
        val saved = progress.observeCampaignProgress().first()
        val release = CompletableDeferred<Unit>()
        val pending = scope.launch { release.await() }
        progress.trackOperation(pending)
        try {
            withContext(Dispatchers.Main) {
                model.onInputChanged("Черновик")
                repeat(4) { model.onNextLevel() }
            }
            assertTrue(model.uiState.value.navigatingNext)
            assertFalse(model.uiState.value.canPlay)
            assertNull(model.uiState.value.nextLevelNavigation)
            release.complete(Unit)
            val navigating = model.await { it.nextLevelNavigation != null }
            assertEquals(2, navigating.nextLevelNavigation)
            assertEquals("", navigating.input)
            assertEquals(saved, progress.observeCampaignProgress().first())
            val returned = model(wordRepository = campaignWords, levelRepository = campaignLevels).ready()
            assertEquals(2, returned.nextLevelNumber)
            assertEquals(listOf("карта", "крот"), returned.game?.found)
            assertEquals(extra.campaignLevel?.completedTaskIds, returned.campaignLevel?.completedTaskIds)
            val last = model(SavedStateHandle(mapOf("index" to "2")),
                wordRepository = campaignWords, levelRepository = campaignLevels).ready()
            assertNull(last.nextLevelNumber)
        } finally { release.complete(Unit) }
    }

    @Test fun nextLevelOfferWaitsForDurableUnlockAndIgnoresTransitionDuringSubmission() = runTest {
        val pause = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (pause.get()) { entered.complete(Unit); release.await() }
                return store.updateData(transform)
            }
        }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(repository = ProgressRepository(wrapped), wordRepository = campaignWords,
            levelRepository = campaignLevels)
        model.ready()
        pause.set(true)
        try {
            withContext(Dispatchers.Main) { model.onInputChanged("карта"); model.onSubmit(); model.onNextLevel() }
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            assertTrue(model.uiState.value.busy)
            assertNull(model.uiState.value.nextLevelNumber)
            assertNull(model.uiState.value.nextLevelNavigation)
            assertEquals(1, progress.observeCampaignProgress().first().highestUnlockedLevel)
            release.complete(Unit)
            val committed = model.await { !it.busy && it.nextLevelNumber == 2 }
            assertEquals(listOf("карта"), committed.game?.found)
            assertEquals(2, progress.observeCampaignProgress().first().highestUnlockedLevel)
            assertTrue(committed.canPlay)
        } finally { release.complete(Unit) }
    }

    @Test fun nextLevelSaveFailureKeepsDraftAndProgressAndCanBeRetried() = runTest {
        val fail = AtomicBoolean(false)
        val wrapped = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail.get()) throw IOException("Synthetic exit barrier failure")
                return store.updateData(transform)
            }
        }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(repository = ProgressRepository(wrapped), wordRepository = campaignWords,
            levelRepository = campaignLevels)
        model.ready(); model.answer("карта")
        val saved = progress.observeCampaignProgress().first()
        fail.set(true)
        withContext(Dispatchers.Main) { model.onInputChanged("Черновик"); model.onNextLevel() }
        val rejected = model.await { !it.navigatingNext && it.message?.text == R.string.game_save_error }
        assertNull(rejected.nextLevelNavigation)
        assertEquals("Черновик", rejected.input)
        assertEquals(saved, progress.observeCampaignProgress().first())
        fail.set(false)
        withContext(Dispatchers.Main) { model.onNextLevel() }
        assertEquals(2, model.await { it.nextLevelNavigation != null }.nextLevelNavigation)
        assertEquals(saved, progress.observeCampaignProgress().first())
    }

    @Test fun legacyNextLevelUsesExistingAccessRulesAndLastHasNoNextLevel() = runTest {
        seed("торт")
        val model = model()
        assertNull(model.ready().nextLevelNumber)
        model.answer("рот")
        assertNull(model.uiState.value.nextLevelNumber)
        model.answer("тор")
        assertEquals(2, model.await { it.nextLevelNumber != null }.nextLevelNumber)
        // Новые ответы сохраняются и после появления предложения перейти.
        model.answer("тот")
        assertEquals(3, model.uiState.value.game?.foundCount)
        withContext(Dispatchers.Main) { model.onNextLevel() }
        assertEquals(2, model.await { it.nextLevelNavigation != null }.nextLevelNavigation)
        val last = model(SavedStateHandle(mapOf("index" to "2"))).ready()
        assertNull(last.nextLevelNumber)
        assertEquals(listOf("рот", "тор", "тот"), progress.levelAnswers("торт"))
    }

    @Test fun campaignMissingLettersReachMessageAndRepeatedSubmitDoesNotLeaveBusy() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exactWords = WordRepository({ context.assets.open("words.txt").bufferedReader().use { it.readText() } })
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = {
            """{"version":2,"countMinLength":4,
                "stages":[{"number":1,"firstLevel":1,"lastLevel":1}],
                "levels":[{"number":1,"word":"вегетарианец","stage":1,"requiredStars":1,
                "tasks":[{"id":"count","type":"T1","n":1}]}]}"""
        })
        val model = model(wordRepository = exactWords, levelRepository = campaignLevels)
        val initial = model.ready()
        repeat(2) {
            val state = model.answer("бык")
            assertEquals(R.string.answer_letters_missing, state.message?.text)
            assertEquals("бык", state.message?.word)
            assertEquals(com.example.sostavslova.core.MissingLetter('б', 0, 1), state.message?.missingLetter)
            assertFalse(state.busy)
            assertTrue(state.canPlay)
            assertEquals(initial.game, state.game)
            assertEquals(initial.campaignLevel, state.campaignLevel)
        }
        assertEquals(R.string.answer_unknown, model.answer("ыыыыы").message?.text)
        assertEquals(R.string.word_too_short, model.answer("ве").message?.text)
        assertEquals(R.string.answer_same_as_source, model.answer("вегетарианец").message?.text)
        assertEquals(R.string.correct_three_letter_answer, model.answer("тир").message?.text)
        assertEquals(R.string.answer_already_found, model.answer("тир").message?.text)
        assertEquals(listOf("тир"), model.uiState.value.game?.found)
        assertEquals(0, model.uiState.value.campaignLevel?.completedTaskIds?.size)
        assertFalse(model.uiState.value.busy)
    }

    @Test fun correctRepeatIncorrectAndLatinLetterFollowCoreAndPersistOnlyCorrectAnswers() = runTest {
        seed("портал")
        val model = model(); model.ready()
        assertEquals(R.string.correct_answer, model.answer("пар").message?.text)
        assertEquals(listOf("пар"), saved())
        assertEquals(R.string.answer_already_found, model.answer("ПАР").message?.text)
        assertEquals("пар", model.uiState.value.message?.word)
        assertEquals(R.string.word_too_short, model.answer("пaр").message?.text)
        assertEquals(R.string.answer_unknown, model.answer("нос").message?.text)
        assertEquals(R.string.answer_same_as_source, model.answer("ПОРТАЛ").message?.text)
        assertEquals(1, model.uiState.value.game?.foundCount)
        // Последний ответ — отказ, поэтому набранное слово остаётся в поле.
        assertEquals("ПОРТАЛ", model.uiState.value.input)
    }

    @Test fun onlyCorrectAndAlreadyFoundClearInput() {
        val expected = mapOf(
            AnswerResult.CORRECT to true,
            AnswerResult.ALREADY_FOUND to true,
            AnswerResult.NOT_FOUND to false,
            AnswerResult.NOT_COUNTED to false,
            AnswerResult.LETTERS_MISSING to false,
            AnswerResult.TOO_SHORT to false,
            AnswerResult.SAME_AS_SOURCE to false,
            AnswerResult.EMPTY to false,
        )
        // Новое значение AnswerResult не должно молча стирать ввод.
        assertEquals(AnswerResult.entries.toSet(), expected.keys)
        expected.forEach { (result, clears) ->
            assertEquals("Очистка ввода после $result", clears, submissionClearsInput(result))
        }
    }

    @Test fun refusalsKeepTypedWordWhileCorrectAndRepeatedAnswerClearIt() = runTest {
        seed("торт")
        val model = model(); val initial = model.ready()
        // Отказ сохраняет текст: опечатку можно исправить без повторного набора.
        assertEquals(R.string.word_too_short, model.answer("пaр").message?.text)
        assertEquals("пaр", model.uiState.value.input)
        assertEquals(R.string.answer_unknown, model.answer("нос").message?.text)
        assertEquals("нос", model.uiState.value.input)
        assertEquals(R.string.answer_letters_missing, model.answer("пар").message?.text)
        assertEquals("пар", model.uiState.value.input)
        assertEquals(R.string.answer_same_as_source, model.answer("торт").message?.text)
        assertEquals("торт", model.uiState.value.input)
        assertEquals(initial.game, model.uiState.value.game)

        assertEquals(R.string.correct_answer, model.answer("рот").message?.text)
        assertEquals("", model.uiState.value.input)
        assertEquals(R.string.answer_already_found, model.answer("рот").message?.text)
        assertEquals("", model.uiState.value.input)
    }

    @Test fun emptyOrNonRussianSubmissionExplainsItselfAndLongInputIsKeptWhole() = runTest {
        seed("портал")
        val handle = SavedStateHandle(mapOf("index" to route))
        val model = model(handle); model.ready()
        val before = saved()
        val state = model.answer("123--")
        // Нормализация выбрасывает латиницу/цифры; молчаливый no-op выглядел как
        // сломанная кнопка. Пользователь должен увидеть, что нужны русские буквы.
        assertEquals(R.string.answer_empty, state.message?.text)
        assertEquals("123--", state.input)
        assertEquals(before, saved())
        withContext(Dispatchers.Main) { model.onInputChanged("аб") }
        assertEquals("аб", model.uiState.value.input)
        val tooShort = model.answer("аб")
        assertEquals(R.string.word_too_short, tooShort.message?.text)

        val long = "а".repeat(31)
        withContext(Dispatchers.Main) { model.onInputChanged(long) }
        assertEquals(long, model.uiState.value.input)
        assertFalse(handle.contains("game.input"))
        val snapshot = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any?>(it) } }
        val restored = model(SavedStateHandle(snapshot))
        assertEquals("", restored.ready().input)
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

    @Test fun legacyLetterHintNeverReadsRiddlesOrChangesSavedAnswers() = runTest {
        seed("портал", listOf("пар", "лот"))
        val model = model(meanings = RiddlesRepository { error("Legacy hint must not read riddles") })
        model.ready(); val before = saved()
        withContext(Dispatchers.Main) { model.onHint() }
        val hint = checkNotNull(model.await { it.hint != null && !it.hintLoading }.hint)
        assertEquals(3, hint.length)
        assertTrue(hint.firstLetter in setOf("Р", "Т"))
        assertEquals(before, saved())
    }

    @Test fun installedMinimalAssetSuppliesEveryMandatoryRiddleToGameUi() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        suspend fun asset(name: String) = withContext(Dispatchers.IO) {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { asset("campaign.json") })
        val campaignWords = WordRepository({ asset("words.txt") })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        val meanings = JsonRiddlesRepository({ context.assets.open("owned_riddles.json").use { it.readBytes() } })
        val exact = org.json.JSONObject(asset("owned_riddles.json")).getJSONObject("riddles")
        var riddlesChecked = 0
        try {
            for (level in campaign.levels.filter { row -> row.tasks.any { it is com.example.sostavslova.core.CampaignTask.T4 } }) {
                val earlier = org.json.JSONObject()
                for (previous in campaign.levels.filter { it.number < level.number }) {
                    earlier.put(previous.word, org.json.JSONObject().put("completedTaskIds",
                        org.json.JSONArray(previous.tasks.map { it.id })))
                }
                val seed = org.json.JSONObject().put("version", 2).put("highestUnlockedLevel", level.number)
                    .put("campaignId", campaign.contentSha256).put("levels", earlier)
                store.edit { it[com.example.sostavslova.data.jsonstore.stringPreferencesKey("campaignProgressV2")] = seed.toString() }
                val game = model(SavedStateHandle(mapOf("index" to level.number.toString())), meanings = meanings,
                    wordRepository = campaignWords, levelRepository = campaignLevels)
                val initial = game.ready()
                val riddles = initial.riddles.values.flatten()
                assertTrue(riddles.isNotEmpty())
                assertTrue(riddles.all { it.text.isNotBlank() })
                level.tasks.filterIsInstance<com.example.sostavslova.core.CampaignTask.T4>().forEach { task ->
                    task.targets.forEachIndexed { index, word -> assertEquals(exact.getString(word), initial.riddles.getValue(task.id)[index].text) }
                }
                riddlesChecked += riddles.size
                withContext(Dispatchers.Main) { game.onHint() }
                val hinted = game.await { !it.hintLoading && it.campaignHint != null }
                assertTrue(checkNotNull(hinted.campaignLevel).hints.ladder.steps.all {
                    it.stage in setOf(com.example.sostavslova.core.HintStage.FIRST_LETTER,
                        com.example.sostavslova.core.HintStage.PATTERN, com.example.sostavslova.core.HintStage.ANSWER)
                })
                assertTrue(hinted.game?.found.orEmpty().isEmpty())
                assertEquals(0, hinted.campaignLevel?.hints?.paidUnits)
                withContext(Dispatchers.Main) { owners.last().viewModelStore.clear() }
            }
            assertEquals(11, riddlesChecked)
        } finally {
            withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        }
    }



    @Test fun statisticsAndTasksDoNotLoadWordDefinitionsOrChangeProgress() = runTest {
        seed("портал", listOf("пар"))
        val reads = AtomicInteger()
        val meanings = RiddlesRepository { reads.incrementAndGet(); error("No riddle task") }
        val model = model(meanings = meanings); model.ready()
        val before = model.uiState.value.game
        val saved = saved()
        for (dialog in GameDialog.entries) {
            withContext(Dispatchers.Main) { model.onDialog(dialog) }
            assertEquals(dialog, model.uiState.value.dialog)
            assertEquals(before, model.uiState.value.game)
            withContext(Dispatchers.Main) { model.onDismissDialog() }
            assertNull(model.uiState.value.dialog)
        }
        assertEquals(saved, saved())
        assertEquals(0, reads.get())
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
        assertNull(store.data.first()[com.example.sostavslova.data.jsonstore.stringPreferencesKey("currentGame")])
    }

    @Test fun routeAndProgressRestoreButOldDraftIsIgnoredAfterViewModelRecreation() = runTest {
        seed("портал", listOf("пар"))
        val handle = SavedStateHandle(mapOf("index" to "2"))
        val model = model(handle); model.ready()
        withContext(Dispatchers.Main) { model.onInputChanged("ЧеРнОвИк") }
        val snapshot = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any?>(it) } }
        // Снимок старой версии с сохранённым черновиком также не восстанавливает ввод.
        val restored = model(SavedStateHandle(snapshot + mapOf(
            "game.input" to "Старый черновик", "game.source" to "портал")))
        val state = restored.ready()
        assertEquals("портал", state.game?.word)
        assertEquals(listOf("пар"), state.game?.found)
        assertEquals("", state.input)
    }

    @Test fun hintLadderAccumulatesAndRestoresAcrossReturnRotationAndNewStore() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val reads = AtomicInteger()
        val meanings = RiddlesRepository { reads.incrementAndGet(); "Синтетическое существо для проверки подсказок." }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels,
            retainedOwner = owner)
        model.ready()
        val riddleReads = reads.get()
        repeat(2) {
            withContext(Dispatchers.Main) { repeat(20) { model.onHint() } }
            model.await { state -> !state.hintLoading && state.campaignHints.size == it + 1 }
        }
        val shown = model.uiState.value
        assertEquals(listOf(com.example.sostavslova.core.HintStage.FIRST_LETTER,
            com.example.sostavslova.core.HintStage.PATTERN),
            shown.campaignHints.map { it.stage })
        assertFalse(shown.campaignHints[1].wiktionary)
        assertEquals(1, shown.campaignLevel?.hints?.paidUnits)
        assertEquals(riddleReads, reads.get())
        assertEquals(1, shown.hintCost)
        assertEquals(3, shown.campaignLevel?.hints?.ladder?.steps?.size)
        assertEquals("кот", shown.campaignLevel?.hints?.ladder?.target?.word)
        assertTrue(shown.campaignHints.none { it.text?.contains("https://") == true })
        // Повторный вход с тем же владельцем (поворот) не производит событий.
        val rotated = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels,
            retainedOwner = owner)
        assertSame(model, rotated)
        withContext(Dispatchers.Main) { rotated.onGameOpened() }
        assertEquals(shown.campaignHints, rotated.uiState.value.campaignHints)
        assertEquals(shown.hintCost, rotated.uiState.value.hintCost)
        assertEquals(shown.hintRemaining, rotated.uiState.value.hintRemaining)
        val returned = model(wordRepository = campaignWords, levelRepository = campaignLevels).ready()
        assertEquals(shown.campaignHints, returned.campaignHints)
        assertEquals(shown.campaignLevel?.hints, returned.campaignLevel?.hints)
        // Закрываем все VM и файловый DataStore, читаем файл новым экземпляром.
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope.cancel(); scope.coroutineContext.job.join()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.json") }
        progress = ProgressRepository(store)
        val fresh = model(wordRepository = campaignWords, levelRepository = campaignLevels)
        val restored = fresh.ready()
        assertEquals(shown.campaignHints, restored.campaignHints)
        assertEquals(shown.campaignLevel?.hints, restored.campaignLevel?.hints)
        withContext(Dispatchers.Main) { fresh.onHint() }
        val paid = fresh.await { !it.hintLoading && it.campaignHints.size == 3 }
        assertEquals(2, paid.campaignLevel?.hints?.paidUnits)
        assertEquals(com.example.sostavslova.core.HintStage.ANSWER, paid.campaignHint?.stage)
        fresh.answer("кот")
        assertTrue(fresh.uiState.value.campaignHints.isEmpty())
        withContext(Dispatchers.Main) { fresh.onHint() }
        val another = fresh.await { !it.hintLoading && it.campaignHint != null }
        assertNotEquals("кот", another.campaignLevel?.hints?.ladder?.target?.word)
    }

    @Test fun campaignHintStartsWithUnknownPositionWithoutDatabase() = runTest {
        val json = miniCampaignJson.replace(
            """{"id": "ending", "type": "T5", "ending": "ка", "minLength": 3}""",
            """{"id": "theme_first", "type": "T6", "category": "animals", "n": 1, "words": ["кот"]}""")
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { json })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(wordRepository = campaignWords, levelRepository = campaignLevels)
        model.ready()
        withContext(Dispatchers.Main) { model.onHint() }
        val state = model.await { !it.hintLoading && it.campaignHint != null }
        assertEquals(com.example.sostavslova.core.HintStage.FIRST_LETTER, state.campaignHint?.stage)
        assertEquals(3, state.campaignHint?.text?.length)
        assertEquals(1, state.campaignHint?.revealedPositions?.size)
        assertTrue(0 in checkNotNull(state.campaignHint).revealedPositions)
        assertEquals(0, state.campaignLevel?.hints?.paidUnits)
    }

    @Test fun noDatabaseFreeT4UnknownLetterWorksAtZero() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        progress.migrateCampaign(campaign, campaignWords.requireIndex())
        // Запись fixture через тот же совместимый DTO.
        store.edit { it[com.example.sostavslova.data.jsonstore.stringPreferencesKey("campaignProgressV2")] =
            """{"version":2,"levels":{"картотека":{"hints":{"paidUnits":5,"shownSteps":5}}}}""" }
        val model = model(wordRepository = campaignWords, levelRepository = campaignLevels)
        val ready = model.ready()
        assertEquals(0, ready.hintRemaining)
        assertEquals(0, ready.hintCost)
        withContext(Dispatchers.Main) { model.onHint() }
        val free = model.await { !it.hintLoading && it.campaignHint != null }
        assertEquals(com.example.sostavslova.core.HintStage.FIRST_LETTER, free.campaignHint?.stage)
        assertEquals(1, free.campaignHint?.revealedPositions?.size)
        assertEquals(5, free.campaignLevel?.hints?.paidUnits)
        assertEquals(6, free.campaignLevel?.hints?.shownSteps)
        assertEquals(1, free.hintCost)
        withContext(Dispatchers.Main) { model.onHint() }
        model.await { !it.hintLoading && it.message?.text == R.string.hint_budget_exhausted }
        assertEquals(free.campaignLevel?.hints, model.uiState.value.campaignLevel?.hints)
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
        assertNull(store.data.first()[com.example.sostavslova.data.jsonstore.stringPreferencesKey("currentGame")])
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
        store.edit { it[com.example.sostavslova.data.jsonstore.stringPreferencesKey("campaignProgressV2")] = seeded.toString() }
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
        val migrated = progress.migrateCampaign(campaign, index)
        assertEquals(campaign.contentSha256, migrated.campaignId)
        assertFalse(migrated.finaleReached)
        progress.submitCampaignAnswer(campaign, 1, "карта", index)
        progress.submitCampaignAnswer(campaign, 2, "торт", index)
        val reached = progress.observeCampaignProgress().first()
        assertTrue(reached.finaleReached)
        assertFalse(reached.finaleShown)
        val model = model(SavedStateHandle(mapOf("index" to "2")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        model.await { it.finaleSummary != null }
        withContext(Dispatchers.Main) { model.onCloseFinale(true) }
        model.await { it.returnLevels }
        assertTrue(progress.observeCampaignProgress().first().finaleShown)
        val reopened = model(SavedStateHandle(mapOf("index" to "1")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        assertNull(reopened.ready().finaleSummary)
        val acknowledged = progress.observeCampaignProgress().first()
        assertTrue(acknowledged.finaleReached)
        assertTrue(acknowledged.finaleShown)
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
        // Activity уничтожает экран, но сохраняет NavBackStackEntry и ViewModelStore.
        withContext(Dispatchers.Main) { first.onScreenClosed() }
        val retained = model(handle, wordRepository = countedWords, retainedOwner = owner)
        assertSame(first, retained)
        progress.saveLevelResult(level, listOf("рот", "тор"))
        val updated = first.await { it.game?.foundCount == 2 }
        assertSame(initial.game?.allWords, updated.game?.allWords)
        assertEquals(LevelProgress(completed = true), updated.levelProgress)
        assertEquals(2, updated.statistics.totalFound)
        assertEquals("", updated.input)
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

    @Test fun missingOrCorruptMandatoryJsonFailsWithoutFallbackOrProgressReset() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        progress.migrateCampaign(campaign, campaignWords.requireIndex())
        val before = saved()
        for (failure in listOf(IOException("Missing JSON"), IllegalArgumentException("Corrupt JSON"))) {
            val model = model(meanings = RiddlesRepository { throw failure }, wordRepository = campaignWords, levelRepository = campaignLevels)
            val state = model.await { it.loadError }
            assertFalse(state.canPlay)
            assertTrue(state.riddles.isEmpty())
            assertEquals(before, saved())
        }
    }

    @Test fun campaignSafeRiddleTextHasNoMetadataAndProgressSurvivesRecreation() = runTest {
        val meanings = RiddlesRepository { "Условное безопасное описание для синтетического теста" }
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = model(meanings = meanings, wordRepository = campaignWords, levelRepository = campaignLevels)
        val riddle = model.ready().riddles.getValue("riddle").single()
        assertNotNull(riddle.text)
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

    @Test fun newTaskTypesAreExplainedAtTheirFirstActualLevelAndNeverRepeat() = runTest {
        val campaignLevels = LevelsRepository({ error("No v1") }, readCampaign = { firstAppearanceCampaignJson() })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val campaign = requireNotNull(campaignLevels.getCampaign())
        val seeded = org.json.JSONObject().put("version", 2).put("highestUnlockedLevel", 15)
        val savedLevels = org.json.JSONObject()
        campaign.levels.take(14).forEach { level ->
            savedLevels.put(level.word, org.json.JSONObject().put("completedTaskIds",
                org.json.JSONArray(level.tasks.map { it.id })))
        }
        seeded.put("levels", savedLevels)
        store.edit { it[com.example.sostavslova.data.jsonstore.stringPreferencesKey("campaignProgressV2")] = seeded.toString() }

        val fourteenth = model(SavedStateHandle(mapOf("index" to "14")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        val first = fourteenth.ready()
        assertEquals(listOf(com.example.sostavslova.core.TaskType.T6), first.explanations)
        assertEquals(setOf(com.example.sostavslova.core.TaskType.T6),
            progress.observeCampaignProgress().first().seenExplanations)
        withContext(Dispatchers.Main) { fourteenth.onDismissExplanations() }
        assertTrue(fourteenth.uiState.value.explanations.isEmpty())

        val reopened = model(SavedStateHandle(mapOf("index" to "14")),
            wordRepository = campaignWords, levelRepository = campaignLevels).ready()
        assertTrue(reopened.explanations.isEmpty())

        val fifteenth = model(SavedStateHandle(mapOf("index" to "15")),
            wordRepository = campaignWords, levelRepository = campaignLevels)
        val last = fifteenth.ready()
        assertEquals(listOf(com.example.sostavslova.core.TaskType.T7), last.explanations)
        assertEquals(setOf(com.example.sostavslova.core.TaskType.T6, com.example.sostavslova.core.TaskType.T7),
            progress.observeCampaignProgress().first().seenExplanations)

        val lastAgain = model(SavedStateHandle(mapOf("index" to "15")),
            wordRepository = campaignWords, levelRepository = campaignLevels).ready()
        assertTrue(lastAgain.explanations.isEmpty())
    }

    /** 15 уровней: T1 знаком с первого уровня, T6 впервые на 14, T7 — на 15. */
    private fun firstAppearanceCampaignJson(): String {
        val suffixes = "бвгдзлмнпсфхцчшщ"
        val levels = (1..15).joinToString(",") { number ->
            val word = "картотека" + suffixes[number - 1]
            val stage = when {
                number <= 3 -> 1
                number <= 5 -> 2
                number <= 8 -> 3
                number <= 10 -> 4
                number <= 13 -> 5
                else -> 6
            }
            val tasks = when (number) {
                14 -> """{"id":"count14","type":"T1","n":1},
                    {"id":"theme14","type":"T6","category":"test","n":1,"words":["кот"]}"""
                15 -> """{"id":"count15","type":"T1","n":1},
                    {"id":"contains15","type":"T7","letter":"р","n":1}"""
                else -> """{"id":"count$number","type":"T1","n":1}"""
            }
            """{"number":$number,"word":"$word","stage":$stage,"requiredStars":1,"tasks":[$tasks]}"""
        }
        val stages = """[{"number":1,"firstLevel":1,"lastLevel":3},
            {"number":2,"firstLevel":4,"lastLevel":5},
            {"number":3,"firstLevel":6,"lastLevel":8},
            {"number":4,"firstLevel":9,"lastLevel":10},
            {"number":5,"firstLevel":11,"lastLevel":13},
            {"number":6,"firstLevel":14,"lastLevel":15}]"""
        return """{"version":2,"countMinLength":4,"stages":$stages,"levels":[$levels]}"""
    }

}
