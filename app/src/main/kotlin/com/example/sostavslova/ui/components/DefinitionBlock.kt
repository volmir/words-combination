package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sostavslova.R
import com.example.sostavslova.data.Definition
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun DefinitionBlock(definition: Definition, modifier: Modifier = Modifier, labelWord: String? = definition.display) {
    val separator = stringResource(R.string.definition_separator)
    val text = buildAnnotatedString {
        if (!labelWord.isNullOrEmpty()) {
            withStyle(SpanStyle(background = WordsColors.labelBackground, color = WordsColors.labelText)) { append(labelWord) }
            append(separator)
        }
        append(definition.text)
    }
    Column(
        modifier.drawBehind {
            drawLine(WordsColors.blockquoteBorder, Offset(2.5.dp.toPx(), 0f), Offset(2.5.dp.toPx(), size.height), 5.dp.toPx())
        }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = WordsColors.text)
        if (definition.notes.isNotEmpty()) {
            Text(definition.notes, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.6.sp), color = WordsColors.text)
        }
        if (definition.antonym.isNotEmpty()) {
            val prefix = stringResource(R.string.definition_antonym_prefix)
            Text(buildAnnotatedString {
                append(prefix)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(definition.antonym) }
            }, style = MaterialTheme.typography.bodyLarge)
        }
        if (definition.example.isNotEmpty()) {
            Text(definition.example, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
        }
        // В подсказке URL содержит ответ, поэтому источник показываем только вместе со словом.
        if (!labelWord.isNullOrEmpty() && definition.source.isNotEmpty()) {
            SelectionContainer {
                Text(
                    stringResource(R.string.definition_source, definition.source),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
