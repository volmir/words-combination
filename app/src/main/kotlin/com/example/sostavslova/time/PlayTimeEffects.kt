package com.example.sostavslova.time

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

internal val LocalPlayTimeTracker = staticCompositionLocalOf<PlayTimeTracker?> { null }

/** Наблюдение касаний на проходе Initial без потребления событий. */
internal fun Modifier.playTimeUserActivity(tracker: PlayTimeTracker?): Modifier =
    if (tracker == null) this
    else pointerInput(tracker) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                tracker.onUserActivity()
            }
        }
    }

/** Регистрируется также ВНУТРИ Dialog, где LocalView принадлежит его окну. */
@Composable
internal fun PlayTimeWindowFocus() {
    val tracker = LocalPlayTimeTracker.current ?: return
    val view = LocalView.current
    DisposableEffect(tracker, view) {
        val window = Any()
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { tracker.setWindowFocused(window, it) }
        observer.addOnWindowFocusChangeListener(listener)
        tracker.setWindowFocused(window, view.hasWindowFocus())
        onDispose {
            val currentObserver = if (observer.isAlive) observer else view.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnWindowFocusChangeListener(listener)
            tracker.setWindowFocused(window, false)
        }
    }
}

