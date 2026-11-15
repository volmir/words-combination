package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка именно установленной short15-кампании, а не синтетического 30/100 стенда. */
class Short15ProductionCampaignTest {
    private val campaign = requireNotNull(TestData.campaign) { "В data/manifest.json нет campaign_source" }

    private fun earn(progress: CampaignProgress, level: CampaignLevel, tasks: Int): CampaignProgress {
        val current = CurrentCampaignLevel(
            level = level,
            game = GameState(level.word, emptyList()),
            completedTaskIds = level.tasks.take(tasks).map { it.id }.toSet(),
        )
        return CampaignRules.recordLevel(campaign, progress, current)
    }

    @Test fun installedCampaignHasFifteenLevelsSixStagesAndEveryTaskType() {
        assertEquals(15, campaign.levels.size)
        assertEquals(49, campaign.maxStars)
        assertEquals(listOf(3, 2, 3, 2, 3, 2), campaign.stages.map { it.lastLevel - it.firstLevel + 1 })
        assertEquals(TaskType.entries.toSet(), campaign.levels.flatMap { it.tasks }.map { it.type }.toSet())
        assertEquals(4, campaign.levels.last().requiredStars)
        assertEquals("short15-release-v1", TestData.campaignProfile)
        assertTrue(ProductionCampaignValidator.validate(campaign, "short15-release-v1").isEmpty())
        assertEquals(TestData.manifest.getJSONObject("campaign_source").getString("sha256"), campaign.contentSha256)
        assertEquals(listOf("чемпионство", "вегетарианец", "аккумулятор"), campaign.levels.take(3).map { it.word })
    }

    @Test fun fourOfFiveReachesFinaleButMasterNeedsAllFortyNine() {
        var progress = CampaignProgress()
        for (level in campaign.levels.dropLast(1)) {
            progress = earn(progress, level, level.tasks.size)
        }
        assertEquals(15, progress.highestUnlockedLevel)
        assertFalse(progress.finaleReached)

        val finalLevel = campaign.levels.last()
        val four = earn(progress, finalLevel, 4)
        assertEquals(4, CampaignRules.stars(finalLevel, four))
        assertTrue(four.finaleReached)
        assertFalse(CampaignRules.summary(campaign, four).isMaster)
        assertEquals(48, CampaignRules.summary(campaign, four).stars)
        assertEquals(finalLevel, CampaignRules.continueLevel(campaign, four))

        // Повторный заход и добор не отнимают финал и не открывают чужие уровни.
        val revisited = earn(four, campaign.levels.first(), 0)
        assertEquals(48, CampaignRules.summary(campaign, revisited).stars)
        assertTrue(revisited.finaleReached)

        val five = earn(revisited, finalLevel, 5)
        val summary = CampaignRules.summary(campaign, five)
        assertEquals(49, summary.stars)
        assertEquals(49, summary.maxStars)
        assertTrue(summary.isMaster)
        assertEquals(15, summary.passedLevels)
        assertTrue(five.finaleReached)
        assertEquals(15, five.highestUnlockedLevel)
        assertEquals(finalLevel, CampaignRules.continueLevel(campaign, five))
    }
}
