package com.example.sostavslova.ui.stats

import com.example.sostavslova.ui.gameKeyboardInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.sostavslova.MainActivity
import com.example.sostavslova.ui.dismissWelcomeIfShown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import com.example.sostavslova.ui.IsolatedGameStorageRule

class StatsNavigationTest {
    private val storage = IsolatedGameStorageRule()
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(compose)
    private val container get() = storage.container
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openStats() {
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithText("Статистика").performClick()
        awaitTag("stats_summary")
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun statsReadsWithoutWritesPreservesMapAndStopsGameOnDrawerNavigation() = runTest {
        awaitTag("levels_grid")
        compose.dismissWelcomeIfShown()
        val campaign = requireNotNull(container.levelsRepository.getCampaign())
        assertEquals(15, campaign.levels.size)
        assertEquals(6, campaign.stages.size)
        val beforeProgress = container.progressRepository.observeCampaignProgress().first()
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.last().word)
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        openStats()
        compose.onNodeWithTag("stats_achievements_total").assertExists()
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_1")
        compose.onNodeWithTag("stats_toggle_1").performScrollTo().performClick()
        compose.onNodeWithTag("stats_achievement_1_no_hints").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("achievement_dialog").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("stats_row_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "развёрнуто"))
        assertEquals(beforeProgress, container.progressRepository.observeCampaignProgress().first())
        compose.activityRule.scenario.recreate()
        awaitTag("stats_row_1")
        compose.onNodeWithTag("stats_row_1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "развёрнуто"))
        assertEquals(beforeProgress, container.progressRepository.observeCampaignProgress().first())
        back()
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.first().word)
        compose.onNodeWithTag("level_card_1").performClick()
        awaitTag("game_tasks_summary")
        if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Закрыть").performClick()
        }
        compose.gameKeyboardInput().performScrollTo().performTextReplacement("черновик")
        openStats()
        val progress = container.progressRepository.observeCampaignProgress().first()
        // Recomposition, scrolling and recreation on the stats route never change progress.
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_15")
        compose.activityRule.scenario.recreate()
        // После восстановления список остаётся на уровне 15, поэтому шапка уже вне окна.
        awaitTag("stats_row_15")
        assertEquals(progress, container.progressRepository.observeCampaignProgress().first())
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithText("Правила").performClick()
        compose.onNodeWithTag("stats_screen").assertDoesNotExist()
        back()
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitTag("game_tasks_summary")
        compose.gameKeyboardInput().assertTextEquals("")
        compose.onNodeWithTag("game_found_summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("game_word_statistics").assertDoesNotExist()
        compose.onNodeWithTag("game_back").performClick()
    }
}
