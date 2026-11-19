package com.example.sostavslova.ui.game

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.MainActivity
import com.example.sostavslova.R
import com.example.sostavslova.ui.dismissWelcomeIfShown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import com.example.sostavslova.ui.IsolatedGameStorageRule

@RunWith(AndroidJUnit4::class)
class GameNavigationTest {
    private val diagnostics = GameRootDiagnostics()
    private val storage = IsolatedGameStorageRule()
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(diagnostics).around(storage).around(compose).around(diagnostics.failure)
    private val container get() = storage.container
    @Before fun setUp() = runTest {
        container.wordRepository.ensureLoaded()
        compose.waitUntil(10_000) { compose.onNodeWithTag("level_card_1").isEnabled() }
        compose.dismissWelcomeIfShown()
    }
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
    private suspend fun awaitCorrectAnswer(answer: String) {
        if (InstrumentationRegistry.getArguments().getString("g12Trace") == "true") {
            diagnostics.capture("before-feedback")
        }
        val message = if (answer.length == 3 && container.levelsRepository.getCampaign() != null)
            R.string.correct_three_letter_answer else R.string.correct_answer
        awaitText(InstrumentationRegistry.getInstrumentation().targetContext.getString(message))
    }
    private suspend fun awaitOpenedHint() {
        val tag = if (container.levelsRepository.getCampaign() != null) "game_hint_pattern" else "game_hint"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun unsubmittedAnswerIsDiscardedOnBackAndRecreation() = runTest {
        val source = source()
        start()
        val saved = container.progressRepository.observeCampaignProgress().first()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Без отправки")
        back()
        start()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        compose.onNode(hasSetTextAction()).performTextReplacement("Снова без отправки")
        compose.activityRule.scenario.recreate()
        awaitText("Найденные слова · 0 из")
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        assertTrue(foundAnswers(source).isEmpty())
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
    }

    @Test fun levelAnswersShowLengthTotalsAndNonInteractiveFoundWordsRemainAvailable() = runTest {
        start()
        val source = source()
        val valid = validAnswer(source)
        answer(valid); awaitCorrectAnswer(valid)
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsFocused()
        assertEquals(listOf(valid), foundAnswers(source))
        awaitText("Найденные слова · 1 из")
        answer(valid.uppercase()); awaitText("Вы уже разгадали слово $valid")
        // Зачёт и повтор уже найденного очищают поле, отказы — нет.
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        answer("пaр"); awaitText("Допустимы только русские буквы, без пробелов и знаков")
        compose.onNode(hasSetTextAction()).assertTextEquals("пaр")
        val unknown = unknownAnswer()
        answer(unknown); awaitText("Слово «$unknown» не распознано словарём")
        compose.onNode(hasSetTextAction()).assertTextEquals(unknown)
        val total = container.wordRepository.startGame(source).statistics().rows
            .single { it.length == valid.length }.total
        compose.onNodeWithTag("level_found_group_${valid.length}").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))
            .assertTextContains("1 из $total", substring = true)
        compose.onNodeWithText(valid).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(valid).performTouchInput { click() }
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        compose.onNodeWithTag("game_screen").assertExists()
        assertEquals(listOf(valid), foundAnswers(source))
        compose.onNodeWithTag("level_found_group_${valid.length}").performScrollTo().performClick()
        compose.onNodeWithText(valid).assertDoesNotExist()
    }

    @Test fun hintAndBackKeepSavedAnswers() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitCorrectAnswer(valid)
        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        awaitOpenedHint()
        compose.onNodeWithTag("game_hint").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithTag("levels_screen").assertExists()
        assertEquals(listOf(valid), foundAnswers(source))
    }

    @Test fun recreationAndBackKeepAnswersAndDiscardDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitCorrectAnswer(valid)
        compose.onNodeWithTag("game_hint_button").performScrollTo().performClick()
        awaitOpenedHint()
        val saved = container.progressRepository.observeCampaignProgress().first()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("ЧеРнОвИк")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Найденные слова · 1 из", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
        compose.onNode(hasSetTextAction()).performTextReplacement("После пересоздания")
        back()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitText("Найденные слова · 1 из")
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        awaitOpenedHint()
        assertEquals(listOf(valid), foundAnswers(source))
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
        val total = container.wordRepository.startGame(source).statistics().rows
            .single { it.length == valid.length }.total
        compose.onNodeWithTag("level_found_group_${valid.length}").performScrollTo()
            .assertTextContains("1 из $total", substring = true)
        compose.onNodeWithText(valid).performScrollTo().assertIsDisplayed()
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
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Перед выходом")
        compose.onNodeWithTag("game_back").performClick()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitText("Найденные слова · 1 из")
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        awaitOpenedHint()
        assertEquals(beforeReturn, container.progressRepository.observeCampaignProgress().first())
    }

    @Test fun mapRecreationDiscardsDraftsWithoutTransferringThemBetweenLevels() = runTest {
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
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
                .performTextReplacement(draft)
            compose.onNodeWithTag("game_back").performClick()
        }
        val saved = container.progressRepository.observeCampaignProgress().first()
        val first = campaign.levels.first()
        assertTrue(saved.levels[first.word]?.completedTaskIds.orEmpty().size >= first.requiredStars)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("level_card_1").fetchSemanticsNodes().isNotEmpty() }
        for (number in listOf(1, 2)) {
            compose.onNodeWithTag("level_card_$number").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
            back()
        }
        assertEquals(saved, container.progressRepository.observeCampaignProgress().first())
    }

    @Test fun repeatedResumeKeepsSessionAndRecreationDiscardsDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitCorrectAnswer(valid)
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
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("")
        assertEquals(hint, hintText())
        assertEquals(saved, foundAnswers(source))
    }
}
