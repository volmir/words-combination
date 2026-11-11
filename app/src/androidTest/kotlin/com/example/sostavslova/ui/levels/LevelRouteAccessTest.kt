package com.example.sostavslova.ui.levels

import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.nav.WordsNavHost
import com.example.sostavslova.ui.theme.WordsTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ссылки и обычная навигация приходят в тот же маршрут и проверку доступа. */
@RunWith(AndroidJUnit4::class)
class LevelRouteAccessTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var nav: NavHostController
    private val progress get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as WordsApplication).container.progressRepository

    @Before fun setUp() = runTest {
        progress.clearAll()
        compose.setContent {
            val controller = rememberNavController()
            val dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect { nav = controller; back = dispatcher }
            WordsTheme { WordsNavHost(controller) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_1").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun tearDown() = runTest { progress.clearAll() }

    @Test fun invalidIndicesReturnToMapWithMessage() {
        for (index in listOf("bad", "0", "-1", "31", "999999999999")) {
            compose.runOnIdle { nav.navigate("level/$index") }
            awaitMapMessage("Такого уровня нет. Выберите уровень на карте.")
        }
    }

    @Test fun closedDeepLinkReturnsToMapAndAvailableLinkOpensGame() {
        compose.runOnIdle { nav.navigate(Uri.parse("sostavslova://level/2")) }
        awaitMapMessage("Этот уровень ещё закрыт. Пройдите предыдущий уровень.")
        compose.onNodeWithTag("level_2").assertIsNotEnabled()
        compose.runOnIdle { nav.navigate(Uri.parse("sostavslova://level/1")) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_screen").assertIsDisplayed()
    }

    @Test fun backFromLinkedLevelReturnsToMap() {
        compose.runOnIdle {
            nav.navigate(Uri.parse("sostavslova://level/1"))
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("game_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { back.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("levels_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
    }

    private fun awaitMapMessage(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("levels_screen").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
