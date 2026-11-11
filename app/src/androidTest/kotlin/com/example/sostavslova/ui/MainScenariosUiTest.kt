package com.example.sostavslova.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.WordsApplication
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Основные сценарии кампании: выбор уровня, ответы, подсказка и возврат. */
@RunWith(AndroidJUnit4::class)
class MainScenariosUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication).container

    @Before
    fun setUp() = runTest {
        container.random = Random(42)
        container.progressRepository.clearAll()
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) { compose.onNodeWithTag("level_1").isEnabled() }
    }

    @After
    fun tearDown() = runTest {
        container.random = Random.Default
        container.progressRepository.clearAll()
    }

    private fun SemanticsNodeInteraction.isEnabled() = runCatching { assertIsEnabled() }.isSuccess

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, substring = substring).performScrollTo().assertIsDisplayed()
    }

    private fun startLevel() {
        compose.onNodeWithTag("level_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_screen").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }
    private suspend fun source() = container.levelsRepository.getLevels().first().word

    private fun answerWord(word: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(word)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    /**
     * Доступный уровень → ответ → сохранённый счёт.
     */
    @Test
    fun levelAnswerIsSavedAndScoreUpdated() = runTest {
        startLevel()
        val game = container.wordRepository.startGame(source())
        awaitText("вы отгадали 0", substring = true)
        assertTrue("пар" in game.allWords)

        answerWord("пар")
        awaitText("Правильно!")
        awaitText("вы отгадали 1", substring = true)
        assertEquals(listOf("пар"), container.progressRepository.levelAnswers(source()))
    }

    /**
     * Сценарий 2: Неверное слово → «Слово … не найдено!»; повтор верного → «Вы уже разгадали слово …».
     */
    @Test
    fun scenario2_incorrectWordNotFoundAndRepeatedWordAlreadyFound() = runTest {
        startLevel()
        answerWord("пар")
        awaitText("Правильно!")

        answerWord("нос")
        awaitText("Слово нос не найдено!")

        answerWord("пар")
        awaitText("Вы уже разгадали слово пар")
    }

    /**
     * Сценарий 3: Подсказка не раскрывает слово (текст подсказки не содержит ни одного из ещё не найденных слов целиком).
     */
    @Test
    fun scenario3_hintDoesNotRevealUnfoundWords() = runTest {
        startLevel()
        answerWord("пар")
        awaitText("Правильно!")

        val found = container.progressRepository.levelAnswers(source())
        val allBuildable = container.wordRepository.search(source())?.words ?: emptyList()
        val unrevealedWords = (allBuildable - found).filter { it.length >= 3 }
        assertTrue("Должны быть неотгаданные слова", unrevealedWords.isNotEmpty())

        compose.onNodeWithText("Показать подсказку").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("game_hint").fetchSemanticsNodes().isNotEmpty()
        }
        val hintNode = compose.onNodeWithTag("game_hint").performScrollTo()
        hintNode.assertIsDisplayed()

        // Извлекаем отображаемый текст из блока подсказки
        val semantics = hintNode.fetchSemanticsNode()
        val textList = semantics.children.flatMap { child ->
            child.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        }.map { it.text } + semantics.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text }
        val fullHintText = textList.joinToString(" ").lowercase()

        // Разбиваем подсказку на токены слов
        val hintTokens = fullHintText.split(Regex("[^а-яёa-z0-9]+")).filter { it.isNotBlank() }.toSet()

        // Проверяем, что ни одно из неотгаданных слов целиком не содержится в подсказке как отдельное слово
        for (unrevealed in unrevealedWords) {
            assertFalse(
                "Подсказка не должна содержать неотгаданное слово '$unrevealed' целиком: '$fullHintText'",
                hintTokens.contains(unrevealed)
            )
        }
    }

    @Test
    fun backToMapAndReopenPreservesAnswers() = runTest {
        startLevel(); answerWord("пар"); awaitText("Правильно!")
        compose.onNodeWithText("Завершить игру").assertDoesNotExist()
        compose.onNodeWithTag("game_back").performClick()
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        startLevel()
        awaitText("вы отгадали 1", substring = true)
        assertEquals(listOf("пар"), container.progressRepository.levelAnswers(source()))
    }

    /**
     * Сценарий 5: Прохождение: уровень 2 заблокирован на чистых данных.
     */
    @Test
    fun scenario5_levelsLevel2IsLockedOnCleanData() = runTest {
        // Убеждаемся, что данные чистые
        container.progressRepository.clearAll()

        compose.onNodeWithContentDescription("Открыть меню").performClick()
        compose.onNodeWithText("Прохождение").performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("level_1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("level_1").assertIsEnabled()
        compose.onNodeWithTag("level_2").assertIsNotEnabled()
    }

}
