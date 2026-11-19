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
import androidx.compose.ui.text.font.FontWeight
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.RussianPlural
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

    @Test fun emptyStateShowsEveryLengthAndKeepsEmptyGroupsCollapsed() {
        val state = mutableStateOf(state(emptyList()))
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, { state.value = state.value.copy(dialog = it) },
                    { state.value = state.value.copy(dialog = null) }, {})
            }
        }
        compose.onAllNodesWithTag("game_found_summary").assertCountEquals(1)
        compose.onNodeWithTag("game_found_summary").performScrollTo()
            .assertTextEquals("Найденные слова · 0 из 147")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        for (row in state.value.statistics.rows) {
            compose.onNodeWithTag("level_found_group_${row.length}").performScrollTo()
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнута"))
                .assertTextContains("${row.length} ${RussianPlural.letters(row.length)} · 0 из ${row.total}")
            compose.onNodeWithTag("game_found_words_${row.length}").assertDoesNotExist()
        }
        compose.onNodeWithTag("game_word_statistics").assertDoesNotExist()
        compose.onAllNodesWithText("Статистика слов").assertCountEquals(0)
        compose.onAllNodesWithText("Статистика по слову", substring = true).assertCountEquals(0)
        assertTextFits()
    }

    @Test fun foundGroupsUseStatisticsAlphabeticalWordsAndBoldLastFound() {
        val state = mutableStateOf(state(emptyList()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, { state.value = state.value.copy(dialog = it) },
                    { state.value = state.value.copy(dialog = null) }, {})
            }
        }
        compose.runOnIdle { state.value = state(words.take(146)) }
        compose.onNodeWithTag("game_found_summary").performScrollTo().assertTextEquals("Найденные слова · 146 из 147")
        compose.onAllNodesWithText("Найденные слова", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Найдено", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Статистика слов").assertCountEquals(0)

        val lastFound = words[145]
        val foundByLength = words.take(146).groupBy { it.length }.toSortedMap()
        for ((length, found) in foundByLength) {
            val expected = if (length == lastFound.length) "Раскрыта" else "Свёрнута"
            compose.onNodeWithTag("level_found_group_$length").performScrollTo()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
                .assertTextContains("$length ${RussianPlural.letters(length)} · ${found.size} из ${found.size}")
        }
        val emptyGroup = state.value.statistics.rows.single { it.found == 0 }
        compose.onNodeWithTag("level_found_group_${emptyGroup.length}").performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("${emptyGroup.length} ${RussianPlural.letters(emptyGroup.length)} · 0 из ${emptyGroup.total}")
        for (length in listOf(3, 5)) {
            compose.onNodeWithTag("game_found_words_$length").assertDoesNotExist()
        }

        assertEquals(foundByLength.getValue(lastFound.length).sorted(), wordTexts(lastFound.length))
        assertBold(lastFound, true)
        assertBold(foundByLength.getValue(lastFound.length).first { it != lastFound }, false)
        for (word in foundByLength.getValue(3)) {
            compose.onNodeWithText(word).assertDoesNotExist()
        }

        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))
        compose.onNodeWithTag("game_found_words_3").assertExists()
        assertEquals(foundByLength.getValue(3).sorted(), wordTexts(3))
        for (word in foundByLength.getValue(3)) {
            compose.onNodeWithText(word).assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        }

        // A manually collapsed last-found group stays collapsed after recreation.
        compose.onNodeWithTag("level_found_group_${lastFound.length}").performScrollTo().performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнута"))
        compose.onNodeWithTag("game_found_words_${lastFound.length}").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("level_found_group_${lastFound.length}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнута"))
        compose.onNodeWithTag("game_found_words_${lastFound.length}").assertDoesNotExist()
        compose.onNodeWithTag("level_found_group_3")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))

        // The next found word reopens its own length group and is highlighted.
        compose.runOnIdle { state.value = state(words) }
        val newest = words.last()
        compose.onNodeWithTag("level_found_group_${newest.length}").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Раскрыта"))
        compose.onNodeWithText(newest).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        assertBold(newest, true)
        compose.onNodeWithTag("level_found_group_${lastFound.length}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнута"))
        assertTextFits()
    }

    @Test fun largestInstalledLevelShowsEveryLengthTotalWithAllGroupsExpanded() {
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
        for (row in snapshot.statistics.rows) {
            val group = compose.onNodeWithTag("level_found_group_${row.length}")
            group.performScrollTo()
            if (group.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) != "Раскрыта") {
                group.performClick()
            }
            compose.onNodeWithTag("game_found_words_${row.length}").assertExists()
            val lastWord = game.found.filter { it.length == row.length }.max()
            compose.onNodeWithText(lastWord).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithTag("level_found_group_${row.length}")
                .assertTextContains("${row.found} из ${row.total}", substring = true)
        }
        compose.onNodeWithTag("game_word_statistics").assertDoesNotExist()
        compose.onNodeWithText("Значение слова").assertDoesNotExist()
        for (row in snapshot.statistics.rows) {
            compose.onNodeWithTag("level_found_group_${row.length}").performScrollTo().performClick()
            compose.onNodeWithTag("game_found_words_${row.length}").assertDoesNotExist()
        }
    }

    private fun wordTexts(length: Int): List<String> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
            hasAnyAncestor(hasTestTag("game_found_words_$length")),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun assertBold(word: String, bold: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(word).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val weight = layouts.single().layoutInput.style.fontWeight
        if (bold) assertEquals(FontWeight.Bold, weight) else assertNotEquals(FontWeight.Bold, weight)
    }

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
