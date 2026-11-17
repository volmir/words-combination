package com.example.sostavslova.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class TextLayoutAssertionsTest {
    @get:Rule val compose = createComposeRule()

    private fun layout(
        text: String = "Мастер · уровень 30 из 30",
        maxLines: Int = Int.MAX_VALUE,
        overflow: TextOverflow = TextOverflow.Clip,
    ): TextLayoutResult {
        compose.setContent {
            WordsTheme {
                Text(text, Modifier.width(120.dp).testTag("layout_text"),
                    maxLines = maxLines, overflow = overflow)
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("layout_text").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test fun cachedParagraphCanBeWiderThanTextWithoutClippingAnyLine() {
        val original = layout("К")
        // Как при повторном использовании абзаца в shrink-wrap Text:
        // остаётся исходный MultiParagraph, размер Text равен ширине строки.
        val width = ceil(original.getLineRight(0)).toInt()
        val shrinkWrapped = original.copy(size = IntSize(width, original.size.height))
        assertTrue(shrinkWrapped.didOverflowWidth)
        assertTextLayoutFits(shrinkWrapped)
    }

    @Test fun wrappedHeaderKeepsEveryLineInsideText() {
        val wrapped = layout()
        assertTrue(wrapped.lineCount > 1)
        assertTextLayoutFits(wrapped)
    }

    @Test fun actualHorizontalClippingIsRejected() {
        val original = layout("К")
        val clipped = original.copy(size = IntSize(
            floor(original.getLineRight(0)).toInt() - 1, original.size.height))
        assertThrows(AssertionError::class.java) { assertTextLayoutFits(clipped) }
    }

    @Test fun actualVerticalClippingIsRejected() {
        val original = layout()
        val clipped = original.copy(size = IntSize(original.size.width,
            floor(original.getLineBottom(original.lineCount - 1)).toInt() - 1))
        assertThrows(AssertionError::class.java) { assertTextLayoutFits(clipped) }
    }

    @Test fun omittedLinesAreRejected() {
        val clipped = layout(maxLines = 1)
        assertTrue(clipped.multiParagraph.didExceedMaxLines)
        assertThrows(AssertionError::class.java) { assertTextLayoutFits(clipped) }
    }

    @Test fun ellipsizedTextIsRejected() {
        val clipped = layout(maxLines = 1, overflow = TextOverflow.Ellipsis)
        assertTrue(clipped.isLineEllipsized(0))
        assertThrows(AssertionError::class.java) { assertTextLayoutFits(clipped) }
    }
}
