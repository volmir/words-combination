package com.example.sostavslova.qa

import android.util.Log
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.LevelPlayTime
import com.example.sostavslova.data.PlayTimeStore
import com.example.sostavslova.di.AppContainer
import com.example.sostavslova.time.PlayTimeTarget
import com.example.sostavslova.time.PlayTimeTracker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящие DataStore в TemporaryFolder; пользовательский play_time не используется. */
class QaCampaignStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val containers = mutableListOf<AppContainer>()

    private fun container(name: String) = QaCampaign.container(File(temporary.root, "$name.preferences_pb"))
        .also { containers.add(it) }

    @After fun close() = runTest {
        // Один сбой не должен оставить scope остальных контейнеров живым.
        var failure: Throwable? = null
        containers.forEach {
            try { it.closeQa() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Test fun freshProgressDoesNotReuseAnotherContainersTime() = runTest {
        val old = container("old")
        val campaign = requireNotNull(old.levelsRepository.getCampaign())
        old.playTimeRepository.initialize(campaign, 1, 3)
        val historical = old.playTimeRepository.appendSegment(campaign, 1, 1, 40, 3)
        old.closeQa()

        val fresh = container("fresh")
        val index = fresh.wordRepository.requireIndex()
        fresh.progressRepository.migrateCampaign(campaign, index)
        fresh.playTimeTracker.updateLevel("fresh", PlayTimeTarget(campaign, 1, 0))
        fresh.progressRepository.submitCampaignAnswer(campaign, 1, "кот", index)
        try {
            fresh.awaitSaved()
        } catch (error: Exception) {
            // Только синтетическая запись и числовые инварианты, без ответов/путей.
            val previous = fresh.playTimeRepository.snapshot(campaign).getValue(1)
            Log.e("QaPlayTime", "freshBaseline=0 starsAfter=1 maxStars=3 requiredStars=1 " +
                "previous=$previous sequence=${previous.lastSequence + 1} activeMs=0", error)
            throw error
        }
        val saved = fresh.playTimeRepository.snapshot(campaign).getValue(1)
        assertEquals(1, saved.lastStars)
        assertEquals(1L, saved.lastSequence)
        assertNull(fresh.playTimeTracker.writeFailure.value)

        // Одновременный второй DataStore также должен владеть другим файлом.
        val reopened = container("old")
        assertEquals(historical, reopened.playTimeRepository.snapshot(campaign).getValue(1))
    }

    @Test fun closeDrainsProgressAndTimeAndReopenKeepsBoth() = runTest {
        val first = container("restart")
        val campaign = requireNotNull(first.levelsRepository.getCampaign())
        val index = first.wordRepository.requireIndex()
        first.progressRepository.migrateCampaign(campaign, index)
        first.playTimeTracker.updateLevel("game", PlayTimeTarget(campaign, 1, 0))
        first.progressRepository.submitCampaignAnswer(campaign, 1, "кот", index)
        val progress = first.progressRepository.observeCampaignProgress().first()
        // Ни tracker.awaitSaved, ни container.awaitSaved до close: барьер проверяется здесь.
        first.closeQa()
        assertTrue(first.applicationScope.coroutineContext.job.isCompleted)

        val second = container("restart")
        assertEquals(progress, second.progressRepository.observeCampaignProgress().first())
        val time = second.playTimeRepository.snapshot(campaign).getValue(1)
        assertEquals(CampaignRules.stars(campaign.levels.first(), progress), time.lastStars)
        assertEquals(1L, time.lastSequence)
        assertEquals(0L, time.timeToRMs)
        second.closeQa()
        assertTrue(second.applicationScope.coroutineContext.job.isCompleted)
        // Повторный close после restart безопасен и не запускает барьер в отменённом scope.
        first.closeQa()
    }

    @Test fun failedClosePropagatesTimeFailureAndStillJoinsScope() = runTest {
        val app = container("failed-close")
        val campaign = requireNotNull(app.levelsRepository.getCampaign())
        // Намеренно создаём старую несовместимую запись только внутри TemporaryFolder.
        app.playTimeRepository.initialize(campaign, 1, 3)
        app.playTimeRepository.appendSegment(campaign, 1, 1, 40, 3)
        app.playTimeTracker.updateLevel("fresh", PlayTimeTarget(campaign, 1, 0))
        app.playTimeTracker.checkpointProgress(CampaignProgress(campaignId = campaign.contentSha256,
            levels = mapOf("картотека" to CampaignLevelProgress(completedTaskIds = setOf("riddle1")))))
        var failure: IllegalArgumentException? = null
        try { app.closeQa() } catch (error: IllegalArgumentException) { failure = error }
        assertEquals("Stars cannot decrease within a timing record", failure?.message)
        assertTrue(app.applicationScope.coroutineContext.job.isCompleted)
    }

    @Test fun sharedTimeWithFreshProgressReportsOriginalInvariantFailure() = runTest {
        val app = container("collision")
        val campaign = requireNotNull(app.levelsRepository.getCampaign())
        val repository = app.playTimeRepository
        repository.initialize(campaign, 1, 3)
        repository.appendSegment(campaign, 1, 1, 40, 3)
        var original: Throwable? = null
        val diagnostic = object : PlayTimeStore {
            override suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime =
                repository.initialize(campaign, levelNumber, starsBeforeTracking)

            override suspend fun appendSegment(campaign: Campaign, levelNumber: Int, sequence: Long,
                activeMs: Long, starsAfter: Int): LevelPlayTime {
                val previous = repository.snapshot(campaign).getValue(levelNumber)
                try {
                    return repository.appendSegment(campaign, levelNumber, sequence, activeMs, starsAfter)
                } catch (error: Exception) {
                    original = error
                    Log.e("QaPlayTime", "syntheticCollision baseline=0 level=$levelNumber sequence=$sequence " +
                        "activeMs=$activeMs starsAfter=$starsAfter maxStars=3 requiredStars=1 previous=$previous", error)
                    throw error
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tracker = PlayTimeTracker(diagnostic, scope, { 0L })
            tracker.updateLevel("fresh", PlayTimeTarget(campaign, 1, 0))
            tracker.checkpointProgress(CampaignProgress(
                campaignId = campaign.contentSha256,
                levels = mapOf("картотека" to CampaignLevelProgress(
                    completedTaskIds = setOf("riddle1"))),
            ))
            var barrierFailure: Throwable? = null
            try { tracker.awaitSaved() } catch (error: IllegalArgumentException) { barrierFailure = error }
            assertNotNull(original)
            assertSame(original, barrierFailure)
            assertEquals("Stars cannot decrease within a timing record", barrierFailure?.message)
            assertTrue(requireNotNull(barrierFailure).stackTrace.any {
                it.className.startsWith("com.example.sostavslova.data.PlayTimeRepository") && it.methodName == "invokeSuspend"
            })
            assertEquals("append", tracker.writeFailure.value?.operation)
            assertEquals("IllegalArgumentException", tracker.writeFailure.value?.errorType)
            val unchanged = repository.snapshot(campaign).getValue(1)
            assertEquals(3, unchanged.lastStars)
            assertEquals(1L, unchanged.lastSequence)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
