package com.example.sostavslova.core

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignStatisticsAssemblerTest {
    private val campaign = Campaign(
        stages = listOf(Stage(1, 1, 2), Stage(2, 3, 3)),
        levels = listOf(
            CampaignLevel(1, "торт", 1, 1, listOf(CampaignTask.T1("a", 1), CampaignTask.T1("b", 2))),
            CampaignLevel(2, "порт", 1, 2, listOf(
                CampaignTask.T1("a", 1), CampaignTask.T1("b", 2), CampaignTask.T1("c", 3))),
            CampaignLevel(3, "кот", 2, 1, listOf(CampaignTask.T1("a", 1))),
        ),
        contentSha256 = "a".repeat(64),
    )
    private val index = WordIndex(listOf("рот", "тор", "тот", "пот", "топ", "ток", "кто"))
    private val names = mapOf(1 to "Разминка", 2 to "Ещё этап")

    private fun progress(vararg levels: Pair<Int, CampaignLevelProgress>): CampaignProgress =
        CampaignProgress(levels = levels.associate { (number, value) ->
            campaign.levels.single { it.number == number }.word to value
        }, campaignId = CampaignIdentity.of(campaign))

    private fun time(vararg values: Pair<Int, LevelTimeMeasurement>): CampaignTimeSummary =
        CampaignTimeStatistics.aggregate(campaign, CampaignTimeSnapshot(CampaignIdentity.of(campaign),
            values.associate { (number, measurement) ->
                number to measurement.copy(word = campaign.levels.single { it.number == number }.word)
            }))

    private fun assemble(
        progress: CampaignProgress = CampaignProgress(),
        time: CampaignTimeSummary? = null,
        historical: Set<Int> = emptySet(),
        pending: Set<Int> = emptySet(),
    ) = CampaignStatisticsAssembler.assemble(campaign, progress, index, time, historical, pending, names)

    @Test fun unplayedLevelHasWordTotalsButNoInventedTime() {
        val result = assemble()
        assertEquals(3, result.levels.size)
        assertEquals(0, result.stars)
        assertEquals(6, result.maxStars)
        assertEquals(0, result.passedLevels)
        assertEquals(0, result.hintsShown)
        assertEquals(BigInteger.ZERO, result.totalTimeMs)
        assertEquals(0, result.measuredLevelCount)
        val first = result.levels.first()
        assertEquals(0, first.found)
        assertEquals(3, first.total)
        assertEquals(0, first.percent)
        assertEquals(listOf(LengthStat(3, 0, 3)), first.byLength)
        assertNull(first.activeMs)
        assertEquals(LevelTimeStatus.Unplayed, first.timeStatus)
        assertEquals(LevelThresholdStatus.NotPassed, first.passedThresholdStatus())
        assertFalse(first.passed)
        assertEquals(0, result.foundWords)
        assertEquals(9, result.totalWords)
    }

    @Test fun partiallyPlayedLevelUsesSavedWordsHintsAndMeasuredTime() {
        val saved = CampaignLevelProgress(
            found = listOf("рот", "рот", "нос"),
            completedTaskIds = setOf("a"),
            hints = HintState(shownSteps = 2),
        )
        val result = assemble(progress(1 to saved), time(1 to LevelTimeMeasurement("",
            2_280_000, true, 1_200_000, null)))
        val row = result.levels.first()
        assertEquals(1, row.found)
        assertEquals(3, row.total)
        assertEquals(33, row.percent)
        assertEquals(listOf(LengthStat(3, 1, 3)), row.byLength)
        assertEquals(2, row.hintsShown)
        assertEquals(1, row.stars)
        assertTrue(row.passed)
        assertEquals(2_280_000L, row.activeMs)
        assertEquals(1_200_000L, row.toRMs)
        assertNull(row.toFullMs)
        assertEquals(LevelTimeStatus.Measured, row.timeStatus)
        assertEquals(LevelThresholdStatus.Measured, row.passedThresholdStatus())
        assertEquals(LevelThresholdStatus.NotPassed, row.fullThresholdStatus())
        assertEquals(2_280_000L, row.activeMs)
        assertEquals(BigInteger.valueOf(2_280_000), result.totalTimeMs)
        assertEquals(1, result.measuredLevelCount)
        assertEquals(1, result.stars)
        assertEquals(1, result.passedLevels)
        assertEquals(1, result.foundWords)
        assertEquals(2, result.hintsShown)
    }

    @Test fun levelPassedByRequiredThresholdAndLevelWithAllStarsAreDifferentRows() {
        val result = assemble(
            progress(
                1 to CampaignLevelProgress(completedTaskIds = setOf("a"), found = listOf("рот")),
                2 to CampaignLevelProgress(completedTaskIds = setOf("a", "b", "c"),
                    found = listOf("пот", "топ")),
            ),
            time(
                1 to LevelTimeMeasurement("", 60_000, true, 60_000, null),
                2 to LevelTimeMeasurement("", 100_000, true, 40_000, 90_000),
            ),
        )
        val byRequired = result.levels[0]
        val allStars = result.levels[1]
        assertTrue(byRequired.passed)
        assertEquals(1, byRequired.stars)
        assertEquals(2, byRequired.maxStars)
        assertEquals(LevelThresholdStatus.Measured, byRequired.passedThresholdStatus())
        assertEquals(LevelThresholdStatus.NotPassed, byRequired.fullThresholdStatus())
        assertNull(byRequired.toFullMs)
        assertTrue(allStars.passed)
        assertEquals(3, allStars.stars)
        assertEquals(3, allStars.maxStars)
        assertEquals(40_000L, allStars.toRMs)
        assertEquals(90_000L, allStars.toFullMs)
        assertEquals(LevelThresholdStatus.Measured, allStars.passedThresholdStatus())
        assertEquals(LevelThresholdStatus.Measured, allStars.fullThresholdStatus())
        assertEquals(4, result.stars)
        assertEquals(2, result.passedLevels)
    }

    @Test fun historicalAndPendingLevelsHaveNoInventedTimeButKeepEarnedStars() {
        val result = assemble(
            progress(1 to CampaignLevelProgress(completedTaskIds = setOf("a"))),
            historical = setOf(1),
            pending = setOf(2),
        )
        val historical = result.levels[0]
        val pending = result.levels[1]
        assertEquals(LevelTimeStatus.Historical, historical.timeStatus)
        assertNull(historical.activeMs)
        assertEquals(LevelThresholdStatus.NotMeasured, historical.passedThresholdStatus())
        assertEquals(1, historical.stars)
        assertEquals(LevelTimeStatus.Pending, pending.timeStatus)
        assertNull(pending.activeMs)
        assertEquals(LevelTimeStatus.Unplayed, result.levels[2].timeStatus)
        assertEquals(BigInteger.ZERO, result.totalTimeMs)
        assertEquals(0, result.measuredLevelCount)
    }

    @Test fun measuredZeroWinsOverHistoricalMarkerAndStageNamesAreGrouped() {
        val result = assemble(
            progress(1 to CampaignLevelProgress(completedTaskIds = setOf("a"))),
            time(1 to LevelTimeMeasurement("", 0, true, 0, null)),
            historical = setOf(1),
        )
        val measured = result.levels.first()
        assertEquals(0L, measured.activeMs)
        assertEquals(LevelTimeStatus.Measured, measured.timeStatus)
        assertEquals(LevelThresholdStatus.Measured, measured.passedThresholdStatus())
        assertEquals("Разминка", measured.stageName)
        assertEquals(listOf("Разминка", "Ещё этап"), result.stages.map { it.name })
        assertEquals(listOf(1, 2), result.stages.first().levels.map { it.number })
        assertEquals(listOf(3), result.stages.last().levels.map { it.number })
    }

    @Test fun preparedBasisCanBeReusedWithoutRepeatingDictionarySearch() {
        val basis = CampaignStatisticsAssembler.prepare(campaign, index)
        val empty = CampaignStatisticsAssembler.assemble(campaign, CampaignProgress(), basis, stageNames = names)
        val played = CampaignStatisticsAssembler.assemble(campaign,
            progress(1 to CampaignLevelProgress(found = listOf("тор"), completedTaskIds = setOf("a"))),
            basis, stageNames = names)
        assertEquals(0, empty.levels.first().found)
        assertEquals(1, played.levels.first().found)
        assertEquals(empty.levels.first().total, played.levels.first().total)
    }
}
