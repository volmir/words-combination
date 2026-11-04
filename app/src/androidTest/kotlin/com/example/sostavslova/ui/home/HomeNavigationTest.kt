package com.example.sostavslova.ui.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
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

/** Реальный NavHost, SavedStateHandle, словарь и DataStore приложения. */
@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication).container
    private var originalGame: CurrentGame? = null

    @Before
    fun setUp() = runTest {
        originalGame = container.progressRepository.observeCurrentGame().first()
        container.progressRepository.clearCurrentGame()
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) {
            compose.onNodeWithText("Начать игру").fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        }
    }

    @After
    fun tearDown() = runTest {
        container.progressRepository.clearCurrentGame()
        originalGame?.let { saved ->
            container.progressRepository.saveGame(container.wordRepository.startGame(saved.word, saved.found))
        }
    }

    private fun enter(raw: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(raw)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    private fun awaitGameScreen() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("game_screen").assertIsDisplayed()
    }

    @Test
    fun emptyShortAndOverflowShowExactMessagesAndDoNotNavigate() = runTest {
        for ((raw, message) in listOf("" to "Для начала игры введите корректное слово",
            "до" to "Введите слово длиной не менее 3-х символов", "ёж" to "Введите слово длиной не менее 3-х символов",
            "а".repeat(31) to "Введите слово длиной не более 30-ти символов")) {
            enter(raw)
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("home_screen").assertExists()
            assertNull(container.progressRepository.observeCurrentGame().first())
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("а".repeat(30))
        compose.onNodeWithText("Начать игру").performScrollTo().performClick()
        assertNull(container.progressRepository.observeCurrentGame().first())
    }

    @Test
    fun uppercaseAndLowercaseSaveCanonicalGameBeforeNavigation() = runTest {
        enter("ПОРТАЛ")
        awaitGameScreen()
        assertEquals("портал", container.progressRepository.observeCurrentGame().first()?.word)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertTextEquals("ПОРТАЛ")
        enter("портал")
        awaitGameScreen()
        assertEquals("портал", container.progressRepository.observeCurrentGame().first()?.word)
    }

    @Test
    fun aaaPreservesCurrentGameAndContinueCardOpensGame() = runTest {
        val saved = container.wordRepository.startGame("портал", listOf("пар"))
        container.progressRepository.saveGame(saved)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Продолжить игру").fetchSemanticsNodes().isNotEmpty() }
        enter("ааа")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Комбинации слов не найдены!").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Комбинации слов не найдены!").performScrollTo().assertIsDisplayed()
        assertEquals(CurrentGame("портал", listOf("пар")), container.progressRepository.observeCurrentGame().first())
        compose.onNodeWithText("ПОРТАЛ — отгадано 1 из ${saved.total}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Продолжить игру").performScrollTo().performClick()
        awaitGameScreen()
        assertEquals(CurrentGame("портал", listOf("пар")), container.progressRepository.observeCurrentGame().first())
    }

    @Test
    fun activityRecreationPreservesInputAndOverflowValidation() {
        enter("а".repeat(31))
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextEquals("а".repeat(30))
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("Введите слово длиной не более 30-ти символов").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("КаМеНиСтОсТь")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextEquals("КаМеНиСтОсТь")
    }
}
