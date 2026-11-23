package com.example.sostavslova.ui.stats

import com.example.sostavslova.core.*
import org.junit.Assert.*
import org.junit.Test

/** Host-runnable snapshot tests: no Android clock, resources or writes. */
class StatsPresentationTest {
    private val campaign = Campaign(listOf(Stage(1, 1, 2)), (1..2).map {
        CampaignLevel(it, if (it == 1) "картотека" else "сторона", 1, 1,
            listOf(CampaignTask.T1("one", 1), CampaignTask.T1("two", 2)))
    }, contentSha256 = "a".repeat(64))
    private val basis = CampaignStatisticsAssembler.prepare(campaign, WordIndex(emptyList()))
    private val names = mapOf(1 to "Разминка")

    private fun state(progress: CampaignProgress = CampaignProgress()): StatsUiState =
        statsUiState(campaign, basis, progress, names)

    private fun progress(vararg stars: Pair<Int, Int>): CampaignProgress = CampaignProgress(
        campaignId = CampaignIdentity.of(campaign),
        levels = stars.associate { (number, count) ->
            val level = campaign.levels.single { it.number == number }
            level.word to CampaignLevelProgress(
                completedTaskIds = level.tasks.take(count).map { it.id }.toSet())
        })

    private fun row(state: StatsUiState, number: Int): LevelStatsRow =
        requireNotNull(state.statistics).levels.single { it.number == number }

    @Test fun freshProgressHasNoStarsAndKeepsLevelTotals() {
        val fresh = state()
        assertEquals(0, row(fresh, 1).stars)
        assertEquals(0, row(fresh, 2).stars)
        assertEquals(0, requireNotNull(fresh.statistics).stars)
        assertEquals(0, requireNotNull(fresh.statistics).hintsShown)
        assertEquals(2, requireNotNull(fresh.statistics).totalLevels)
    }

    @Test fun savedStarsAreKeptForEveryLevel() {
        val played = state(progress(1 to 1, 2 to 2))
        assertEquals(1, row(played, 1).stars)
        assertEquals(2, row(played, 2).stars)
        assertEquals(3, requireNotNull(played.statistics).stars)
        assertEquals(2, requireNotNull(played.statistics).passedLevels)
        assertTrue(row(played, 1).passed)
        assertTrue(row(played, 2).passed)
        assertEquals("Разминка", row(played, 1).stageName)
    }

    @Test fun progressOutsideCampaignIdentityIsIgnored() {
        val foreign = state(CampaignProgress(campaignId = "b".repeat(64),
            levels = mapOf("картотека" to CampaignLevelProgress(
                completedTaskIds = setOf("one")))))
        assertEquals(0, row(foreign, 1).stars)
        assertEquals(0, requireNotNull(foreign.statistics).stars)
    }
}
