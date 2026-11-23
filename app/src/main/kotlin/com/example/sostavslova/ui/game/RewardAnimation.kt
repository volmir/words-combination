package com.example.sostavslova.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.ui.components.starTint
import com.example.sostavslova.ui.theme.LocalWordsPalette

/** Все задержки входят в tween: Compose масштабирует их вместе с длительностью. */
@Composable
internal fun rewardRevealProgress(animate: Boolean, durationMillis: Int, delayMillis: Int = 0): Float {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val enabled = animate && durationScale > 0f
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(enabled) {
        if (enabled) progress.animateTo(1f, tween(durationMillis, delayMillis))
        else progress.snapTo(1f)
    }
    // При scale=0 виден конечный кадр уже в этой композиции, без ожидания эффекта.
    return if (enabled) progress.value else 1f
}

@Composable
internal fun Modifier.rewardReveal(animate: Boolean, durationMillis: Int, delayMillis: Int = 0): Modifier {
    val progress = rewardRevealProgress(animate, durationMillis, delayMillis)
    return graphicsLayer {
        alpha = progress
        scaleX = 0.9f + 0.1f * progress
        scaleY = scaleX
    }
}

@Composable
internal fun LevelCompleteStars(stars: Int, maxStars: Int) {
    // Даже при большом числе заданий последний значок появляется не позже 600 мс.
    val interval = if (maxStars > 1) minOf(100, 360 / (maxStars - 1)) else 0
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxStars) { index ->
            val earned = index < stars
            Icon(painterResource(if (earned) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                contentDescription = null, // Доступное количество уже есть в level_complete_stars.
                modifier = Modifier.size(24.dp).rewardReveal(earned, 240, index * interval)
                    .testTag("level_complete_star_$index"),
                tint = starTint(LocalWordsPalette.current, earned))
        }
    }
}
