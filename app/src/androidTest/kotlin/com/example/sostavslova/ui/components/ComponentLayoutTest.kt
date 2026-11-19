package com.example.sostavslova.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

/** Прямой рендер компонентов на Android, включая нативные окна диалогов. */
@RunWith(Parameterized::class)
class ComponentLayoutTest(private val exampleName: String) {
    private val example get() = ComponentExample.valueOf(exampleName)
    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersWithoutTextOverflowAndWith48DpClickTargets() {
        compose.setContent {
            WordsTheme { example.Render() }
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
                val context = "$example, native: ${layout.layoutInput.text}"
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
        @Parameterized.Parameters(name = "{0}")
        fun examples(): List<Array<Any>> = ComponentExample.entries.map { example ->
            arrayOf<Any>(example.name)
        }
    }
}

/** Фикстуры принадлежат тестам и не включаются в приложение. */
private enum class ComponentExample {
    WELL, WORD_INPUT, LETTERS, STARS, FOUND_WORDS, ALERTS, STATISTICS, RIDDLE, TOP_BAR, DRAWER;

    @Composable
    fun Render() {
        when (this) {
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
                        FOUND_WORDS -> FoundWords(listOf("пар", "порт", "тропа"))
                        ALERTS -> {
                            AlertBanner(AlertType.Success, "Правильно!")
                            AlertBanner(AlertType.Info, "Найденные слова")
                            AlertBanner(AlertType.Warning, "Нет комбинаций")
                            AlertBanner(AlertType.Danger, "Слово слишком короткое")
                        }
                        STATISTICS -> StatsTable(Statistics(
                            listOf(LengthStat(3, 2, 10), LengthStat(4, 1, 12), LengthStat(5, 0, 6)),
                        ))
                        RIDDLE -> Text("Учебный пример: <органы управления>.\nВторая строка загадки сохраняет перенос.")
                        else -> Unit
                    }
                }
            }
        }
    }

}

/**
 * Плитки исходного слова на ширинах 320/360/411 dp и системном масштабе
 * шрифта 1.0/1.3/2.0: строки ровные, плитки не выходят за экран.
 * Отдельный класс, потому что [ComponentLayoutTest] параметризован по компонентам.
 */
class LetterTilesLayoutTest {
    @get:Rule val compose = createComposeRule()

    private data class WordCase(val width: Int, val fontScale: Float, val word: String)

    @Test
    fun sourceWordTilesWrapEvenlyWithoutClipping() {
        val words = listOf("чемпионство", "журналистика", "благодарность")
        assertEquals("Длины слов фикстуры", listOf(11, 12, 13), words.map { it.length })
        val cases = listOf(320, 360, 411).flatMap { width ->
            listOf(1f, 1.3f, 2f).flatMap { fontScale ->
                words.map { word -> WordCase(width, fontScale, word) }
            }
        }
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, current.value.fontScale)) {
                WordsTheme {
                    Box(Modifier.width(current.value.width.dp).background(Color.White).padding(16.dp)) {
                        LetterTiles(current.value.word)
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            verifySourceWord(case)
        }
    }

    private fun verifySourceWord(case: WordCase) {
        val context = "${case.width}dp, fontScale=${case.fontScale}, ${case.word}"
        val container = compose.onNodeWithTag("source_word_tiles", useUnmergedTree = true).fetchSemanticsNode()
        val tiles = case.word.indices.map { index ->
            compose.onNodeWithTag("source_word_tile_${index + 1}", useUnmergedTree = true).fetchSemanticsNode()
        }
        assertEquals("$context: потеряны плитки", case.word.length, tiles.size)
        val density = compose.density.density
        val minimum = 28f * density - 1f
        val maximum = 36f * density + 1f
        tiles.forEachIndexed { index, node ->
            assertTrue("$context: плитка ${index + 1} меньше 28 dp: ${node.size}",
                node.size.width >= minimum && node.size.height >= minimum)
            assertTrue("$context: плитка ${index + 1} больше 36 dp: ${node.size}",
                node.size.width <= maximum && node.size.height <= maximum)
            val bounds = node.boundsInRoot
            val parent = container.boundsInRoot
            assertTrue("$context: плитка ${index + 1} обрезана слева", bounds.left >= parent.left - 1f)
            assertTrue("$context: плитка ${index + 1} обрезана справа", bounds.right <= parent.right + 1f)
        }
        val rows = tiles.groupBy { it.boundsInRoot.top.roundToInt() }.values.map { it.size }
        val widest = rows.maxOrNull() ?: 0
        val narrowest = rows.minOrNull() ?: 0
        assertTrue("$context: неровные строки $rows", widest - narrowest <= 1)
        assertEquals("$context: потеряны буквы в строках", case.word.length, rows.sum())
        assertEquals("$context: описание исходного слова", "Исходное слово: ${case.word}",
            container.config[SemanticsProperties.ContentDescription].joinToString(""))
        assertTrue("$context: не должно быть горизонтальной прокрутки",
            compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("source_word_tiles")),
                useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val letterNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("source_word_tiles")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("$context: потеряны буквы", case.word.length, letterNodes.size)
        for (node in letterNodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) assertTextLayoutFits(layout, context)
        }
    }
}
