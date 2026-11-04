package com.example.sostavslova.ui.meaning

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
import com.example.sostavslova.ui.components.AlertBanner
import com.example.sostavslova.ui.components.AlertType
import com.example.sostavslova.ui.components.DefinitionBlock
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.components.WordInputRow

@Composable
fun WordMeaningRoute() {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val factory = remember(container) {
        viewModelFactory {
            initializer {
                WordMeaningViewModel(
                    createSavedStateHandle(),
                    container.definitionsRepository,
                )
            }
        }
    }
    val viewModel: WordMeaningViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    WordMeaningScreen(
        state = state,
        onInputChanged = viewModel::onInputChanged,
        onSearch = viewModel::onSearch,
    )
}

@Composable
fun WordMeaningScreen(
    state: WordMeaningUiState,
    onInputChanged: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("word_meaning_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.nav_word_meaning),
                style = MaterialTheme.typography.titleLarge,
            )

            if (!state.hasDefinitionsInBuild) {
                Text(
                    stringResource(R.string.definitions_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("definitions_unavailable"),
                )
            } else {
                Well {
                    WordInputRow(
                        label = stringResource(R.string.enter_word),
                        value = state.input,
                        onValueChange = onInputChanged,
                        buttonText = stringResource(R.string.search_button),
                        onSubmit = onSearch,
                        imeAction = ImeAction.Search,
                        enabled = !state.isSearching,
                        modifier = Modifier.testTag("word_meaning_input_row"),
                    )
                }

                state.message?.let { msgId ->
                    AlertBanner(
                        type = AlertType.Danger,
                        text = stringResource(msgId),
                        modifier = Modifier.testTag("word_meaning_error"),
                    )
                }

                if (state.hasSearched) {
                    if (state.definitions.isEmpty()) {
                        Text(
                            stringResource(R.string.definition_not_found),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("word_meaning_empty"),
                        )
                    } else {
                        Column(
                            modifier = Modifier.testTag("word_meaning_results"),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (definition in state.definitions) {
                                DefinitionBlock(
                                    definition = definition,
                                    labelWord = definition.display.ifEmpty { state.searchedWord.orEmpty() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
