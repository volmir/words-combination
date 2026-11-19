package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.sostavslova.R
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsControlShape

@Composable
fun WordsDrawer(
    currentRoute: String?,
    onExit: () -> Unit = {},
    onNavigate: (Destination) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = LocalWordsPalette.current.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            DrawerItem(Destination.Levels, currentRoute, onNavigate)
            DrawerItem(Destination.Stats, currentRoute, onNavigate)
            DrawerSection(R.string.nav_help)
            DrawerItem(Destination.Rules, currentRoute, onNavigate)
            DrawerItem(Destination.About, currentRoute, onNavigate)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalWordsPalette.current.wellBorder)
            WordsDrawerItem(stringResource(R.string.drawer_exit), onClick = onExit,
                modifier = Modifier.testTag("drawer_exit"))
        }
    }
}

@Composable
private fun DrawerSection(@androidx.annotation.StringRes title: Int) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalWordsPalette.current.wellBorder)
    Text(
        stringResource(title),
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun DrawerItem(
    destination: Destination,
    currentRoute: String?,
    onNavigate: (Destination) -> Unit,
) {
    WordsDrawerItem(
        label = stringResource(destination.label),
        selected = currentRoute == destination.route,
        onClick = { onNavigate(destination) },
    )
}

@Composable
internal fun WordsDrawerItem(label: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected },
        shape = WordsControlShape,
        color = if (selected) LocalWordsPalette.current.primary else LocalWordsPalette.current.background,
        contentColor = if (selected) LocalWordsPalette.current.background else LocalWordsPalette.current.text,
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
