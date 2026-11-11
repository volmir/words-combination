package com.example.sostavslova.data

import com.example.sostavslova.core.*
import org.junit.Assert.*
import org.junit.Test

class LevelRouteRulesTest {
    private val legacy = listOf(Level(1, "торт", 1, 2, 'т', 1), Level(2, "портал", 1, 2, 'п', 1))
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), legacy.map {
        CampaignLevel(it.index, it.word, 1, 1, listOf(CampaignTask.T7("letter", 'р', 1)))
    })

    @Test fun legacyRejectsUnknownNumbersAndUsesCompletedForAccess() {
        for (number in listOf(null, -1, 0, 3, Int.MAX_VALUE)) {
            assertFalse(LevelRouteRules.exists(number, null, legacy))
            assertFalse(LevelRouteRules.isUnlocked(number, null, CampaignProgress(), legacy, emptyMap()))
        }
        assertTrue(LevelRouteRules.isUnlocked(1, null, CampaignProgress(), legacy, emptyMap()))
        assertFalse(LevelRouteRules.isUnlocked(2, null, CampaignProgress(), legacy,
            mapOf("торт" to LevelProgress(wordsTask = true, lettersTask = true))))
        assertTrue(LevelRouteRules.isUnlocked(2, null, CampaignProgress(), legacy,
            mapOf("торт" to LevelProgress(completed = true))))
    }

    @Test fun v2AccessUsesSavedFrontierAndIgnoresLegacyStars() {
        for (number in listOf(null, -1, 0, 3)) {
            assertFalse(LevelRouteRules.isUnlocked(number, campaign, CampaignProgress(), legacy, emptyMap()))
        }
        assertTrue(LevelRouteRules.isUnlocked(1, campaign, CampaignProgress(), legacy, emptyMap()))
        assertFalse(LevelRouteRules.isUnlocked(2, campaign, CampaignProgress(), legacy,
            mapOf("торт" to LevelProgress(true, true, true))))
        assertTrue(LevelRouteRules.isUnlocked(2, campaign, CampaignProgress(highestUnlockedLevel = 2),
            emptyList(), emptyMap()))
    }
}
