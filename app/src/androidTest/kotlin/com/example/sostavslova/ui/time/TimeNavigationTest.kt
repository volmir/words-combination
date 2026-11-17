package com.example.sostavslova.ui.time

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

class TimeNavigationTest {
    private val storage = IsolatedGameStorageRule()
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(compose)
    private val container get() = storage.container
    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openTime() {
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithText("Время игры").performClick()
        awaitTag("time_total")
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun timeReadsWithoutWritesPreservesMapAndStopsGameOnDrawerNavigation() = runTest {
        awaitTag("levels_grid")
        compose.dismissWelcomeIfShown()
        val campaign = requireNotNull(container.levelsRepository.getCampaign())
        assertEquals(15, campaign.levels.size)
        assertEquals(6, campaign.stages.size)
        val beforeProgress = container.progressRepository.observeCampaignProgress().first()
        val beforeTime = container.playTimeRepository.snapshot(campaign)
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.last().word)
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        openTime()
        assertEquals(beforeProgress, container.progressRepository.observeCampaignProgress().first())
        assertEquals(beforeTime, container.playTimeRepository.snapshot(campaign))
        compose.activityRule.scenario.recreate()
        awaitTag("time_total")
        back()
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.first().word)
        compose.onNodeWithTag("level_card_1").performClick()
        awaitTag("game_tasks_summary")
        if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Закрыть").performClick()
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("черновик")
        openTime()
        container.playTimeTracker.awaitSaved()
        val stopped = container.playTimeRepository.snapshot(campaign)
        val progress = container.progressRepository.observeCampaignProgress().first()
        // Recomposition, scrolling and recreation on the time route never create new segments.
        compose.onNodeWithTag("time_screen").performScrollToKey("level_15")
        compose.activityRule.scenario.recreate()
        awaitTag("time_screen")
        container.playTimeTracker.awaitSaved()
        assertEquals(stopped, container.playTimeRepository.snapshot(campaign))
        assertEquals(progress, container.progressRepository.observeCampaignProgress().first())
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithText("Правила").performClick()
        compose.onNodeWithTag("time_screen").assertDoesNotExist()
        back()
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitTag("game_tasks_summary")
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        compose.onNodeWithText("Статистика слов").performScrollTo().performClick()
        compose.onNodeWithText("Статистика по слову", substring = true).assertExists()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        compose.onNodeWithTag("game_back").performClick()
    }
}
