package com.example.sostavslova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun StarsRow(
    progress: LevelProgress,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    label: String? = null,
    requiredStars: Int? = null,
) {
    StarsRow(listOf(progress.completed, progress.wordsTask, progress.lettersTask), modifier, onClick, label, requiredStars)
}

@Composable
fun StarsRow(
    stars: Int,
    maxStars: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    requiredStars: Int? = null,
) {
    StarsRow(List(maxStars) { it < stars }, modifier, null, label, requiredStars)
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
    onClick: (() -> Unit)? = null,
    label: String? = null,
    requiredStars: Int? = null,
) {
    // Кликабельный ряд открывает задания, поэтому по умолчанию называется «Задания».
    // На карте кампании тот же ряд только показывает прогресс и называется «Звёзды».
    val title = label ?: stringResource(R.string.tasks)
    val description = starsStateDescription(positions.count { it }, positions.size, requiredStars)
    val interactive = if (onClick != null) {
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(8.dp)
    } else Modifier
    Row(
        modifier.then(interactive).semantics(mergeDescendants = true) {
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
                tint = if (done) WordsColors.starDone else WordsColors.starEmpty,
            )
            if (requiredStars != null && index == requiredStars - 1 && index < positions.lastIndex) {
                Box(
                    Modifier.width(1.dp).height(20.dp).testTag("stars_threshold")
                        .background(WordsColors.starEmpty.copy(alpha = 0.6f)),
                )
            }
        }
    }
}
