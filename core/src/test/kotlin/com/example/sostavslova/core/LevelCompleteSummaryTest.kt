package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelCompleteSummaryTest {
    private fun current(number: Int = 1, required: Int = 2, completed: Int = 2): CurrentCampaignLevel {
        val tasks = listOf(CampaignTask.T1("count", 1), CampaignTask.T2("initial", 'к', 1))
        return CurrentCampaignLevel(CampaignLevel(number, "картотека", 1, required, tasks),
            GameState("картотека", listOf("карта", "крот", "кот"), listOf("карта")),
            tasks.take(completed).map { it.id }.toSet())
    }

    @Test fun firstEightLevelsReachRAndAllStarsTogether() {
        for (number in 1..8) {
            val current = current(number)
            val result = assertNotNull(LevelCompleteSummary.build(current,
                CampaignLevelProgress(completedTaskIds = current.completedTaskIds), 15, true))
            assertEquals(number, result.levelNumber)
            assertEquals("картотека", result.word)
            assertEquals(2, result.stars)
            assertEquals(2, result.maxStars)
            assertTrue(result.allStars)
            assertTrue(result.hasNextLevel)
        }
    }

    @Test fun partialStarsIncludeConfirmedAchievementsHintsAndWordTotals() {
        val current = current(required = 1, completed = 1)
        val earned = setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT)
        val result = assertNotNull(LevelCompleteSummary.build(current,
            CampaignLevelProgress(completedTaskIds = current.completedTaskIds,
                achievements = earned, hints = HintState(shownSteps = 3)),
            15, true, setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT)))
        assertFalse(result.allStars)
        assertEquals(1, result.stars)
        assertEquals(1, result.foundWords)
        assertEquals(3, result.totalWords)
        assertEquals(3, result.hintsUsed)
        assertEquals(earned, result.achievements)
        assertEquals(setOf(LevelAchievement.EXPERT), result.newAchievements)
        assertEquals(AchievementRules.available(current.game, 1, 2), result.availableAchievements)
    }

    @Test fun noAchievementsAndNoTransitionOrFinalLevel() {
        val current = current()
        val saved = CampaignLevelProgress(completedTaskIds = current.completedTaskIds)
        val result = assertNotNull(LevelCompleteSummary.build(current, saved, 15, true))
        assertTrue(result.achievements.isEmpty())
        assertTrue(result.newAchievements.isEmpty())
        assertNull(LevelCompleteSummary.build(current, saved, 15, false))
        assertNull(LevelCompleteSummary.build(current(15), saved, 15, true))
    }
}
