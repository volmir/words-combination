package com.example.sostavslova.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CampaignTimeStatisticsTest {
    private val campaign = requireNotNull(TestData.campaign)
    private fun snapshot(vararg times: Pair<Int, LevelTimeMeasurement>) =
        CampaignTimeSnapshot(requireNotNull(campaign.contentSha256), times.toMap())

    private fun time(number: Int, ms: Long, r: Long? = null, full: Long? = null) =
        number to LevelTimeMeasurement(campaign.levels.single { it.number == number }.word, ms, true, r, full)

    @Test fun installedFifteenLevelsAndSixStagesUseExactCampaignMapping() {
        val input = snapshot(*campaign.levels.map { time(it.number, it.number * 10L) }.toTypedArray())
        val result = CampaignTimeStatistics.aggregate(campaign, input)
        assertEquals(15, result.levels.size)
        assertEquals(6, result.stages.size)
        assertEquals(BigInteger.valueOf(1200), result.screenActiveMs)
        assertEquals(80.0, result.meanLevelActiveMs)
        assertEquals(80.0, result.medianLevelActiveMs)
        assertEquals(1, result.fastestLevel?.levelNumber)
        assertEquals(15, result.slowestLevel?.levelNumber)
        campaign.stages.forEach { stage ->
            val expected = campaign.levels.filter { it.stage == stage.number }
            val actual = result.stages.single { it.stageNumber == stage.number }
            assertEquals(expected.map { it.number }, actual.levels.map { it.levelNumber })
            assertEquals(BigInteger.valueOf(expected.sumOf { it.number * 10L }), actual.screenActiveMs)
        }
        assertEquals(result, CampaignTimeStatistics.aggregate(campaign, input))
    }

    @Test fun unknownAndHistoricalTimesAreNotZeroRecordsButMeasuredZeroCounts() {
        val result = CampaignTimeStatistics.aggregate(campaign, snapshot(time(2, 0), time(3, 60, 20, 40)))
        assertEquals(2, result.measuredLevelCount)
        assertEquals(30.0, result.meanLevelActiveMs)
        assertEquals(30.0, result.medianLevelActiveMs)
        assertNull(result.levels.first().screenActiveMs)
        assertEquals(0L, result.levels[1].screenActiveMs)
        assertEquals(2, result.fastestLevel?.levelNumber)
        assertEquals(BigInteger.valueOf(60), result.screenActiveMs)
        assertEquals(MilestoneTimeSummary(1, BigInteger.valueOf(20)), result.toR)
        assertEquals(MilestoneTimeSummary(1, BigInteger.valueOf(40)), result.toFull)
        val empty = CampaignTimeStatistics.aggregate(campaign, snapshot())
        assertEquals(BigInteger.ZERO, empty.screenActiveMs)
        assertNull(empty.meanLevelActiveMs)
        assertNull(empty.medianLevelActiveMs)
        assertNull(empty.fastestLevel)
        assertNull(empty.slowestLevel)
        assertEquals(0, empty.toR.measuredLevelCount)
    }

    @Test fun maximumLongTotalsAndMedianNeverWrap() {
        val result = CampaignTimeStatistics.aggregate(campaign,
            snapshot(time(1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                time(2, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)))
        val total = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)
        assertEquals(total, result.screenActiveMs)
        assertEquals(total, result.toR.totalMs)
        assertEquals(total, result.toFull.totalMs)
        assertEquals(Long.MAX_VALUE.toDouble(), result.meanLevelActiveMs)
        assertEquals(Long.MAX_VALUE.toDouble(), result.medianLevelActiveMs)
    }

    @Test fun medianUsesSortedMeasuredLevelsRatherThanMeanOrMissingZeros() {
        val result = CampaignTimeStatistics.aggregate(campaign, snapshot(time(1, 90), time(2, 10), time(3, 20)))
        assertEquals(40.0, result.meanLevelActiveMs)
        assertEquals(20.0, result.medianLevelActiveMs)
        assertEquals(2, result.fastestLevel?.levelNumber)
        assertEquals(1, result.slowestLevel?.levelNumber)
    }

    @Test fun otherCampaignWordAndInvalidIntervalsCannotPolluteStatistics() {
        assertFailsWith<IllegalArgumentException> {
            CampaignTimeStatistics.aggregate(campaign, snapshot(time(1, 10)).copy(campaignSha256 = "b".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            CampaignTimeStatistics.aggregate(campaign, snapshot(1 to LevelTimeMeasurement("other", 10, true)))
        }
        for (bad in listOf(LevelTimeMeasurement(campaign.levels.first().word, -1, true),
            LevelTimeMeasurement(campaign.levels.first().word, 10, true, 11),
            LevelTimeMeasurement(campaign.levels.first().word, 10, true, 9, 8))) {
            assertFailsWith<IllegalArgumentException> { CampaignTimeStatistics.aggregate(campaign, snapshot(1 to bad)) }
        }
    }
}
