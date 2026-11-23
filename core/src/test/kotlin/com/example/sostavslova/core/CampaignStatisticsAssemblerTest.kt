package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun assemble(progress: CampaignProgress = CampaignProgress()) =
        CampaignStatisticsAssembler.assemble(campaign, progress, index, names)

    @Test fun unplayedLevelHasWordTotals() {
        val result = assemble()
        assertEquals(3, result.levels.size)
        assertEquals(0, result.stars)
        assertEquals(6, result.maxStars)
        assertEquals(0, result.passedLevels)
        assertEquals(0, result.hintsShown)
        val first = result.levels.first()
        assertEquals(0, first.found)
        assertEquals(3, first.total)
        assertEquals(0, first.percent)
        assertEquals(listOf(LengthStat(3, 0, 3)), first.byLength)
        assertFalse(first.passed)
        assertEquals(0, result.foundWords)
        assertEquals(9, result.totalWords)
    }

    @Test fun partiallyPlayedLevelUsesSavedWordsAndHints() {
        val saved = CampaignLevelProgress(
            found = listOf("рот", "рот", "нос"),
            completedTaskIds = setOf("a"),
            hints = HintState(shownSteps = 2),
        )
        val result = assemble(progress(1 to saved))
        val row = result.levels.first()
        assertEquals(1, row.found)
        assertEquals(3, row.total)
        assertEquals(33, row.percent)
        assertEquals(listOf(LengthStat(3, 1, 3)), row.byLength)
        assertEquals(2, row.hintsShown)
        assertEquals(1, row.stars)
        assertTrue(row.passed)
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
        )
        val byRequired = result.levels[0]
        val allStars = result.levels[1]
        assertTrue(byRequired.passed)
        assertEquals(1, byRequired.stars)
        assertEquals(2, byRequired.maxStars)
        assertFalse(byRequired.stars >= byRequired.maxStars)
        assertTrue(allStars.passed)
        assertEquals(3, allStars.stars)
        assertEquals(3, allStars.maxStars)
        assertTrue(allStars.stars >= allStars.maxStars)
        assertEquals(4, result.stars)
        assertEquals(2, result.passedLevels)
    }

    @Test fun earnedStarsAreKeptForEverySavedLevel() {
        val result = assemble(
            progress(1 to CampaignLevelProgress(completedTaskIds = setOf("a"))),
        )
        assertEquals(1, result.levels[0].stars)
        assertTrue(result.levels[0].passed)
        assertEquals(0, result.levels[1].stars)
        assertEquals(0, result.levels[2].stars)
        assertEquals(1, result.stars)
    }

    @Test fun stageNamesAreGroupedForEveryStage() {
        val result = assemble(progress(1 to CampaignLevelProgress(completedTaskIds = setOf("a"))))
        assertEquals("Разминка", result.levels.first().stageName)
        assertEquals(listOf("Разминка", "Ещё этап"), result.stages.map { it.name })
        assertEquals(listOf(1, 2), result.stages.first().levels.map { it.number })
        assertEquals(listOf(3), result.stages.last().levels.map { it.number })
    }

    @Test fun preparedBasisCanBeReusedWithoutRepeatingDictionarySearch() {
        val basis = CampaignStatisticsAssembler.prepare(campaign, index)
        val empty = CampaignStatisticsAssembler.assemble(campaign, CampaignProgress(), basis, names)
        val played = CampaignStatisticsAssembler.assemble(campaign,
            progress(1 to CampaignLevelProgress(found = listOf("тор"), completedTaskIds = setOf("a"))),
            basis, names)
        assertEquals(0, empty.levels.first().found)
        assertEquals(1, played.levels.first().found)
        assertEquals(empty.levels.first().total, played.levels.first().total)
    }

    @Test fun statisticsShowEarnedAndAvailableAchievementsPerLevel() {
        val earned = setOf(LevelAchievement.EXPERT, LevelAchievement.LONG_WORD)
        val result = assemble(progress(1 to CampaignLevelProgress(
            found = listOf("рот"), completedTaskIds = setOf("a", "b"), achievements = earned)))
        val row = result.levels.first()
        assertEquals(earned, row.achievements)
        assertEquals(setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT, LevelAchievement.COLLECTOR,
            LevelAchievement.EXPLORER, LevelAchievement.STRAIGHT_A), row.achievementsAvailable)
        assertEquals(2, result.achievementsEarned)
        assertEquals(14, result.achievementsTotal)
        assertEquals(emptySet<LevelAchievement>(), result.levels[1].achievements)

        val withoutWords = CampaignStatisticsAssembler.assemble(campaign, CampaignProgress(), WordIndex(emptyList()))
        assertEquals(2, withoutWords.achievementsTotal) // Два уровня с R < M: «Отличник».
        assertEquals(0, withoutWords.achievementsEarned)
    }
}
