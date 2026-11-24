package com.example.sostavslova.ui.stats

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.CampaignStatistics
import com.example.sostavslova.core.LengthStat
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.LevelStatsRow
import com.example.sostavslova.core.StageStatsRows
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Обе формы строки: вся матрица ширин и шрифтов, включая таблицу и восемь достижений. */
@RunWith(Parameterized::class)
class StatsLayoutTest(private val widthDp: Int, private val fontScale: Float, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun fullWidthRowsKeepCompactAchievementsAdaptiveToggleAndReadableDetails() {
        val all = LevelAchievement.entries.toSet()
        val row = LevelStatsRow(1, "электростанция", "Разминка", 1, 5, 3, 38, 112, 7,
            listOf(LengthStat(3, 8, 32), LengthStat(4, 30, 80)),
            achievements = setOf(LevelAchievement.EXPERT), achievementsAvailable = all)
        val statistics = CampaignStatistics(1, 5, 0, 1, 38, 112, 7,
            listOf(StageStatsRows(1, "Разминка", 1, 1, listOf(row))), listOf(row), 1, 8)
        compose.setContent {
            val density = LocalDensity.current.density
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
                LocalConfiguration provides configuration) { WordsTheme {
                // requiredWidth сохраняет все три заданные ширины даже на узком тестовом устройстве.
                Box(Modifier.requiredWidth(widthDp.dp)) {
                    StatsScreen(StatsUiState(loading = false, statistics = statistics))
                }
            }
        } }
        checkTextLayout()
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_1")
        for (expanded in listOf(false, true)) {
            compose.onNodeWithTag("stats_row_1").assertWidthIsEqualTo(widthDp.dp)
            checkTextLayout()
            val words = compose.onNodeWithTag("stats_metric_words_1", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val stars = compose.onNodeWithTag("stats_metric_stars_1", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue("Метрики рядом без пересечения", stars.left >= words.right)
            assertEquals("Метрики выровнены по центру", (words.top.value + words.bottom.value) / 2, (stars.top.value + stars.bottom.value) / 2, 1f)
            for ((metric, label, value) in listOf(
                Triple("words", "Найдено слов", "38 / 112"), Triple("stars", "Звёзды", "1 / 5"),
            )) {
                val ancestor = hasAnyAncestor(hasTestTag("stats_metric_${metric}_1"))
                val labelBounds = compose.onNode(ancestor and hasText(label), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val valueBounds = compose.onNode(ancestor and hasText(value), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                assertTrue("Подпись над значением", labelBounds.bottom <= valueBounds.top)
            }
            val achievements = compose.onNodeWithTag("stats_achievements_1", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val icons = LevelAchievement.entries.map { achievement ->
                val tag = "stats_achievement_1_${achievement.name.lowercase(java.util.Locale.ROOT)}"
                compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .getUnclippedBoundsInRoot()
            }
            assertTrue("Достижения ниже показателей", achievements.top >= maxOf(words.bottom, stars.bottom))
            val columns = (widthDp - 32) / 48
            assertEquals("Использована вся внутренняя ширина без лишних рядов",
                (8 + columns - 1) / columns, icons.map { it.top }.distinct().size)
            assertEquals("Внутренняя ширина карточки", (widthDp - 32).toFloat(), (achievements.right - achievements.left).value, 1f)
            icons.forEachIndexed { index, bounds ->
                assertEquals(48f, (bounds.right - bounds.left).value, 0.5f)
                assertEquals(48f, (bounds.bottom - bounds.top).value, 0.5f)
                assertEquals(achievements.left.value + (index % columns) * 48, bounds.left.value, 1f)
                assertEquals(icons.first().top.value + (index / columns) * 48, bounds.top.value, 1f)
            }
            for ((index, bounds) in icons.withIndex()) {
                assertTrue("Достижение внутри ширины блока", bounds.left >= achievements.left && bounds.right <= achievements.right)
                icons.take(index).forEach { previous ->
                    assertTrue("Цели достижений не пересекаются",
                        bounds.top >= previous.bottom || bounds.left >= previous.right)
                }
            }
            val toggle = compose.onNodeWithTag("stats_toggle_1")
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assertTextEquals(if (expanded) "Свернуть ▴" else "Подробнее ▾")
                .getUnclippedBoundsInRoot()
            compose.onAllNodesWithTag("stats_toggle_1").assertCountEquals(1)
            assertEquals("Кнопка у внутреннего правого края", achievements.right.value, toggle.right.value, 1f)
            assertTrue("Кнопка выше достижений", toggle.bottom <= achievements.top)
            if (fontScale == 1f) {
                assertTrue("Три блока рядом без пересечений", toggle.left >= stars.right)
                assertEquals("Кнопка в центре строки", (words.top.value + words.bottom.value) / 2, (toggle.top.value + toggle.bottom.value) / 2, 1f)
            } else if (fontScale == 2f) {
                assertTrue("На крупном шрифте кнопка ниже метрик", toggle.top >= maxOf(words.bottom, stars.bottom))
            }
            if (expanded) {
                val table = compose.onNodeWithTag("stats_table_1", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                val required = compose.onNodeWithTag("stats_required_1", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                assertTrue("Таблица занимает ширину блока", table.right - table.left == achievements.right - achievements.left)
                assertTrue("Подробности ниже компактной шапки", table.top >= achievements.bottom && required.top >= table.bottom)
                // Проверяем и при прокрутке нижней части высокой раскрытой строки.
                compose.onNodeWithTag("stats_required_1", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                checkTextLayout()
            } else {
                compose.onNodeWithTag("stats_toggle_1").performScrollTo().performTouchInput { click() }
            }
        }
    }

    private fun checkTextLayout() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            assertTrue(action(layouts))
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertTextLayoutFits(it, "width=$widthDp, fontScale=$fontScale") }
        }
        compose.onAllNodes(hasAnyAncestor(hasTestTag("stats_row_1")), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                // size не обрезан границами видимой области LazyColumn.
                assertTrue("Нет горизонтального переполнения", node.size.width <= widthDp * compose.density.density + 1f)
            }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "width={0}, fontScale={1}, dark={2}")
        fun sizes(): List<Array<Any>> = listOf(320, 360, 411).flatMapIndexed { index, width ->
            listOf(1f, 1.5f, 2f).mapIndexed { scaleIndex, scale ->
                arrayOf<Any>(width, scale, (index + scaleIndex) % 2 == 1)
            }
        } + listOf(arrayOf<Any>(416, 1f, false))
    }
}
