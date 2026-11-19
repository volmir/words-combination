package com.example.sostavslova.ui.stats

import com.example.sostavslova.core.*
import com.example.sostavslova.data.LevelPlayTime
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Host-runnable snapshot tests: no Android clock, resources or writes. */
class StatsPresentationTest {
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), (1..2).map {
        CampaignLevel(it, if (it == 1) "картотека" else "сторона", 1, 1,
            listOf(CampaignTask.T1("one", 1), CampaignTask.T1("two", 2)))
    }, contentSha256 = "a".repeat(64))
    private val basis = CampaignStatisticsAssembler.prepare(campaign, WordIndex(emptyList()))
    private val names = mapOf(1 to "Разминка")

    private fun state(records: Map<Int, LevelPlayTime>, progress: CampaignProgress = CampaignProgress()): StatsUiState {
        val summary = CampaignTimeStatistics.aggregate(campaign,
            CampaignTimeSnapshot(CampaignIdentity.of(campaign), records.mapValues { (number, record) ->
                LevelTimeMeasurement(campaign.levels.single { it.number == number }.word,
                    record.cumulativeActiveMs, record.lastSequence > 0, record.timeToRMs, record.timeToFullMs)
            }))
        return statsUiState(campaign, basis, summary, records, progress, names)
    }

    private fun progress(vararg stars: Pair<Int, Int>): CampaignProgress = CampaignProgress(
        campaignId = CampaignIdentity.of(campaign),
        levels = stars.associate { (number, count) ->
            val level = campaign.levels.single { it.number == number }
            level.word to CampaignLevelProgress(
                completedTaskIds = level.tasks.take(count).map { it.id }.toSet())
        })

    private fun row(state: StatsUiState, number: Int): LevelStatsRow =
        requireNotNull(state.statistics).levels.single { it.number == number }

    @Test fun durationBoundariesTruncateSubsecondsWithoutWrappingHours() {
        val cases = mapOf(
            0L to listOf("0", "0", "0"),
            999L to listOf("0", "0", "0"),
            1000L to listOf("0", "0", "1"),
            59_999L to listOf("0", "0", "59"),
            60_000L to listOf("0", "1", "0"),
            3_599_999L to listOf("0", "59", "59"),
            3_600_000L to listOf("1", "0", "0"),
            86_400_000L to listOf("24", "0", "0"),
        )
        cases.forEach { (ms, parts) -> assertEquals(parts, durationParts(BigInteger.valueOf(ms))) }
    }

    @Test fun longDurationUsesTotalHoursIncludingBeyondLongSum() {
        assertEquals(listOf("27", "3", "5"), durationParts(BigInteger.valueOf(97_385_999)))
        assertEquals(listOf("0", "0", "0"), durationParts(BigInteger.ZERO))
        val enormous = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TEN
        val parts = durationParts(enormous)
        assertEquals(enormous / BigInteger.valueOf(1000),
            BigInteger(parts[0]) * BigInteger.valueOf(3600) + BigInteger(parts[1]) * BigInteger.valueOf(60) + BigInteger(parts[2]))
    }

    @Test fun durationTextKeepsOnlySignificantUnits() {
        val units = listOf("ч", "мин", "с")
        fun text(ms: Long) = durationText(durationParts(BigInteger.valueOf(ms)), units)
        assertEquals("0 с", text(0))
        assertEquals("45 с", text(45_000))
        assertEquals("5 мин 3 с", text(303_000))
        assertEquals("2 ч 14 мин", text(8_040_000))
        assertEquals("2 ч 5 с", text(7_205_000))
        assertEquals("27 ч 3 мин 5 с", text(97_385_999))
    }

    @Test fun freshHistoricalPendingAndMeasuredZeroAreDifferentSnapshots() {
        val empty = mapOf(1 to LevelPlayTime(), 2 to LevelPlayTime())
        val fresh = state(empty)
        assertEquals(LevelTimeStatus.Unplayed, row(fresh, 1).timeStatus)
        assertEquals(BigInteger.ZERO, requireNotNull(fresh.statistics).totalTimeMs)
        val history = CampaignProgress(campaignId = CampaignIdentity.of(campaign),
            levels = mapOf("картотека" to CampaignLevelProgress()), lastVisitedLevel = 1)
        assertEquals(LevelTimeStatus.Historical, row(state(empty, history), 1).timeStatus)
        assertEquals(LevelTimeStatus.Unplayed,
            row(state(empty, history.copy(campaignId = "b".repeat(64))), 1).timeStatus)
        assertEquals(LevelTimeStatus.Historical,
            row(state(empty + (1 to LevelPlayTime(initialized = true, lastStars = 1))), 1).timeStatus)
        val pending = empty + (1 to LevelPlayTime(initialized = true))
        assertEquals(LevelTimeStatus.Pending, row(state(pending, history), 1).timeStatus)
        val measured = pending + (1 to LevelPlayTime(initialized = true, lastSequence = 1))
        assertEquals(0L, row(state(measured), 1).activeMs)
        assertEquals(LevelTimeStatus.Measured, row(state(measured), 1).timeStatus)
        assertEquals(1, requireNotNull(state(measured).statistics).measuredLevelCount)
    }

    @Test fun activeTimeAndThresholdStatesAreUnambiguous() {
        val empty = mapOf(1 to LevelPlayTime(), 2 to LevelPlayTime())
        assertEquals(LevelTimeStatus.Unplayed, levelTimeStatus(null, historical = false, pending = false))
        assertEquals(LevelThresholdStatus.NotPassed, levelThresholdStatus(stars = 0, thresholdStars = 1, milliseconds = null))
        assertEquals(LevelThresholdStatus.NotMeasured, levelThresholdStatus(stars = 1, thresholdStars = 1, milliseconds = null))
        assertEquals(LevelThresholdStatus.Measured, levelThresholdStatus(stars = 1, thresholdStars = 1, milliseconds = 5_000))

        val historical = state(empty, progress(1 to 1))
        val historicalRow = row(historical, 1)
        assertEquals(LevelTimeStatus.Historical, historicalRow.timeStatus)
        assertEquals(1, historicalRow.stars)
        assertEquals(LevelThresholdStatus.NotMeasured, historicalRow.passedThresholdStatus())

        val measured = state(empty + (1 to LevelPlayTime(initialized = true, lastSequence = 1,
            cumulativeActiveMs = 5_000, lastStars = 1, timeToRMs = 4_000)), progress(1 to 1))
        val measuredRow = row(measured, 1)
        assertEquals(5_000L, measuredRow.activeMs)
        assertEquals(LevelTimeStatus.Measured, measuredRow.timeStatus)
        assertEquals(LevelThresholdStatus.Measured, measuredRow.passedThresholdStatus())
    }

    @Test fun measuredTimeWinsOverHistoricalMarker() {
        val record = LevelPlayTime(initialized = true, rAccounted = true, lastStars = 1,
            lastSequence = 1, cumulativeActiveMs = 5_000)
        val snapshot = state(mapOf(1 to record, 2 to LevelPlayTime()), progress(1 to 1))
        val first = row(snapshot, 1)
        assertEquals(5_000L, first.activeMs)
        assertEquals(LevelTimeStatus.Measured, first.timeStatus)
        assertEquals(LevelThresholdStatus.NotMeasured, first.passedThresholdStatus())
    }

    @Test fun historicalThresholdDoesNotBecomeFullCompletionAndSnapshotsUpdate() {
        val old = mapOf(1 to LevelPlayTime(initialized = true, rAccounted = true, lastStars = 1),
            2 to LevelPlayTime())
        val initial = state(old)
        assertEquals(LevelTimeStatus.Historical, row(initial, 1).timeStatus)
        val updated = state(old + (1 to old.getValue(1).copy(cumulativeActiveMs = 97_385_000, lastSequence = 1)))
        assertEquals(BigInteger.ZERO, requireNotNull(initial.statistics).totalTimeMs)
        assertEquals(BigInteger.valueOf(97_385_000), requireNotNull(updated.statistics).totalTimeMs)
        assertNull(row(updated, 1).toRMs)
        assertNull(row(updated, 1).toFullMs)
        assertEquals(1, requireNotNull(updated.statistics).levels.first().requiredStars)
        assertEquals(2, requireNotNull(updated.statistics).levels.first().maxStars)
    }
}
