package com.example.sostavslova.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Карта и приветствие публикуются одним состоянием, поэтому после появления
 * карточки диалог уже можно закрыть до основных действий теста.
 */
internal fun ComposeContentTestRule.dismissWelcomeIfShown() {
    waitForIdle()
    if (onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("levels_welcome_confirm").performClick()
        waitUntil(10_000) { onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isEmpty() }
    }
}
