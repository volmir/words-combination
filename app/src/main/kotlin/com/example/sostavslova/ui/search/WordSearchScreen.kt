package com.example.sostavslova.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.DefinitionDialog
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.WordInputRow
import com.example.sostavslova.ui.components.WordLinks

@Composable
fun WordSearchRoute() {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory {
            initializer {
                WordSearchViewModel(
                    createSavedStateHandle(),
                    container.wordRepository,
                    container.definitionsRepository,
                )
            }
        }
    }
    val viewModel: WordSearchViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    WordSearchScreen(
        state = state,
        onInputChanged = viewModel::onInputChanged,
        onSearch = viewModel::onSearch,
        onWordClicked = viewModel::onWordClicked,
        onDismissDialog = viewModel::onDismissDialog,
    )
}

@Composable
fun WordSearchScreen(
    state: WordSearchUiState,
    onInputChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onWordClicked: (String) -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("word_search_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.nav_word_search),
                style = MaterialTheme.typography.titleLarge,
            )

            Well {
                WordInputRow(
                    label = stringResource(R.string.enter_word),
                    value = state.input,
                    onValueChange = onInputChanged,
                    buttonText = stringResource(R.string.search_button),
                    onSubmit = onSearch,
                    imeAction = ImeAction.Search,
                    enabled = !state.isSearching,
                    modifier = Modifier.testTag("word_search_input_row"),
                )
            }

            state.message?.let { msgId ->
                AlertBanner(
                    type = AlertType.Danger,
                    text = stringResource(msgId),
                    modifier = Modifier.testTag("word_search_error"),
                )
            }

            if (state.hasSearched) {
                val result = state.result
                if (result == null || result.total == 0) {
                    Text(
                        stringResource(R.string.nothing_found),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("word_search_empty"),
                    )
                } else {
                    Column(
                        modifier = Modifier.testTag("word_search_results"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.word_search_result_title, result.source),
                            style = MaterialTheme.typography.titleMedium,
                        )

                        Text(
                            stringResource(
                                R.string.word_search_summary,
                                result.source,
                                result.total,
                                RussianPlural.words(result.total),
                                result.lengthKeysSummary,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        for (group in result.groups) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val genitiveLetters = RussianPlural.form(
                                    group.length,
                                    stringResource(R.string.hint_letter_genitive_one),
                                    stringResource(R.string.hint_letter_genitive_many),
                                    stringResource(R.string.hint_letter_genitive_many),
                                )
                                val wordsForm = RussianPlural.words(group.words.size)
                                val header = stringResource(
                                    R.string.word_search_group_header,
                                    group.length,
                                    genitiveLetters,
                                    result.source,
                                    group.words.size,
                                    wordsForm,
                                )

                                Text(
                                    header,
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                WordLinks(
                                    words = group.words,
                                    onClick = onWordClicked,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showDialog) {
        DefinitionDialog(
            word = state.dialogWord,
            definitions = state.dialogDefinitions,
            onDismiss = onDismissDialog,
            definitionsAvailable = state.dialogDefinitionsAvailable,
        )
    }
}
