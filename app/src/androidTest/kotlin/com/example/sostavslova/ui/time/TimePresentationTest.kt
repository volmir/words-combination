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
