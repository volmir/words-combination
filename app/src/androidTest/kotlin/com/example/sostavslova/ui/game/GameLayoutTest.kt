package com.example.sostavslova.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.components.LetterSlots
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class GameLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val game = GameState("а".repeat(30), listOf("демонстрация", "окаменелость"), listOf("демонстрация"))
    private val initial = GameUiState(loading = false, game = game,
        level = Level(1, game.word, 8, 21, 'д', 5), statistics = game.statistics(),
        hint = GameHint(11, "О"))

    @Test fun gameAndDialogsWrapAndKeep48DpTargets() {
        val state = mutableStateOf(initial)
        compose.setContent {
            WordsTheme {
                GameScreen(state.value, {}, {}, {}, {}, {}, {})
            }
        }
        checkLayout()
        compose.onNodeWithText("Завершить игру").assertDoesNotExist()
        for (dialog in GameDialog.entries) {
            compose.runOnIdle { state.value = initial.copy(dialog = dialog) }
            checkLayout()
            when (dialog) {
                GameDialog.Statistics -> compose.onAllNodesWithText("1 / 2")[1].performScrollTo().assertIsDisplayed()
                GameDialog.Tasks -> compose.onNodeWithText("Введите 21 слово (1/21)").performScrollTo().assertIsDisplayed()
            }
        }
    }

    private data class SlotsCase(val width: Int, val fontScale: Float, val pattern: String)

    @Test fun letterSlotsStayVisibleAtNarrowWidthsAndLargeFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = CampaignJson.decode(
            context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val longest = campaign.levels.maxBy { it.word.length }.word
        val words = listOf("а".repeat(8), "а".repeat(11), "а".repeat(13))
        val halfOpen = words.map { word ->
            word.mapIndexed { index, char -> if (index % 2 == 0) char else '_' }.joinToString("")
        }
        val patterns = halfOpen + longest
        val cases = listOf(320, 360, 411).flatMap { width ->
            listOf(1f, 2f).flatMap { fontScale ->
                patterns.map { SlotsCase(width, fontScale, it) }
            }
        }
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, current.value.fontScale)) {
                WordsTheme {
                    Box(Modifier.width(current.value.width.dp).background(Color.White).padding(16.dp)) {
                        Well {
                            LetterSlots(current.value.pattern,
                                Modifier.fillMaxWidth().testTag("game_hint_pattern"))
                        }
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            verifySlots(case)
        }
    }

    private fun verifySlots(case: SlotsCase) {
        val density = compose.density.density
        val patternNode = compose.onNodeWithTag("game_hint_pattern").fetchSemanticsNode()
        val parent = patternNode.boundsInRoot
        val slots = case.pattern.indices.map { index ->
            compose.onNodeWithTag("game_hint_slot_${index + 1}", useUnmergedTree = true).fetchSemanticsNode()
        }
        val context = "${case.width}dp, fontScale=${case.fontScale}, ${case.pattern}"
        assertEquals(context, case.pattern.length, slots.size)
        val minimum = 28f * density - 1f
        val maximum = 36f * density + 1f
        slots.forEachIndexed { index, node ->
            assertTrue("$context: слот ${index + 1} слишком мал: ${node.size}",
                node.size.width >= minimum && node.size.height >= minimum)
            assertTrue("$context: слот ${index + 1} шире 36 dp: ${node.size}",
                node.size.width <= maximum && node.size.height <= maximum)
            val bounds = node.boundsInRoot
            assertTrue("$context: слот ${index + 1} обрезан слева", bounds.left >= parent.left - 1f)
            assertTrue("$context: слот ${index + 1} обрезан справа", bounds.right <= parent.right + 1f)
        }
        val rows = slots.groupBy { it.boundsInRoot.top.roundToInt() }.values.map { it.size }
        assertTrue("$context: неровные строки $rows", rows.max() - rows.min() <= 1)
        assertEquals("$context: потеряны ячейки", case.pattern.length, rows.sum())
        when (case.pattern.length) {
            8 -> assertEquals("$context: ряд из 8", listOf(8), rows)
            11 -> assertEquals("$context: строки 6+5", listOf(6, 5), rows)
            13 -> assertEquals("$context: строки 7+6", listOf(7, 6), rows)
        }
        assertEquals("$context: описание ряда", HintText.slotsDescription(case.pattern),
            patternNode.config[SemanticsProperties.ContentDescription].joinToString(""))
        for (node in compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("game_hint_pattern")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { assertTextLayoutFits(it, context) }
        }
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
}
