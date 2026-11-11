package com.example.sostavslova.ui.levels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LevelsViewModelTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var progress: ProgressRepository
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private val fixture = """[{"index":1,"word":"торт","answers":2,"words":3,"letter":"т","letterCount":1},
        {"index":2,"word":"портал","answers":2,"words":3,"letter":"п","letterCount":1}]"""
    private val first = Level(1, "торт", 2, 3, 'т', 1)
    private val levels = LevelsRepository({ fixture })
    private val words = WordRepository({ "лот\nпар\nпортал\nрот\nтор\nторт\nтот" })
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        directory = File(context.cacheDir, "levels-test-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.preferences_pb") }
        progress = ProgressRepository(store)
    }
    @After fun tearDown() = runTest {
        withContext(Dispatchers.Main) { owners.forEach { it.viewModelStore.clear() } }
        scope.cancel(); scope.coroutineContext.job.join(); directory.deleteRecursively()
    }
    private suspend inline fun <reified T : ViewModel> create(crossinline factory: () -> T): T = withContext(Dispatchers.Main) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        owners.add(owner)
        ViewModelProvider(owner, viewModelFactory { initializer { factory() } })[T::class.java]
    }
    private suspend fun model(saved: ProgressRepository = progress, source: LevelsRepository = levels,
        dictionary: WordRepository = words) = create { LevelsViewModel(source, saved, dictionary) }
    private suspend fun LevelsViewModel.await(predicate: (LevelsUiState) -> Boolean): LevelsUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }
    private suspend fun LevelsViewModel.ready() = await { !it.loading && !it.loadError }
    @Test fun freshMapShowsFirstOnlyAndSelectionDoesNotWriteGame() = runTest {
        val model = create { LevelsViewModel(levels, progress, words) }
        val initial = model.await { !it.loading }
        assertEquals(listOf(1), initial.levels.filter { it.unlocked }.map { it.index })
        val before = store.data.first()
        withContext(Dispatchers.Main) { model.onSelectLevel("портал") }
        assertNull(model.uiState.value.openLevel)
        assertEquals(R.string.level_closed, model.uiState.value.message)
        withContext(Dispatchers.Main) { model.onSelectLevel("торт") }
        assertEquals(1, model.uiState.value.openLevel)
        assertEquals(before, store.data.first())
        assertNull(store.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("currentGame")])
    }

    @Test fun v1CompletionUpdatesAccessAndEarnedStars() = runTest {
        val model = create { LevelsViewModel(levels, progress, words) }
        model.await { !it.loading }
        progress.saveLevelResult(first, listOf("рот", "тор"))
        val updated = model.await { it.levels.getOrNull(1)?.unlocked == true }
        assertTrue(updated.levels.first().stars > 0)
        withContext(Dispatchers.Main) { model.onSelectLevel("портал") }
        assertEquals(2, model.uiState.value.openLevel)
    }

    @Test fun v2UsesCampaignSnapshotAndNeverReadsV1Levels() = runTest {
        val campaignLevels = LevelsRepository({ error("v1 must not load") },
            readCampaign = { com.example.sostavslova.data.miniCampaignJson })
        val campaignWords = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
        val model = create { LevelsViewModel(campaignLevels, progress, campaignWords) }
        val initial = model.await { !it.loading }
        assertFalse(initial.loadError)
        assertEquals("картотека", initial.levels.first().word)
        assertEquals(5, initial.levels.first().maxStars)
        assertFalse(initial.levels[1].unlocked)
        progress.submitCampaignAnswer(requireNotNull(campaignLevels.getCampaign()), 1, "карта", campaignWords.requireIndex())
        val updated = model.await { it.levels.getOrNull(1)?.unlocked == true }
        assertEquals(2, updated.levels.first().stars)
    }
}
