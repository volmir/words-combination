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

/**
 * Compose UI-тесты (androidTest) для главных сценариев приложения:
 * 1. Ввод «портал» → экран игры → ввод «пар» → «Правильно!» → счёт «вы отгадали 1».
 * 2. Неверное слово → «Слово … не найдено!»; повтор верного → «Вы уже разгадали слово …».
 * 3. Подсказка не раскрывает слово (текст подсказки не содержит ни одного из ещё не найденных слов целиком).
 * 4. Завершить игру → подтверждение → возврат на главную; «Продолжить игру» исчезает.
 * 5. Прохождение: уровень 2 заблокирован на чистых данных.
 * 6. Поиск слов: «акробат» → 100 слов.
 * Детерминированность обеспечивается фиксированием Random через внедрение в AppContainer.
 */
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
        compose.waitUntil(10_000) { compose.onNodeWithText("Начать игру").isEnabled() }
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

    private fun startWithWord(word: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(word)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("game_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun answerWord(word: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(word)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    /**
     * Сценарий 1: Ввод «портал» → экран игры → ввод «пар» → «Правильно!» → счёт «вы отгадали 1».
     */
    @Test
    fun scenario1_portalInputGameScreenAnswerParCorrectAndScoreUpdated() = runTest {
        startWithWord("портал")
        awaitText("всего найдено 47 слов, вы отгадали 0")

        answerWord("пар")
        awaitText("Правильно!")
        awaitText("вы отгадали 1", substring = true)
        assertEquals(listOf("пар"), container.progressRepository.observeCurrentGame().first()?.found)
    }

    /**
     * Сценарий 2: Неверное слово → «Слово … не найдено!»; повтор верного → «Вы уже разгадали слово …».
     */
    @Test
    fun scenario2_incorrectWordNotFoundAndRepeatedWordAlreadyFound() = runTest {
        startWithWord("портал")
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
        startWithWord("портал")
        answerWord("пар")
        awaitText("Правильно!")

        val game = container.progressRepository.observeCurrentGame().first()
        val allBuildable = container.wordRepository.search("портал")?.words ?: emptyList()
        val unrevealedWords = (allBuildable - (game?.found.orEmpty())).filter { it.length >= 3 }
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

    /**
     * Сценарий 4: Завершить игру → подтверждение → возврат на главную; «Продолжить игру» исчезает.
     */
    @Test
    fun scenario4_finishGameConfirmationReturnsHomeAndContinueGameDisappears() = runTest {
        startWithWord("портал")
        answerWord("пар")
        awaitText("Правильно!")
        assertNotNull(container.progressRepository.observeCurrentGame().first())

        compose.onNodeWithText("Завершить игру").performScrollTo().performClick()
        awaitText("Вы уверены, что хотите завершить игру?")

        compose.onNodeWithText("Да").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Продолжить игру").assertDoesNotExist()
        assertNull(container.progressRepository.observeCurrentGame().first())
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

    /**
     * Сценарий 6: Поиск слов: «акробат» → 100 слов.
     */
    @Test
    fun scenario6_wordSearchAkrobatReturns100Words() = runTest {
        compose.onNodeWithContentDescription("Открыть меню").performClick()
        compose.onNodeWithText("Поиск слов").performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("word_search_screen").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasSetTextAction()).performTextReplacement("акробат")
        compose.onNode(hasSetTextAction()).performImeAction()

        awaitText("Все слова, которые можно составить из \"акробат\"")
        awaitText("100 слов", substring = true)
    }
}
