package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewardAnimationTest {
    @Test fun noRewardDoesNotAnimate() {
        assertFalse(rewardAnimation(0, false, emptyList()))
    }

    @Test fun eachKindOfRewardAnimatesIndependently() {
        assertTrue(rewardAnimation(1, false, emptyList()))
        assertTrue(rewardAnimation(0, true, emptyList()))
        assertTrue(rewardAnimation(0, false, listOf(LevelAchievement.EXPERT)))
    }

    @Test fun combinedRewardsAnimate() {
        assertTrue(rewardAnimation(3, true, LevelAchievement.entries))
    }
}
