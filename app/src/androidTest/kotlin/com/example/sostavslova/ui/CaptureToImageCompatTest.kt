package com.example.sostavslova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureToImageCompatTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capturesUpdatedPixelsWithoutAdvancingPausedComposeClock() {
        val color = mutableStateOf(Color.Red)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(64.dp).background(color.value).testTag("capture_color"))
        }
        repeat(8) { index ->
            val expected = if (index % 2 == 0) Color.Red else Color.Blue
            compose.runOnUiThread { color.value = expected }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val time = compose.mainClock.currentTime
            val pixels = compose.onNodeWithTag("capture_color").captureToImageCompat().toPixelMap()
            assertEquals("Кадр $index", expected, pixels[pixels.width / 2, pixels.height / 2])
            assertEquals("Снимок не продвигает часы анимации", time, compose.mainClock.currentTime)
        }
    }
}
