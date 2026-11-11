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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun WordsDrawer(
    currentRoute: String?,
    footer: @Composable () -> Unit = {},
    onNavigate: (Destination) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = WordsColors.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            DrawerItem(Destination.Levels, currentRoute, onNavigate)
            DrawerSection(R.string.nav_services)
            if (BuildConfig.HAS_DEFINITIONS) {
                DrawerItem(Destination.WordMeaning, currentRoute, onNavigate)
            }
            DrawerSection(R.string.nav_about)
            DrawerItem(Destination.Rules, currentRoute, onNavigate)
            DrawerItem(Destination.About, currentRoute, onNavigate)
            footer()
        }
    }
}

@Composable
private fun DrawerSection(@androidx.annotation.StringRes title: Int) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = WordsColors.wellBorder)
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
internal fun WordsDrawerItem(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(4.dp),
        color = if (selected) WordsColors.primary else WordsColors.background,
        contentColor = if (selected) WordsColors.background else WordsColors.text,
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
