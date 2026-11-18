package com.example.sostavslova.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the packaged Android drawables, including the normalized Material Symbols crown. */
@RunWith(AndroidJUnit4::class)
class GraphicsResourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allNineVectorResourcesRenderVisiblePixels() {
        val ids = listOf(R.drawable.ic_arrow_back, R.drawable.ic_close, R.drawable.ic_help,
            R.drawable.ic_lock, R.drawable.ic_menu, R.drawable.ic_star_filled,
            R.drawable.ic_star_outline, R.drawable.ic_statistics, R.drawable.ic_crown)
        ids.forEach { id ->
            val bitmap = render(id)
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue("Empty drawable $id", pixels.any { Color.alpha(it) > 0 })
            bitmap.recycle()
        }
    }

    @Test
    fun outlineStarHasAnEmptyCenterAndFilledStarHasASolidCenter() {
        val filled = render(R.drawable.ic_star_filled)
        val outline = render(R.drawable.ic_star_outline)
        assertTrue(Color.alpha(filled.getPixel(SIZE / 2, SIZE / 2)) > 0)
        assertEquals(0, Color.alpha(outline.getPixel(SIZE / 2, SIZE / 2)))
        filled.recycle()
        outline.recycle()
    }

    @Test
    fun packagedNoticesKeepGoogleGraphicsAndIndependentDataLicenses() {
        val notices = context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        listOf("Google Material Icons", "Google Material Symbols", "Copyright Google LLC",
            "Apache-2.0", "CC0-1.0", "CC BY-SA 4.0", "FrequencyWords", "MIT").forEach {
            assertTrue("Missing notice $it", notices.contains(it))
        }
        val apache = context.assets.open("Apache-2.0.txt").bufferedReader().use { it.readText() }
        assertTrue(apache.contains("Version 2.0, January 2004"))
        assertTrue(context.getString(R.string.about_graphics_sources).contains("Google Material Icons"))
    }

    private fun render(id: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(context.getDrawable(id))
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val SIZE = 96
    }
}
