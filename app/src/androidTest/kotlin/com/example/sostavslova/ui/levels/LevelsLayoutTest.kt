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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.ui.theme.WordsTheme
import org.json.JSONArray
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
        val json = JSONArray(context.assets.open("levels.json").bufferedReader().use { it.readText() })
        val items = List(json.length()) { i ->
            val row = json.getJSONObject(i)
            LevelUiState(Level(row.getInt("index"), row.getString("word"), row.getInt("answers"),
                row.getInt("words"), row.getString("letter").single(), row.getInt("letterCount")), LevelProgress(), i == 0)
        }
        val state = mutableStateOf(LevelsUiState(loading = false, levels = items))
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
        compose.onNodeWithTag("level_1").assertIsEnabled()
        compose.onNodeWithTag("level_2").assertIsNotEnabled()
        val first = compose.onNodeWithTag("level_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("level_2").fetchSemanticsNode().boundsInRoot
        if (width < 600) assertTrue(second.top > first.top)
        else { assertEquals(first.top, second.top, 1f); assertTrue(second.left > first.left) }
        checkLayout()
        if (width < 600) {
            compose.onNodeWithTag("level_1").performClick()
            compose.runOnIdle { assertEquals("рекуперация", selected) }
        }
        compose.runOnIdle { state.value = state.value.copy(levels = items.mapIndexed { i, item ->
            if (i == 0) item.copy(progress = LevelProgress(true, true, true))
            else if (i == 1) item.copy(unlocked = true) else item
        }) }
        compose.onNodeWithTag("level_1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Звёзды: 3 из 3"))
        compose.onNodeWithTag("level_2").assertIsEnabled()
        compose.onNodeWithTag("levels_grid").performScrollToKey(items.last().level.word)
        compose.onNodeWithTag("level_30").assertExists().assertIsNotEnabled()
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
