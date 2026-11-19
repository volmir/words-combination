package com.example.sostavslova.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R
import com.example.sostavslova.di.appContainer
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.RussianPlural
import com.example.sostavslova.data.LoadState
import com.example.sostavslova.ui.components.Well
import com.example.sostavslova.ui.theme.WordsCardShape
import com.example.sostavslova.ui.theme.WordsColors
import kotlinx.coroutines.CancellationException

@Composable
fun RulesScreen(loadOptionalTaskLevel: (suspend () -> Int?)? = null) {
    val container = appContainer()
    var optionalTaskLevel by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(loadOptionalTaskLevel, container) {
        optionalTaskLevel = try {
            loadOptionalTaskLevel?.invoke()
                ?: container.levelsRepository.getCampaign()?.let(CampaignRules::firstOptionalTaskLevel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
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

                val optionalLevel = optionalTaskLevel
                val items = listOf(
                    stringResource(R.string.rules_item_1),
                    stringResource(R.string.rules_item_2),
                    stringResource(R.string.rules_item_3),
                    if (optionalLevel != null) stringResource(R.string.rules_item_4, optionalLevel)
                    else stringResource(R.string.rules_item_4_no_campaign),
                    stringResource(R.string.rules_item_5),
                    stringResource(R.string.rules_item_6),
                    stringResource(R.string.rules_item_7),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("rules_items")) {
                    for ((index, itemText) in items.withIndex()) {
                        Text(
                            itemText,
                            modifier = Modifier.testTag("rules_item_${index + 1}"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rules_colors_title), style = MaterialTheme.typography.titleMedium)
                val colors = listOf(
                    Triple("rules_level_color_locked", WordsColors.levelLockedBackground, R.string.rules_color_locked),
                    Triple("rules_level_color_available", WordsColors.levelAvailableBackground, R.string.rules_color_available),
                    Triple("rules_level_color_passed", WordsColors.levelPassedBackground, R.string.rules_color_passed),
                    Triple("rules_level_color_all_stars", WordsColors.levelAllStarsBackground, R.string.rules_color_all_stars),
                )
                for ((tag, color, label) in colors) {
                    // Белый фон доступного уровня сливается с экраном без рамки, как и у карточек на карте.
                    Surface(Modifier.fillMaxWidth(), color = color, contentColor = WordsColors.text, shape = WordsCardShape,
                        border = BorderStroke(1.dp, WordsColors.defaultButtonBorder)) {
                        Text(stringResource(label), Modifier.padding(12.dp).testTag(tag),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WordsColors.alertInfoBackground,
                contentColor = WordsColors.alertInfoText,
                shape = WordsCardShape,
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
    val container = appContainer()
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
                stringResource(R.string.about_words_count, count, RussianPlural.words(count))
            } else {
                stringResource(R.string.about_words_loading)
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
                }
            }

            Text(
                stringResource(R.string.about_code_heading),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                stringResource(R.string.about_code_sources),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("about_code_license"),
            )

            Text(
                stringResource(R.string.about_graphics_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.about_graphics_sources),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("about_graphics_license"),
            )

        }
    }
}
