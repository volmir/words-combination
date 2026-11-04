package com.example.sostavslova.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sostavslova.ui.theme.WordsColors
import java.util.Locale

@Composable
fun LetterTiles(word: String, modifier: Modifier = Modifier) {
    val baseSize = if (word.length > 20) 32.dp else 36.dp
    // В обычном масштабе размеры точные; крупный шрифт получает место для буквы и рамки.
    val tileSize = maxOf(baseSize, with(LocalDensity.current) { 18.sp.toDp() } + 12.dp)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (letter in word.uppercase(Locale.ROOT)) {
            Box(Modifier.size(tileSize).border(4.dp, WordsColors.tileBorder), contentAlignment = Alignment.Center) {
                Text(letter.toString(), color = WordsColors.text, fontSize = 18.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
