package com.example.sostavslova.ui.time

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
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

class TimeDataFlowTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun subscribedLocalSnapshotsUpdateWithoutRefreshOrReadSideWrites() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val path = temporaryFolder.newFolder().resolve("time.preferences_pb").absolutePath.toPath()
        val store = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path })
        val repository = PlayTimeRepository(store)
        val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(CampaignLevel(1, "картотека", 1, 1,
            listOf(CampaignTask.T1("r", 1), CampaignTask.T1("m", 2)))), contentSha256 = "a".repeat(64))
        try {
            val firstSnapshot = CompletableDeferred<Unit>()
            val observed = async {
                repository.observe(campaign).map { records ->
                    timeUiState(campaign, repository.statistics(campaign, records), records, CampaignProgress())
                }.onEach { if (it.summary?.screenActiveMs == BigInteger.ZERO) firstSnapshot.complete(Unit) }
                    .filter { it.summary?.screenActiveMs != BigInteger.ZERO }.first()
            }
            firstSnapshot.await()
            assertTrue(store.data.first().asMap().isEmpty())
            repository.initialize(campaign, 1, 0)
            repository.appendSegment(campaign, 1, 1, 97_385_000, 1)
            val updated = observed.await()
            assertEquals(BigInteger.valueOf(97_385_000), updated.summary?.screenActiveMs)
            assertEquals(97_385_000L, updated.summary?.levels?.first()?.timeToRMs)
            assertNull(updated.summary?.levels?.first()?.timeToFullMs)
            val saved = store.data.first()
            repeat(3) { repository.observeStatistics(campaign).first() }
            assertEquals(saved, store.data.first())
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
