package com.example.sostavslova.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Все сценарии редактируемого ввода явно выбирают клавиатуру через настоящий UI. */
fun ComposeTestRule.gameKeyboardInput(): SemanticsNodeInteraction {
    waitUntil(10_000) {
        onAllNodesWithTag("game_input_display").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithTag("game_input").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithTag("game_input_display").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("game_input_mode_toggle").performScrollTo().performClick()
    }
    waitUntil(10_000) { onAllNodesWithTag("game_input").fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithTag("game_input")
}
