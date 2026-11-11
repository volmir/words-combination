package com.example.sostavslova.ui

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.sostavslova.MainActivity
import com.example.sostavslova.WordsApplication
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExitNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun drawerBackClosesDrawerAndDialogSurvivesRecreationWithCancel() {
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithTag("drawer_exit").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithTag("drawer_exit").assertIsNotDisplayed()
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithTag("drawer_exit").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("exit_dialog_confirm").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Прогресс сохранён.").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("exit_dialog_cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("exit_dialog_confirm").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun recreationWhileSavingKeepsConfirmDisabledAndCancelPreventsExit() {
        val pending = Job()
        (compose.activity.application as WordsApplication).container.progressRepository.trackOperation(pending)
        try {
            compose.onNodeWithTag("app_menu").performClick()
            compose.onNodeWithTag("drawer_exit").performScrollTo().performClick()
            compose.onNodeWithTag("exit_dialog_confirm").assertIsNotEnabled()
            compose.onNodeWithText("Прогресс сохранён.").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("exit_dialog_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("exit_dialog_cancel").performClick()
            pending.complete()
            compose.waitForIdle()
            compose.onNodeWithTag("exit_dialog_confirm").assertDoesNotExist()
            assertFalse(compose.activity.isFinishing)
        } finally { pending.complete() }
    }

    @Test fun systemBackDismissesDialogAndActivityIsFoundThroughWrappers() {
        assertSame(compose.activity, ContextWrapper(ContextWrapper(compose.activity)).findActivity())
        assertNull(compose.activity.applicationContext.findActivity())
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithTag("drawer_exit").performScrollTo().performClick()
        back()
        compose.onNodeWithTag("exit_dialog_confirm").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)
    }
}
