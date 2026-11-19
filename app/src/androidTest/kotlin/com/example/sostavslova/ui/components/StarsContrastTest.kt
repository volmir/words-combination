package com.example.sostavslova.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.ui.theme.DarkWordsPalette
import com.example.sostavslova.ui.theme.LightWordsPalette
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Контраст звёзд и текста на фоне обеих тем и на всех фонах карточек уровней. */
@RunWith(AndroidJUnit4::class)
class StarsContrastTest {
    @Test
    fun filledAndOutlineStarsKeepThreeToOneOnActualBackground() {
        for ((theme, palette) in palettes()) {
            val backgrounds = listOf(palette.background,
                palette.levelAvailableBackground.compositeOver(palette.background),
                palette.levelLockedBackground.compositeOver(palette.background),
                palette.levelPassedBackground.compositeOver(palette.background),
                palette.levelAllStarsBackground.compositeOver(palette.background))
            for (background in backgrounds) {
                val filled = contrastRatio(palette.starDone, background)
                val outline = contrastRatio(palette.starEmpty, background)
                val text = contrastRatio(palette.text, background)
                assertTrue("$theme: заполненная звезда на $background: контраст $filled < 3:1", filled >= 3.0)
                assertTrue("$theme: контурная звезда на $background: контраст $outline < 3:1", outline >= 3.0)
                assertTrue("$theme: текст на $background: контраст $text < 4,5:1", text >= 4.5)
            }
            assertNotEquals("$theme: звёзды различаются только цветом", palette.starDone, palette.starEmpty)
        }
    }

    private fun palettes() = listOf("light" to LightWordsPalette, "dark" to DarkWordsPalette)

    @Test
    fun filledAndOutlineStarDrawablesDifferByGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filled = pathData(context, R.drawable.ic_star_filled)
        val outline = pathData(context, R.drawable.ic_star_outline)
        assertNotEquals("Звёзды различаются только цветом", filled, outline)
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

    private fun pathData(context: Context, drawable: Int): String {
        val parser = context.resources.getXml(drawable)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "path") {
                return parser.getAttributeValue(ANDROID_NAMESPACE, "pathData")
                    ?: error("В drawable $drawable нет pathData")
            }
            event = parser.next()
        }
        error("В drawable $drawable нет пути")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
