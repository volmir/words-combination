package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.core.WordIndex
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
import okio.FileSystem
import okio.Path.Companion.toPath

/** Настоящие ключи и файловый DataStore, без Android API: запускается также JUnit на JVM. */
class LegacyCampaignMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: ControlledStore
    private lateinit var repository: ProgressRepository
    private val v2 = stringPreferencesKey("campaignProgressV2")
    private val current = stringPreferencesKey("currentGame")
    private val progress = stringPreferencesKey("levelProgress")
    private val answers = stringPreferencesKey("levelAnswers")
    private val index = WordIndex(listOf(
        "река", "каре", "перец", "пир", "перо", "клоп", "пункт", "ррр", "кот", "еж", "рекуперация",
    ))
    private val campaign = campaign("рекуперация", "опровержение", "хлопкопункт")

    private fun campaign(vararg words: String) = Campaign(
        listOf(Stage(1, 1, words.size)),
        words.mapIndexed { i, word -> CampaignLevel(i + 1, word, 1, 2, listOf(
            CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1),
        )) },
    )

    @Before fun setUp() {
        directory = temporaryFolder.newFolder()
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = ControlledStore(PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(directory, "migration.preferences_pb").absolutePath.toPath()
            }, scope = scope,
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

    private suspend fun seed(
        levelProgress: String? = null, levelAnswers: String? = null, currentGame: String? = null,
    ) {
        store.edit {
            levelProgress?.let { raw -> it[progress] = raw }
            levelAnswers?.let { raw -> it[answers] = raw }
            currentGame?.let { raw -> it[current] = raw }
        }
    }

    private suspend fun migrate(target: Campaign = campaign) = repository.migrateCampaign(target, index)
    private suspend fun snapshot() = repository.observeCampaignProgress().first()
    private suspend fun assertLegacyRemoved() {
        val saved = store.data.first()
        assertNotNull(saved[v2])
        assertNull(saved[current])
        assertNull(saved[progress])
        assertNull(saved[answers])
    }

    @Test fun reorderedLevelsMatchNormalizedWordAndOldFrontier() = runTest {
        seed(
            levelProgress = """{" РЕКУПЁРАЦИЯ ":{"completed":true},"хлопкопункт":{"wordsTask":true}}""",
            levelAnswers = """{" РЕКУПЁРАЦИЯ ":[" КАРЁ ","река"],"хлопкопункт":["клоп"]}""",
        )
        val target = campaign("хлопкопункт", "аспирантура", "рекуперация", "опровержение")
        val saved = migrate(target)
        assertEquals(2, saved.highestUnlockedLevel) // Старый completed №1, а не новый №3.
        assertEquals(listOf("каре", "река"), saved.levels.getValue("рекуперация").found)
        assertEquals(listOf("клоп"), saved.levels.getValue("хлопкопункт").found)
        assertEquals(setOf("count", "initial"), saved.levels.getValue("рекуперация").completedTaskIds)
        assertNull(saved.levels["аспирантура"])
        assertNull(saved.lastVisitedLevel)
        assertLegacyRemoved()
    }

    @Test fun replacedAndDeletedSourcesDoNotGiveAnswersOrStarsToNewOccupants() = runTest {
        seed(
            levelProgress = """{"рекуперация":{"completed":true,"wordsTask":true,"lettersTask":true}}""",
            levelAnswers = """{"рекуперация":["река","каре"]}""",
            currentGame = """{"word":"рекуперация","found":["перец"]}""",
        )
        val saved = migrate(campaign("аспирантура", "опровержение"))
        assertEquals(2, saved.highestUnlockedLevel)
        assertTrue(saved.levels.isEmpty())
        assertFalse(saved.finaleReached)
        assertLegacyRemoved()
    }

    @Test fun frontierUsesHistoricalPredecessorEvenWhenWordWasRemovedAndCapsAtNewSize() = runTest {
        seed(levelProgress = """{"селитроварня":{"completed":true}}""")
        val target = campaign("аспирантура", "опровержение", "рекуперация", "хлопкопункт")
        assertEquals(4, migrate(target).highestUnlockedLevel)
    }

    @Test fun flagsAloneKeepAccessButCannotBecomeV2StarsOrFinale() = runTest {
        seed(levelProgress = """{"рекуперация":{"completed":true,"wordsTask":true,"lettersTask":true},"пудлинговка":{"completed":true}}""")
        val saved = migrate()
        assertEquals(3, saved.highestUnlockedLevel)
        assertTrue(saved.levels.isEmpty())
        assertEquals(0, CampaignRules.summary(campaign, saved).stars)
        assertFalse(saved.finaleReached)
        assertFalse(saved.finaleShown)
    }

    @Test fun secondaryFlagsAnswersAndCurrentGameDoNotInventCompletedPredecessor() = runTest {
        seed(
            levelProgress = """{"рекуперация":{"wordsTask":true,"lettersTask":true}}""",
            levelAnswers = """{"рекуперация":["каре"],"хлопкопункт":["клоп"]}""",
            currentGame = """{"word":"хлопкопункт","found":["пункт"]}""",
        )
        val saved = migrate()
        assertEquals(1, saved.highestUnlockedLevel)
        assertEquals(2, saved.levels.getValue("хлопкопункт").found.size)
        assertEquals(2, saved.levels.getValue("рекуперация").completedTaskIds.size)
    }

    @Test fun foundIsNormalizedDeduplicatedAndFilteredByNewDictionaryAndMultiset() = runTest {
        seed(levelAnswers = """{"рекуперация":[" РЁКА ","река","ррр","кот","еж","рация","рекуперация","???","пир"]}""")
        val saved = migrate().levels.getValue("рекуперация")
        assertEquals(listOf("река", "пир"), saved.found)
        assertEquals(setOf("count"), saved.completedTaskIds) // Три буквы не идут в T1/T2.
    }

    @Test fun everyNewTaskAndRiddleTargetsAreRecomputedWithoutOldFlagsOrHintCost() = runTest {
        val target = campaign("рекуперация").let { it.copy(levels = listOf(it.levels.single().copy(tasks = listOf(
            CampaignTask.T1("t1", 3), CampaignTask.T2("t2", 'к', 1),
            CampaignTask.T3("t3", 5, "перец"), CampaignTask.T4("t4", listOf("пир", "река")),
            CampaignTask.T5("t5", "ка", 4), CampaignTask.T6("t6", "test", 2, listOf("река", "каре")),
            CampaignTask.T7("t7", 'п', 2),
        )))) }
        seed(levelAnswers = """{"рекуперация":["река","каре","перец","пир"]}""")
        val saved = migrate(target)
        val level = saved.levels.getValue("рекуперация")
        assertEquals((1..7).map { "t$it" }.toSet(), level.completedTaskIds)
        assertEquals(setOf("пир", "река"), level.solvedRiddleTargets)
        assertEquals(0, level.hints.paidUnits)
        assertEquals(0, level.hints.shownSteps)
        assertNull(level.hints.ladder.target)
        assertTrue(CampaignRules.summary(target, saved).isMaster)
        assertFalse(saved.finaleReached) // Даже все пересчитанные звёзды не воспроизводят событие финала.
        assertFalse(repository.consumeCampaignFinaleDialog())
    }

    @Test fun incompleteRecordsUseDefaultsAndNormalizedAliasesMerge() = runTest {
        seed(
            levelProgress = """{"рекуперация":{},"опровержение":{"completed":true}}""",
            levelAnswers = """{"рекуперация":["река"]," РЕКУПЁРАЦИЯ ":["каре","река"]}""",
            currentGame = """{"word":"рекуперация"}""",
        )
        val saved = migrate()
        assertEquals(listOf("река", "каре"), saved.levels.getValue("рекуперация").found)
        assertEquals(3, saved.highestUnlockedLevel)
        assertTrue(saved.seenExplanations.isEmpty())
    }

    @Test fun currentGameAddsOnlyValidAnswersOfIndependentlyRecordedRealLevel() = runTest {
        seed(
            levelProgress = """{"рекуперация":{}}""",
            levelAnswers = """{"рекуперация":["река"]}""",
            currentGame = """{"word":" РЕКУПЁРАЦИЯ ","found":["каре","кот","река"]}""",
        )
        assertEquals(listOf("река", "каре"), migrate().levels.getValue("рекуперация").found)
    }

    @Test fun currentGameCannotLeakIntoDifferentRecordedLevel() = runTest {
        seed(
            levelProgress = """{"рекуперация":{}}""",
            levelAnswers = """{"рекуперация":["река"]}""",
            currentGame = """{"word":"опровержение","found":["перо"]}""",
        )
        assertEquals(setOf("рекуперация"), migrate().levels.keys)
    }

    @Test fun oldFreeGameMatchingOnlyNewLevelDoesNotCreateCampaignProgress() = runTest {
        seed(currentGame = """{"word":"картер","found":["река"]}""")
        assertTrue(migrate(campaign("картер", "рекуперация")).levels.isEmpty())
        assertLegacyRemoved()
    }

    @Test fun ambiguousCurrentOnlyGameOnOldAndNewSourceIsNotPromoted() = runTest {
        seed(currentGame = """{"word":"рекуперация","found":["река","каре"]}""")
        assertEquals(CampaignProgress(), migrate())
        assertLegacyRemoved()
    }

    @Test fun foreignV1MapKeysDoNotTurnFreeGameIntoNewCampaignStarsOrAccess() = runTest {
        seed(
            levelProgress = """{"картер":{"completed":true}}""",
            levelAnswers = """{"картер":["река"]}""",
            currentGame = """{"word":"картер","found":["каре"]}""",
        )
        assertEquals(CampaignProgress(), migrate(campaign("картер", "рекуперация")))
    }

    @Test fun duplicateNewSourcesAbortBeforeChangingLegacyData() = runTest {
        seed(currentGame = """{"word":"рекуперация","found":["река"]}""")
        val before = store.data.first()
        assertTrue(runCatching { migrate(campaign("рекуперация", "рекуперация")) }.isFailure)
        assertEquals(before, store.data.first())
    }

    @Test fun malformedOrFutureLegacyPayloadIsNeverReplacedByEmptyV2() = runTest {
        val cases = listOf(
            Pair(current, "broken JSON"), Pair(current, "{}"), Pair(current, "null"),
            Pair(current, """{"word":"рекуперация","found":null}"""),
            Pair(current, """{"version":3,"word":"рекуперация","found":[]}"""),
            Pair(progress, "broken JSON"), Pair(progress, "[]"), Pair(progress, "null"),
            Pair(progress, """{"рекуперация":{"completed":"true"}}"""),
            Pair(progress, """{"рекуперация":{"version":3}}"""),
            Pair(answers, "broken JSON"), Pair(answers, "null"), Pair(answers, """{"рекуперация":null}"""),
            Pair(answers, """{"рекуперация":[false]}"""),
        )
        for ((key, raw) in cases) {
            repository.clearAll()
            seed(levelProgress = """{"опровержение":{"completed":true}}""")
            store.edit { it[key] = raw }
            val before = store.data.first()
            assertTrue("$key = $raw", runCatching { migrate() }.isFailure)
            assertEquals(before, store.data.first())
            assertNull(store.data.first()[v2])
        }
    }

    @Test fun malformedFutureOrUnprocessableV2BlocksMigrationAndEventsWithoutErasingEitherVersion() = runTest {
        for (raw in listOf("broken", "null", """{"version":3}""", """{"seenExplanations":["T99"]}""")) {
            repository.clearAll()
            seed(levelAnswers = """{"рекуперация":["река"]}""")
            store.edit { it[v2] = raw }
            val before = store.data.first()
            assertTrue(runCatching { snapshot() }.isFailure)
            assertTrue(runCatching { migrate() }.isFailure)
            assertTrue(runCatching { repository.markCampaignExplanationSeen(TaskType.T1) }.isFailure)
            assertEquals(before, store.data.first())
        }
    }

    @Test fun readingV1AndV2DoesNotStartMigrationAndEventsCannotBypassIt() = runTest {
        seed(levelProgress = """{"рекуперация":{"completed":true}}""")
        val before = store.data.first()
        assertTrue(repository.observeLevelProgress().first().isEmpty())
        assertEquals("""{"рекуперация":{"completed":true}}""", store.data.first()[progress])
        assertEquals(CampaignProgress(), snapshot())
        assertTrue(runCatching { repository.markCampaignExplanationSeen(TaskType.T1) }.isFailure)
        assertTrue(runCatching { repository.submitCampaignAnswer(campaign, 1, "каре", index) }.isFailure)
        assertEquals(before, store.data.first())
        assertEquals(2, migrate().highestUnlockedLevel)
    }

    @Test fun successfulMigrationIsIdempotentAcrossRestartAndKeepsExistingV2AsAuthority() = runTest {
        seed(
            levelProgress = """{"рекуперация":{"completed":true}}""",
            levelAnswers = """{"рекуперация":["река"]}""",
        )
        val first = migrate()
        val raw = store.data.first()[v2]
        assertEquals(first, migrate())
        assertEquals(raw, store.data.first()[v2])
        restart()
        assertEquals(first, migrate())
        seed(levelAnswers = "broken legacy payload")
        val before = store.data.first()
        assertEquals(first, migrate())
        assertEquals(before, store.data.first()) // v2 не пересчитывается, поздние v1 не удаляются чтением.
    }

    @Test fun failedCommitLeavesAllOldKeysAndRetryMigratesExactlyOnce() = runTest {
        seed(
            levelProgress = """{"рекуперация":{"completed":true}}""",
            levelAnswers = """{"рекуперация":["река"]}""",
            currentGame = """{"word":"рекуперация","found":["каре"]}""",
        )
        val before = store.data.first()
        store.failNext.set(true)
        assertTrue(runCatching { migrate() }.exceptionOrNull() is IOException)
        assertEquals(before, store.data.first())
        restart()
        assertEquals(listOf("река", "каре"), migrate().levels.getValue("рекуперация").found)
        assertLegacyRemoved()
    }

    @Test fun migrationAndDeletionAreInvisibleUntilCommitAndSurviveUiCancellation() = runTest {
        seed(levelAnswers = """{"рекуперация":["река"]}""")
        val before = store.data.first()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeCommit = { entered.complete(Unit); release.await() }
        val ui = launch(Dispatchers.Default) { migrate() }
        entered.await()
        assertEquals(before, store.data.first())
        ui.cancel()
        release.complete(Unit)
        ui.join()
        store.beforeCommit = null
        assertLegacyRemoved()
        assertEquals(listOf("река"), snapshot().levels.getValue("рекуперация").found)
    }

    @Test fun concurrentRepositoriesMigrateOneSnapshotWithoutDuplicatingAnswersOrResettingLaterEvents() = runTest {
        seed(levelAnswers = """{"рекуперация":["река"]}""")
        val other = ProgressRepository(store)
        coroutineScope {
            val migrations = (1..10).map {
                async(Dispatchers.Default) { other.migrateCampaign(campaign, index) }
            }
            assertEquals(1, migrations.map { it.await() }.distinct().size)
        }
        repository.submitCampaignAnswer(campaign, 1, "каре", index)
        val saved = snapshot()
        assertEquals(saved, other.migrateCampaign(campaign, index))
        assertEquals(listOf("река", "каре"), saved.levels.getValue("рекуперация").found)
        assertEquals(2, saved.highestUnlockedLevel)
        assertLegacyRemoved()
    }

    @Test fun clearAllRemovesBothVersionsEvenWhenPayloadIsUnreadableAndDoesNotRemigrateOnRestart() = runTest {
        seed(levelProgress = "broken", levelAnswers = "broken", currentGame = "broken")
        store.edit { it[v2] = """{"version":99}""" }
        repository.clearAll()
        assertTrue(store.data.first().asMap().isEmpty())
        restart()
        assertEquals(CampaignProgress(), migrate())
        seed(levelProgress = """{"рекуперация":{"completed":true}}""")
        repository.clearAll()
        assertEquals(CampaignProgress(), migrate())
        assertLegacyRemoved()
    }

    @Test fun emptyInstallCanInitializeAndRepeatWithoutInventingProgress() = runTest {
        assertEquals(CampaignProgress(), migrate())
        val before = store.data.first()
        assertEquals(CampaignProgress(), migrate())
        assertEquals(before, store.data.first())
    }

    private class ControlledStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val failNext = AtomicBoolean(false)
        @Volatile var beforeCommit: (suspend () -> Unit)? = null
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { previous ->
                val next = transform(previous)
                if (failNext.getAndSet(false)) throw IOException("Injected write failure")
                beforeCommit?.invoke()
                next
            }
    }
}
