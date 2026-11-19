package com.example.sostavslova.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.ui.theme.LocalWordsPalette
import java.util.Locale

/**
 * Плитки исходного слова: строки распределяются равномерно (balancedRows),
 * размер плитки подстраивается под ширину (32–36 dp). Шрифт пропорционален
 * плитке и не обрезается при системном масштабе; рамка — 4 dp, отступы — 2 dp.
 */
@Composable
fun LetterTiles(word: String, modifier: Modifier = Modifier) {
    if (word.isEmpty()) return
    val letters = word.uppercase(Locale.ROOT)
    // Одна фраза вместо N отдельных букв: исходное слово — главное условие игры.
    val description = stringResource(R.string.source_word_description, word)
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .testTag("source_word_tiles")
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val maxCell = if (letters.length > 20) 32.dp else 36.dp
        val layout = letterSlotsLayout(maxWidth, letters.length, spacing = 2.dp, minCell = 32.dp, maxCell = maxCell)
        // Dp.toSp учитывает fontScale: физический размер буквы остаётся
        // пропорционален плитке при любом системном масштабе.
        val fontSize = with(LocalDensity.current) { (layout.cell * 0.5f).toSp() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.spacing)) {
            var index = 0
            for (rowCount in layout.rows) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.spacing, Alignment.CenterHorizontally),
                ) {
                    repeat(rowCount) {
                        val tileIndex = index++
                        Box(
                            Modifier.size(layout.cell)
                                .border(4.dp, LocalWordsPalette.current.tileBorder)
                                .testTag("source_word_tile_${tileIndex + 1}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                letters[tileIndex].toString(),
                                modifier = Modifier.fillMaxWidth(),
                                color = LocalWordsPalette.current.text,
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
