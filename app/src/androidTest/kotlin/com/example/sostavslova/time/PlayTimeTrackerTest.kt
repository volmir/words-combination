package com.example.sostavslova.time

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.Stage
import com.example.sostavslova.data.LevelPlayTime
import com.example.sostavslova.data.PlayTimeStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Pure Kotlin + virtual scheduler: can also run on the host JVM, without Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayTimeTrackerTest {
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), (1..2).map { number ->
        CampaignLevel(number, if (number == 1) "портал" else "сторона", 1, 1,
            listOf(CampaignTask.T1("one", 1), CampaignTask.T1("two", 2)))
    }, contentSha256 = "a".repeat(64))

    private class FakeStore : PlayTimeStore {
        val records = mutableMapOf<Int, LevelPlayTime>()
        val calls = mutableListOf<Triple<Int, Long, Long>>()
        var initializations = 0
        var fail = false
        var failAfterCommit = false
        var blocked: CompletableDeferred<Unit>? = null
        override suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime {
            if (fail) throw IOException("synthetic disk failure")
            initializations++
            return records.getOrPut(levelNumber) {
                LevelPlayTime(initialized = true, lastStars = starsBeforeTracking,
                    rAccounted = starsBeforeTracking >= 1, fullAccounted = starsBeforeTracking >= 2)
            }
        }

        override suspend fun appendSegment(campaign: Campaign, levelNumber: Int, sequence: Long, activeMs: Long, starsAfter: Int): LevelPlayTime {
            calls.add(Triple(levelNumber, sequence, activeMs))
            blocked?.await()
            if (fail) throw IOException("synthetic disk failure")
            val old = records.getValue(levelNumber)
            if (sequence > old.lastSequence) {
                check(sequence == old.lastSequence + 1)
                check(starsAfter >= old.lastStars)
                val total = old.cumulativeActiveMs + activeMs
                records[levelNumber] = old.copy(cumulativeActiveMs = total, lastSequence = sequence, lastStars = starsAfter,
                    timeToRMs = if (!old.rAccounted && starsAfter >= 1) total else old.timeToRMs,
                    timeToFullMs = if (!old.fullAccounted && starsAfter >= 2) total else old.timeToFullMs,
                    rAccounted = old.rAccounted || starsAfter >= 1, fullAccounted = old.fullAccounted || starsAfter >= 2)
            }
            if (failAfterCommit) { failAfterCommit = false; throw IOException("synthetic ambiguous commit") }
            return records.getValue(levelNumber)
        }
        fun total(level: Int = 1) = records[level]?.cumulativeActiveMs ?: 0L
    }

    private inner class Fixture(val test: TestScope, val store: FakeStore = FakeStore(), scope: CoroutineScope = test.backgroundScope) {
        var clockReads = 0
        val diagnostics = mutableListOf<PlayTimeWriteFailure>()
        val tracker = PlayTimeTracker(store, scope, { clockReads++; test.testScheduler.currentTime }, diagnostics::add)
        fun target(level: Int = 1, stars: Int = 0) = PlayTimeTarget(campaign, level, stars)
        fun open(owner: String = "entry1", level: Int = 1, stars: Int = 0) {
            tracker.setRoute(owner, level)
            tracker.updateLevel(owner, target(level, stars))
            tracker.setLevelResumed(owner, true)
            tracker.setActivityResumed(true)
            tracker.setWindowFocused("activity", true)
        }
    }

    private fun confirmed(stars: Int, level: Int = 1, id: String = CampaignIdentity.of(campaign)) =
        com.example.sostavslova.core.CampaignProgress(campaignId = id, levels = mapOf(
            campaign.levels.single { it.number == level }.word to
                com.example.sostavslova.core.CampaignLevelProgress(completedTaskIds =
                    campaign.levels.single { it.number == level }.tasks.take(stars).map { it.id }.toSet())))

    @Test fun timePageRemainsReadOnlyAcrossLongStayAndReturnToMap() = runTest {
        val f = Fixture(this)
        f.open()
        advanceTimeBy(1234)
        f.tracker.setRoute("time", null)
        f.tracker.awaitSaved()
        val saved = f.store.records.toMap()
        val calls = f.store.calls.toList()
        advanceTimeBy(172_800_000)
        f.tracker.setRoute("time", null)
        f.tracker.setRoute("levels", null)
        f.tracker.awaitSaved()
        assertEquals(1234L, f.store.total())
        assertEquals(saved, f.store.records)
        assertEquals(calls, f.store.calls)
    }

    @Test fun uiRerenderCannotAwardStarsOrMilestones() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.updateLevel("entry1", f.target(stars = 2))
        advanceTimeBy(10)
        f.tracker.stop(); f.tracker.awaitSaved()
        val saved = f.store.records.getValue(1)
        assertEquals(0, saved.lastStars)
        assertNull(saved.timeToRMs)
        assertNull(saved.timeToFullMs)
        assertEquals(20L, saved.cumulativeActiveMs)
    }

    @Test fun rThenReturnThenFullUsesBothPeriodsAndDuplicateEventsDoNothing() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.checkpointProgress(confirmed(1))
        f.tracker.checkpointProgress(confirmed(1))
        advanceTimeBy(5)
        f.tracker.detachLevel("entry1")
        f.tracker.setRoute("map", null)
        advanceTimeBy(1000)
        f.open(stars = 1)
        advanceTimeBy(20)
        f.tracker.checkpointProgress(confirmed(2))
        f.tracker.checkpointProgress(confirmed(2))
        f.tracker.awaitSaved()
        val full = f.store.records.getValue(1)
        assertEquals(10L, full.timeToRMs)
        assertEquals(35L, full.timeToFullMs)
        f.tracker.detachLevel("entry1")
        f.open(stars = 2)
        advanceTimeBy(7)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(42L, f.store.total())
        assertEquals(full.timeToRMs, f.store.records.getValue(1).timeToRMs)
        assertEquals(full.timeToFullMs, f.store.records.getValue(1).timeToFullMs)
    }

    @Test fun otherCampaignCommitCannotAwardMilestone() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.checkpointProgress(confirmed(2, id = CampaignIdentity.PREFIX + "b".repeat(64)))
        f.tracker.stop(); f.tracker.awaitSaved()
        assertNull(f.store.records.getValue(1).timeToRMs)
        assertEquals(0, f.store.records.getValue(1).lastStars)
    }

    @Test fun resumedPauseResumeAndDuplicateStopsExcludeBriefBackground() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(125)
        f.tracker.setActivityResumed(false) // PAUSE
        f.tracker.setActivityResumed(false) // STOP
        f.tracker.stop(); f.tracker.stop() // dispose and duplicate PAUSE
        advanceTimeBy(400)
        f.tracker.setActivityResumed(true)
        f.tracker.setActivityResumed(true)
        advanceTimeBy(75)
        f.tracker.setActivityResumed(false)
        f.tracker.awaitSaved()
        assertEquals(200L, f.store.total())
        assertEquals(listOf(125L, 75L), f.store.calls.map { it.third })
    }

    @Test fun loadingErrorLockedUnknownAndWrongRouteDoNotCount() = runTest {
        val f = Fixture(this)
        f.tracker.setActivityResumed(true); f.tracker.setWindowFocused("activity", true)
        f.tracker.setRoute("entry1", 2)
        f.tracker.setLevelResumed("entry1", true)
        f.tracker.updateLevel("entry1", null) // loading/error/locked/unknown
        advanceTimeBy(100)
        f.tracker.updateLevel("entry1", f.target(1)) // successfully loaded WRONG level
        advanceTimeBy(100)
        f.tracker.setRoute("other-entry", 1) // same number but wrong back-stack entry
        advanceTimeBy(100)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(0L, f.store.total())
        assertTrue(f.store.calls.isEmpty())
    }

    @Test fun routeChangesStopAtNavigationAndOldDisposeCannotStopNewLevel() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.setRoute("map", null)
        advanceTimeBy(100)
        f.tracker.setRoute("rules", null)
        advanceTimeBy(100)
        f.tracker.setRoute("future-statistics", null)
        advanceTimeBy(100)
        f.open("entry2", 2)
        f.tracker.detachLevel("entry1")
        advanceTimeBy(20)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(10L, f.store.total(1))
        assertEquals(20L, f.store.total(2))
    }

    @Test fun recreationAndRotationUseNewTickAndSingleInitialization() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.setLevelResumed("entry1", false)
        f.tracker.setActivityResumed(false)
        f.tracker.detachLevel("entry1")
        f.tracker.setRoute(null, null)
        advanceTimeBy(1000)
        f.open() // same retained ViewModel/back-stack id, a new composition
        advanceTimeBy(15)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(25L, f.store.total())
        assertEquals(1, f.store.initializations)
    }

    @Test fun drawerStopsButHintDialogFocusContinuesAndExternalFocusLossStops() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.setDrawerOpen(true)
        advanceTimeBy(100)
        f.tracker.setDrawerOpen(false)
        advanceTimeBy(10)
        f.tracker.setWindowFocused("hint-dialog", true)
        f.tracker.setWindowFocused("activity", false)
        advanceTimeBy(50)
        f.tracker.setWindowFocused("hint-dialog", false) // another app/notification shade
        advanceTimeBy(500)
        f.tracker.setWindowFocused("hint-dialog", true)
        advanceTimeBy(10)
        f.tracker.setWindowFocused("activity", true)
        f.tracker.setWindowFocused("hint-dialog", false)
        advanceTimeBy(10)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(90L, f.store.total())
    }

    @Test fun screenLockAndActivityFinishStopDespiteDialogFocus() = runTest {
        val f = Fixture(this); f.open()
        f.tracker.setWindowFocused("dialog", true)
        advanceTimeBy(10)
        f.tracker.setActivityResumed(false)
        advanceTimeBy(1000)
        f.tracker.setActivityResumed(false)
        f.tracker.detachLevel("entry1")
        f.tracker.awaitSaved()
        assertEquals(10L, f.store.total())
    }

    @Test fun sixtySecondCheckpointHasNoOneHzPollingOrUiTicks() = runTest {
        val f = Fixture(this); f.open(); runCurrent()
        assertEquals(1, f.clockReads)
        assertTrue(f.store.calls.isEmpty())
        repeat(59) {
            advanceTimeBy(1000); runCurrent()
            assertEquals(1, f.clockReads) // neither clock reads nor repository appends each second
            assertTrue(f.store.calls.isEmpty())
        }
        advanceTimeBy(1000); runCurrent()
        assertEquals(60_000L, f.store.total())
        assertEquals(2, f.clockReads)
        advanceTimeBy(70)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(60_070L, f.store.total())
        advanceTimeBy(180_000); runCurrent()
        assertEquals(2, f.store.calls.size)
        assertNull(f.tracker.writeFailure.value)
    }

    @Test fun starCheckpointsContinueAndFinaleStopsWithFinalStars() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.tracker.checkpointProgress(confirmed(1))
        advanceTimeBy(20)
        f.tracker.checkpointProgress(confirmed(2))
        f.tracker.updateLevel("entry1", f.target(stars = 2), finished = true)
        advanceTimeBy(1000)
        f.tracker.setActivityResumed(true) // final screen remains stopped
        f.tracker.stop(); f.tracker.awaitSaved()
        val record = f.store.records.getValue(1)
        assertEquals(30L, record.cumulativeActiveMs)
        assertEquals(10L, record.timeToRMs)
        assertEquals(30L, record.timeToFullMs)
    }

    @Test fun starSnapshotAfterNavigationDoesNotRaceOrChangeOtherRunningLevel() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        f.open("entry2", 2)
        f.tracker.detachLevel("entry1")
        advanceTimeBy(10)
        f.tracker.checkpointProgress(confirmed(1)) // accepted answer's durable callback arrives late
        advanceTimeBy(10)
        f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(10L, f.store.total(1))
        assertEquals(10L, f.store.records.getValue(1).timeToRMs)
        assertEquals(20L, f.store.total(2))
    }

    @Test fun processDeathRestartsSequenceFromDiskWithoutCarryingStartTick() = runTest {
        val store = FakeStore()
        val process = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val f = Fixture(this, store, process); f.open()
        advanceTimeBy(60_000); runCurrent()
        advanceTimeBy(1000) // this unflushed second is lost on process death, not reported as saved
        process.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        val next = Fixture(this, store); next.open()
        advanceTimeBy(20)
        next.tracker.stop(); next.tracker.awaitSaved()
        assertEquals(60_020L, store.total())
        assertEquals(2L, store.records.getValue(1).lastSequence)
    }

    @Test fun storageFailureIsDiagnosticAndExitBarrierFailsUntilRetry() = runTest {
        val f = Fixture(this); f.open(); runCurrent()
        f.store.fail = true
        advanceTimeBy(10); f.tracker.stop(); runCurrent()
        assertEquals(0L, f.store.total())
        assertEquals(PlayTimeWriteFailure(1, "append", 10, "IOException"), f.tracker.writeFailure.value)
        try { f.tracker.awaitSaved(); fail("Must not claim a failed write was saved") } catch (_: IOException) { }
        f.store.fail = false
        f.tracker.awaitSaved()
        assertEquals(10L, f.store.total())
        assertEquals(listOf(1L, 1L, 1L), f.store.calls.map { it.second })
        assertNull(f.tracker.writeFailure.value)
        assertTrue(f.diagnostics.isNotEmpty())
    }

    @Test fun initializeFailureKeepsBaselineAndSegmentsInOrder() = runTest {
        val f = Fixture(this); f.store.fail = true; f.open(stars = 1)
        advanceTimeBy(10); f.tracker.stop(); runCurrent()
        assertEquals("initialize", f.tracker.writeFailure.value?.operation)
        assertEquals(10L, f.tracker.writeFailure.value?.pendingActiveMs)
        f.store.fail = false
        f.tracker.awaitSaved()
        assertEquals(10L, f.store.total())
        assertNull(f.store.records.getValue(1).timeToRMs) // historical star remains unknown
    }

    @Test fun ambiguousCommitRetriesSameSequenceWithoutDoubleCounting() = runTest {
        val f = Fixture(this); f.open(); runCurrent()
        f.store.failAfterCommit = true
        advanceTimeBy(10); f.tracker.stop(); runCurrent()
        assertNotNull(f.tracker.writeFailure.value)
        f.tracker.awaitSaved()
        assertEquals(10L, f.store.total())
        assertEquals(listOf(1L, 1L), f.store.calls.map { it.second })
    }

    @Test fun slowWritesSurviveDisposeAndBarrierWaitsForAllAcceptedSegments() = runTest {
        val f = Fixture(this); f.open(); runCurrent()
        val disk = CompletableDeferred<Unit>(); f.store.blocked = disk
        advanceTimeBy(10); f.tracker.setActivityResumed(false); runCurrent()
        f.tracker.setActivityResumed(true)
        advanceTimeBy(20); f.tracker.detachLevel("entry1")
        var saved = false
        val exit = launch { f.tracker.awaitSaved(); saved = true }
        runCurrent()
        assertFalse(saved)
        disk.complete(Unit)
        exit.join()
        assertTrue(saved)
        assertEquals(30L, f.store.total())
        assertEquals(listOf(1L, 2L), f.store.calls.map { it.second })
    }

    @Test fun explicitExitCannotBeRestartedByRepeatedResume() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10); f.tracker.setExiting(true)
        f.tracker.setActivityResumed(true)
        advanceTimeBy(100)
        f.tracker.awaitSaved()
        assertEquals(10L, f.store.total())
        f.tracker.setExiting(false)
        advanceTimeBy(10); f.tracker.stop(); f.tracker.awaitSaved()
        assertEquals(20L, f.store.total())
    }

    @Test fun fakeAndroidLifecyclePauseStopResumeDestroyAndDisposeAreIdempotent() = runTest {
        val f = Fixture(this); f.open()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val lifecycle = owner.lifecycle
        val dispose = observePlayTimeLifecycle(lifecycle, f.tracker::setActivityResumed)
        advanceTimeBy(100) // CREATED/STARTED do not count
        lifecycle.currentState = Lifecycle.State.RESUMED
        advanceTimeBy(10)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        advanceTimeBy(500)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        advanceTimeBy(20)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        dispose()
        f.tracker.awaitSaved()
        assertEquals(30L, f.store.total())
    }

    @Test fun committedProgressOutlivesScreenAndStaleUiCannotRevertStars() = runTest {
        val f = Fixture(this); f.open()
        advanceTimeBy(10)
        val progress = com.example.sostavslova.core.CampaignProgress(
            campaignId = CampaignIdentity.of(campaign),
            levels = mapOf("портал" to com.example.sostavslova.core.CampaignLevelProgress(completedTaskIds = setOf("one"))))
        f.tracker.checkpointProgress(progress)
        f.tracker.updateLevel("entry1", f.target(stars = 0)) // stale rendering after durable commit
        advanceTimeBy(10)
        f.tracker.detachLevel("entry1")
        f.tracker.checkpointProgress(progress.copy(levels = mapOf("портал" to
            com.example.sostavslova.core.CampaignLevelProgress(completedTaskIds = setOf("one", "two")))))
        f.tracker.awaitSaved()
        assertEquals(20L, f.store.total())
        assertEquals(10L, f.store.records.getValue(1).timeToRMs)
        assertEquals(20L, f.store.records.getValue(1).timeToFullMs)
    }
}
