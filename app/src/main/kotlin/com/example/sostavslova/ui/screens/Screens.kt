package com.example.sostavslova.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R
import com.example.sostavslova.WordsApplication
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.data.LoadState
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun WordSearchScreen() = com.example.sostavslova.ui.search.WordSearchRoute()

@Composable
fun WordMeaningScreen() = com.example.sostavslova.ui.meaning.WordMeaningRoute()

@Composable
fun RulesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("rules_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.rules_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Well {
                Text(
                    stringResource(R.string.rules_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Text(
                    stringResource(R.string.rules_description_heading),
                    style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                )

                val items = listOf(
                    R.string.rules_item_1,
                    R.string.rules_item_2,
                    R.string.rules_item_3,
                    R.string.rules_item_4,
                    R.string.rules_item_5,
                    R.string.rules_item_6,
                    R.string.rules_item_7,
                    R.string.rules_item_8,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in items) {
                        Text(
                            stringResource(item),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WordsColors.alertInfoBackground,
                contentColor = WordsColors.alertInfoText,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, WordsColors.alertInfoBorder),
            ) {
                Text(
                    stringResource(R.string.rules_footer),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val container = (LocalContext.current.applicationContext as WordsApplication).container
    val wordRepo = container.wordRepository
    var wordCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(wordRepo) {
        wordRepo.ensureLoaded()
        wordRepo.state.collect { state ->
            if (state is LoadState.Ready) {
                wordCount = state.wordCount
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("about_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.nav_about),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyLarge,
            )

            val count = wordCount
            val countText = if (count != null) {
                val wordsForm = RussianPlural.words(count)
                buildAnnotatedString {
                    append("Всего в базе игры: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("$count $wordsForm")
                    }
                }
            } else {
                buildAnnotatedString {
                    append(stringResource(R.string.about_words_loading))
                }
            }

            Text(
                countText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("about_word_count"),
            )

            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = WordsColors.text,
                modifier = Modifier.testTag("about_version"),
            )

            Text(
                stringResource(R.string.about_definitions_heading),
                style = MaterialTheme.typography.titleMedium,
            )

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.about_definitions_authors),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_definitions_extraction),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_definitions_license),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_definitions_changes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_definitions_history),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_code_sources),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (!BuildConfig.HAS_DEFINITIONS) {
                Text(
                    stringResource(R.string.definitions_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
