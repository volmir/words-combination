package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.edit
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.InstallEvidence
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.WelcomeState
import com.example.sostavslova.core.WordIndex
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProgressRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: ProgressRepository
    private val level = Level(1, "торт", answers = 2, words = 3, letter = 'т', letterCount = 2)
    private val game = GameState("торт", listOf("рот", "тор", "тот"), listOf("рот"))

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("progress")
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(directory, "progress.json").absolutePath.toPath()
            })
        repository = ProgressRepository(store)
    }

    @After
    fun tearDown() = runTest {
        scope.cancel()
        scope.coroutineContext.job.join()
        directory.deleteRecursively()
    }

    @Test
    fun emptyStoreHasNoCurrentGameOrLevelProgress() = runTest {
        assertNull(store.data.first()[stringPreferencesKey("currentGame")])
        assertEquals(emptyMap<String, LevelProgress>(), repository.observeLevelProgress().first())
        assertEquals(emptyList<String>(), repository.levelAnswers("торт"))
    }

    @Test
    fun levelAnswersAndStarsSurviveReopeningDataStore() = runTest {
        repository.saveLevelResult(level, listOf("тор", "тот"))
        scope.cancel()
        scope.coroutineContext.job.join()
        openStore()
        assertEquals(listOf("тор", "тот"), repository.levelAnswers(" ТОРТ "))
        assertEquals(2, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun starsNeverDecreaseAndAnswersAccumulateWithoutDuplicates() = runTest {
        repository.saveLevelResult(level, listOf("тор", "тот"))
        val earned = repository.observeLevelProgress().first().getValue("торт")
        repository.saveLevelResult(level, emptyList())
        assertEquals(earned, repository.observeLevelProgress().first().getValue("торт"))
        repository.saveLevelResult(level, listOf("тор", "рот"))
        assertEquals(listOf("тор", "тот", "рот"), repository.levelAnswers("торт"))
        assertEquals(3, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun migrationKeepsLegacyLevelAnswersAndOpeningButExcludesFreeGame() = runTest {
        store.edit {
            it[stringPreferencesKey("currentGame")] = """{"word":"торт","found":["рот"]}"""
            it[stringPreferencesKey("levelProgress")] = """{"торт":{"completed":true}}"""
            it[stringPreferencesKey("levelAnswers")] = """{"торт":["тор"]}"""
        }
        repository.migrateLegacyLevels(listOf(level))
        assertEquals(listOf("тор", "рот"), repository.levelAnswers("торт"))
        assertTrue(repository.observeLevelProgress().first().getValue("торт").completed)
        repository.migrateLegacyLevels(listOf(level))
        assertEquals(listOf("тор", "рот"), repository.levelAnswers("торт"))
        assertNull(store.data.first()[stringPreferencesKey("currentGame")])
        repository.clearAll()
        store.edit { it[stringPreferencesKey("currentGame")] = """{"word":"торт","found":["рот"]}""" }
        repository.migrateLegacyLevels(listOf(level))
        assertTrue(repository.levelAnswers("торт").isEmpty())
    }

    @Test
    fun parallelSavesMergeAnswersAndStarsAtomically() = runTest {
        coroutineScope {
            repeat(12) { i ->
                launch { repository.saveLevelResult(level, listOf(listOf("рот", "тор", "тот")[i % 3])) }
            }
        }
        assertEquals(setOf("рот", "тор", "тот"), repository.levelAnswers("торт").toSet())
        assertEquals(3, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun malformedLegacyMigrationDoesNotEraseExistingLevelProgress() = runTest {
        repository.saveLevelResult(level, listOf("тор", "тот"))
        store.edit { it[stringPreferencesKey("currentGame")] = "broken JSON" }
        assertEquals(2, repository.observeLevelProgress().first().getValue("торт").stars)
        assertEquals(listOf("тор", "тот"), repository.levelAnswers("торт"))
    }
    @Test fun corruptLegacyPayloadIsPreservedWhenMigrationFails() = runTest {
        store.edit { it[stringPreferencesKey("currentGame")] = "broken JSON" }
        val before = store.data.first()
        assertTrue(runCatching { repository.migrateLegacyLevels(listOf(level)) }.isFailure)
        assertEquals(before, store.data.first())
        assertNull(store.data.first()[stringPreferencesKey("levelSessionV1")])
    }

    @Test fun transitionalV1SnapshotMigratesIntoV2BySourceWord() = runTest {
        val source = "рекуперация"
        val legacy = Level(1, source, 1, 2, 'р', 1)
        store.edit {
            it[stringPreferencesKey("levelProgress")] = """{"рекуперация":{"completed":true}}"""
            it[stringPreferencesKey("levelAnswers")] = """{"рекуперация":["река"]}"""
        }
        repository.migrateLegacyLevels(listOf(legacy))
        repository.saveLevelResult(legacy, listOf("пир"))
        val campaign = com.example.sostavslova.core.Campaign(
            listOf(com.example.sostavslova.core.Stage(1, 1, 1)), listOf(
                com.example.sostavslova.core.CampaignLevel(1, source, 1, 1,
                    listOf(com.example.sostavslova.core.CampaignTask.T1("count", 1)))))
        val migrated = repository.migrateCampaign(campaign,
            com.example.sostavslova.core.WordIndex(listOf("река", "пир")))
        assertEquals(listOf("река", "пир"), migrated.levels.getValue(source).found)
        assertEquals(setOf("count"), migrated.levels.getValue(source).completedTaskIds)
        assertEquals(setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT,
            LevelAchievement.COLLECTOR, LevelAchievement.EXPLORER), migrated.levels.getValue(source).achievements)
        assertNull(store.data.first()[stringPreferencesKey("levelSessionV1")])
    }

    @Test
    fun resetCampaignProgressClearsProgressWithoutArchivePreservesWelcomeAndAllowsReplay() = runTest {
        val freshInstallRepo = ProgressRepository(
            dataStore = store,
            installEvidence = { InstallEvidence.FRESH_INSTALL },
            hadLocalStateAtStartup = false,
        )
        assertEquals(WelcomeState.SHOW, freshInstallRepo.resolveWelcomeState())
        assertTrue(freshInstallRepo.markWelcomeDone())

        val campaign = Campaign(
            stages = listOf(Stage(1, 1, 2)),
            levels = listOf(
                CampaignLevel(1, "картотека", 1, 1, listOf(CampaignTask.T1("count1", 1))),
                CampaignLevel(2, "трактор", 1, 1, listOf(CampaignTask.T1("count2", 1))),
            ),
        )
        val index = WordIndex(listOf("крот", "кора"))
        freshInstallRepo.migrateCampaign(campaign, index)
        freshInstallRepo.submitCampaignAnswer(campaign, 1, "крот", index)
        assertEquals(2, freshInstallRepo.observeCampaignProgress().first().highestUnlockedLevel)

        freshInstallRepo.resetCampaignProgress()

        val afterReset = freshInstallRepo.observeCampaignProgress().first()
        assertEquals(CampaignProgress(), afterReset)
        assertEquals(1, afterReset.highestUnlockedLevel)
        assertTrue(afterReset.levels.isEmpty())

        val prefs = store.data.first()
        assertEquals(WelcomeState.DONE.name, prefs[stringPreferencesKey("welcomeStateV1")])
        assertTrue(
            prefs.asMap().keys.none {
                it.name == "campaignProgressV2Archive" || it.name.startsWith("campaignProgressV2Archive.")
            },
        )
        assertEquals(WelcomeState.DONE, freshInstallRepo.resolveWelcomeState())

        // Перезапуск после сброса не показывает приветствие и позволяет играть с уровня 1.
        scope.cancel()
        scope.coroutineContext.job.join()
        openStore()
        val reopened = ProgressRepository(
            dataStore = store,
            installEvidence = { InstallEvidence.FRESH_INSTALL },
            hadLocalStateAtStartup = true,
        )
        assertEquals(WelcomeState.DONE, reopened.resolveWelcomeState())
        val migrated = reopened.migrateCampaign(campaign, index)
        assertEquals(CampaignIdentity.of(campaign), migrated.campaignId)
        assertEquals(1, migrated.highestUnlockedLevel)
        reopened.submitCampaignAnswer(campaign, 1, "крот", index)
        assertEquals(2, reopened.observeCampaignProgress().first().highestUnlockedLevel)

        // В отличие от resetCampaignProgress, clearAll имитирует полную очистку данных и удаляет welcomeState.
        reopened.clearAll()
        assertNull(store.data.first()[stringPreferencesKey("welcomeStateV1")])
    }

    @Test
    fun achievementsAreGrantedWithAnswerAndClearedOnlyByExplicitRestart() = runTest {
        val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(
            CampaignLevel(1, "картотека", 1, 1, listOf(CampaignTask.T1("count", 1)))))
        val index = WordIndex(listOf("крот"))
        val result = repository.submitCampaignAnswerWithTransitions(campaign, 1, "крот", index)
        assertEquals(listOf(1 to LevelAchievement.NO_HINTS, 1 to LevelAchievement.EXPERT,
            1 to LevelAchievement.COLLECTOR, 1 to LevelAchievement.EXPLORER), result.newAchievements)
        val saved = repository.observeCampaignProgress().first().levels.getValue("картотека")
        assertEquals(setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT,
            LevelAchievement.COLLECTOR, LevelAchievement.EXPLORER), saved.achievements)
        assertFalse(saved.hintsUsed)

        val repeated = repository.submitCampaignAnswerWithTransitions(campaign, 1, "крот", index)
        assertTrue(repeated.newAchievements.isEmpty())
        assertEquals(saved, repository.observeCampaignProgress().first().levels.getValue("картотека"))

        repository.resetCampaignProgress()
        assertTrue(repository.observeCampaignProgress().first().levels.isEmpty())
    }

}
