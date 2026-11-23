package com.example.sostavslova.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.AchievementRules
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsPalette
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun achievementName(achievement: LevelAchievement): String = stringResource(when (achievement) {
    LevelAchievement.NO_HINTS -> R.string.achievement_no_hints
    LevelAchievement.EXPERT -> R.string.achievement_expert
    LevelAchievement.COLLECTOR -> R.string.achievement_collector
    LevelAchievement.EXPLORER -> R.string.achievement_explorer
    LevelAchievement.LONG_WORD -> R.string.achievement_long_word
    LevelAchievement.LONG_WORD_MASTER -> R.string.achievement_long_word_master
    LevelAchievement.GIANT -> R.string.achievement_giant
    LevelAchievement.STRAIGHT_A -> R.string.achievement_straight_a
})

@Composable
private fun achievementCondition(achievement: LevelAchievement): String = when (achievement) {
    LevelAchievement.NO_HINTS -> stringResource(R.string.achievement_no_hints_condition)
    LevelAchievement.EXPERT -> stringResource(R.string.achievement_expert_condition)
    LevelAchievement.COLLECTOR -> stringResource(R.string.achievement_collector_condition)
    LevelAchievement.EXPLORER -> stringResource(R.string.achievement_explorer_condition)
    LevelAchievement.LONG_WORD -> stringResource(R.string.achievement_long_word_condition, AchievementRules.LONG_WORD_LENGTH)
    LevelAchievement.LONG_WORD_MASTER -> stringResource(R.string.achievement_long_word_master_condition,
        AchievementRules.LONG_WORD_MASTER_COUNT, AchievementRules.LONG_WORD_LENGTH)
    LevelAchievement.GIANT -> stringResource(R.string.achievement_giant_condition, AchievementRules.GIANT_LENGTH)
    LevelAchievement.STRAIGHT_A -> stringResource(R.string.achievement_straight_a_condition)
}

/** Рисунок занимает 28–40 dp, а отдельная цель нажатия — минимум 48 dp. */
@Composable
fun AchievementIcon(
    achievement: LevelAchievement,
    earned: Boolean,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(achievement) { mutableStateOf(false) }
    val name = achievementName(achievement)
    val condition = achievementCondition(achievement)
    val description = if (earned) stringResource(R.string.achievement_earned_description, name)
        else stringResource(R.string.achievement_not_earned_description, name, condition)
    Box(modifier
        .testTag("achievement_icon_${achievement.name.lowercase(Locale.ROOT)}")
        .minimumInteractiveComponentSize()
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .clickable(role = Role.Button) { expanded = true }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        AchievementGlyph(achievement, earned, size.coerceIn(28.dp, 40.dp))
    }
    if (expanded) AchievementDialog(achievement, earned) { expanded = false }
}

/** [availableAchievements] берётся из AchievementRules.available, отсутствующие цели не рисуются. */
@Composable
fun AchievementStrip(
    achievements: Set<LevelAchievement>,
    availableAchievements: Set<LevelAchievement>,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LevelAchievement.entries.filter { it in availableAchievements }.forEach { achievement ->
            AchievementIcon(achievement, achievement in achievements)
        }
    }
}

@Composable
fun AchievementDialog(achievement: LevelAchievement, earned: Boolean, onDismiss: () -> Unit) {
    WordsDialog(achievementName(achievement), onDismiss, Modifier.testTag("achievement_dialog")) {
        AchievementGlyph(achievement, earned, 40.dp)
        Text(achievementCondition(achievement), style = MaterialTheme.typography.bodyLarge)
        if (achievement == LevelAchievement.STRAIGHT_A) {
            Text(stringResource(R.string.achievement_straight_a_note), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(if (earned) R.string.achievement_earned else R.string.achievement_not_earned),
            style = MaterialTheme.typography.bodyLarge)
        TextButton(onDismiss, Modifier.align(Alignment.End).minimumInteractiveComponentSize()) {
            Text(stringResource(R.string.close_dialog))
        }
    }
}

/** Без прозрачности: значимая форма сохраняет контраст и в неполученном состоянии. */
internal fun achievementShapeColor(palette: WordsPalette, achievement: LevelAchievement, earned: Boolean): Color =
    if (!earned) palette.starEmpty else if (achievement == LevelAchievement.STRAIGHT_A) palette.starDone else palette.primary

@Composable
private fun AchievementGlyph(achievement: LevelAchievement, earned: Boolean, iconSize: Dp) {
    val palette = LocalWordsPalette.current
    val color = achievementShapeColor(palette, achievement, earned)
    val tileText = when (achievement) {
        LevelAchievement.LONG_WORD -> stringResource(R.string.achievement_length_tile, AchievementRules.LONG_WORD_LENGTH)
        LevelAchievement.LONG_WORD_MASTER -> stringResource(R.string.achievement_count_length_tile,
            AchievementRules.LONG_WORD_MASTER_COUNT, AchievementRules.LONG_WORD_LENGTH)
        LevelAchievement.GIANT -> stringResource(R.string.achievement_length_tile, AchievementRules.GIANT_LENGTH)
        else -> null
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(Modifier.size(iconSize)) {
        // Координаты собственной геометрии нормированы на квадрат 100×100.
        withTransform({ scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) }) {
            val outline = Stroke(5f, cap = StrokeCap.Round)
            when (achievement) {
                LevelAchievement.NO_HINTS -> {
                    val bulb = Path().apply {
                        moveTo(36f, 64f)
                        cubicTo(36f, 54f, 23f, 47f, 23f, 34f)
                        cubicTo(23f, 0f, 77f, 0f, 77f, 34f)
                        cubicTo(77f, 47f, 64f, 54f, 64f, 64f)
                        close()
                    }
                    drawPath(bulb, color, style = if (earned) Fill else outline)
                    drawLine(color, Offset(37f, 75f), Offset(63f, 75f), 5f, StrokeCap.Round)
                    drawLine(color, Offset(42f, 85f), Offset(58f, 85f), 5f, StrokeCap.Round)
                    // Просвет вокруг диагонали сохраняет перечёркивание и на заполненной лампочке.
                    drawLine(palette.background, Offset(12f, 12f), Offset(88f, 90f), 12f, StrokeCap.Round)
                    drawLine(color, Offset(12f, 12f), Offset(88f, 90f), 5f, StrokeCap.Round)
                }
                LevelAchievement.EXPERT, LevelAchievement.COLLECTOR, LevelAchievement.EXPLORER -> {
                    val fraction = when (achievement) {
                        LevelAchievement.EXPERT -> AchievementRules.EXPERT_NUMERATOR.toFloat() / AchievementRules.EXPERT_DENOMINATOR
                        LevelAchievement.COLLECTOR -> AchievementRules.COLLECTOR_NUMERATOR.toFloat() / AchievementRules.COLLECTOR_DENOMINATOR
                        else -> AchievementRules.EXPLORER_NUMERATOR.toFloat() / AchievementRules.EXPLORER_DENOMINATOR
                    }
                    drawArc(color, -90f, 360f * fraction, useCenter = true,
                        topLeft = Offset(12f, 12f), size = Size(76f, 76f), style = if (earned) Fill else outline)
                    drawCircle(color, radius = 38f, center = Offset(50f, 50f), style = outline)
                }
                LevelAchievement.LONG_WORD, LevelAchievement.LONG_WORD_MASTER, LevelAchievement.GIANT ->
                    drawRoundRect(color, topLeft = Offset(5f, 12f), size = Size(90f, 76f),
                        cornerRadius = CornerRadius(12f), style = if (earned) Fill else outline)
                LevelAchievement.STRAIGHT_A -> drawPath(starPath(), color, style = if (earned) Fill else outline)
            }
        }
        if (tileText != null) {
            // Текст — часть пиктограммы: масштабируем вместе с ней независимо от размера шрифта UI.
            val fontSize = with(density) { (size.width * if (achievement == LevelAchievement.LONG_WORD_MASTER) 0.25f else 0.4f).toSp() }
            val text = measurer.measure(tileText, TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold,
                color = if (earned) palette.background else color))
            drawText(text, topLeft = Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2))
        }
    }
}

private fun starPath(): Path = Path().apply {
    repeat(10) { vertex ->
        val angle = -Math.PI / 2 + vertex * Math.PI / 5
        val radius = if (vertex % 2 == 0) 43f else 20f
        val x = 50f + cos(angle).toFloat() * radius
        val y = 50f + sin(angle).toFloat() * radius
        if (vertex == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
