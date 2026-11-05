package com.example.sostavslova.ui.game

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class GameNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication).container
    private var originalGame: CurrentGame? = null

    @Before fun setUp() = runTest {
        originalGame = container.progressRepository.observeCurrentGame().first()
        container.progressRepository.clearCurrentGame()
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) { compose.onNodeWithText("Начать игру").isEnabled() }
    }
    @After fun tearDown() = runTest {
        container.progressRepository.clearCurrentGame()
        originalGame?.let { container.progressRepository.saveGame(container.wordRepository.startGame(it.word, it.found)) }
    }
    private fun SemanticsNodeInteraction.isEnabled() = runCatching { assertIsEnabled() }.isSuccess
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    private fun start() {
        compose.onNode(hasSetTextAction()).performTextReplacement("портал")
        compose.onNode(hasSetTextAction()).performImeAction()
        awaitText("всего найдено 47 слов, вы отгадали 0")
    }
    private fun answer(raw: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(raw)
        compose.onNode(hasSetTextAction()).performImeAction()
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun portalAnswersAndStatisticsMatchAuditedGoldenDictionary() = runTest {
        start()
        answer("пар"); awaitText("Правильно!")
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsFocused()
        assertEquals(listOf("пар"), container.progressRepository.observeCurrentGame().first()?.found)
        awaitText("всего найдено 47 слов, вы отгадали 1")
        answer("ПАР"); awaitText("Вы уже разгадали слово пар")
        answer("пaр"); awaitText("Введите слово длиной не менее 3-х символов")
        answer("нос"); awaitText("Слово нос не найдено!")
        compose.onNodeWithText("Статистика").performScrollTo().performClick()
        compose.onNodeWithText("Статистика по слову \"ПОРТАЛ\"").assertIsDisplayed()
        compose.onNodeWithText("1 / 47").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        compose.onNodeWithText("пар").performScrollTo().performClick()
        compose.onNodeWithText("Определения недоступны в этой версии").assertIsDisplayed()
    }

    @Test fun fallbackHintCancelAndFinishClearOnlyCurrentGame() = runTest {
        start(); answer("пар"); awaitText("Правильно!")
        compose.onNodeWithText("Показать подсказку").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Слово из ", substring = true).assertExists()
        compose.onNodeWithText("Завершить игру").performScrollTo().performClick()
        compose.onNodeWithText("Нет").performClick()
        assertNotNull(container.progressRepository.observeCurrentGame().first())
        compose.onNodeWithText("Завершить игру").performClick()
        compose.onNodeWithText("Да").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Продолжить игру").assertDoesNotExist()
        assertNull(container.progressRepository.observeCurrentGame().first())
        // Повторный вход через меню не восстанавливает завершённый GameViewModel.
        compose.onNodeWithContentDescription("Открыть меню").performClick()
        compose.onNodeWithText("Игра").performClick()
        awaitText("Для начала игры введите корректное слово")
        compose.onNodeWithTag("home_screen").assertExists()
    }

    @Test fun recreationAndBackContinueRestoreAnswersAndDraft() = runTest {
        start(); answer("пар"); awaitText("Правильно!")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("ЧеРнОвИк")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("всего найдено 47 слов, вы отгадали 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        back()
        compose.onNodeWithText("Продолжить игру").performScrollTo().performClick()
        awaitText("всего найдено 47 слов, вы отгадали 1")
        assertEquals(listOf("пар"), container.progressRepository.observeCurrentGame().first()?.found)
    }
}
