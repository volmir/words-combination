package com.example.sostavslova.ui.levels

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.data.CurrentGame
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
    private var originalGame: CurrentGame? = null
    @Before fun setUp() = runTest {
        originalGame = container.progressRepository.observeCurrentGame().first()
        container.progressRepository.clearCurrentGame()
    }
    @After fun tearDown() = runTest {
        container.progressRepository.clearCurrentGame()
        originalGame?.let { container.progressRepository.saveGame(container.wordRepository.startGame(it.word, it.found)) }
    }

    @Test fun drawerOpensLevelsScrollSurvivesRecreationAndSelectionOpensRealGame() = runTest {
        compose.onNodeWithContentDescription("Открыть меню").performClick()
        compose.onNodeWithText("Прохождение").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_1").assertIsEnabled()
        compose.onNodeWithTag("level_2").assertIsNotEnabled()
        compose.onNodeWithTag("levels_grid").performScrollToKey("пудлинговка")
        compose.onNodeWithTag("level_30").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_30").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("level_30").assertIsDisplayed()
        compose.onNodeWithTag("levels_grid").performScrollToKey("рекуперация")
        compose.onNodeWithTag("level_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("всего найдено 133 слова, вы отгадали 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_screen").assertIsDisplayed()
        assertEquals("рекуперация", container.progressRepository.observeCurrentGame().first()?.word)
        repeat(2) {
            compose.onNodeWithContentDescription("Открыть меню").performClick()
            compose.onNodeWithText("Прохождение").performClick()
            compose.onNodeWithTag("levels_screen").assertIsDisplayed()
            compose.onNodeWithTag("level_1").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("всего найдено 133 слова, вы отгадали 0").fetchSemanticsNodes().isNotEmpty() }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithTag("level_1").assertIsEnabled()
    }
}
