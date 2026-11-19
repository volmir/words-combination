package com.example.sostavslova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsCardShape
import com.example.sostavslova.ui.theme.LocalWordsPalette

@Composable
fun Well(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LocalWordsPalette.current.wellBackground,
        contentColor = LocalWordsPalette.current.text,
        shape = WordsCardShape,
        border = BorderStroke(1.dp, LocalWordsPalette.current.wellBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
