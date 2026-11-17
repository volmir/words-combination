package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.data.jsonstore.edit
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import com.example.sostavslova.core.CampaignRules
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящий Preferences DataStore: транзакции, сбои формата и повторное открытие. */
class PlayTimeRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: PlayTimeRepository
    private val campaign = CampaignJson.decode(miniCampaignJson).copy(contentSha256 = "a".repeat(64))
    private val key = stringPreferencesKey("playTimeV1:${campaign.contentSha256}:1:картотека")

    @Before fun setUp() {
        file = File(temporaryFolder.newFolder(), "time.json")
        open()
    }

    private fun open() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() })
        repository = PlayTimeRepository(store)
    }

    private suspend fun close() {
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    @After fun tearDown() = runTest { close() }

    private suspend fun rejected(block: suspend () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (error: Exception) { failure = error }
        assertNotNull("Expected rejected operation", failure)
    }

    @Test fun firstLaunchHasZeroTimesAndDoesNotWriteOrInventRecords() = runTest {
        assertEquals(mapOf(1 to LevelPlayTime(), 2 to LevelPlayTime()), repository.snapshot(campaign))
        assertTrue(store.data.first().asMap().isEmpty())
        rejected { repository.appendSegment(campaign, 1, 1, 10, 0) }
        val initial = repository.initialize(campaign, 1, 0)
        assertTrue(initial.initialized)
        assertFalse(initial.rAccounted)
        assertNull(initial.timeToRMs)
    }

    @Test fun segmentsAccumulateAndThresholdsAreCapturedOnlyOnceIncludingSimultaneousThresholds() = runTest {
        repository.initialize(campaign, 1, 0)
        repository.appendSegment(campaign, 1, 1, 30, 1)
        val r = repository.appendSegment(campaign, 1, 2, 20, 2)
        assertEquals(50L, r.timeToRMs)
        assertNull(r.timeToFullMs)
        val full = repository.appendSegment(campaign, 1, 3, 40, 5)
        assertEquals(50L, full.timeToRMs)
        assertEquals(90L, full.timeToFullMs)
        val later = repository.appendSegment(campaign, 1, 4, 10, 5)
        assertEquals(100L, later.cumulativeActiveMs)
        assertEquals(90L, later.timeToFullMs)
        repository.initialize(campaign, 2, 0)
        val both = repository.appendSegment(campaign, 2, 1, 42, 2)
        assertEquals(42L, both.timeToRMs)
        assertEquals(42L, both.timeToFullMs)
        assertEquals(repository.snapshot(campaign), repository.observe(campaign).first())
    }

    @Test fun historicalStarsNeverBecomeZeroTimeRecords() = runTest {
        repository.initialize(campaign, 1, 2)
        // Повторная инициализация не подменяет исторический baseline.
        repository.initialize(campaign, 1, 0)
        val result = repository.appendSegment(campaign, 1, 1, 120, 5)
        assertTrue(result.rAccounted)
        assertNull(result.timeToRMs)
        assertEquals(120L, result.timeToFullMs)
        repository.initialize(campaign, 2, 2)
        val historicalFull = repository.appendSegment(campaign, 2, 1, 0, 2)
        assertTrue(historicalFull.fullAccounted)
        assertNull(historicalFull.timeToRMs)
        assertNull(historicalFull.timeToFullMs)
    }

    @Test fun restoreAfterMissedCommitDoesNotInventHistoricalMilestones() = runTest {
        repository.initialize(campaign, 1, 0)
        repository.appendSegment(campaign, 1, 1, 10, 0)
        close()
        open()
        val restored = repository.initialize(campaign, 1, 2)
        assertTrue(restored.rAccounted)
        assertNull(restored.timeToRMs)
        assertEquals(10L, restored.cumulativeActiveMs)
        val full = repository.appendSegment(campaign, 1, 2, 20, 5)
        assertNull(full.timeToRMs)
        assertEquals(30L, full.timeToFullMs)
        val before = store.data.first()
        repository.initialize(campaign, 1, 5)
        repository.snapshot(campaign)
        repository.observeStatistics(campaign).first()
        assertEquals(before, store.data.first())
        assertEquals(full, repository.snapshot(campaign).getValue(1))
    }

    @Test fun statisticsReadIsPureAndCampaignScoped() = runTest {
        val readOnly = PlayTimeRepository(object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Statistics reading must never invoke a write")
        })
        val empty = store.data.first()
        assertEquals(0, readOnly.observeStatistics(campaign).first().measuredLevelCount)
        assertEquals(empty, store.data.first())
        repository.initialize(campaign, 1, 0)
        assertEquals(0, readOnly.observeStatistics(campaign).first().measuredLevelCount)
        repository.appendSegment(campaign, 1, 1, 80, 2)
        val before = store.data.first()
        val stats = readOnly.observeStatistics(campaign).first()
        assertEquals(java.math.BigInteger.valueOf(80), stats.screenActiveMs)
        assertEquals(80L, stats.levels.first().timeToRMs)
        assertEquals(0, readOnly.observeStatistics(campaign.copy(contentSha256 = "b".repeat(64))).first().measuredLevelCount)
        assertEquals(before, store.data.first())
    }

    @Test fun zeroAllowedButNegativeOverflowAndInvalidOrderDoNotChangeDisk() = runTest {
        repository.initialize(campaign, 1, 0)
        assertEquals(0L, repository.appendSegment(campaign, 1, 1, 0, 0).cumulativeActiveMs)
        val before = store.data.first()
        rejected { repository.appendSegment(campaign, 1, 2, -1, 0) }
        rejected { repository.appendSegment(campaign, 1, 0, 1, 0) }
        rejected { repository.appendSegment(campaign, 1, 3, 1, 0) }
        rejected { repository.appendSegment(campaign, 1, 2, 1, 6) }
        assertEquals(before, store.data.first())
        repository.appendSegment(campaign, 1, 2, Long.MAX_VALUE, 2)
        val maximum = store.data.first()
        rejected { repository.appendSegment(campaign, 1, 3, 1, 5) }
        rejected { repository.appendSegment(campaign, 1, 3, 0, 1) }
        assertEquals(maximum, store.data.first())
        assertEquals(Long.MAX_VALUE, repository.snapshot(campaign).getValue(1).timeToRMs)
        assertEquals(0L, repository.initialize(campaign, 2, 0).cumulativeActiveMs)
        assertEquals(0L, repository.appendSegment(campaign, 2, 1, 0, 2).timeToFullMs)
    }

    @Test fun committedDuplicateSurvivesRestartAndOlderRetriesAreIgnored() = runTest {
        repository.initialize(campaign, 1, 0)
        repository.appendSegment(campaign, 1, 1, 70, 2)
        close()
        open()
        assertEquals(70L, repository.snapshot(campaign).getValue(1).cumulativeActiveMs)
        assertEquals(70L, repository.appendSegment(campaign, 1, 1, 70, 2).cumulativeActiveMs)
        repository.appendSegment(campaign, 1, 2, 30, 5)
        val saved = repository.snapshot(campaign)
        repository.appendSegment(campaign, 1, 1, 70, 2)
        assertEquals(saved, repository.snapshot(campaign))
    }

    @Test fun concurrentRetriesAndDistinctLevelsSerializeAcrossRepositoryInstances() = runTest {
        repository.initialize(campaign, 1, 0)
        repository.initialize(campaign, 2, 0)
        val second = PlayTimeRepository(store)
        coroutineScope {
            (1..30).map { n -> async(Dispatchers.Default) {
                if (n % 2 == 0) repository.appendSegment(campaign, 1, 1, 10, 2)
                else second.appendSegment(campaign, 2, 1, 20, 2)
            } }.awaitAll()
        }
        assertEquals(10L, repository.snapshot(campaign).getValue(1).cumulativeActiveMs)
        assertEquals(20L, repository.snapshot(campaign).getValue(2).cumulativeActiveMs)
        // Разные последовательные события не теряются.
        repeat(20) { n -> repository.appendSegment(campaign, 1, n.toLong() + 2, 1, 2) }
        assertEquals(30L, repository.snapshot(campaign).getValue(1).cumulativeActiveMs)
    }

    @Test fun campaignHashNumberAndWordIsolateRecordsAndUnidentifiedCampaignIsRejected() = runTest {
        repository.initialize(campaign, 1, 0)
        repository.appendSegment(campaign, 1, 1, 55, 2)
        val imported = campaign.copy(contentSha256 = "b".repeat(64))
        assertEquals(LevelPlayTime(), repository.snapshot(imported).getValue(1))
        repository.initialize(imported, 1, 0)
        repository.appendSegment(imported, 1, 1, 7, 1)
        val renamed = campaign.copy(levels = campaign.levels.map { it.copy(word = it.word + "а") })
        assertEquals(LevelPlayTime(), repository.snapshot(renamed).getValue(1))
        val renumbered = campaign.copy(levels = campaign.levels.map { it.copy(number = it.number + 2) })
        assertEquals(LevelPlayTime(), repository.snapshot(renumbered).getValue(3))
        assertEquals(55L, repository.snapshot(campaign).getValue(1).cumulativeActiveMs)
        rejected { repository.snapshot(campaign.copy(contentSha256 = null)) }
        rejected { repository.snapshot(campaign.copy(contentSha256 = "invalid")) }
        rejected { repository.initialize(campaign, 99, 0) }
    }

    @Test fun malformedJsonInvalidValuesAndFutureSchemaFailClosedWithoutOverwriting() = runTest {
        repository.initialize(campaign, 1, 0)
        val valid = requireNotNull(store.data.first()[key])
        for (bad in listOf("{", "null", "{}", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"cumulativeActiveMs\":0", "\"cumulativeActiveMs\":-1"),
            valid.replace("\"cumulativeActiveMs\":0", "\"cumulativeActiveMs\":9223372036854775808"),
            valid.replace("\"timeToRMs\":null", "\"timeToRMs\":0"))) {
            store.edit { it[key] = bad }
            rejected { repository.snapshot(campaign) }
            rejected { repository.initialize(campaign, 1, 0) }
            rejected { repository.appendSegment(campaign, 1, 1, 10, 2) }
            assertEquals(bad, store.data.first()[key])
        }
        repository.clearTimeStatistics()
        assertEquals(LevelPlayTime(), repository.snapshot(campaign).getValue(1))
    }

    @Test fun repeatedSnapshotsSeeOtherWriterCampaignValidationCorruptionAndClear() = runTest {
        repository.initialize(campaign, 1, 0)
        val initial = repository.snapshot(campaign)
        assertEquals(initial, repository.snapshot(campaign))
        val other = PlayTimeRepository(store)
        val updated = other.appendSegment(campaign, 1, 1, 25, 2)
        assertEquals(updated, repository.snapshot(campaign).getValue(1))
        // Даже при том же SHA изменённые пороги требуют новой проверки записи.
        val differentRules = campaign.copy(levels = campaign.levels.map {
            if (it.number == 1) it.copy(requiredStars = 3) else it
        })
        rejected { repository.snapshot(differentRules) }
        val raw = requireNotNull(store.data.first()[key])
        store.edit { it[key] = "broken" }
        rejected { repository.snapshot(campaign) }
        store.edit { it[key] = raw }
        assertEquals(updated, repository.snapshot(campaign).getValue(1))
        other.clearTimeStatistics()
        assertEquals(mapOf(1 to LevelPlayTime(), 2 to LevelPlayTime()), repository.snapshot(campaign))
    }

    @Test fun damagedPreferencesFileFailsClosedWithoutReplacingIt() = runTest {
        close()
        val bytes = byteArrayOf(-1, -1, -1, 0, 42)
        file.writeBytes(bytes)
        open()
        rejected { repository.snapshot(campaign) }
        rejected { repository.initialize(campaign, 1, 0) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun timingAndExplicitClearLeaveLegacyWordsStarsHintsAndCampaignProgressUntouched() = runTest {
        val progressFile = File(temporaryFolder.newFolder(), "progress.json")
        val progressStore = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { progressFile.absolutePath.toPath() })
        val raw = """{"version":2,"levels":{"картотека":{"found":["карта"],"completedTaskIds":["count","initial"],"hints":{"shownSteps":1,"paidUnits":1,"ownedContentVersion":1}}}}"""
        progressStore.edit {
            it[stringPreferencesKey("campaignProgressV2")] = raw
            it[stringPreferencesKey("levelAnswers")] = """{"картотека":["карта"]}"""
            it[stringPreferencesKey("levelProgress")] = """{"картотека":{"completed":true}}"""
        }
        val progress = ProgressRepository(progressStore)
        val baseline = progress.observeCampaignProgress().first()
        val bytes = progressFile.readBytes()
        assertEquals(1, baseline.levels.getValue("картотека").hints.paidUnits)
        assertEquals(2, CampaignRules.stars(campaign.levels.first(), baseline))
        repository.initialize(campaign, 1, 2)
        repository.appendSegment(campaign, 1, 1, 100, 5)
        repository.clearTimeStatistics()
        progress.awaitSaved()
        assertEquals(baseline, progress.observeCampaignProgress().first())
        assertEquals(raw, progressStore.data.first()[stringPreferencesKey("campaignProgressV2")])
        assertArrayEquals(bytes, progressFile.readBytes())
        // Существующий сброс прогресса также не управляет независимым временем.
        repository.initialize(campaign, 1, 0)
        repository.appendSegment(campaign, 1, 1, 9, 0)
        progress.clearAll()
        assertEquals(9L, repository.snapshot(campaign).getValue(1).cumulativeActiveMs)
    }
}
