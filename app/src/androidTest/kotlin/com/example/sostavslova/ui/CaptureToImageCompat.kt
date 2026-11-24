package com.example.sostavslova.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlin.math.roundToInt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-window screenshots on API 24–25, where Compose's PixelCopy overload is absent. */
fun SemanticsNodeInteraction.captureToImageCompat(): ImageBitmap {
    if (Build.VERSION.SDK_INT >= 26) return captureToImage()
    val bounds = fetchSemanticsNode().boundsInWindow
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val screen = IntArray(2)
    val window = IntArray(2)
    val drawn = AtomicBoolean()
    val frame = CountDownLatch(1)
    val drawListener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
    val frameListener = Window.OnFrameMetricsAvailableListener { _, _, _ ->
        if (drawn.get()) frame.countDown()
    }
    lateinit var hostWindow: Window
    instrumentation.runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).single()
        hostWindow = activity.window
        hostWindow.decorView.getLocationOnScreen(screen)
        hostWindow.decorView.getLocationInWindow(window)
        hostWindow.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        hostWindow.decorView.viewTreeObserver.addOnDrawListener(drawListener)
        hostWindow.decorView.invalidate()
    }
    try {
        // Compose's test clock may be paused. Wait for the real Android render frame,
        // so UiAutomation sees the current pixels rather than the previous surface.
        check(frame.await(10, TimeUnit.SECONDS)) { "Activity did not render a screenshot frame" }
    } finally {
        instrumentation.runOnMainSync {
            hostWindow.removeOnFrameMetricsAvailableListener(frameListener)
            hostWindow.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
        }
    }
    instrumentation.waitForIdleSync()
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val left = bounds.left.roundToInt() + screen[0] - window[0]
    val top = bounds.top.roundToInt() + screen[1] - window[1]
    val width = bounds.right.roundToInt() - bounds.left.roundToInt()
    val height = bounds.bottom.roundToInt() - bounds.top.roundToInt()
    check(left >= 0 && top >= 0 && width > 0 && height > 0 &&
        left + width <= screenshot.width && top + height <= screenshot.height) {
        "Node $bounds is outside screenshot ${screenshot.width}x${screenshot.height}"
    }
    val cropped = Bitmap.createBitmap(screenshot, left, top, width, height)
    if (cropped !== screenshot) screenshot.recycle()
    return cropped.asImageBitmap()
}
