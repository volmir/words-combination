package com.example.sostavslova.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.R
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Основные сценарии кампании: выбор уровня, ответы, подсказка и возврат. */
@RunWith(AndroidJUnit4::class)
class MainScenariosUiTest {
    private val storage = IsolatedGameStorageRule()
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(compose)

    private val container
        get() = storage.container

    @Before
    fun setUp() = runTest {
        container.random = Random(42)
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) { compose.onNodeWithTag("level_card_1").isEnabled() }
        compose.dismissWelcomeIfShown()
    }

    private fun SemanticsNodeInteraction.isEnabled() = runCatching { assertIsEnabled() }.isSuccess

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, substring = substring).performScrollTo().assertIsDisplayed()
    }

    private fun startLevel() {
        compose.onNodeWithTag("level_card_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_screen").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Закрыть").performClick()
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.check_answer)).assertIsEnabled()
    }
    private suspend fun source() = requireNotNull(container.levelsRepository.getCampaign()).levels.first().word

    // Обычное «Правильно!» проверяем на слове от четырёх букв.
    // У трёхбуквенного ответа кампании своё сообщение и отдельный сценарий ниже.
    private suspend fun validAnswer(source: String) =
        container.wordRepository.startGame(source).allWords.first { it.length >= 4 }
    // Заведомо отсутствующее в словаре слово для проверки ветки NOT_FOUND.
    private fun unknownAnswer() = "щщщ"

    private suspend fun foundAnswers(source: String): List<String> =
        container.progressRepository.observeCampaignProgress().first().levels[source]?.found.orEmpty()

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
        val source = source()
        val game = container.wordRepository.startGame(source)
        awaitText("Найденные слова · 0 из", substring = true)
        val valid = validAnswer(source)
        assertTrue(valid in game.allWords)

        answerWord(valid)
        awaitText("Правильно!")
        awaitText("Найденные слова · 1 из", substring = true)
        assertEquals(listOf(valid), foundAnswers(source))
    }

    @Test
    fun threeLetterAnswerAndRepeatKeepFeedbackCountAndSavedProgress() = runTest {
        startLevel()
        val source = source()
        val answer = container.wordRepository.startGame(source).allWords.first { it.length == 3 }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        answerWord(answer)
        awaitText(context.getString(R.string.correct_three_letter_answer))
        awaitText("Найденные слова · 1 из", substring = true)
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsEnabled()
        assertEquals(listOf(answer), foundAnswers(source))
        val saved = container.progressRepository.observeCampaignProgress().first()
        assertTrue(saved.levels.getValue(source).completedTaskIds.isEmpty())

        answerWord(answer.uppercase())
        awaitText("Вы уже нашли слово $answer")
        awaitText("Найденные слова · 1 из", substring = true)
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsEnabled()
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
        compose.onNodeWithTag("game_back").performClick()
        startLevel()
        awaitText("Найденные слова · 1 из", substring = true)
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
    }

    /**
     * Сценарий 2: Неизвестное слово → «Слово … не распознано словарём»;
     * повтор верного → «Вы уже нашли слово …».
     */
    @Test
    fun scenario2_incorrectWordNotFoundAndRepeatedWordAlreadyFound() = runTest {
        startLevel()
        val source = source()
        val valid = validAnswer(source)
        answerWord(valid)
        awaitText("Правильно!")

        val unknown = unknownAnswer()
        answerWord(unknown)
        awaitText("Слово «$unknown» не распознано словарём")

        answerWord(valid)
        awaitText("Вы уже нашли слово $valid")
        awaitText("Найденные слова · 1 из", substring = true)
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsEnabled()
        assertEquals(listOf(valid), foundAnswers(source))
    }

    /**
     * Сценарий 3: Подсказка не раскрывает слово (текст подсказки не содержит ни одного из ещё не найденных слов целиком).
     */
    @Test
    fun scenario3_hintDoesNotRevealUnfoundWords() = runTest {
        startLevel()
        val source = source()
        val valid = validAnswer(source)
        answerWord(valid)
        awaitText("Правильно!")

        val found = foundAnswers(source)
        val allBuildable = container.wordRepository.search(source)?.words ?: emptyList()
        val unrevealedWords = (allBuildable - found).filter { it.length >= 3 }
        assertTrue("Должны быть неотгаданные слова", unrevealedWords.isNotEmpty())

        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("game_hint_pattern").fetchSemanticsNodes().isNotEmpty()
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
        val source = source()
        val valid = validAnswer(source)
        startLevel(); answerWord(valid); awaitText("Правильно!")
        compose.onNodeWithText("Завершить игру").assertDoesNotExist()
        compose.onNodeWithTag("game_back").performClick()
        compose.onNodeWithTag("levels_screen").assertIsDisplayed()
        startLevel()
        awaitText("Найденные слова · 1 из", substring = true)
        assertEquals(listOf(valid), foundAnswers(source))
    }

    /**
     * Сценарий 5: Прохождение: уровень 2 заблокирован на чистых данных.
     */
    @Test
    fun scenario5_levelsLevel2IsLockedOnCleanData() = runTest {
        compose.onNodeWithTag("app_title").assertTextEquals("Составь слова")
        // Убеждаемся, что данные чистые
        container.progressRepository.clearAll()

        compose.onNodeWithContentDescription("Открыть меню").performClick()
        compose.onNodeWithText("Прохождение").performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("level_card_1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("level_card_1").assertIsEnabled()
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assert(hasContentDescription("Уровень недоступен"))
    }

}
