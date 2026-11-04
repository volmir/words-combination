package com.example.sostavslova.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.ui.theme.WordsColors

@Composable
fun WordInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    buttonText: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
    inputEnabled: Boolean = enabled,
    onLengthExceeded: ((String) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val submit = { if (enabled) onSubmit() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
            val field: @Composable (Modifier) -> Unit = { fieldModifier ->
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        if (it.length > Alphabet.MAX_WORD_LENGTH && onLengthExceeded != null) {
                            onLengthExceeded(it)
                        } else {
                            onValueChange(it.take(Alphabet.MAX_WORD_LENGTH))
                        }
                    },
                    modifier = fieldModifier.then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
                        .semantics { contentDescription = label },
                    enabled = inputEnabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WordsColors.inputFocusBorder,
                        unfocusedBorderColor = WordsColors.defaultButtonBorder,
                        focusedContainerColor = WordsColors.background,
                        unfocusedContainerColor = WordsColors.background,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = imeAction,
                        hintLocales = LocaleList("ru"),
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }, onGo = { submit() }, onSearch = { submit() },
                        onSend = { submit() }, onNext = { submit() },
                    ),
                )
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(Modifier.fillMaxWidth())
                    InputButton(buttonText, enabled, submit, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    field(Modifier.weight(1f))
                    InputButton(buttonText, enabled, submit)
                }
            }
        }
    }
}

@Composable
private fun InputButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(4.dp),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) WordsColors.primaryPressed else WordsColors.primary,
            contentColor = WordsColors.background,
        ),
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}
