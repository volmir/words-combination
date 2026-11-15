package com.example.sostavslova.ui.levels

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.WordsApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LevelsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication).container
    @Before fun setUp() = runTest { container.progressRepository.clearAll() }
    @After fun tearDown() = runTest { container.progressRepository.clearAll() }

    @Test fun drawerOpensLevelsScrollSurvivesRecreationAndSelectionOpensRealGame() = runTest {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_card_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_card_1").assertIsEnabled()
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assertTextContains("Закрыт", substring = true)
        compose.onNodeWithTag("level_card_2").performClick()
        compose.onNodeWithText("Для открытия наберите на уровне 1 1 звезду.").assertExists()
        compose.onNodeWithTag("game_screen").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").performClick()
        val campaign = requireNotNull(container.levelsRepository.getCampaign())
        assertEquals(15, campaign.levels.size)
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.last().word)
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_card_15").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_card_15").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey(campaign.levels.first().word)
        compose.onNodeWithTag("level_card_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("вы отгадали 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("К карте уровней").assertExists()
        repeat(2) {
            compose.onNodeWithTag("game_back").performClick()
            compose.onNodeWithTag("levels_screen").assertIsDisplayed()
            compose.onNodeWithTag("level_card_1").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("вы отгадали 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithTag("level_card_1").assertIsEnabled()
    }
}
