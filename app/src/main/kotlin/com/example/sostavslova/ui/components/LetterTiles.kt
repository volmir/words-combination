package com.example.sostavslova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sostavslova.R
import com.example.sostavslova.core.LetterOrder
import com.example.sostavslova.core.TileUsage
import com.example.sostavslova.ui.theme.LocalWordsPalette
import java.util.Locale

/** Ровные строки с отдельными доступными кнопками; индексы относятся к исходному слову. */
@Composable
fun LetterTiles(
    word: String,
    modifier: Modifier = Modifier,
    order: List<Int> = LetterOrder.natural(word),
    input: String = "",
    enabled: Boolean = true,
    onTileTap: (Char) -> Unit = {},
) {
    if (word.isEmpty()) return
    val description = stringResource(R.string.source_word_description, word)
    val used = TileUsage.usedMask(word, order, input)
    BoxWithConstraints(
        modifier.fillMaxWidth().testTag("source_word_tiles")
            .semantics { contentDescription = description },
    ) {
        // 20 sp растут с системным шрифтом. При 200% буква помещается в 48 dp;
        // при большем масштабе увеличивается сама плитка, а не обрезается текст.
        val cell = maxOf(48.dp, with(LocalDensity.current) { 20.sp.toDp() } + 8.dp)
        val layout = letterSlotsLayout(maxWidth, order.size, spacing = 2.5.dp, minCell = cell, maxCell = cell)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.spacing)) {
            var position = 0
            for (rowCount in layout.rows) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(layout.spacing, Alignment.CenterHorizontally),
                ) {
                    repeat(rowCount) {
                        val displayedIndex = position++
                        val sourceIndex = order[displayedIndex]
                        val char = word[sourceIndex]
                        val letter = char.toString().uppercase(Locale.ROOT)
                        val occupied = used[displayedIndex]
                        val tileDescription = stringResource(
                            if (occupied) R.string.game_tile_used else R.string.game_tile_add, letter)
                        Box(
                            Modifier.minimumInteractiveComponentSize().size(layout.cell)
                                .alpha(if (occupied) 0.4f else 1f)
                                .background(LocalWordsPalette.current.background)
                                .border(2.dp, LocalWordsPalette.current.tileBorder)
                                .testTag("source_word_tile_${sourceIndex + 1}")
                                .semantics { contentDescription = tileDescription }
                                .clickable(enabled = enabled && !occupied, role = Role.Button) { onTileTap(char) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(letter, modifier = Modifier.fillMaxWidth(), color = LocalWordsPalette.current.text,
                                fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
