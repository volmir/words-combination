package com.example.sostavslova.ui

import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Проверяет размещение строк, а не ширину кэшированного MultiParagraph. */
internal fun assertTextLayoutFits(layout: TextLayoutResult, context: String = "") {
    val details = "$context: ${layout.layoutInput.text}, size=${layout.size}, " +
        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
        "constraints=${layout.layoutInput.constraints}"
    // hasVisualOverflow может быть true при shrink-wrap: пустая часть абзаца
    // шире Text, хотя все строки полностью помещаются в его измеренный размер.
    assertFalse("Потеряны строки: $details", layout.multiParagraph.didExceedMaxLines)
    for (line in 0 until layout.lineCount) {
        assertFalse("Многоточие, строка $line: $details", layout.isLineEllipsized(line))
        assertTrue("Обрезан левый край, строка $line: $details", layout.getLineLeft(line) >= 0f)
        assertTrue("Обрезан правый край, строка $line: $details",
            layout.getLineRight(line) <= layout.size.width)
        assertTrue("Обрезан верх, строка $line: $details", layout.getLineTop(line) >= 0f)
        assertTrue("Обрезан низ, строка $line: $details",
            layout.getLineBottom(line) <= layout.size.height)
    }
}
