package com.example.sostavslova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.HintText
import com.example.sostavslova.core.balancedRows
import com.example.sostavslova.ui.theme.LocalWordsPalette

/** Геометрия ряда слотов; переиспользуется плитками исходного слова в UX-13. */
internal data class LetterSlotsLayout(val rows: List<Int>, val cell: Dp, val spacing: Dp)

internal fun letterSlotsLayout(
    maxWidth: Dp,
    count: Int,
    spacing: Dp = 4.dp,
    minCell: Dp = 28.dp,
    maxCell: Dp = 36.dp,
): LetterSlotsLayout {
    require(count > 0) { "count must be positive" }
    val maxPerRow = ((maxWidth + spacing) / (minCell + spacing)).toInt().coerceAtLeast(1)
    val rows = balancedRows(count, maxPerRow)
    val widestRow = rows.maxOrNull() ?: 1
    val cell = ((maxWidth - spacing * (widestRow - 1).toFloat()) / widestRow.toFloat())
        .coerceIn(minCell, maxCell)
    return LetterSlotsLayout(rows, cell, spacing)
}

/**
 * Ряд слотов по числу букв шаблона: открытая буква видна в ячейке, скрытая —
 * пустая рамка без «_». Размер ячейки подстраивается под ширину (28–36 dp),
 * строки распределяются равномерно, а шрифт остаётся пропорционален ячейке.
 */
@Composable
fun LetterSlots(
    pattern: String,
    modifier: Modifier = Modifier,
    slotTagPrefix: String = "game_hint_slot",
) {
    if (pattern.isEmpty()) return
    val description = HintText.slotsDescription(pattern)
    BoxWithConstraints(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val layout = letterSlotsLayout(maxWidth, pattern.length)
        val cell = layout.cell
        // Клетка ограничена 28–36 dp, поэтому буква растёт вместе с ней, но не
        // выходит за рамку даже при системном fontScale 2.0.
        val fontSize = with(LocalDensity.current) { (cell * 0.5f).toSp() }
        val corner = RoundedCornerShape(cell * 0.14f)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.spacing)) {
            var index = 0
            for (rowCount in layout.rows) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.spacing, Alignment.CenterHorizontally),
                ) {
                    repeat(rowCount) {
                        val cellIndex = index++
                        val letter = pattern[cellIndex].takeUnless { it == '_' }?.uppercaseChar()?.toString()
                        val open = letter != null
                        Box(
                            Modifier.size(cell)
                                .background(
                                    if (open) LocalWordsPalette.current.primary.copy(alpha = 0.12f) else LocalWordsPalette.current.background,
                                    corner,
                                )
                                .border(2.dp, if (open) LocalWordsPalette.current.primary else LocalWordsPalette.current.defaultButtonBorder, corner)
                                .testTag("${slotTagPrefix}_${cellIndex + 1}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (letter != null) {
                                Text(
                                    letter,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = LocalWordsPalette.current.primary,
                                    fontSize = fontSize,
                                    lineHeight = fontSize,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
