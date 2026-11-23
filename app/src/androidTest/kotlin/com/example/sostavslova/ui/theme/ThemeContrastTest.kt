package com.example.sostavslova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG-контраст токенов слотов подсказки в обеих темах.
 * Пустая ячейка передаёт длину слова, поэтому её рамка — значимый элемент (3:1),
 * а буква открытой ячейки — текст (4,5:1).
 */
@RunWith(AndroidJUnit4::class)
class ThemeContrastTest {
    @Test
    fun emptySlotBorderMeetsThreeToOneInBothThemes() {
        for ((theme, palette) in palettes()) {
            for ((surfaceName, surface) in surfaces(palette)) {
                val ratio = contrastRatio(palette.slotEmptyBorder, surface)
                assertAtLeast("$theme: рамка пустой ячейки на $surfaceName", 3.0, ratio)
            }
        }
    }

    @Test
    fun openSlotLetterMeetsFourAndHalfToOneInBothThemes() {
        for ((theme, palette) in palettes()) {
            for ((surfaceName, surface) in surfaces(palette)) {
                val cell = palette.primary.copy(alpha = OPEN_SLOT_ALPHA).compositeOver(surface)
                val ratio = contrastRatio(palette.slotOpenText, cell)
                assertAtLeast("$theme: буква открытой ячейки на $surfaceName", 4.5, ratio)
            }
        }
    }

    @Test
    fun previouslyCheckedPairsDoNotRegress() {
        for ((theme, palette) in palettes()) {
            assertAtLeast("$theme: text/background", 4.5, contrastRatio(palette.text, palette.background))
            assertAtLeast(
                "$theme: defaultButtonText/defaultButton",
                4.5,
                contrastRatio(palette.defaultButtonText, palette.defaultButton),
            )
            assertAtLeast("$theme: starDone/background", 3.0, contrastRatio(palette.starDone, palette.background))
            assertAtLeast("$theme: starEmpty/background", 3.0, contrastRatio(palette.starEmpty, palette.background))
            assertAtLeast(
                "$theme: open slot border/background",
                3.0,
                contrastRatio(palette.primary, palette.background),
            )
        }
    }

    /** Подложки, на которых реально стоит ряд слотов: экран игры и карточка подсказки. */
    private fun surfaces(palette: WordsPalette) = listOf(
        "background" to palette.background,
        "wellBackground" to palette.wellBackground,
    )

    private fun palettes() = listOf("light" to LightWordsPalette, "dark" to DarkWordsPalette)

    private fun assertAtLeast(name: String, threshold: Double, ratio: Double) {
        assertTrue("$name: контраст $ratio < $threshold:1", ratio >= threshold)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        /** Заливка открытой ячейки в LetterSlots: 12% основного цвета. */
        const val OPEN_SLOT_ALPHA = 0.12f
    }
}
