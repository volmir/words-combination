package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.sostavslova.core.LengthStat
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.data.Definition
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Прямой рендер компонентов на Android, включая нативные окна диалогов. */
@RunWith(Parameterized::class)
class ComponentLayoutTest(private val exampleName: String, private val fontScale: Float) {
    private val example get() = ComponentExample.valueOf(exampleName)
    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersWithoutTextOverflowAndWith48DpClickTargets() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.requiredWidth(if (fontScale > 1f) 320.dp else 360.dp).fillMaxHeight()) {
                    WordsTheme { example.Render() }
                }
            }
        }
        compose.waitForIdle()
        val texts = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val descriptions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("В компоненте должен быть текст или описание иконок: $example", texts.isNotEmpty() || descriptions.isNotEmpty())
        for (node in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                // Кэшированный MultiParagraph может быть шире shrink-wrap Text.
                // Сравниваем фактические строки с размером Text, а не ширину constraints.
                val context = "$example, $fontScale: ${layout.layoutInput.text}"
                assertFalse("Потеряны строки: $context", layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Многоточие: $context", layout.isLineEllipsized(line))
                    assertTrue("Обрезан правый край: $context", layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue("Обрезан левый край: $context", layout.getLineLeft(line) >= -1f)
                    assertTrue("Обрезан низ: $context", layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val minimumPx = 48f * compose.density.density
            assertTrue("Узкая цель $example: ${node.size}", node.size.width >= minimumPx - 1f)
            assertTrue("Низкая цель $example: ${node.size}", node.size.height >= minimumPx - 1f)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, fontScale={1}")
        fun examples(): List<Array<Any>> = ComponentExample.entries.flatMap { example ->
            listOf(arrayOf<Any>(example.name, 1f), arrayOf<Any>(example.name, 2f))
        }
    }
}

/** Фикстуры принадлежат тестам и не включаются в приложение. */
private enum class ComponentExample {
    WELL, WORD_INPUT, LETTERS, STARS, LINKS, ALERTS, STATISTICS, DEFINITION, DEFINITION_DIALOG, TOP_BAR, DRAWER;

    @Composable
    fun Render() {
        when (this) {
            DEFINITION_DIALOG -> DefinitionDialog("портал", listOf(definition()), {}, definitionsAvailable = true)
            TOP_BAR -> WordsTopBar {}
            DRAWER -> WordsDrawer(Destination.Levels.route) {}
            else -> Surface {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (this@ComponentExample) {
                        WELL -> Well { Text("Введите слово") }
                        WORD_INPUT -> WordInputRow("Ваш ответ", "портал", {}, "Проверить", {})
                        LETTERS -> {
                            LetterTiles("портал")
                            LetterTiles("электроэнцефалографический")
                        }
                        STARS -> StarsRow(LevelProgress(completed = true, wordsTask = true), onClick = {})
                        LINKS -> WordLinks(listOf("пар", "порт", "тропа"), {})
                        ALERTS -> {
                            AlertBanner(AlertType.Success, "Правильно!")
                            AlertBanner(AlertType.Info, "Найденные слова")
                            AlertBanner(AlertType.Warning, "Нет комбинаций")
                            AlertBanner(AlertType.Danger, "Слово слишком короткое")
                        }
                        STATISTICS -> StatsTable(Statistics(
                            listOf(LengthStat(3, 2, 10), LengthStat(4, 1, 12), LengthStat(5, 0, 6)),
                        ))
                        DEFINITION -> DefinitionBlock(definition())
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun definition() = Definition(
        display = "портал",
        text = "Учебный пример: <органы управления>.\nВторая строка определения сохраняет перенос.",
        notes = "Примечание к учебному примеру.",
        antonym = "противоположное значение",
        example = "Это пример употребления слова.",
    )
}
