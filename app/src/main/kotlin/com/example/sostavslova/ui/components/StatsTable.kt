package com.example.sostavslova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.core.Statistics
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun StatsTable(statistics: Statistics, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        StatsRow(
            stringResource(R.string.stats_letters), stringResource(R.string.stats_found_total),
            stringResource(R.string.stats_percent), WordsColors.tableHead, bold = true,
        )
        for (row in statistics.rows) {
            StatsRow(
                stringResource(R.string.stats_number, row.length),
                stringResource(R.string.stats_pair, row.found, row.total),
                stringResource(R.string.stats_number, row.percent),
            )
        }
        StatsRow(
            stringResource(R.string.stats_total),
            stringResource(R.string.stats_pair, statistics.totalFound, statistics.totalWords),
            stringResource(R.string.stats_number, statistics.totalPercent),
            WordsColors.tableFoot, bold = true,
        )
    }
}

@Composable
private fun StatsRow(first: String, second: String, third: String, background: Color = WordsColors.background, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 8.dp)) {
        val style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(first, Modifier.weight(0.38f).padding(horizontal = 6.dp), style = style, color = WordsColors.text)
        Text(second, Modifier.weight(0.35f).padding(horizontal = 6.dp), style = style, color = WordsColors.text, textAlign = TextAlign.Center)
        Text(third, Modifier.weight(0.27f).padding(horizontal = 6.dp), style = style, color = WordsColors.text, textAlign = TextAlign.End)
    }
}
