package com.example.sostavslova.ui.levels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.InstallEvidence
import com.example.sostavslova.core.WelcomeState
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.data.miniCampaignJson
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Приветствие поверх настоящей карты: один показ, DONE по факту композиции. */
@RunWith(AndroidJUnit4::class)
class WelcomeDialogUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var directory: File
    private var scope: CoroutineScope? = null
    private lateinit var store: DataStore<Preferences>
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val mounted = mutableStateOf(false)
    private val active = mutableStateOf<LevelsViewModel?>(null)
    private val welcomeKey = stringPreferencesKey("welcomeStateV1")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dictionary = WordRepository({ "карта\nкрот\nкот\nторт\nрот\nрека" })
    private val campaign = LevelsRepository({ error("v1 must not load") }, readCampaign = { miniCampaignJson })
    private val broken = LevelsRepository({ error("v1 must not load") },
        readCampaign = { throw IllegalStateException("broken campaign") })
    private val empty = LevelsRepository({ "[]" }, readCampaign = { null })

    @Before fun setUp() {
        directory = File(context.cacheDir, "welcome-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() = runTest {
        runCatching { compose.runOnIdle { mounted.value = false } }
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope?.cancel()
        scope?.coroutineContext?.job?.join()
        directory.deleteRecursively()
    }

    private fun open(evidence: InstallEvidence, hadLocalState: Boolean): ProgressRepository {
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = currentScope
        store = PreferenceDataStoreFactory.create(scope = currentScope) {
            File(directory, "progress.json")
        }
        return ProgressRepository(store, installEvidence = { evidence },
            hadLocalStateAtStartup = hadLocalState)
    }

    private suspend fun createModel(repository: ProgressRepository,
        levels: LevelsRepository = campaign): LevelsViewModel = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory { initializer {
            LevelsViewModel(levels, repository, dictionary)
        } })[LevelsViewModel::class.java]
    }

    private fun show(model: LevelsViewModel) {
        active.value = model
        mounted.value = true
        compose.setContent {
            WordsTheme {
                if (mounted.value) active.value?.let { current ->
                    val state by current.uiState.collectAsStateWithLifecycle()
                    LevelsScreen(state, {}, {},
                        onWelcomeShown = current::onWelcomeShown,
                        onWelcomeDismiss = current::dismissWelcome)
                }
            }
        }
    }

    private suspend fun LevelsViewModel.ready(predicate: (LevelsUiState) -> Boolean = { !it.loading && !it.loadError }) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }

    private fun awaitWelcome() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun freshInstallShowsOneDialogAndMarksDoneBeforeConfirmation() = runTest {
        val model = createModel(open(InstallEvidence.FRESH_INSTALL, hadLocalState = false))
        show(model)
        awaitWelcome()
        compose.onNodeWithTag("levels_welcome").assertIsDisplayed()
        compose.onNodeWithTag("levels_welcome_text").assertTextEquals(context.getString(R.string.welcome_message))
        compose.onNodeWithTag("levels_welcome_confirm").assertTextEquals(context.getString(R.string.welcome_confirm))
        // DONE записывается по факту показа, кнопка ещё не нажата.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { store.data.first { it[welcomeKey] == WelcomeState.DONE.name } }
        }
        assertTrue(model.uiState.value.showWelcome)
        compose.onNodeWithTag("levels_welcome_confirm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun dismissedWelcomeDoesNotReturnAfterRecompositionRotationOrNewMap() = runTest {
        val repository = open(InstallEvidence.FRESH_INSTALL, hadLocalState = false)
        val model = createModel(repository)
        show(model)
        awaitWelcome()
        compose.onNodeWithTag("levels_welcome_confirm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()
        val reopened = createModel(repository)
        compose.runOnIdle { active.value = reopened }
        reopened.ready()
        compose.waitForIdle()
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()
    }

    @Test fun existingInstallWithoutWelcomeKeyDoesNotShowDialog() = runTest {
        val model = createModel(open(InstallEvidence.EXISTING_INSTALL, hadLocalState = true))
        show(model)
        model.ready()
        compose.waitForIdle()
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()
        assertFalse(model.uiState.value.showWelcome)
        assertNull(store.data.first()[welcomeKey])
    }

    @Test fun loadErrorAndEmptyMapDoNotShowDialogOrWriteWelcomeKey() = runTest {
        val failed = createModel(open(InstallEvidence.FRESH_INSTALL, hadLocalState = false), broken)
        show(failed)
        failed.ready { !it.loading && it.loadError }
        compose.waitForIdle()
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()
        assertNull(store.data.first()[welcomeKey])

        val emptyModel = createModel(ProgressRepository(store, installEvidence = { InstallEvidence.FRESH_INSTALL },
            hadLocalStateAtStartup = false), empty)
        compose.runOnIdle { active.value = emptyModel }
        emptyModel.ready { !it.loading && !it.loadError && it.levels.isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("levels_welcome").assertDoesNotExist()
        assertNull(store.data.first()[welcomeKey])
    }

    @Test fun clearingDataOnFreshInstallShowsWelcomeAgain() = runTest {
        val repository = open(InstallEvidence.FRESH_INSTALL, hadLocalState = false)
        val model = createModel(repository)
        show(model)
        awaitWelcome()
        compose.onNodeWithTag("levels_welcome_confirm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("levels_welcome").fetchSemanticsNodes().isEmpty()
        }
        repository.clearAll()
        assertNull(store.data.first()[welcomeKey])
        // Новый запуск после очистки данных: файла состояния ещё нет.
        val relaunched = ProgressRepository(store, installEvidence = { InstallEvidence.FRESH_INSTALL },
            hadLocalStateAtStartup = false)
        val next = createModel(relaunched)
        compose.runOnIdle { active.value = next }
        awaitWelcome()
        compose.onAllNodesWithTag("levels_welcome").assertCountEquals(1)
    }
}
