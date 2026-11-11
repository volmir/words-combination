package com.example.sostavslova.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.sostavslova.ui.theme.WordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExitDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyDisablesConfirmationButAllowsCancelAndDoesNotPromiseSaving() {
        var confirms = 0
        var cancels = 0
        compose.setContent {
            WordsTheme { ExitDialog(ExitUiState(dialog = true, busy = true), { confirms++ }, { cancels++ }) }
        }
        compose.onNodeWithTag("exit_dialog_confirm").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Прогресс сохранён.").assertDoesNotExist()
        compose.onNodeWithTag("exit_dialog_cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, confirms); assertEquals(1, cancels) }
    }

    @Test fun onlyCommittedStatePromisesSavingAndErrorKeepsRetryEnabled() {
        val state = mutableStateOf(ExitUiState(dialog = true, saved = true))
        var confirms = 0
        compose.setContent { WordsTheme { ExitDialog(state.value, { confirms++ }, {}) } }
        compose.onNodeWithText("Прогресс сохранён.").assertIsDisplayed()
        compose.runOnIdle { state.value = ExitUiState(dialog = true, error = true) }
        compose.onNodeWithText("Прогресс сохранён.").assertDoesNotExist()
        compose.onNodeWithTag("exit_dialog_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
}
