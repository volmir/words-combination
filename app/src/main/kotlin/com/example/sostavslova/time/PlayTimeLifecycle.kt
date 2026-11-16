package com.example.sostavslova.time

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Подменяемый Lifecycle: одинаковая обработка PAUSE/STOP/DESTROY и dispose. */
internal fun observePlayTimeLifecycle(lifecycle: Lifecycle, onResumed: (Boolean) -> Unit): () -> Unit {
    val observer = LifecycleEventObserver { _, _ -> onResumed(lifecycle.currentState == Lifecycle.State.RESUMED) }
    lifecycle.addObserver(observer)
    onResumed(lifecycle.currentState == Lifecycle.State.RESUMED)
    return {
        lifecycle.removeObserver(observer)
        onResumed(false)
    }
}
