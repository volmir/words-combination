package com.example.sostavslova.data

import com.example.sostavslova.core.*
import org.junit.Assert.*
import org.junit.Test

class LevelRouteRulesTest {
    private val legacy = listOf(Level(1, "торт", 1, 2, 'т', 1), Level(2, "портал", 1, 2, 'п', 1))
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), legacy.map {
        CampaignLevel(it.index, it.word, 1, 1, listOf(CampaignTask.T7("letter", 'р', 1)))
    })

    @Test fun v2RejectsUnknownNumbers() {
        for (number in listOf(null, -1, 0, 3, Int.MAX_VALUE)) {
            assertFalse(LevelRouteRules.exists(number, campaign))
            assertFalse(LevelRouteRules.isUnlocked(number, campaign, CampaignProgress()))
        }
    }

    @Test fun v2AccessUsesSavedFrontierAndIgnoresLegacyStars() {
        assertTrue(LevelRouteRules.isUnlocked(1, campaign, CampaignProgress()))
        assertFalse(LevelRouteRules.isUnlocked(2, campaign, CampaignProgress()))
        assertTrue(LevelRouteRules.isUnlocked(2, campaign, CampaignProgress(highestUnlockedLevel = 2)))
    }
}
