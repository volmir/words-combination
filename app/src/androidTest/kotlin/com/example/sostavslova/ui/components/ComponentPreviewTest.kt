package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.catalog.ComponentExample
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Проверяет реальные @Preview-функции на Android, включая нативные окна диалогов. */
@RunWith(Parameterized::class)
class ComponentPreviewTest(private val exampleName: String, private val fontScale: Float) {
    private val example get() = ComponentExample.valueOf(exampleName)
    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersWithoutTextOverflowAndWith48DpClickTargets() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.requiredWidth(if (fontScale > 1f) 320.dp else 360.dp).fillMaxHeight()) {
                    example.RenderPreview()
                }
            }
        }
        compose.waitForIdle()
        val texts = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val descriptions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("В превью должен быть текст или описание иконок: $example", texts.isNotEmpty() || descriptions.isNotEmpty())
        for (node in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                // Кэшированный MultiParagraph может быть шире shrink-wrap Text.
                // Сравниваем фактические строки с размером Text, а не ширину constraints.
                val context = "$example, $fontScale: ${layout.layoutInput.text}"
                assertFalse("Потеряны строки: $context", layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Многоточие: $context", layout.isLineEllipsized(line))
                    assertTrue("Обрезан правый край: $context", layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue("Обрезан левый край: $context", layout.getLineLeft(line) >= -1f)
                    assertTrue("Обрезан низ: $context", layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val minimumPx = 48f * compose.density.density
            assertTrue("Узкая цель $example: ${node.size}", node.size.width >= minimumPx - 1f)
            assertTrue("Низкая цель $example: ${node.size}", node.size.height >= minimumPx - 1f)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, fontScale={1}")
        fun examples(): List<Array<Any>> = ComponentExample.entries.flatMap { example ->
            listOf(arrayOf<Any>(example.name, 1f), arrayOf<Any>(example.name, 2f))
        }
    }
}
