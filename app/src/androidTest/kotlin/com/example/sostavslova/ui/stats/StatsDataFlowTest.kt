package com.example.sostavslova.ui.stats

import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.core.*
import com.example.sostavslova.data.PlayTimeRepository
import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsDataFlowTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun subscribedLocalSnapshotsUpdateWithoutRefreshOrReadSideWrites() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val path = temporaryFolder.newFolder().resolve("time.json").absolutePath.toPath()
        val store = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path })
        val repository = PlayTimeRepository(store)
        val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(CampaignLevel(1, "картотека", 1, 1,
            listOf(CampaignTask.T1("r", 1), CampaignTask.T1("m", 2)))), contentSha256 = "a".repeat(64))
        val basis = CampaignStatisticsAssembler.prepare(campaign, WordIndex(listOf("торт")))
        val names = mapOf(1 to "Разминка")
        try {
            val firstSnapshot = CompletableDeferred<Unit>()
            val observed = async {
                repository.observe(campaign).map { records ->
                    statsUiState(campaign, basis, repository.statistics(campaign, records), records,
                        CampaignProgress(), names)
                }.onEach { if (it.statistics?.totalTimeMs == BigInteger.ZERO) firstSnapshot.complete(Unit) }
                    .filter { it.statistics?.totalTimeMs != BigInteger.ZERO }.first()
            }
            firstSnapshot.await()
            assertTrue(store.data.first().asMap().isEmpty())
            repository.initialize(campaign, 1, 0)
            repository.appendSegment(campaign, 1, 1, 97_385_000, 1)
            val updated = observed.await()
            assertEquals(BigInteger.valueOf(97_385_000), updated.statistics?.totalTimeMs)
            assertEquals(97_385_000L, updated.statistics?.levels?.first()?.toRMs)
            assertNull(updated.statistics?.levels?.first()?.toFullMs)
            assertEquals(LevelTimeStatus.Measured, updated.statistics?.levels?.first()?.timeStatus)
            val saved = store.data.first()
            repeat(3) { repository.observeStatistics(campaign).first() }
            assertEquals(saved, store.data.first())
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
