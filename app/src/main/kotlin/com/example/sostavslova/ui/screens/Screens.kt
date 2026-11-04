package com.example.sostavslova.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R

@Composable
private fun ScreenPlaceholder(@StringRes title: Int, content: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun WordSearchScreen() = ScreenPlaceholder(R.string.nav_word_search)

@Composable
fun WordMeaningScreen() = ScreenPlaceholder(R.string.nav_word_meaning) {
    if (!BuildConfig.HAS_DEFINITIONS) {
        Text(stringResource(R.string.definitions_unavailable), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun RulesScreen() = ScreenPlaceholder(R.string.nav_rules)

@Composable
fun ContactsScreen() = ScreenPlaceholder(R.string.nav_contacts)

@Composable
fun AboutScreen() = ScreenPlaceholder(R.string.nav_about) {
    Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.about_definitions_heading), style = MaterialTheme.typography.titleMedium)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_definitions_authors))
            Text(stringResource(R.string.about_definitions_extraction))
            Text(stringResource(R.string.about_definitions_license))
            Text(stringResource(R.string.about_definitions_changes))
            Text(stringResource(R.string.about_definitions_history))
            Text(stringResource(R.string.about_code_sources))
        }
    }
    if (!BuildConfig.HAS_DEFINITIONS) {
        Text(stringResource(R.string.definitions_unavailable), style = MaterialTheme.typography.bodyLarge)
    }
}
