package com.example.sostavslova.ui.game

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.ui.dismissWelcomeIfShown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class GameNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WordsApplication).container
    @Before fun setUp() = runTest {
        container.progressRepository.clearAll()
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) { compose.onNodeWithTag("level_card_1").isEnabled() }
        compose.dismissWelcomeIfShown()
    }
    @After fun tearDown() = runTest { container.progressRepository.clearAll() }
    private suspend fun source() = container.levelsRepository.getCampaign()?.levels?.first()?.word
        ?: container.levelsRepository.getLevels().first().word
    private fun validAnswer(source: String) = if (source == "чемпионство") "нос" else "пар"
    // Заведомо отсутствующее в словаре слово: реальное «пар» даёт отказ по буквам,
    // а не проверяемую здесь ветку «слово не распознано».
    private fun unknownAnswer() = "щщщ"
    private suspend fun foundAnswers(source: String): List<String> =
        container.levelsRepository.getCampaign()?.let { campaign ->
            container.progressRepository.observeCampaignProgress().first()
                .levels[source]?.found.orEmpty()
        } ?: container.progressRepository.levelAnswers(source)
    private fun SemanticsNodeInteraction.isEnabled() = runCatching { assertIsEnabled() }.isSuccess
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
    }
    private fun start() {
        compose.onNodeWithTag("level_card_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_tasks_summary").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Новые задания этапа").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Закрыть").performClick()
        }
        awaitText("Найденные слова · 0 из")
    }
    private fun answer(raw: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(raw)
        compose.onNode(hasSetTextAction()).performImeAction()
    }
    private suspend fun awaitOpenedHint() {
        val tag = if (container.levelsRepository.getCampaign() != null) "game_hint_part_1" else "game_hint"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun levelAnswersStatisticsAndNonInteractiveFoundWordsRemainAvailable() = runTest {
        start()
        val source = source()
        val valid = validAnswer(source)
        answer(valid); awaitText("Правильно!")
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsFocused()
        assertEquals(listOf(valid), foundAnswers(source))
        awaitText("Найденные слова · 1 из")
        answer(valid.uppercase()); awaitText("Вы уже разгадали слово $valid")
        answer("пaр"); awaitText("Введите слово длиной не менее 3-х символов")
        val unknown = unknownAnswer()
        answer(unknown); awaitText("Слово «$unknown» не распознано словарём")
        compose.onNodeWithText("Статистика слов").performScrollTo().performClick()
        compose.onNodeWithText("Статистика по слову", substring = true).assertIsDisplayed()
        val total = container.wordRepository.startGame(source).total
        compose.onNodeWithText("1 / $total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText(valid).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(valid).performTouchInput { click() }
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        compose.onNodeWithTag("game_screen").assertExists()
        assertEquals(listOf(valid), foundAnswers(source))
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText(valid).assertDoesNotExist()
    }

    @Test fun hintAndBackKeepSavedAnswers() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        awaitOpenedHint()
        compose.onNodeWithTag("game_hint").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithTag("levels_screen").assertExists()
        assertEquals(listOf(valid), foundAnswers(source))
    }

    @Test fun recreationAndBackContinueRestoreAnswersAndDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        awaitOpenedHint()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("ЧеРнОвИк")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Найденные слова · 1 из", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        back()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitText("Найденные слова · 1 из")
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        awaitOpenedHint()
        assertEquals(listOf(valid), foundAnswers(source))
        container.levelsRepository.getCampaign()?.levels?.first()?.let { level ->
            val saved = container.progressRepository.observeCampaignProgress().first().levels[source]
            val completed = level.tasks.count { it.id in saved?.completedTaskIds.orEmpty() }
            compose.onNodeWithTag("game_tasks_summary").performScrollTo()
                .assertTextEquals("Задания · $completed из ${level.tasks.size}")
            level.tasks.forEach { task ->
                compose.onNodeWithTag("level_task_${task.type.name.lowercase()}").assertExists()
            }
        }
        val beforeReturn = container.progressRepository.observeCampaignProgress().first()
        compose.onNodeWithTag("game_back").performClick()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitText("Найденные слова · 1 из")
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        awaitOpenedHint()
        assertEquals(beforeReturn, container.progressRepository.observeCampaignProgress().first())
    }

    @Test fun mapRecreationKeepsDraftsSeparateForDifferentLevels() = runTest {
        val campaign = requireNotNull(container.levelsRepository.getCampaign())
        com.example.sostavslova.core.TaskType.entries.forEach {
            container.progressRepository.markCampaignExplanationSeen(it)
        }
        val index = container.wordRepository.requireIndex()
        for (word in container.wordRepository.startGame(campaign.levels.first().word).allWords) {
            container.progressRepository.submitCampaignAnswerWithTransitions(campaign, 1, word, index)
        }
        compose.waitUntil(10_000) { compose.onNodeWithTag("level_card_2").isEnabled() }
        for ((number, draft) in listOf(1 to "Первый черновик", 2 to "Второй черновик")) {
            compose.onNodeWithTag("level_card_$number").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(draft)
            compose.onNodeWithTag("game_back").performClick()
        }
        val saved = container.progressRepository.observeCampaignProgress().first()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_card_1").fetchSemanticsNodes().isNotEmpty() }
        for ((number, draft) in listOf(1 to "Первый черновик", 2 to "Второй черновик")) {
            compose.onNodeWithTag("level_card_$number").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals(draft)
            back()
        }
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
    }

    @Test fun repeatedResumeAndRotationKeepHintAnswersAndDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        awaitOpenedHint()
        fun hintText() = compose.onAllNodes(hasAnyAncestor(hasTestTag("game_hint")), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        val hint = hintText()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("ЧеРнОвИк")
        val saved = foundAnswers(source)
        repeat(3) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
            assertEquals(hint, hintText())
        }
        compose.activityRule.scenario.recreate()
        awaitOpenedHint()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        assertEquals(hint, hintText())
        assertEquals(saved, foundAnswers(source))
    }
}
