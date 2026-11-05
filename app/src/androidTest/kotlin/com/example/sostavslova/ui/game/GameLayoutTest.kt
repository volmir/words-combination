package com.example.sostavslova.ui.game

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
import com.example.sostavslova.core.*
import com.example.sostavslova.data.Definition
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GameLayoutTest(private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val game = GameState("а".repeat(30), listOf("демонстрация", "окаменелость"), listOf("демонстрация"))
    private val initial = GameUiState(loading = false, game = game,
        level = Level(1, game.word, 8, 21, 'д', 5), statistics = game.statistics(),
        hint = GameHint(11, "О", listOf(Definition("", "Длинное определение <с угловыми скобками>\nВторая строка подсказки"))))

    @Test fun gameAndDialogsWrapAtLargeFontsAndKeep48DpTargets() {
        val state = mutableStateOf(initial)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(if (fontScale > 1f) 320.dp else 360.dp).fillMaxHeight()) {
                        GameScreen(state.value, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        checkLayout()
        compose.onNodeWithText("Завершить игру").performScrollTo().assertIsDisplayed()
        for (dialog in GameDialog.entries) {
            compose.runOnIdle { state.value = initial.copy(dialog = dialog, definitionsAvailable = false) }
            checkLayout()
            when (dialog) {
                GameDialog.Statistics -> compose.onAllNodesWithText("1 / 2")[1].performScrollTo().assertIsDisplayed()
                GameDialog.Tasks -> compose.onNodeWithText("Введите 21 слово (1/21)").performScrollTo().assertIsDisplayed()
                GameDialog.Definition -> compose.onNodeWithText("Определения недоступны в этой версии").performScrollTo().assertIsDisplayed()
                GameDialog.Finish -> compose.onNodeWithText("Да").performScrollTo().assertIsDisplayed()
            }
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
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf<Any>(1f), arrayOf<Any>(2f))
    }
}
