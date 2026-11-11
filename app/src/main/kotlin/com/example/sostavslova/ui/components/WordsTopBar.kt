package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun WordsTopBar(onBack: (() -> Unit)? = null, onOpenMenu: () -> Unit) {
    Surface(color = WordsColors.navbar, contentColor = WordsColors.navbarActive) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack ?: onOpenMenu, modifier = Modifier.size(48.dp).testTag(if (onBack != null) "game_back" else "app_menu")) {
                Icon(painterResource(if (onBack != null) R.drawable.ic_arrow_back else R.drawable.ic_menu),
                    stringResource(if (onBack != null) R.string.back_to_levels else R.string.open_menu))
            }
            Text(
                stringResource(R.string.top_bar_title),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
