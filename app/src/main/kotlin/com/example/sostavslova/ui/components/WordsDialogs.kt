package com.example.sostavslova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.R
import com.example.sostavslova.data.Definition
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun DefinitionDialog(
    word: String,
    definitions: List<Definition>,
    onDismiss: () -> Unit,
    definitionsAvailable: Boolean = BuildConfig.HAS_DEFINITIONS,
) {
    WordsDialog(stringResource(R.string.nav_word_meaning), onDismiss) {
        when {
            !definitionsAvailable -> Text(stringResource(R.string.definitions_unavailable), style = MaterialTheme.typography.bodyLarge)
            definitions.isEmpty() -> Text(stringResource(R.string.definition_not_found), style = MaterialTheme.typography.bodyLarge)
            else -> definitions.forEach { DefinitionBlock(it, labelWord = it.display.ifEmpty { word }) }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.close_dialog), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun ConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    WordsDialog(stringResource(R.string.confirm_finish_game), onDismiss) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WordsColors.dangerButton, contentColor = WordsColors.background),
            ) { Text(stringResource(R.string.yes), style = MaterialTheme.typography.bodyLarge) }
            Button(
                onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WordsColors.successButton, contentColor = WordsColors.background),
            ) { Text(stringResource(R.string.no), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsDialog(title: String, onDismiss: () -> Unit, titleText: AnnotatedString? = null, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }.padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }, shape = RoundedCornerShape(4.dp), color = WordsColors.background, contentColor = WordsColors.text) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(titleText ?: AnnotatedString(title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    content()
                }
            }
        }
        }
    }
}
