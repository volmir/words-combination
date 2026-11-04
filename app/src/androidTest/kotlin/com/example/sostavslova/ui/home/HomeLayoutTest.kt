package com.example.sostavslova.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.sostavslova.core.GameState
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HomeLayoutTest(private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longWordsWrapAndClickTargetsRemain48Dp() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WordsTheme {
                    Box(Modifier.requiredWidth(if (fontScale > 1f) 320.dp else 360.dp).fillMaxHeight()) {
                        HomeScreen(HomeUiState(dictionaryReady = true, dictionaryLoading = false,
                            currentGame = GameState("а".repeat(30), listOf("ааа")),
                            randomWords = listOf("демонстрация", "окаменелость", "регулировщик", "правоведение")),
                            {}, {}, {}, {}, {})
                    }
                }
            }
        }
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
        compose.onNodeWithText("Об игре").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dictionaryLoadingDisablesButtonsButKeepsInputEditable() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WordsTheme { HomeScreen(HomeUiState(), {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Начать игру").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNodeWithText("Другие слова").performScrollTo().assertIsNotEnabled()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf<Any>(1f), arrayOf<Any>(2f))
    }
}
