package com.example.sostavslova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsPalette

/** Цвет звезды задаётся состоянием и активной палитрой, а не drawable. */
internal fun starTint(palette: WordsPalette, done: Boolean): Color =
    if (done) palette.starDone else palette.starEmpty

/** Полупрозрачный маркер порога звёзд на подложке карточки. */
internal fun thresholdMarkerColor(palette: WordsPalette): Color =
    palette.starEmpty.copy(alpha = 0.6f)

@Composable
fun StarsRow(
    stars: Int,
    maxStars: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    requiredStars: Int? = null,
) {
    StarsRow(List(maxStars) { it < stars }, modifier, label, requiredStars)
}

/** Прогресс звёзд для доступности; порог открытия добавляется, когда он известен. */
@Composable
fun starsStateDescription(stars: Int, maxStars: Int, requiredStars: Int? = null): String {
    val progress = pluralStringResource(R.plurals.level_stars_progress, maxStars, stars, maxStars)
    return if (requiredStars == null) progress
        else progress + " · " + stringResource(R.string.level_stars_threshold, requiredStars)
}

@Composable
private fun StarsRow(
    positions: List<Boolean>,
    modifier: Modifier,
    label: String? = null,
    requiredStars: Int? = null,
) {
    // Ряд звёзд на карте только показывает прогресс и по умолчанию называется «Звёзды».
    val title = label ?: stringResource(R.string.level_stars_label)
    val description = starsStateDescription(positions.count { it }, positions.size, requiredStars)
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = title
            stateDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, done) in positions.withIndex()) {
            Icon(
                painterResource(if (done) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                contentDescription = null,
                modifier = Modifier.size(24.dp).testTag("stars_position_$index"),
                tint = starTint(LocalWordsPalette.current, done),
            )
            if (requiredStars != null && index == requiredStars - 1 && index < positions.lastIndex) {
                Box(
                    Modifier.width(1.dp).height(20.dp).testTag("stars_threshold")
                        .background(thresholdMarkerColor(LocalWordsPalette.current)),
                )
            }
        }
    }
}
