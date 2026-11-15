package com.example.sostavslova.ui.levels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.Stage
import com.example.sostavslova.ui.theme.WordsTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LevelsLayoutTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    @Test fun levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = JSONObject(context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val levelsJson = campaign.getJSONArray("levels")
        val items = List(levelsJson.length()) { i ->
            val row = levelsJson.getJSONObject(i)
            LevelUiState(row.getInt("number"), row.getString("word"), LevelProgress(),
                unlocked = i == 0, campaignStars = 0, maxStars = row.getJSONArray("tasks").length(),
                requiredStars = row.getInt("requiredStars"), stage = row.getInt("stage"))
        }
        val stagesJson = campaign.getJSONArray("stages")
        val stages = List(stagesJson.length()) { i ->
            val row = stagesJson.getJSONObject(i)
            Stage(row.getInt("number"), row.getInt("firstLevel"), row.getInt("lastLevel"))
        }
        val state = mutableStateOf(LevelsUiState(loading = false, levels = items, stages = stages))
        var selected = ""
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        LevelsScreen(state.value, { selected = it }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("level_card_1").assertIsEnabled()
            .assertTextContains("Открыт", substring = true)
            .assert(hasContentDescription("Звёзды"))
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assertTextContains("Закрыт", substring = true)
        // Ряд звёзд на карте только показывает прогресс: «Задания» здесь вводит в заблуждение.
        compose.onAllNodesWithContentDescription("Задания").assertCountEquals(0)
        compose.onAllNodesWithText("Для прохождения", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Можно добрать", substring = true).assertCountEquals(0)
        val first = compose.onNodeWithTag("level_card_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("level_card_2").fetchSemanticsNode().boundsInRoot
        if (width < 600) assertTrue(second.top > first.top)
        else { assertEquals(first.top, second.top, 1f); assertTrue(second.left > first.left) }
        checkLayout()
        if (width < 600) {
            compose.onNodeWithTag("level_card_1").performClick()
            compose.runOnIdle { assertEquals("чемпионство", selected) }
        }
        compose.runOnIdle { state.value = state.value.copy(levels = items.mapIndexed { i, item ->
            if (i == 0) item.copy(campaignStars = item.maxStars)
            else if (i == 1) item.copy(unlocked = true) else item
        }) }
        val starsDescription = context.resources.getQuantityString(R.plurals.level_stars_progress, 1, 1, 1)
        compose.onNodeWithTag("level_card_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, starsDescription))
            .assertTextContains("Все звёзды", substring = true)
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assertTextContains("Открыт", substring = true)
        compose.onNodeWithTag("levels_grid").performScrollToKey("stage_6")
        compose.onNodeWithTag("levels_stage_6").assertTextEquals("Мастер · уровни 14–15")
        compose.onNodeWithTag("levels_grid").performScrollToKey(items.last().word)
        compose.onNodeWithTag("level_card_15").assertExists()
        compose.onAllNodesWithTag("level_card_16").assertCountEquals(0)
        checkLayout()
    }

    private fun checkLayout() {
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                val text = layout.layoutInput.text.text
                assertFalse(text, layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse(text, layout.isLineEllipsized(line))
                    assertTrue(text, layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue(text, layout.getLineLeft(line) >= -1f)
                    assertTrue(text, layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val minimum = 48f * compose.density.density - 1f
            assertTrue(node.size.toString(), node.size.width >= minimum && node.size.height >= minimum)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0},fontScale={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf<Any>(320, 1f), arrayOf<Any>(320, 2f), arrayOf<Any>(840, 1f), arrayOf<Any>(840, 2f))
    }
}
