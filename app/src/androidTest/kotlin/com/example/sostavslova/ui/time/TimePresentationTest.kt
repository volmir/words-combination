package com.example.sostavslova.ui.time

import com.example.sostavslova.core.*
import com.example.sostavslova.data.LevelPlayTime
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Host-runnable snapshot tests: no Android clock, resources or writes. */
class TimePresentationTest {
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), (1..2).map {
        CampaignLevel(it, if (it == 1) "картотека" else "сторона", 1, 1,
            listOf(CampaignTask.T1("one", 1), CampaignTask.T1("two", 2)))
    }, contentSha256 = "a".repeat(64))

    private fun state(records: Map<Int, LevelPlayTime>, progress: CampaignProgress = CampaignProgress()) =
        timeUiState(campaign, CampaignTimeStatistics.aggregate(campaign,
            CampaignTimeSnapshot(requireNotNull(campaign.contentSha256), records.mapValues { (number, record) ->
                LevelTimeMeasurement(campaign.levels.single { it.number == number }.word,
                    record.cumulativeActiveMs, record.lastSequence > 0, record.timeToRMs, record.timeToFullMs)
            })), records, progress)

    private fun progress(vararg stars: Pair<Int, Int>): CampaignProgress = CampaignProgress(
        campaignId = campaign.contentSha256,
        levels = stars.associate { (number, count) ->
            val level = campaign.levels.single { it.number == number }
            level.word to CampaignLevelProgress(
                completedTaskIds = level.tasks.take(count).map { it.id }.toSet())
        })

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
        assertTrue(fresh.historicalLevels.isEmpty())
        assertNull(fresh.summary?.meanLevelActiveMs)
        val history = CampaignProgress(campaignId = campaign.contentSha256,
            levels = mapOf("картотека" to CampaignLevelProgress()), lastVisitedLevel = 1)
        assertEquals(setOf(1), state(empty, history).historicalLevels)
        assertTrue(state(empty, history.copy(campaignId = "b".repeat(64))).historicalLevels.isEmpty())
        assertEquals(setOf(1), state(empty + (1 to LevelPlayTime(initialized = true, lastStars = 1))).historicalLevels)
        val pending = empty + (1 to LevelPlayTime(initialized = true))
        assertEquals(setOf(1), state(pending, history).pendingLevels)
        assertTrue(state(pending, history).historicalLevels.isEmpty())
        val measured = pending + (1 to LevelPlayTime(initialized = true, lastSequence = 1))
        assertEquals(0L, state(measured).summary?.levels?.first()?.screenActiveMs)
        assertEquals(1, state(measured).summary?.measuredLevelCount)
    }

    @Test fun activeTimeAndThresholdStatesAreUnambiguous() {
        val empty = mapOf(1 to LevelPlayTime(), 2 to LevelPlayTime())
        val unplayed = state(empty)
        assertNull(unplayed.summary?.levels?.first()?.screenActiveMs)
        assertEquals(ActiveTimeStatus.Unplayed, activeTimeStatus(null, historical = false, pending = false))
        assertEquals(ThresholdStatus.NotPassed, thresholdStatus(stars = 0, thresholdStars = 1, milliseconds = null))
        assertEquals(ThresholdStatus.NotMeasured, thresholdStatus(stars = 1, thresholdStars = 1, milliseconds = null))
        assertEquals(ThresholdStatus.Measured, thresholdStatus(stars = 1, thresholdStars = 1, milliseconds = 5_000))

        val historical = state(empty, progress(1 to 1))
        assertEquals(setOf(1), historical.historicalLevels)
        assertEquals(1, historical.levelStars[1])
        val historicalLevel = historical.summary?.levels?.first()
        assertEquals(ActiveTimeStatus.Historical,
            activeTimeStatus(historicalLevel?.screenActiveMs, historical = true, pending = false))
        assertEquals(ThresholdStatus.NotMeasured,
            thresholdStatus(historical.levelStars.getValue(1), 1, historicalLevel?.timeToRMs))

        val measured = state(empty + (1 to LevelPlayTime(initialized = true, lastSequence = 1,
            cumulativeActiveMs = 5_000, lastStars = 1, timeToRMs = 4_000)), progress(1 to 1))
        assertEquals(5_000L, measured.summary?.levels?.first()?.screenActiveMs)
        assertEquals(ActiveTimeStatus.Measured,
            activeTimeStatus(measured.summary?.levels?.first()?.screenActiveMs, historical = false, pending = false))
        assertEquals(ThresholdStatus.Measured,
            thresholdStatus(measured.levelStars.getValue(1), 1, measured.summary?.levels?.first()?.timeToRMs))
    }

    @Test fun measuredTimeWinsOverHistoricalMarker() {
        val record = LevelPlayTime(initialized = true, rAccounted = true, lastStars = 1,
            lastSequence = 1, cumulativeActiveMs = 5_000)
        val snapshot = state(mapOf(1 to record, 2 to LevelPlayTime()), progress(1 to 1))
        assertTrue(1 in snapshot.historicalLevels)
        assertEquals(5_000L, snapshot.summary?.levels?.first()?.screenActiveMs)
        assertEquals(ActiveTimeStatus.Measured,
            activeTimeStatus(snapshot.summary?.levels?.first()?.screenActiveMs,
                historical = 1 in snapshot.historicalLevels, pending = false))
        assertEquals(ThresholdStatus.NotMeasured,
            thresholdStatus(snapshot.levelStars.getValue(1), 1, snapshot.summary?.levels?.first()?.timeToRMs))
    }

    @Test fun historicalThresholdDoesNotBecomeFullCompletionAndSnapshotsUpdate() {
        val old = mapOf(1 to LevelPlayTime(initialized = true, rAccounted = true, lastStars = 1),
            2 to LevelPlayTime())
        val initial = state(old)
        assertEquals(setOf(1), initial.historicalLevels)
        val updated = state(old + (1 to old.getValue(1).copy(cumulativeActiveMs = 97_385_000, lastSequence = 1)))
        assertEquals(BigInteger.ZERO, initial.summary?.screenActiveMs)
        assertEquals(BigInteger.valueOf(97_385_000), updated.summary?.screenActiveMs)
        assertNull(updated.summary?.levels?.first()?.timeToRMs)
        assertNull(updated.summary?.levels?.first()?.timeToFullMs)
        assertEquals(1, updated.campaign?.levels?.first()?.requiredStars)
        assertEquals(2, updated.campaign?.levels?.first()?.maxStars)
    }
}
