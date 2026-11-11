package com.example.sostavslova.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
fun StarsRow(progress: LevelProgress, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    StarsRow(listOf(progress.completed, progress.wordsTask, progress.lettersTask), modifier, onClick)
}

@Composable
fun StarsRow(stars: Int, maxStars: Int, modifier: Modifier = Modifier) {
    StarsRow(List(maxStars) { it < stars }, modifier)
}

@Composable
private fun StarsRow(positions: List<Boolean>, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val title = stringResource(R.string.tasks)
    val description = stringResource(R.string.level_stars_progress, positions.count { it }, positions.size)
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
        }
    }
}
