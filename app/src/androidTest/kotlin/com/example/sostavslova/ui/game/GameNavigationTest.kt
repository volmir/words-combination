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
    }
    @After fun tearDown() = runTest { container.progressRepository.clearAll() }
    private suspend fun source() = container.levelsRepository.getCampaign()?.levels?.first()?.word
        ?: container.levelsRepository.getLevels().first().word
    private fun validAnswer(source: String) = if (source == "чемпионство") "нос" else "пар"
    private fun invalidAnswer(source: String) = if (source == "чемпионство") "пар" else "нос"
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
        awaitText("вы отгадали 0")
    }
    private fun answer(raw: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(raw)
        compose.onNode(hasSetTextAction()).performImeAction()
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun levelAnswersStatisticsAndDefinitionsRemainAvailable() = runTest {
        start()
        val source = source()
        val valid = validAnswer(source)
        answer(valid); awaitText("Правильно!")
        compose.onNode(hasSetTextAction()).assertTextEquals("").assertIsFocused()
        assertEquals(listOf(valid), foundAnswers(source))
        awaitText("вы отгадали 1")
        answer(valid.uppercase()); awaitText("Вы уже разгадали слово $valid")
        answer("пaр"); awaitText("Введите слово длиной не менее 3-х символов")
        answer(invalidAnswer(source)); awaitText("Слово ${invalidAnswer(source)} не найдено!")
        compose.onNodeWithText("Статистика").performScrollTo().performClick()
        compose.onNodeWithText("Статистика по слову", substring = true).assertIsDisplayed()
        val total = container.wordRepository.startGame(source).total
        compose.onNodeWithText("1 / $total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText(valid).performScrollTo().performClick()
        val meaning = container.definitionsRepository.definitionsFor(valid).first().text
        compose.waitUntil(10_000) { compose.onAllNodesWithText(meaning, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(meaning, substring = true).assertIsDisplayed()
    }

    @Test fun hintAndBackKeepSavedAnswers() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNodeWithText("Показать подсказку").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("game_hint").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithTag("levels_screen").assertExists()
        assertEquals(listOf(valid), foundAnswers(source))
    }

    @Test fun recreationAndBackContinueRestoreAnswersAndDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("ЧеРнОвИк")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("вы отгадали 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        back()
        compose.onNodeWithTag("level_card_1").performClick()
        awaitText("вы отгадали 1")
        assertEquals(listOf(valid), foundAnswers(source))
    }

    @Test fun repeatedResumeAndRotationKeepHintAnswersAndDraft() = runTest {
        val source = source()
        val valid = validAnswer(source)
        start(); answer(valid); awaitText("Правильно!")
        compose.onNodeWithText("Показать подсказку").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_hint").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("game_hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals("ЧеРнОвИк")
        assertEquals(hint, hintText())
        assertEquals(saved, foundAnswers(source))
    }
}
