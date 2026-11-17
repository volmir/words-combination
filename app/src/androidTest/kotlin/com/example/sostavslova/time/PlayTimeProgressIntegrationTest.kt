package com.example.sostavslova.time

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.WordIndex
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящие независимые DataStore + отмена ViewModel посреди принятой записи. */
class PlayTimeProgressIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedStarsReachRThenFullAcrossTrackerRestartWithoutHistoricalReplays() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fun store(name: String) = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                    temporary.root.resolve(name).absolutePath.toPath()
                }, scope = scope)
            val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(
                CampaignLevel(1, "картотека", 1, 1, listOf(
                    CampaignTask.T1("count", 1), CampaignTask.T7("letter", 'т', 1)))
            ), contentSha256 = "a".repeat(64))
            val index = WordIndex(listOf("кора", "крот"))
            val progressStore = store("progress.json")
            val time = PlayTimeRepository(store("time.json"))
            val clock = AtomicLong(0)
            var tracker = PlayTimeTracker(time, scope, clock::get)
            val progress = ProgressRepository(progressStore, onCampaignCommitted = { tracker.checkpointProgress(it) })
            progress.migrateCampaign(campaign, index)
            fun open(stars: Int) {
                tracker.setRoute("entry", 1)
                tracker.updateLevel("entry", PlayTimeTarget(campaign, 1, stars))
                tracker.setLevelResumed("entry", true)
                tracker.setActivityResumed(true)
                tracker.setWindowFocused("activity", true)
            }
            open(0)
            clock.set(10)
            progress.submitCampaignAnswer(campaign, 1, "кора", index)
            tracker.awaitSaved()
            assertEquals(10L, time.snapshot(campaign).getValue(1).timeToRMs)
            assertNull(time.snapshot(campaign).getValue(1).timeToFullMs)
            clock.set(15)
            tracker.detachLevel("entry")
            tracker.awaitSaved()
            tracker = PlayTimeTracker(time, scope, clock::get) // new process: no old tick/sequence
            clock.set(1000)
            open(1)
            clock.set(1020)
            progress.submitCampaignAnswer(campaign, 1, "крот", index)
            tracker.awaitSaved()
            val full = time.snapshot(campaign).getValue(1)
            assertEquals(10L, full.timeToRMs)
            assertEquals(35L, full.timeToFullMs)
            val sequence = full.lastSequence
            progress.submitCampaignAnswer(campaign, 1, "крот", index) // repeated answer
            tracker.updateLevel("entry", PlayTimeTarget(campaign, 1, 2)) // repeated rendering
            tracker.awaitSaved()
            assertEquals(sequence, time.snapshot(campaign).getValue(1).lastSequence)
            tracker.detachLevel("entry")
            tracker.awaitSaved()
            tracker = PlayTimeTracker(time, scope, clock::get)
            open(2)
            clock.set(1027)
            tracker.stop()
            tracker.awaitSaved()
            val revisited = time.snapshot(campaign).getValue(1)
            assertEquals(42L, revisited.cumulativeActiveMs)
            assertEquals(full.timeToRMs, revisited.timeToRMs)
            assertEquals(full.timeToFullMs, revisited.timeToFullMs)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test fun exitWaitsForCancelledAnswerCommitThenForTimeCheckpoint() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fun store(name: String) = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                    temporary.root.resolve(name).absolutePath.toPath()
                }, scope = scope)
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val actualProgress = store("progress.json")
            val heldProgress = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = actualProgress.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val result = actualProgress.updateData(transform)
                    committed.complete(Unit)
                    release.await()
                    return result
                }
            }
            val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(
                CampaignLevel(1, "картотека", 1, 1, listOf(CampaignTask.T1("count", 1), CampaignTask.T2("letter", 'к', 1)))
            ), contentSha256 = "a".repeat(64))
            val index = WordIndex(listOf("крот"))
            ProgressRepository(actualProgress).migrateCampaign(campaign, index)
            val clock = AtomicLong(0)
            val time = PlayTimeRepository(store("time.json"))
            val tracker = PlayTimeTracker(time, scope, clock::get)
            val progress = ProgressRepository(heldProgress, onCampaignCommitted = tracker::checkpointProgress)
            tracker.setRoute("entry", 1)
            tracker.updateLevel("entry", PlayTimeTarget(campaign, 1, 0))
            tracker.setLevelResumed("entry", true)
            tracker.setActivityResumed(true)
            tracker.setWindowFocused("activity", true)
            clock.set(100)
            val answer = launch(Dispatchers.Default) {
                progress.submitCampaignAnswer(campaign, 1, "крот", index)
            }
            progress.trackOperation(answer)
            committed.await() // durable progress; callback is still blocked
            tracker.detachLevel("entry")
            tracker.setRoute("map", null)
            answer.cancel() // real ViewModel goes away before edit returns
            clock.set(1000)
            var exitSaved = false
            val exit = launch {
                tracker.setExiting(true)
                progress.awaitSaved()
                tracker.awaitSaved()
                exitSaved = true
            }
            assertFalse(exitSaved)
            release.complete(Unit)
            exit.join()
            assertTrue(exitSaved)
            val saved = time.snapshot(campaign).getValue(1)
            assertEquals(100L, saved.cumulativeActiveMs)
            assertEquals(100L, saved.timeToRMs)
            assertEquals(100L, saved.timeToFullMs)
            assertEquals(2, saved.lastStars)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
