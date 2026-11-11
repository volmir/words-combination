package com.example.sostavslova.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.*
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CampaignGameUiTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val tasks = listOf(
        CampaignTask.T1("count", 2), CampaignTask.T2("initial", 'к', 1),
        CampaignTask.T3("long", 4, "карта"), CampaignTask.T4("riddles", listOf("кот", "крот")),
        CampaignTask.T5("ending", "та", 4), CampaignTask.T6("theme", "animals", 1, listOf("кот", "крот")),
        CampaignTask.T7("contains", 'р', 1),
    )
    private val game = GameState("картотека", listOf("кот", "крот", "карта"), listOf("кот"))
    private val evaluated = TaskRules.evaluate(CurrentCampaignLevel(CampaignLevel(30, game.word, 6, 4, tasks), game), 4)
    private val initial = GameUiState(loading = false, game = game, campaignLevel = evaluated.state,
        totalLevels = 30, taskProgress = evaluated.progress, statistics = game.statistics(),
        riddles = mapOf("riddles" to listOf(
            RiddleUiState("Условное безопасное описание животного", 3, "К", solved = true),
            RiddleUiState(null, 4, "К"))),
    )

    @Test fun allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes() {
        val state = mutableStateOf(initial)
        var selected = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, {}, { selected = it }, { state.value = state.value.copy(dialog = it) },
                            { state.value = state.value.copy(dialog = null) }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("level_header").assertTextEquals("Мастер · уровень 30 из 30")
        compose.onNodeWithTag("level_difficulty").assertTextContains("Для финала", substring = true)
        TaskType.entries.forEach { type ->
            compose.onNodeWithTag("level_task_${type.name.lowercase()}").performScrollTo().assertExists()
            assertTextFits()
        }
        compose.onNodeWithText("Найдите 1 слово по теме «Животные»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("level_riddle_1").onChildren().filter(hasText("Разгадана")).assertCountEquals(1)
        compose.onNodeWithTag("level_riddle_2").onChildren().filter(hasText("Ещё не разгадана")).assertCountEquals(1)
        compose.onNodeWithText("Слово из 4 букв, начинается на «К»").performScrollTo().assertIsDisplayed()
        // Ни полные ответы загадок, ни адреса их статей не попадают в дерево доступности.
        val semantics = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val text = semantics.flatMap { root -> flatten(root) }.flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text } +
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.joinToString("\n")
        assertFalse(text.contains("крот", ignoreCase = true))
        assertFalse(text.contains("https://"))
        compose.onNodeWithText("кот").assertDoesNotExist()
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText("кот").performScrollTo().performClick()
        assertEquals("кот", selected)
        compose.onNodeWithTag("level_found_group_3").performScrollTo().performClick()
        compose.onNodeWithText("кот").assertDoesNotExist()
        compose.onNodeWithText("Статистика").performScrollTo().performClick()
        compose.onNodeWithTag("level_stats").assertExists()
        compose.onNodeWithText("Осталось").assertExists()
        compose.onNodeWithText("Взято шагов подсказок: 0 · потрачено единиц: 0").performScrollTo().assertIsDisplayed()
        assertTextFits()
    }

    private fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun assertTextFits() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { layout ->
                    assertFalse(layout.layoutInput.text.text, layout.hasVisualOverflow)
                }
            }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}, font={1}")
        fun sizes(): List<Array<Any>> = listOf(arrayOf(320, 2f), arrayOf(360, 1f), arrayOf(840, 1f))
    }
}
