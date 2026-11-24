package com.example.sostavslova.ui.components

import com.example.sostavslova.ui.captureToImageCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.AchievementRules
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.ui.theme.WordsTheme
import com.example.sostavslova.ui.theme.LightWordsPalette
import com.example.sostavslova.ui.theme.DarkWordsPalette
import com.example.sostavslova.ui.theme.LocalWordsPalette
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AchievementComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun icon(achievement: LevelAchievement) = compose.onNodeWithTag(
        "achievement_icon_${achievement.name.lowercase(Locale.ROOT)}")

    @Test fun stripUsesAvailableRulesOrdersIconsAndWrapsWithoutOverlappingTargets() {
        // Синтетический пул делает доступными все восемь целей, включая длинные слова.
        val available = AchievementRules.available(GameState("исходник", listOf(
            "а".repeat(AchievementRules.LONG_WORD_LENGTH),
            "б".repeat(AchievementRules.LONG_WORD_LENGTH),
            "в".repeat(AchievementRules.GIANT_LENGTH))), 1, 2)
        assertEquals(LevelAchievement.entries.toSet(), available)
        compose.setContent { WordsTheme { Surface {
            Box(Modifier.width(112.dp)) {
                AchievementStrip(setOf(LevelAchievement.EXPERT), available.reversed().toSet())
            }
        } } }
        val bounds = LevelAchievement.entries.map { achievement ->
            val node = icon(achievement).assertHasClickAction().assertIsDisplayed()
            node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            node.fetchSemanticsNode().boundsInRoot
        }
        for ((previous, next) in bounds.zipWithNext()) {
            assertTrue("Фиксированный порядок слева направо, затем следующая строка",
                next.top > previous.top || (next.top == previous.top && next.left > previous.left))
            if (next.top == previous.top) {
                assertEquals(8f * compose.density.density, next.left - previous.right, 1f)
            } else {
                assertTrue(next.top - previous.bottom >= 8f * compose.density.density - 1f)
            }
        }
        assertTrue("Есть перенос", bounds.last().top > bounds.first().top)
        icon(LevelAchievement.EXPERT).assertContentDescriptionEquals("Достижение «Знаток»: получено")
        icon(LevelAchievement.COLLECTOR).assertContentDescriptionEquals(
            "Достижение «Коллекционер»: не получено. Условие: Найти половину слов уровня")
    }

    @Test fun compactStripPacksOneToEightAvailableTargetsWithoutEmptyCells() {
        val available = mutableStateOf(emptySet<LevelAchievement>())
        val width = mutableStateOf(288)
        compose.setContent { WordsTheme { Surface {
            Box(Modifier.requiredWidth(width.value.dp)) {
                AchievementStrip(setOf(LevelAchievement.EXPERT, LevelAchievement.GIANT),
                    available.value, compact = true)
            }
        } } }
        for (innerWidth in listOf(288, 328, 379, 384)) {
            for (count in 1..8) {
                // Обратный порядок и разреженное подмножество выявляют пустые ячейки и нарушение порядка.
                val selected = LevelAchievement.entries.reversed().take(count).toSet()
                compose.runOnIdle { width.value = innerWidth; available.value = selected }
                val bounds = LevelAchievement.entries.filter { it in selected }.map { achievement ->
                    icon(achievement).assertHasClickAction().assertWidthIsEqualTo(48.dp)
                        .assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
                }
                LevelAchievement.entries.filter { it !in selected }.forEach { icon(it).assertDoesNotExist() }
                val columns = innerWidth / 48
                assertEquals((count + columns - 1) / columns, bounds.map { it.top }.distinct().size)
                bounds.forEachIndexed { index, rect ->
                    assertEquals(bounds.first().left.value + (index % columns) * 48, rect.left.value, 1f)
                    assertEquals(bounds.first().top.value + (index / columns) * 48, rect.top.value, 1f)
                    assertTrue(rect.right <= bounds.first().left + innerWidth.dp)
                }
                if (LevelAchievement.EXPERT in selected) {
                    icon(LevelAchievement.EXPERT).assertContentDescriptionEquals("Достижение «Знаток»: получено")
                }
                icon(LevelAchievement.STRAIGHT_A).assertContentDescriptionEquals(
                    "Достижение «Отличник»: не получено. Условие: Получить все звёзды уровня")
            }
        }
    }

    @Test fun stripHidesUnavailableEvenIfPreviouslyEarnedAndHandlesEmptySet() {
        val available = mutableStateOf(AchievementRules.available(GameState("слово", listOf("лов")), 1, 1))
        compose.setContent { WordsTheme { AchievementStrip(LevelAchievement.entries.toSet(), available.value) } }
        for (achievement in listOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT,
            LevelAchievement.COLLECTOR, LevelAchievement.EXPLORER)) icon(achievement).assertExists()
        for (achievement in listOf(LevelAchievement.LONG_WORD, LevelAchievement.LONG_WORD_MASTER,
            LevelAchievement.GIANT, LevelAchievement.STRAIGHT_A)) icon(achievement).assertDoesNotExist()
        compose.runOnIdle {
            available.value = AchievementRules.available(GameState("слово", emptyList()), 1, 1)
        }
        for (achievement in LevelAchievement.entries) icon(achievement).assertDoesNotExist()
    }

    @Test fun everyIconOpensNamedDialogWithConditionStateAndCloses() {
        val current = mutableStateOf(LevelAchievement.NO_HINTS)
        val earned = mutableStateOf(false)
        compose.setContent { WordsTheme { AchievementIcon(current.value, earned.value, size = 24.dp, compact = true) } }
        val titles = listOf("Без подсказок", "Знаток", "Коллекционер", "Исследователь", "Длинное слово",
            "Мастер длинных слов", "Гигант мысли", "Отличник")
        val conditions = listOf("Пройти уровень без подсказок", "Найти треть слов уровня",
            "Найти половину слов уровня", "Найти две трети слов уровня",
            context.getString(R.string.achievement_long_word_condition, AchievementRules.LONG_WORD_LENGTH),
            context.getString(R.string.achievement_long_word_master_condition,
                AchievementRules.LONG_WORD_MASTER_COUNT, AchievementRules.LONG_WORD_LENGTH),
            context.getString(R.string.achievement_giant_condition, AchievementRules.GIANT_LENGTH),
            "Получить все звёзды уровня")
        for ((index, achievement) in LevelAchievement.entries.withIndex()) {
            for (isEarned in listOf(false, true)) {
                compose.runOnIdle { current.value = achievement; earned.value = isEarned }
                val description = if (isEarned) "Достижение «${titles[index]}»: получено"
                    else "Достижение «${titles[index]}»: не получено. Условие: ${conditions[index]}"
                icon(achievement).assertContentDescriptionEquals(description)
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithTag("achievement_dialog").assertIsDisplayed()
                compose.onNodeWithText(titles[index]).assertIsDisplayed()
                compose.onNodeWithText(conditions[index]).assertIsDisplayed()
                compose.onNodeWithText(if (isEarned) "Получено" else "Не получено").assertIsDisplayed()
                val note = compose.onNodeWithText("Есть только на уровнях, где часть заданий можно пропустить.")
                if (achievement == LevelAchievement.STRAIGHT_A) note.assertIsDisplayed() else note.assertDoesNotExist()
                compose.onNodeWithText("Закрыть").performClick()
                compose.onNodeWithTag("achievement_dialog").assertDoesNotExist()
            }
        }
    }

    @Test fun everyGlyphRendersFilledAndOutlineGeometryInBothThemes() {
        val current = mutableStateOf(LevelAchievement.NO_HINTS)
        val earned = mutableStateOf(false)
        val palette = mutableStateOf(LightWordsPalette)
        compose.setContent { WordsTheme {
            CompositionLocalProvider(LocalWordsPalette provides palette.value) {
                Surface(color = palette.value.background) {
                    AchievementIcon(current.value, earned.value, size = 28.dp)
                }
            }
        } }
        for (theme in listOf(LightWordsPalette, DarkWordsPalette)) {
            for (achievement in LevelAchievement.entries) {
                val areas = listOf(false, true).map { isEarned ->
                    compose.runOnIdle { palette.value = theme; current.value = achievement; earned.value = isEarned }
                    val pixels = icon(achievement).captureToImageCompat().toPixelMap()
                    val color = achievementShapeColor(theme, achievement, isEarned)
                    var area = 0
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                        val pixel = pixels[x, y]
                        if (abs(pixel.red - color.red) < 0.02f && abs(pixel.green - color.green) < 0.02f &&
                            abs(pixel.blue - color.blue) < 0.02f) area++
                    }
                    assertTrue("$achievement earned=$isEarned: форма нарисована цветом палитры", area > 0)
                    area
                }
                assertTrue("$achievement: полученная форма заполнена, неполученная — контур",
                    areas[1] > areas[0])
            }
        }
    }

    @Test fun dialogReflectsEarnedUpdateWhileOpen() {
        val earned = mutableStateOf(false)
        compose.setContent { WordsTheme { AchievementIcon(LevelAchievement.EXPERT, earned.value, size = 40.dp) } }
        icon(LevelAchievement.EXPERT).performClick()
        compose.onNodeWithText("Не получено").assertExists()
        compose.runOnIdle { earned.value = true }
        compose.onNodeWithText("Получено").assertExists()
        compose.onNodeWithText("Не получено").assertDoesNotExist()
    }
}
