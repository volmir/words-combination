package com.example.sostavslova.ui.levels

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Итог кампании показывается из модели, без зашитых в UI значений. */
@RunWith(AndroidJUnit4::class)
class CampaignStarsSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installedCampaignMasterShowsFortyNineOfFortyNineFromModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() },
        )
        val completed = CampaignProgress(
            levels = campaign.levels.associate { level ->
                level.word to CampaignLevelProgress(completedTaskIds = level.tasks.map { it.id }.toSet())
            },
            highestUnlockedLevel = campaign.levels.size,
            finaleReached = true,
        )
        val summary = CampaignRules.summary(campaign, completed)
        // Число 49 — контракт установленной short15; экран получает его из summary.
        assertEquals(49, summary.maxStars)
        assertEquals(summary.maxStars, summary.stars)
        assertTrue(summary.isMaster)

        val state = LevelsUiState(
            loading = false,
            summary = summary,
            stages = campaign.stages,
            levels = campaign.levels.map { level ->
                LevelUiState(level.number, level.word, level.maxStars, unlocked = true,
                    maxStars = level.maxStars, requiredStars = level.requiredStars, stage = level.stage)
            },
            finaleReached = true,
        )
        compose.setContent { WordsTheme { LevelsScreen(state, {}, {}) } }
        val expected = context.resources.getString(R.string.levels_campaign_progress,
            summary.stars, summary.maxStars, summary.passedLevels, summary.totalLevels)
        compose.onNodeWithTag("levels_progress").assertTextEquals(expected)
        compose.onNodeWithTag("levels_finale_banner").assertTextEquals(context.getString(R.string.levels_master))
    }
}
