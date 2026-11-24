package com.example.sostavslova.ui.game

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.sostavslova.R
import com.example.sostavslova.data.InputMode
import com.example.sostavslova.ui.components.WordInputRow
import com.example.sostavslova.ui.theme.LocalWordsPalette
import com.example.sostavslova.ui.theme.WordsControlShape

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GameInput(
    state: GameUiState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleInputMode: () -> Unit,
    onBackspace: () -> Unit,
    onClearInput: () -> Unit,
    onShuffle: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardMode = state.inputMode == InputMode.KEYBOARD
    var inputLaidOut by remember(keyboardMode) { mutableStateOf(false) }
    val entered = stringResource(R.string.game_input_entered, state.input)
    LaunchedEffect(keyboardMode, inputLaidOut) {
        if (keyboardMode) {
            // WordInputRow subcomposes the field during layout; its focus target
            // must be attached before requesting focus, including after recreation.
            if (inputLaidOut) {
                focus.requestFocus()
                keyboard?.show()
            }
        } else {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    val toggleLabel = stringResource(if (keyboardMode) R.string.game_touch else R.string.game_keyboard)
    val shuffleLabel = stringResource(R.string.game_shuffle)
    val backspaceLabel = stringResource(R.string.game_backspace)
    val toggleDescription = stringResource(if (keyboardMode) R.string.game_touch_description else R.string.game_keyboard_description)
    val eraseDescription = stringResource(R.string.game_backspace_description)
    val clearDescription = stringResource(R.string.game_clear_description)
    val shuffleDescription = stringResource(R.string.game_shuffle_description)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val actionButtonPadding = 4.dp
    val actionGap = 2.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (keyboardMode) {
            WordInputRow(stringResource(R.string.your_answer), state.input, onInputChanged,
                stringResource(R.string.check_answer), {
                    onSubmit()
                    focus.requestFocus()
                    keyboard?.show()
                }, enabled = state.canPlay,
                inputEnabled = !state.loading && !state.returnLevels && !state.navigatingNext,
                maxLength = null, focusRequester = focus,
                inputModifier = Modifier.testTag("game_input").onGloballyPositioned { inputLaidOut = true }.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = entered
                }, submitModifier = Modifier.testTag("game_submit"), forceStacked = true)
        } else {
            Text(stringResource(R.string.your_answer), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .border(1.dp, LocalWordsPalette.current.defaultButtonBorder, WordsControlShape)
                .testTag("game_input_display").semantics(mergeDescendants = true) {
                    contentDescription = entered
                    liveRegion = LiveRegionMode.Polite
                }.padding(12.dp), contentAlignment = Alignment.CenterStart) {
                Text(state.input, style = MaterialTheme.typography.bodyLarge)
            }
            Button(onSubmit, enabled = state.canPlay,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("game_submit")) {
                Text(stringResource(R.string.check_answer), style = MaterialTheme.typography.bodyLarge)
            }
        }
        val toggle: @Composable () -> Unit = {
            TextButton(onToggleInputMode, enabled = state.canPlay && !state.inputModeSaving,
                modifier = Modifier.testTag("game_input_mode_toggle").heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .semantics { contentDescription = toggleDescription },
                contentPadding = PaddingValues(horizontal = actionButtonPadding)) {
                Text(toggleLabel)
            }
        }
        val shuffle: @Composable () -> Unit = {
            TextButton(onShuffle, enabled = state.canPlay,
                modifier = Modifier.testTag("game_shuffle").heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .semantics { contentDescription = shuffleDescription },
                contentPadding = PaddingValues(horizontal = actionButtonPadding)) {
                Text(shuffleLabel)
            }
        }
        val backspace: @Composable () -> Unit = {
            Box(Modifier.minimumInteractiveComponentSize().widthIn(min = 48.dp).heightIn(min = 48.dp)
                .border(1.dp, LocalWordsPalette.current.defaultButtonBorder, WordsControlShape)
                .testTag("game_backspace").semantics { contentDescription = eraseDescription }
                .combinedClickable(enabled = state.canPlay && state.input.isNotEmpty(), role = Role.Button,
                    onClick = onBackspace, onLongClickLabel = clearDescription, onLongClick = onClearInput)
                .padding(horizontal = actionButtonPadding, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(backspaceLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val paddingPx = with(density) { actionButtonPadding.toPx() * 2 }
            val gapPx = with(density) { actionGap.toPx() }
            val minButtonPx = with(density) { 58.dp.toPx() }
            val minBackspacePx = with(density) { 48.dp.toPx() }
            fun actionWidth(label: String, style: androidx.compose.ui.text.TextStyle, minimum: Float): Float {
                val textWidth = textMeasurer.measure(label, style).size.width + paddingPx
                return maxOf(textWidth, minimum)
            }
            val toggleWidth = actionWidth(toggleLabel, MaterialTheme.typography.labelLarge, minButtonPx)
            val shuffleWidth = actionWidth(shuffleLabel, MaterialTheme.typography.labelLarge, minButtonPx)
            val backspaceWidth = actionWidth(backspaceLabel, MaterialTheme.typography.bodyLarge, minBackspacePx)
            val fitsOneRow = toggleWidth + shuffleWidth + backspaceWidth + gapPx * 2 <= constraints.maxWidth
            if (fitsOneRow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    toggle()
                    shuffle()
                    backspace()
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        toggle()
                        shuffle()
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        backspace()
                    }
                }
            }
        }
    }
}
