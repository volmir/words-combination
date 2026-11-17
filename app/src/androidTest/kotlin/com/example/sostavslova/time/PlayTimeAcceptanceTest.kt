package com.example.sostavslova.time

import android.util.Log
import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.Preferences
import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.data.LevelPlayTime
import com.example.sostavslova.data.PlayTimeStore
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host JVM reads the same data/ resources; device reads installed assets. No campaign import. */
class PlayTimeAcceptanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun text(name: String): String =
        javaClass.classLoader?.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.assets.open(name)
                .bufferedReader().use { it.readText() }

    /** Wall time, independent of runTest's virtual clock; only phase names and numbers. */
    private class PhaseTimings {
        private data class Sample(var count: Int = 0, var nanos: Long = 0, var maxNanos: Long = 0)
        private val started = System.nanoTime()
        private val samples = linkedMapOf<String, Sample>()

        inline fun <T> measure(phase: String, block: () -> T): T {
            val start = System.nanoTime()
            try { return block() }
            finally { record(phase, System.nanoTime() - start) }
        }

        @Synchronized fun record(phase: String, nanos: Long) {
            val sample = samples.getOrPut(phase) { Sample() }
            sample.count++
            sample.nanos += nanos
            sample.maxNanos = maxOf(sample.maxNanos, nanos)
        }

        @Synchronized fun report(checkpoint: String) {
            Log.i("G08Timing", "checkpoint=$checkpoint elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            samples.forEach { (phase, sample) ->
                Log.i("G08Timing", "phase=$phase count=${sample.count} totalMs=${sample.nanos / 1_000_000} maxMs=${sample.maxNanos / 1_000_000}")
            }
        }
    }

    private inner class Process(val timings: PhaseTimings = PhaseTimings()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun store(name: String): DataStore<Preferences> {
            val delegate = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                    temporary.root.resolve(name).absolutePath.toPath()
                })
            val phase = name.substringBefore('.')
            return object : DataStore<Preferences> by delegate {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    timings.measure("${phase}_transaction") {
                        delegate.updateData { preferences ->
                            timings.measure("${phase}_transform") { transform(preferences) }
                        }
                    }
            }
        }
        val clock = AtomicLong(0)
        val time = PlayTimeRepository(store("time.json"))
        val tracker = PlayTimeTracker(object : PlayTimeStore {
            override suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime =
                timings.measure("time_initialize") { time.initialize(campaign, levelNumber, starsBeforeTracking) }
            override suspend fun appendSegment(campaign: Campaign, levelNumber: Int, sequence: Long, activeMs: Long, starsAfter: Int): LevelPlayTime =
                timings.measure("time_append") { time.appendSegment(campaign, levelNumber, sequence, activeMs, starsAfter) }
        }, scope, clock::get)
        val progressStore = store("progress.json")
        val progress = ProgressRepository(progressStore,
            onCampaignCommitted = tracker::checkpointProgress)
        fun open(campaign: Campaign, level: CampaignLevel, stars: Int) {
            tracker.setRoute("entry", level.number)
            tracker.updateLevel("entry", PlayTimeTarget(campaign, level.number, stars))
            tracker.setLevelResumed("entry", true)
            tracker.setActivityResumed(true)
            tracker.setWindowFocused("activity", true)
        }
        suspend fun close() = timings.measure("process_close") { scope.cancel(); scope.coroutineContext.job.join() }
        suspend fun awaitTime() = timings.measure("actor_barrier") { tracker.awaitSaved() }
        suspend fun exit() {
            tracker.setExiting(true)
            timings.measure("progress_barrier") { progress.awaitSaved() }
            awaitTime()
        }
    }

    @Test fun installedCampaignRThenRevisitsTo49StarsHasExactLevelStageAndCampaignTotals() = runTest {
        // Real DataStore IO: avoid routing every reply through the virtual-time event loop.
        withContext(Dispatchers.Default) {
            val timings = PhaseTimings()
            val source = timings.measure("campaign_read") { text("campaign.json") }
            val sha = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val campaign = CampaignJson.decode(source).copy(contentSha256 = sha)
            val index = timings.measure("word_list") { WordIndex.fromText(text("words.txt")) }
            assertEquals(15, campaign.levels.size)
            assertEquals(6, campaign.stages.size)
            assertEquals(49, campaign.levels.sumOf { it.maxStars })
            var process = Process(timings)
            val totals = campaign.levels.associate { it.number to 0L }.toMutableMap()
            val toR = mutableMapOf<Int, Long>()
            val toM = mutableMapOf<Int, Long>()
            try {
                timings.measure("migration") { process.progress.migrateCampaign(campaign, index) }
                // Obtain witnesses through TaskRules, including T4/T6/T7; never inject stars.
                val answers = timings.measure("witnesses") {
                    campaign.levels.associate { level ->
                        val initial = CurrentCampaignLevel(level, GameState.start(level.word, index))
                        level.number to level.tasks.flatMap { task ->
                            val single = initial.copy(level = level.copy(tasks = listOf(task), requiredStars = 1))
                            val target = TaskRules.evaluate(single, campaign.countMinLength).progress.single().target
                            initial.game.allWords.sorted().filter { word ->
                                TaskRules.submit(single, word, campaign.countMinLength, index)
                                    .evaluation.progress.single().current > 0
                            }.take(target)
                        }.distinct()
                    }
                }
                timings.report("witnesses_ready")
                suspend fun saved() = timings.measure("progress_read") { process.progress.observeCampaignProgress().first() }
                suspend fun timeSnapshot() = timings.measure("time_read") { process.time.snapshot(campaign) }
                fun active(level: CampaignLevel, ms: Long) {
                    process.clock.addAndGet(ms)
                    totals[level.number] = totals.getValue(level.number) + ms
                }
                suspend fun play(level: CampaignLevel, full: Boolean) = timings.measure("play_level") {
                    process.open(campaign, level, CampaignRules.stars(level, saved()))
                    // Inline found words and hint dialog belong to ONE active game interval.
                    active(level, 1000)
                    process.tracker.setWindowFocused("hint", true)
                    process.tracker.setWindowFocused("activity", false)
                    timings.measure("hint") { process.progress.requestCampaignHint(campaign, level.number,
                        saved().levels[level.word]?.hints?.shownSteps ?: 0, index) }
                    active(level, 2000)
                    process.tracker.setWindowFocused("activity", true)
                    process.tracker.setWindowFocused("hint", false)
                    for (word in answers.getValue(level.number)) {
                        val before = saved()
                        val stars = CampaignRules.stars(level, before)
                        if (stars >= if (full) level.maxStars else level.requiredStars) break
                        if (word in before.levels[level.word]?.found.orEmpty()) continue
                        active(level, 1000)
                        val result = timings.measure("answer") { process.progress.submitCampaignAnswer(campaign, level.number, word, index) }
                        assertEquals(AnswerResult.CORRECT, result.result)
                        val after = result.evaluation.stars
                        if (stars < level.requiredStars && after >= level.requiredStars) toR[level.number] = totals.getValue(level.number)
                        if (stars < level.maxStars && after >= level.maxStars) toM[level.number] = totals.getValue(level.number)
                        // Duplicate answer + UI render must not create a second milestone/segment.
                        process.awaitTime()
                        val snapshot = timeSnapshot()
                        timings.measure("duplicate") { process.progress.submitCampaignAnswer(campaign, level.number, word, index) }
                        process.tracker.updateLevel("entry", PlayTimeTarget(campaign, level.number, after))
                        process.awaitTime()
                        assertEquals(snapshot, timeSnapshot())
                    }
                    val completed = saved()
                    assertTrue(CampaignRules.stars(level, completed) >= if (full) level.maxStars else level.requiredStars)
                    assertEquals(if (full) campaign.levels.size else minOf(level.number + 1, campaign.levels.size),
                        completed.highestUnlockedLevel)
                    process.tracker.setDrawerOpen(true)
                    process.clock.addAndGet(50_000) // menu time excluded
                    process.tracker.setRoute("time", null)
                    process.tracker.detachLevel("entry")
                    process.tracker.setDrawerOpen(false)
                    process.awaitTime()
                    assertEquals(totals.getValue(level.number), timeSnapshot().getValue(level.number).cumulativeActiveMs)
                }
                timings.measure("pass_R") {
                    campaign.levels.forEach { play(it, false); Log.i("G08Timing", "checkpoint=R_${it.number}") }
                }
                assertTrue(saved().finaleReached)
                assertTrue(campaign.levels.any { CampaignRules.stars(it, saved()) < it.maxStars })
                // Cold start with actual disk files and a NEW monotonic epoch.
                process.exit()
                val atR = saved()
                val timeAtR = timeSnapshot()
                process.close()
                process = Process(timings)
                assertEquals(atR, process.progress.migrateCampaign(campaign, index))
                assertEquals(timeAtR, timeSnapshot())
                timings.measure("pass_M") {
                    campaign.levels.forEach { play(it, true); Log.i("G08Timing", "checkpoint=M_${it.number}") }
                }
                assertEquals(49, CampaignRules.summary(campaign, saved()).stars)
                // Replay after M keeps milestones and contributes to total time.
                val first = campaign.levels.first()
                process.open(campaign, first, first.maxStars)
                active(first, 7000)
                process.exit()
                timings.measure("final_checks") {
                    val records = timeSnapshot()
                    campaign.levels.forEach { level ->
                        val record = records.getValue(level.number)
                        assertEquals(totals.getValue(level.number), record.cumulativeActiveMs)
                        assertEquals(toR.getValue(level.number), record.timeToRMs)
                        assertEquals(toM.getValue(level.number), record.timeToFullMs)
                    }
                    val summary = process.time.observeStatistics(campaign).first()
                    val total = totals.values.sum()
                    assertEquals(BigInteger.valueOf(total), summary.screenActiveMs)
                    assertEquals(total / 15.0, summary.meanLevelActiveMs)
                    assertEquals(totals.values.sorted()[7].toDouble(), summary.medianLevelActiveMs)
                    summary.stages.forEach { stage ->
                        assertEquals(BigInteger.valueOf(campaign.levels.filter { it.stage == stage.stageNumber }
                            .sumOf { totals.getValue(it.number) }), stage.screenActiveMs)
                    }
                    assertEquals(summary.screenActiveMs, summary.stages.fold(BigInteger.ZERO) { sum, stage -> sum + stage.screenActiveMs })
                    assertEquals(BigInteger.valueOf(toR.values.sum()), summary.toR.totalMs)
                    assertEquals(BigInteger.valueOf(toM.values.sum()), summary.toFull.totalMs)
                    val progress = saved()
                    process.close()
                    process = Process(timings)
                    assertEquals(progress, process.progress.migrateCampaign(campaign, index))
                    assertEquals(records, timeSnapshot())
                }
            } finally {
                withContext(NonCancellable) { process.close() }
                timings.report("finished")
            }
        }
    }

    @Test fun abruptProcessDeathKeepsDurableCheckpointAndDropsOnlyUnflushedTail() = runTest {
        val campaign = Campaign(listOf(Stage(1, 1, 1)), listOf(CampaignLevel(1, "картотека", 1, 1,
            listOf(CampaignTask.T1("r", 1), CampaignTask.T7("m", 'т', 1)))), contentSha256 = "c".repeat(64))
        val index = WordIndex(listOf("кора", "крот"))
        var process = Process()
        try {
            process.progress.migrateCampaign(campaign, index)
            process.open(campaign, campaign.levels.first(), 0)
            process.clock.set(70_000)
            process.progress.submitCampaignAnswer(campaign, 1, "кора", index)
            process.tracker.awaitSaved() // confirmed star checkpoint, game remains running
            val saved = process.progress.observeCampaignProgress().first()
            val checkpoint = process.time.snapshot(campaign)
            assertEquals(70_000L, checkpoint.getValue(1).cumulativeActiveMs)
            process.clock.set(80_000) // unsaved tail; no stop, Exit or lifecycle event
            process.close()
            process = Process() // monotonic tick now 0, persisted ticks must never be reused
            assertEquals(saved, process.progress.migrateCampaign(campaign, index))
            assertEquals(checkpoint, process.time.snapshot(campaign))
            process.open(campaign, campaign.levels.first(), 1)
            process.clock.set(5000)
            process.progress.submitCampaignAnswer(campaign, 1, "крот", index)
            process.exit()
            val record = process.time.snapshot(campaign).getValue(1)
            assertEquals(75_000L, record.cumulativeActiveMs)
            assertEquals(70_000L, record.timeToRMs)
            assertEquals(75_000L, record.timeToFullMs)
            assertEquals(listOf("кора", "крот"), process.progress.observeCampaignProgress().first()
                .levels.getValue("картотека").found)
        } finally { process.close() }
    }

    @Test fun coldStartsBeforeAndAfterFixtureCampaignChangeKeepVersionsIsolatedAndArchiveProgress() = runTest {
        val a = Campaign(listOf(Stage(1, 1, 1)), listOf(CampaignLevel(1, "картотека", 1, 1,
            listOf(CampaignTask.T1("r", 1), CampaignTask.T7("m", 'т', 1)))), contentSha256 = "a".repeat(64))
        val b = a.copy(contentSha256 = "b".repeat(64)) // TEST fixture only: same words, different version
        val index = WordIndex(listOf("кора", "крот"))
        var process = Process()
        try {
            process.progress.migrateCampaign(a, index)
            process.open(a, a.levels.first(), 0)
            process.clock.set(12_000)
            process.progress.submitCampaignAnswer(a, 1, "кора", index)
            process.exit()
            val savedA = process.progress.observeCampaignProgress().first()
            val timeA = process.time.snapshot(a)
            process.close()
            process = Process()
            assertEquals(savedA, process.progress.migrateCampaign(a, index))
            assertEquals(timeA, process.time.snapshot(a))
            val rawA = process.progressStore.data.first()[stringPreferencesKey("campaignProgressV2")]
            val reset = process.progress.migrateCampaign(b, index)
            assertEquals(rawA, process.progressStore.data.first()[stringPreferencesKey("campaignProgressV2Archive")])
            assertEquals(b.contentSha256, reset.campaignId)
            assertTrue(reset.levels.isEmpty())
            assertTrue(requireNotNull(reset.resetReason).startsWith("campaign-mismatch:"))
            assertEquals(0L, process.time.observeStatistics(b).first().screenActiveMs.toLong())
            assertEquals(timeA, process.time.snapshot(a))
            process.open(b, b.levels.first(), 0)
            process.clock.set(3000)
            process.progress.submitCampaignAnswer(b, 1, "крот", index)
            process.exit()
            val savedB = process.progress.observeCampaignProgress().first()
            assertEquals(2, CampaignRules.summary(b, savedB).stars)
            val timeB = process.time.snapshot(b)
            assertEquals(3000L, timeB.getValue(1).timeToFullMs)
            assertEquals(timeA, process.time.snapshot(a))
            process.close()
            process = Process()
            assertEquals(savedB, process.progress.migrateCampaign(b, index))
            assertEquals(timeA, process.time.snapshot(a))
            assertEquals(timeB, process.time.snapshot(b))
        } finally { process.close() }
    }
}
