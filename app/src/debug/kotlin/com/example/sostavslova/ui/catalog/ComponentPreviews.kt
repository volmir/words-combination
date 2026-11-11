package com.example.sostavslova.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.LengthStat
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.data.Definition
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.components.*
import com.example.sostavslova.ui.theme.WordsTheme

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Preview(name = "Обычный шрифт", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Шрифт 200%", widthDp = 320, heightDp = 800, fontScale = 2f, showBackground = true)
internal annotation class ComponentPreview

internal val sampleStatistics = Statistics(listOf(LengthStat(3, 2, 10), LengthStat(4, 1, 12), LengthStat(5, 0, 6)))

@Composable
internal fun sampleDefinition() = Definition(
    display = stringResource(R.string.catalog_word),
    text = stringResource(R.string.catalog_definition_text),
    notes = stringResource(R.string.catalog_definition_notes),
    antonym = stringResource(R.string.catalog_definition_antonym),
    example = stringResource(R.string.catalog_definition_example),
)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    WordsTheme {
        Surface {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@ComponentPreview
@Composable
internal fun WellPreview() = PreviewFrame { Well { Text(stringResource(R.string.enter_word)) } }

@ComponentPreview
@Composable
internal fun WordInputRowPreview() = PreviewFrame {
    WordInputRow(stringResource(R.string.your_answer), stringResource(R.string.catalog_word), {}, stringResource(R.string.check_answer), {})
}

@ComponentPreview
@Composable
internal fun LetterTilesPreview() = PreviewFrame {
    LetterTiles(stringResource(R.string.catalog_word))
    LetterTiles(stringResource(R.string.catalog_long_word))
}

@ComponentPreview
@Composable
internal fun StarsRowPreview() = PreviewFrame { StarsRow(LevelProgress(completed = true, wordsTask = true), onClick = {}) }

@ComponentPreview
@Composable
internal fun WordLinksPreview() = PreviewFrame { WordLinks(stringArrayResource(R.array.catalog_words).toList(), {}) }

@ComponentPreview
@Composable
internal fun AlertBannerPreview() = PreviewFrame {
    AlertBanner(AlertType.Success, stringResource(R.string.correct_answer))
    AlertBanner(AlertType.Info, stringResource(R.string.found_words))
    AlertBanner(AlertType.Warning, stringResource(R.string.no_combinations))
    AlertBanner(AlertType.Danger, stringResource(R.string.word_too_short))
}

@ComponentPreview
@Composable
internal fun StatsTablePreview() = PreviewFrame { StatsTable(sampleStatistics) }

@ComponentPreview
@Composable
internal fun DefinitionBlockPreview() = PreviewFrame { DefinitionBlock(sampleDefinition()) }

@ComponentPreview
@Composable
internal fun DefinitionDialogPreview() {
    WordsTheme { DefinitionDialog(stringResource(R.string.catalog_word), listOf(sampleDefinition()), {}, definitionsAvailable = true) }
}

@ComponentPreview
@Composable
internal fun WordsTopBarPreview() { WordsTheme { WordsTopBar {} } }

@ComponentPreview
@Composable
internal fun WordsDrawerPreview() { WordsTheme { WordsDrawer(Destination.Levels.route) {} } }

/** Тесты исполняют те же функции, которые помечены @Preview, без репозиториев и IO. */
internal enum class ComponentExample {
    Well, WordInput, Letters, Stars, Links, Alerts, Statistics, Definition, DefinitionDialog, TopBar, Drawer;

    @Composable
    fun RenderPreview() {
        when (this) {
            Well -> WellPreview()
            WordInput -> WordInputRowPreview()
            Letters -> LetterTilesPreview()
            Stars -> StarsRowPreview()
            Links -> WordLinksPreview()
            Alerts -> AlertBannerPreview()
            Statistics -> StatsTablePreview()
            Definition -> DefinitionBlockPreview()
            DefinitionDialog -> DefinitionDialogPreview()
            TopBar -> WordsTopBarPreview()
            Drawer -> WordsDrawerPreview()
        }
    }
}
