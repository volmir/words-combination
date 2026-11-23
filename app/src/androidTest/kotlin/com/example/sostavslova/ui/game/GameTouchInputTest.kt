package com.example.sostavslova.ui.game

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.data.*
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.ui.gameKeyboardInput
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameTouchInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchTypingEraseLongPressShuffleAndKeyboardSwitchUseRealViewModel() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "touch-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            val progress = ProgressRepository(PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "progress.json")
            })
            val levels = LevelsRepository(readCampaign = { miniCampaignJson })
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(owner, viewModelFactory { initializer {
                    GameViewModel(SavedStateHandle(mapOf("index" to "1")),
                        WordRepository({ "карта\nкрот\nкот\nрот\nторт" }), levels, progress,
                        RiddlesRepository { "Синтетическая загадка" }, shuffleSeed = { 19L })
                } })[GameViewModel::class.java]
            }
            withContext(Dispatchers.Default) { withTimeout(10_000) { model.uiState.first { it.canPlay } } }
            withContext(Dispatchers.Main) { model.onDismissExplanations() }
            lateinit var view: android.view.View
            compose.setContent {
                val state by model.uiState.collectAsState()
                val currentView = LocalView.current
                SideEffect { view = currentView }
                WordsTheme {
                    GameScreen(state, model::onInputChanged, model::onSubmit, {}, {},
                        onTileTap = model::onTileTap, onBackspace = model::onBackspace,
                        onClearInput = model::onClearInput, onShuffle = model::onShuffle,
                        onToggleInputMode = model::onToggleInputMode)
                }
            }
            compose.onNodeWithTag("game_input_display").assertExists()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            }
            fun tap(index: Int) = compose.onNodeWithTag("source_word_tile_$index").performScrollTo().performClick()
            tap(1); tap(5); tap(4) // к + о + т
            compose.onNodeWithTag("game_input_display").assertTextEquals("кот")
                .assertContentDescriptionEquals("Введено: кот")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("source_word_tile_1").assertIsNotEnabled()
                .assertContentDescriptionEquals("Буква К, уже использована")
            compose.onNodeWithTag("game_shuffle").performScrollTo().performClick()
            compose.onNodeWithTag("game_input_display").assertTextEquals("кот")
            assertNotEquals(com.example.sostavslova.core.LetterOrder.natural("картотека"), model.uiState.value.tileOrder)
            compose.onNodeWithTag("game_backspace").performScrollTo().performClick()
            compose.onNodeWithTag("game_input_display").assertTextEquals("ко")
            compose.onNodeWithTag("game_backspace").performTouchInput { longClick() }
            compose.onNodeWithTag("game_input_display").assertTextEquals("")
            // Снова набрать и отправить после перемешивания: индексы плиток устойчивы.
            tap(1); tap(5); tap(4)
            compose.onNodeWithTag("game_submit").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.uiState.value.busy && model.uiState.value.game?.found == listOf("кот") }
            compose.onNodeWithTag("game_input_display").assertTextEquals("")
            compose.onNodeWithTag("game_input").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            }
            compose.gameKeyboardInput().assertIsFocused().performTextReplacement("ко")
            compose.waitUntil(10_000) {
                var shown = false
                compose.runOnIdle { shown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                shown
            }
            // Плитки также активны в KEYBOARD.
            tap(4)
            compose.gameKeyboardInput().assertTextEquals("кот")
            compose.onNodeWithTag("game_input_mode_toggle").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.uiState.value.inputMode == InputMode.TOUCH }
            compose.onNodeWithTag("game_input_display").assertTextEquals("кот")
            compose.waitUntil(10_000) {
                var hidden = false
                compose.runOnIdle { hidden = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
                hidden
            }
        } finally {
            withContext(Dispatchers.Main) { owner.viewModelStore.clear() }
            progressBarrier(scope)
            directory.deleteRecursively()
        }
    }

    private suspend fun progressBarrier(scope: CoroutineScope) {
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
