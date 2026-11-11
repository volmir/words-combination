package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.HintStatus
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.core.WordIndex
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Path.Companion.toPath
import okio.FileSystem

/** Реальный файловый DataStore (OkioStorage); тесты также запускаются обычным JUnit на JVM. */
class CampaignProgressRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: ControlledStore
    private lateinit var repository: ProgressRepository
    private val key = stringPreferencesKey("campaignProgressV2")
    private val index = WordIndex(listOf("кот", "торт", "крот", "карта", "река", "актер"))
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), listOf("картотека", "электрокарта").mapIndexed { i, word ->
        CampaignLevel(i + 1, word, 1, 2, listOf(
            CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1),
            CampaignTask.T4("riddle", listOf("кот", "торт")),
        ))
    })

    @Before fun setUp() {
        directory = temporaryFolder.newFolder()
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = ControlledStore(PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(directory, "campaign.preferences_pb").absolutePath.toPath()
            },
            scope = scope,
        ))
        repository = ProgressRepository(store)
    }

    private suspend fun restart() {
        scope.cancel()
        scope.coroutineContext.job.join()
        openStore()
    }

    @After fun tearDown() = runTest {
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    private suspend fun snapshot() = repository.observeCampaignProgress().first()
    private suspend fun answer(word: String, level: Int = 1) =
        repository.submitCampaignAnswer(campaign, level, word, index)

    @Test fun oneAnswerPersistsSeveralStarsAndUnlockInOneTransactionAndOneSnapshot() = runTest {
        assertEquals(CampaignProgress(), snapshot())
        val result = answer(" КРОТ ")
        assertEquals(AnswerResult.CORRECT, result.result)
        assertEquals(setOf("count", "initial"), result.evaluation.newlyCompletedTaskIds)
        assertEquals(1, store.calls.get())
        assertEquals(1, store.committed.size)
        val observed = snapshot()
        assertEquals(listOf("крот"), observed.levels.getValue("картотека").found)
        assertEquals(2, observed.highestUnlockedLevel)
        assertNull(observed.lastVisitedLevel)
        val summary = CampaignRules.summary(campaign, observed)
        assertEquals(2, summary.stars)
        assertEquals(1, summary.foundWords)
        assertEquals(1, summary.passedLevels)
        assertEquals(setOf(key), store.committed.single().asMap().keys)
    }

    @Test fun completeSnapshotIncludingHintLadderAndFinaleSurvivesRestart() = runTest {
        answer("крот")
        repeat(3) { step ->
            assertEquals(HintStatus.SHOWN,
                repository.requestCampaignHint(campaign, 1, step, index)?.status)
        }
        answer("кот")
        answer("торт")
        answer("крот", 2)
        repository.visitCampaignLevel(campaign, 2)
        repository.visitCampaignLevel(campaign, 1)
        assertTrue(repository.markCampaignExplanationSeen(TaskType.T4))
        assertTrue(repository.consumeCampaignFinaleDialog())
        val before = snapshot()
        assertEquals(setOf("кот", "торт"), before.levels.getValue("картотека").solvedRiddleTargets)
        val hints = before.levels.getValue("картотека").hints
        assertEquals(3, hints.shownSteps)
        assertEquals(2, hints.paidUnits)
        assertNotNull(hints.ladder.target)
        assertTrue(hints.ladder.revealedPositions.isNotEmpty())
        assertTrue(before.finaleReached)
        assertTrue(before.finaleShown)
        restart()
        assertEquals(before, snapshot())
        assertEquals(1, snapshot().lastVisitedLevel)
        assertEquals(2, snapshot().highestUnlockedLevel)
        assertEquals(setOf(TaskType.T4), snapshot().seenExplanations)
        assertFalse(repository.consumeCampaignFinaleDialog())
        assertFalse(repository.markCampaignExplanationSeen(TaskType.T4))
        assertNull(repository.requestCampaignHint(campaign, 1, 2, index))
        assertEquals(before, snapshot())
    }

    @Test fun sameAnswerOnTwoLevelsIsIndependentAndReplacedSourceHasNoStars() = runTest {
        answer("крот")
        answer("крот", 2)
        val saved = snapshot()
        assertEquals(listOf("крот"), saved.levels.getValue("картотека").found)
        assertEquals(listOf("крот"), saved.levels.getValue("электрокарта").found)
        assertEquals(2, CampaignRules.summary(campaign, saved).foundWords)
        assertEquals(4, CampaignRules.summary(campaign, saved).stars)
        val replaced = campaign.copy(levels = listOf(campaign.levels[0].copy(word = "картограф")) + campaign.levels.drop(1))
        assertEquals(0, CampaignRules.stars(replaced.levels.first(), saved))
        assertEquals(2, CampaignRules.summary(replaced, saved).stars)
    }

    @Test fun hundredConcurrentDistinctAnswersAcrossTwoRepositoryInstancesNeverLoseUpdates() = runTest {
        val letters = "бвгдежзийк"
        val words = letters.flatMap { a -> letters.filter { it != a }.flatMap { b ->
            letters.filter { it != a && it != b }.map { c -> "а$a$b$c" }
        } }.take(100)
        val dictionary = WordIndex(words)
        val level = CampaignLevel(1, "а$letters", 1, 2,
            listOf(CampaignTask.T1("hundred", 100), CampaignTask.T2("initial", 'а', 100)))
        val model = Campaign(listOf(Stage(1, 1, 1)), listOf(level))
        val otherRepository = ProgressRepository(store)
        val observedFinal = async(Dispatchers.Default) {
            repository.observeCampaignProgress().first { state ->
                val count = state.levels[level.word]?.found.orEmpty().size
                assertEquals(if (count == 100) 2 else 0, CampaignRules.summary(model, state).stars)
                assertEquals(count == 100, state.finaleReached)
                count == 100
            }
        }
        coroutineScope {
            words.forEachIndexed { i, word -> launch(Dispatchers.Default) {
                val writer = if (i % 2 == 0) repository else otherRepository
                assertEquals(AnswerResult.CORRECT, writer.submitCampaignAnswer(model, 1, word, dictionary).result)
            } }
        }
        val saved = snapshot()
        assertEquals(saved, observedFinal.await())
        assertEquals(words.toSet(), saved.levels.getValue(level.word).found.toSet())
        assertEquals(100, saved.levels.getValue(level.word).found.size)
        assertEquals(2, CampaignRules.summary(model, saved).stars)
        assertTrue(saved.finaleReached)
        assertEquals(100, store.calls.get())
        restart()
        assertEquals(saved, snapshot())
    }

    @Test fun concurrentAnswerAndPaidHintPreserveBothUpdatesAndEarnedBudget() = runTest {
        val level = campaign.levels.first().copy(requiredStars = 1, tasks = listOf(CampaignTask.T1("count", 10)))
        val model = campaign.copy(levels = listOf(level))
        for (word in listOf("кот", "торт", "карта")) repository.submitCampaignAnswer(model, 1, word, index)
        val otherRepository = ProgressRepository(store)
        coroutineScope {
            val submitted = async(Dispatchers.Default) { repository.submitCampaignAnswer(model, 1, "крот", index) }
            val hinted = async(Dispatchers.Default) { otherRepository.requestCampaignHint(model, 1, 0, index) }
            assertEquals(AnswerResult.CORRECT, submitted.await().result)
            assertEquals(HintStatus.SHOWN, hinted.await()?.status)
        }
        val current = requireNotNull(CampaignRules.openLevel(model, snapshot(), 1, index))
        assertEquals(setOf("кот", "торт", "карта", "крот"), current.game.found.toSet())
        assertEquals(1, current.hints.shownSteps)
        assertEquals(1, current.hints.paidUnits)
        assertEquals(1, HintRules.budget(current).earned)
        assertEquals(5, HintRules.budget(current).remaining)
    }

    @Test fun repeatedAnswerAndConcurrentReplayOfHintDoNotAwardOrSpendTwice() = runTest {
        answer("крот")
        val before = snapshot()
        assertEquals(AnswerResult.ALREADY_FOUND, answer("КРОТ").result)
        assertEquals(before, snapshot())
        val results = coroutineScope {
            List(20) { async(Dispatchers.Default) { repository.requestCampaignHint(campaign, 1, 0, index) } }.map { it.await() }
        }
        assertEquals(1, results.count { it?.status == HintStatus.SHOWN })
        val once = snapshot()
        assertEquals(1, once.levels.getValue("картотека").hints.shownSteps)
        restart()
        assertNull(repository.requestCampaignHint(campaign, 1, 0, index))
        assertEquals(once, snapshot())
        val paidResults = coroutineScope {
            List(20) { async(Dispatchers.Default) { repository.requestCampaignHint(campaign, 1, 1, index) } }.map { it.await() }
        }
        assertEquals(1, paidResults.count { it?.status == HintStatus.SHOWN })
        assertEquals(2, snapshot().levels.getValue("картотека").hints.shownSteps)
        assertEquals(1, snapshot().levels.getValue("картотека").hints.paidUnits)
    }

    @Test fun writeFailureDoesNotPublishAnswerStarsUnlockOrHintCostAndRetrySucceeds() = runTest {
        store.failNext.set(true)
        assertTrue(runCatching { answer("крот") }.exceptionOrNull() is IOException)
        assertEquals(CampaignProgress(), snapshot())
        assertTrue(store.committed.isEmpty())
        assertEquals(AnswerResult.CORRECT, answer("крот").result)
        repository.requestCampaignHint(campaign, 1, 0, index) // Бесплатная длина T4.
        val before = snapshot()
        store.failNext.set(true)
        assertTrue(runCatching { repository.requestCampaignHint(campaign, 1, 1, index) }.exceptionOrNull() is IOException)
        assertEquals(before, snapshot())
        assertEquals(HintStatus.SHOWN, repository.requestCampaignHint(campaign, 1, 1, index)?.status)
        assertEquals(1, snapshot().levels.getValue("картотека").hints.paidUnits)
    }

    @Test fun cancellationWhileAnswerWaitsForCommitCannotLoseAcceptedWrite() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeCommit = { entered.complete(Unit); release.await() }
        val ui = launch(Dispatchers.Default) { answer("крот") }
        entered.await()
        assertEquals(CampaignProgress(), snapshot())
        ui.cancel()
        release.complete(Unit)
        ui.join()
        store.beforeCommit = null
        assertEquals(listOf("крот"), snapshot().levels.getValue("картотека").found)
        assertEquals(2, snapshot().highestUnlockedLevel)
        restart()
        assertEquals(2, CampaignRules.summary(campaign, snapshot()).stars)
    }

    @Test fun hintResultIsNotReturnedBeforeCommitAndNavigationCancellationPreservesCost() = runTest {
        repository.requestCampaignHint(campaign, 1, 0, index)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        store.beforeCommit = { entered.complete(Unit); release.await() }
        val ui = launch(Dispatchers.Default) {
            repository.requestCampaignHint(campaign, 1, 1, index)
            returned.set(true)
        }
        entered.await()
        assertFalse(returned.get())
        assertEquals(0, snapshot().levels.getValue("картотека").hints.paidUnits)
        ui.cancel()
        release.complete(Unit)
        ui.join()
        store.beforeCommit = null
        restart()
        assertEquals(2, snapshot().levels.getValue("картотека").hints.shownSteps)
        assertEquals(1, snapshot().levels.getValue("картотека").hints.paidUnits)
        assertNull(repository.requestCampaignHint(campaign, 1, 1, index))
    }

    @Test fun explanationAndFinaleFlagsAreCommittedOnceAndFailureAllowsRetry() = runTest {
        store.failNext.set(true)
        assertTrue(runCatching { repository.markCampaignExplanationSeen(TaskType.T2) }.exceptionOrNull() is IOException)
        assertTrue(snapshot().seenExplanations.isEmpty())
        val before = store.calls.get()
        assertTrue(repository.markCampaignExplanationSeen(TaskType.T2))
        assertEquals(before + 1, store.calls.get())
        assertFalse(repository.markCampaignExplanationSeen(TaskType.T2))
        assertFalse(repository.consumeCampaignFinaleDialog())
        answer("крот")
        answer("крот", 2)
        store.failNext.set(true)
        assertTrue(runCatching { repository.consumeCampaignFinaleDialog() }.exceptionOrNull() is IOException)
        assertFalse(snapshot().finaleShown)
        assertTrue(repository.consumeCampaignFinaleDialog())
        assertFalse(repository.consumeCampaignFinaleDialog())
    }

    @Test fun absentDtoFieldsDefaultAndLegacySaveRemainsReadableAndIndependent() = runTest {
        store.edit {
            it[stringPreferencesKey("currentGame")] = """{"word":"торт","found":["рот"]}"""
            it[key] = """{"levels":{"картотека":{"found":["крот"]}}}"""
        }
        val legacy = repository.observeCurrentGame().first()
        assertEquals(CurrentGame("торт", listOf("рот")), legacy)
        val saved = snapshot()
        assertEquals(1, saved.highestUnlockedLevel)
        assertNull(saved.lastVisitedLevel)
        assertTrue(saved.seenExplanations.isEmpty())
        assertTrue(saved.levels.getValue("картотека").completedTaskIds.isEmpty())
        repository.markCampaignExplanationSeen(TaskType.T1)
        assertEquals(legacy, repository.observeCurrentGame().first())
        repository.saveGame(GameState("торт", listOf("рот"), listOf("рот")))
        assertEquals(saved.levels, snapshot().levels)
    }

    @Test fun closedRoutesInvalidAnswersAndUnreadableVersionsCannotOverwriteProgress() = runTest {
        assertTrue(runCatching { answer("крот", 2) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.visitCampaignLevel(campaign, 2) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(AnswerResult.TOO_SHORT, answer("ко").result)
        assertEquals(AnswerResult.NOT_FOUND, answer("выдумка").result)
        assertEquals(CampaignProgress(), snapshot())
        for (raw in listOf("broken", """{"version":3}""")) {
            store.edit { it[key] = raw }
            assertTrue(runCatching { repository.markCampaignExplanationSeen(TaskType.T1) }.isFailure)
            assertEquals(raw, store.data.first()[key])
        }
    }

    @Test fun clearAllRemovesCampaignAndLegacyDataAndSurvivesRestart() = runTest {
        repository.saveGame(GameState("торт", listOf("рот"), listOf("рот")))
        repository.saveLevelResult(Level(1, "торт", answers = 2, words = 3, letter = 'т', letterCount = 2),
            listOf("тор", "тот"))
        assertFalse(repository.observeLevelProgress().first().isEmpty())
        assertFalse(repository.levelAnswers("торт").isEmpty())
        answer("крот")
        repository.requestCampaignHint(campaign, 1, 0, index)
        repository.visitCampaignLevel(campaign, 2)
        repository.markCampaignExplanationSeen(TaskType.T4)
        answer("крот", 2)
        repository.consumeCampaignFinaleDialog()
        val before = store.calls.get()
        repository.clearAll()
        assertEquals(before + 1, store.calls.get())
        assertEquals(CampaignProgress(), snapshot())
        assertNull(repository.observeCurrentGame().first())
        assertTrue(repository.observeLevelProgress().first().isEmpty())
        assertTrue(repository.levelAnswers("картотека").isEmpty())
        assertTrue(repository.levelAnswers("торт").isEmpty())
        restart()
        assertEquals(CampaignProgress(), snapshot())
        assertTrue(repository.markCampaignExplanationSeen(TaskType.T4))
        assertEquals(AnswerResult.CORRECT, answer("крот").result)
        assertEquals(HintStatus.SHOWN, repository.requestCampaignHint(campaign, 1, 0, index)?.status)
    }

    /** Сбой и барьер расположены после transform, но до реального коммита DataStore. */
    private class ControlledStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val calls = AtomicInteger()
        val failNext = AtomicBoolean(false)
        val committed = ConcurrentLinkedQueue<Preferences>()
        @Volatile var beforeCommit: (suspend () -> Unit)? = null
        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            calls.incrementAndGet()
            val result = delegate.updateData { previous ->
                val next = transform(previous)
                if (failNext.getAndSet(false)) throw IOException("Injected write failure")
                beforeCommit?.invoke()
                next
            }
            committed.add(result)
            return result
        }
    }
}
