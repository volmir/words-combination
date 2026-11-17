package com.example.sostavslova.ui.game

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FoundWordsSectionTest(private val legacy: Boolean) {
    @get:Rule val compose = createComposeRule()
    // Synthetic words only: 49 three-letter, 49 four-letter and 48 five-letter strings.
    private val letters = "абвгдежзиклмнопрстуфхцчшщэюя"
    private val words = List(146) { index ->
        "а".repeat(1 + index % 3) + letters[index / 3 / letters.length] + letters[index / 3 % letters.length]
    } + "демонстрационное"

    private fun state(found: List<String>): GameUiState {
        val game = GameState("а".repeat(30), words, found)
        val campaign = CampaignLevel(1, game.word, 1, 1, listOf(CampaignTask.T1("count", 150)))
        return GameUiState(loading = false, game = game, statistics = game.statistics(),
            totalLevels = 15, campaignLevel = if (legacy) null else CurrentCampaignLevel(campaign, game),
            level = if (legacy) Level(1, game.word, 8, 150, 'а', 5) else null)
    }

    @Test fun emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions() {
        val state = mutableStateOf(state(emptyList()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, { state.value = state.value.copy(dialog = it) },
                    { state.value = state.value.copy(dialog = null) }, {})
            }
        }
        compose.onAllNodesWithTag("game_found_summary").assertCountEquals(1)
        compose.onNodeWithTag("game_found_summary").performScrollTo()
            .assertTextEquals("Найденные слова · 0 из 147")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        sectionActions().assertCountEquals(1)
        compose.onNodeWithTag("game_word_statistics").performScrollTo().performClick()
        compose.onNodeWithTag("level_stats").assertExists()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        compose.runOnIdle { state.value = state(words.take(146)) }
        compose.onNodeWithTag("game_found_summary").performScrollTo().assertTextEquals("Найденные слова · 146 из 147")
        compose.onAllNodesWithText("Найденные слова", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Найдено", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Статистика слов").assertCountEquals(1)
        compose.onAllNodesWithTag("game_hint_button").assertCountEquals(1)
        compose.onNodeWithText("Задания").assertDoesNotExist()
        sectionActions().assertCountEquals(4)
        for ((length, found) in words.take(146).groupBy { it.length }.toSortedMap()) {
            val group = compose.onNodeWithTag("level_found_group_$length")
            group.performScrollTo().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнута"))
                .assertTextContains("$length ${if (length == 5) "букв" else "буквы"} · ${found.size} слов")
            group.performClick()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))
            for (word in found) {
                compose.onNodeWithText(word).assertHasNoClickAction()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            }
            compose.onNodeWithText(found.last()).performScrollTo().assertIsDisplayed()
            assertTextFits()
        }
        sectionActions().assertCountEquals(4)
        val headerY = compose.onNodeWithTag("game_found_summary").fetchSemanticsNode().positionInRoot.y
        val firstGroupY = compose.onNodeWithTag("level_found_group_3").fetchSemanticsNode().positionInRoot.y
        val lastGroupY = compose.onNodeWithTag("level_found_group_5").fetchSemanticsNode().positionInRoot.y
        val statisticsY = compose.onNodeWithTag("game_word_statistics").fetchSemanticsNode().positionInRoot.y
        assertTrue(headerY < firstGroupY && firstGroupY < lastGroupY && lastGroupY < statisticsY)
        if (!legacy) {
            val tasksY = compose.onNodeWithTag("game_tasks").fetchSemanticsNode().positionInRoot.y
            val hintY = compose.onNodeWithTag("game_hint").fetchSemanticsNode().positionInRoot.y
            assertTrue(tasksY < hintY && hintY < headerY)
        }
        compose.onNodeWithText("Показать значение", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        val statistics = compose.onNodeWithTag("game_word_statistics")
        statistics.performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        statistics.assertIsDisplayed() // Scroll position, as well as group expansion, survives restoration.
        for (length in 3..5) {
            compose.onNodeWithTag("level_found_group_$length")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))
        }
        statistics.performClick()
        compose.onNodeWithTag("level_stats").assertExists()
        compose.onNodeWithText("146 / 147").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        for (length in 3..5) {
            compose.onNodeWithTag("level_found_group_$length").performScrollTo().performClick()
            compose.onNodeWithTag("game_found_words_$length").assertDoesNotExist()
        }
        // Include a long unbroken word to exercise wrapping inside the FlowRow.
        compose.runOnIdle { state.value = state(words) }
        compose.onNodeWithTag("level_found_group_${words.last().length}").performScrollTo().performClick()
        compose.onNodeWithText(words.last()).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        assertTextFits()
    }

    @Test fun largestInstalledLevelKeepsStatisticsReachableWithEveryGroupExpanded() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = com.example.sostavslova.data.CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val index = com.example.sostavslova.core.WordIndex(
            context.assets.open("words.txt").bufferedReader().use { it.readLines() })
        val games = campaign.levels.map { it to GameState.start(it.word, index) }
        val (level, initial) = games.maxBy { it.second.total }
        val game = initial.copy(found = initial.allWords)
        assertTrue("Stress fixture must contain at least 600 words", game.foundCount >= 600)
        val snapshot = GameUiState(loading = false, game = game, statistics = game.statistics(),
            totalLevels = campaign.levels.size, campaignLevel = CurrentCampaignLevel(level, game))
        compose.setContent {
            WordsTheme {
                GameScreen(snapshot, {}, {}, {}, {}, {}, {})
            }
        }
        for ((length, found) in game.found.groupBy { it.length }.toSortedMap()) {
            compose.onNodeWithTag("level_found_group_$length").performScrollTo().performClick()
            compose.onNodeWithText(found.last()).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        }
        compose.onNodeWithTag("game_word_statistics").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        for (length in game.found.map { it.length }.distinct()) {
            compose.onNodeWithTag("level_found_group_$length").performScrollTo().performClick()
            compose.onNodeWithTag("game_found_words_$length").assertDoesNotExist()
        }
    }

    private fun sectionActions() = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("game_found_words")))

    private fun assertTextFits() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { layout ->
                    assertTextLayoutFits(layout, "native, " +
                        "tag=${node.config.getOrNull(SemanticsProperties.TestTag)}")
                }
            }
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val minimum = 48f * compose.density.density - 1f
            assertTrue(node.size.toString(), node.size.width >= minimum && node.size.height >= minimum)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "legacy={0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
