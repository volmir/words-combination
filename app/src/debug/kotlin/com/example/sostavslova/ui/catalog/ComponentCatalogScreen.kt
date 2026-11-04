package com.example.sostavslova.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.ui.components.*

@Composable
internal fun ComponentCatalogScreen(onClose: () -> Unit) {
    val sampleWord = stringResource(R.string.catalog_word)
    val definition = sampleDefinition()
    var value by rememberSaveable { mutableStateOf(sampleWord) }
    var selectedWord by rememberSaveable { mutableStateOf(sampleWord) }
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { WordsTopBar(onClose) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.titleLarge)
                CatalogHeading(R.string.catalog_well)
                Well {
                    WordInputRow(stringResource(R.string.your_answer), value, { value = it }, stringResource(R.string.check_answer), { submitted = true }, imeAction = ImeAction.Done)
                }
                if (submitted) AlertBanner(AlertType.Info, stringResource(R.string.catalog_submit_received))
                CatalogHeading(R.string.catalog_letters)
                LetterTiles(sampleWord)
                LetterTiles(stringResource(R.string.catalog_long_word))
                CatalogHeading(R.string.catalog_stars)
                StarsRow(LevelProgress(completed = true, wordsTask = true), onClick = { submitted = true })
                CatalogHeading(R.string.catalog_links)
                WordLinks(stringArrayResource(R.array.catalog_words).toList(), { selectedWord = it; dialog = 1 })
                CatalogHeading(R.string.catalog_alerts)
                AlertBanner(AlertType.Success, stringResource(R.string.correct_answer))
                AlertBanner(AlertType.Info, stringResource(R.string.found_words))
                AlertBanner(AlertType.Warning, stringResource(R.string.no_combinations))
                AlertBanner(AlertType.Danger, stringResource(R.string.word_too_short))
                CatalogHeading(R.string.catalog_statistics)
                StatsTable(sampleStatistics)
                CatalogHeading(R.string.catalog_definition)
                DefinitionBlock(definition)
                CatalogHeading(R.string.catalog_dialogs)
                CatalogButton(R.string.catalog_definition_button) { selectedWord = sampleWord; dialog = 1 }
                CatalogButton(R.string.catalog_missing_button) { dialog = 2 }
                CatalogButton(R.string.catalog_unavailable_button) { dialog = 3 }
                CatalogButton(R.string.finish_game) { dialog = 4 }
                CatalogButton(R.string.close_dialog, onClose)
            }
        }
    }
    when (dialog) {
        1 -> DefinitionDialog(selectedWord, listOf(definition.copy(display = selectedWord)), { dialog = 0 }, definitionsAvailable = true)
        2 -> DefinitionDialog(sampleWord, emptyList(), { dialog = 0 }, definitionsAvailable = true)
        3 -> DefinitionDialog(sampleWord, emptyList(), { dialog = 0 }, definitionsAvailable = false)
        4 -> ConfirmDialog(onConfirm = { dialog = 0 }, onDismiss = { dialog = 0 })
    }
}

@Composable
private fun CatalogHeading(@androidx.annotation.StringRes title: Int) {
    HorizontalDivider()
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CatalogButton(@androidx.annotation.StringRes text: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}
