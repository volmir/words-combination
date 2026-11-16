package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun FoundWords(words: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (word in words) {
            Text(
                word,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .wrapContentHeight().padding(horizontal = 4.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = WordsColors.primary,
            )
        }
    }
}
