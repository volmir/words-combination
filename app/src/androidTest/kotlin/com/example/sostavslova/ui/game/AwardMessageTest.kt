package com.example.sostavslova.ui.game

import com.example.sostavslova.R
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.ui.components.AlertType
import org.junit.Assert.*
import org.junit.Test

/** Pure resource-model tests: no Context, Compose or ViewModel needed. */
class AwardMessageTest {
    private fun star(count: Int) = GameMessage(R.string.star_received,
        type = AlertType.Success, pluralText = R.plurals.stars_received, count = count)
    private fun achievement(name: Int) = GameMessage(R.string.achievement_received,
        type = AlertType.Success, argumentText = name)

    @Test fun onlyStars() {
        assertEquals(star(2).copy(animateReward = true), awardMessage(2, false, emptyList()))
    }

    @Test fun starsAndAchievement() {
        assertEquals(star(1).copy(animateReward = true, additionalLines = listOf(achievement(R.string.achievement_expert))),
            awardMessage(1, false, listOf(LevelAchievement.EXPERT)))
    }

    @Test fun achievementWithoutStars() {
        assertEquals(achievement(R.string.achievement_giant).copy(animateReward = true),
            awardMessage(0, false, listOf(LevelAchievement.GIANT)))
    }

    @Test fun openedLevelAndTwoAchievementsFollowDisplayOrder() {
        assertEquals(GameMessage(R.string.game_next_opened, type = AlertType.Success, animateReward = true,
            additionalLines = listOf(achievement(R.string.achievement_no_hints),
                achievement(R.string.achievement_expert))),
            awardMessage(0, true, listOf(LevelAchievement.EXPERT, LevelAchievement.NO_HINTS)))
    }

    @Test fun fiveAchievementsShowThreeAndOverflowCount() {
        assertEquals(achievement(R.string.achievement_no_hints).copy(animateReward = true, additionalLines = listOf(
            achievement(R.string.achievement_expert), achievement(R.string.achievement_collector),
            GameMessage(R.string.achievement_more, type = AlertType.Success,
                pluralText = R.plurals.achievements_more, count = 2))),
            awardMessage(0, false, listOf(LevelAchievement.GIANT, LevelAchievement.COLLECTOR,
                LevelAchievement.EXPERT, LevelAchievement.NO_HINTS, LevelAchievement.EXPLORER)))
    }

    @Test fun openedLevelWithoutOtherAwards() {
        assertEquals(GameMessage(R.string.game_next_opened, type = AlertType.Success, animateReward = true),
            awardMessage(0, true, emptyList()))
    }

    @Test fun emptyAwardsDoNotShowBanner() {
        assertNull(awardMessage(0, false, emptyList()))
    }
}
