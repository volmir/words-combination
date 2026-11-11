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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_1").assertIsEnabled()
        compose.onNodeWithTag("level_2").assertIsNotEnabled()
        val all = container.levelsRepository.getLevels()
        compose.onNodeWithTag("levels_grid").performScrollToKey(all.last().word)
        compose.onNodeWithTag("level_30").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_30").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_30").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey(all.first().word)
        compose.onNodeWithTag("level_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("вы отгадали 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("К карте уровней").assertExists()
        repeat(2) {
            compose.onNodeWithTag("game_back").performClick()
            compose.onNodeWithTag("levels_screen").assertIsDisplayed()
            compose.onNodeWithTag("level_1").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("вы отгадали 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithTag("level_1").assertIsEnabled()
    }
}
