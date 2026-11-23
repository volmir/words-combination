package com.example.sostavslova.ui.levels

import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.data.*
import com.example.sostavslova.core.AchievementRules
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.LevelAchievement
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
    private val words = WordRepository({ "карта\nкрот\nкот\nторт\nрот" })
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        directory = File(context.cacheDir, "levels-test-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "progress.json") }
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
    private suspend fun model(saved: ProgressRepository = progress, source: LevelsRepository,
        dictionary: WordRepository = words) = create { LevelsViewModel(source, saved, dictionary) }
    private suspend fun LevelsViewModel.await(predicate: (LevelsUiState) -> Boolean): LevelsUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { uiState.first(predicate) } }
    private suspend fun LevelsViewModel.ready() = await { !it.loading && !it.loadError }

    @Test fun freshMapShowsFirstOnlyAndSelectionDoesNotWriteGame() = runTest {
        val source = LevelsRepository(readCampaign = { miniCampaignJson })
        val model = model(source = source)
        val initial = model.ready()
        assertEquals(listOf(1), initial.levels.filter { it.unlocked }.map { it.index })
        val before = store.data.first()
        withContext(Dispatchers.Main) { model.onSelectLevel("трактор") }
        assertNull(model.uiState.value.openLevel)
        assertEquals(1 to 2, model.uiState.value.lockedRequirement)
        withContext(Dispatchers.Main) { model.onSelectLevel("картотека") }
        assertEquals(1, model.uiState.value.openLevel)
        assertEquals(before, store.data.first())
        assertNull(store.data.first()[com.example.sostavslova.data.jsonstore.stringPreferencesKey("currentGame")])
    }

    @Test fun v2UsesCampaignSnapshotAndNeverReadsV1Levels() = runTest {
        val campaignLevels = LevelsRepository(readCampaign = { com.example.sostavslova.data.miniCampaignJson })
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
    @Test fun achievementAvailabilityComesFromCoreAndEarnedUpdatesWithSavedAnswers() = runTest {
        val source = LevelsRepository(readCampaign = { miniCampaignJson })
        val campaign = requireNotNull(source.getCampaign())
        val model = model(source = source)
        val initial = model.ready()
        for ((level, item) in campaign.levels.zip(initial.levels)) {
            val expected = AchievementRules.available(GameState.start(level.word, words.requireIndex()),
                level.requiredStars, level.maxStars)
            assertEquals(expected, item.achievementsAvailable)
            assertTrue(item.achievements.isEmpty())
            assertFalse(LevelAchievement.LONG_WORD in item.achievementsAvailable)
        }
        progress.submitCampaignAnswer(campaign, 1, "карта", words.requireIndex())
        val updated = model.await { LevelAchievement.NO_HINTS in it.levels.first().achievements }
        assertEquals(initial.levels.first().achievementsAvailable, updated.levels.first().achievementsAvailable)
        assertTrue(LevelAchievement.STRAIGHT_A in updated.levels.first().achievementsAvailable)
        assertFalse(LevelAchievement.STRAIGHT_A in updated.levels.first().achievements)
    }

    @Test fun miniV2ContinueBackfillFinaleAndFullCollection() = runTest {
        val source = LevelsRepository(readCampaign = { miniCampaignJson })
        val dictionary = WordRepository({ "карта\nкрот\nкот\nторт\nрот\nрека" })
        val campaign = requireNotNull(source.getCampaign())
        val index = dictionary.requireIndex()
        val model = model(source = source, dictionary = dictionary)
        val initial = model.ready()
        assertEquals(7, initial.summary?.maxStars)
        assertEquals(2, initial.summary?.totalLevels)
        assertEquals(1, initial.recommendedLevel)
        val beforeLockedClick = store.data.first()
        withContext(Dispatchers.Main) { model.onSelectLevel("трактор") }
        assertEquals(beforeLockedClick, store.data.first())
        assertNull(model.uiState.value.openLevel)
        assertEquals(1 to 2, model.uiState.value.lockedRequirement)
        assertFalse(progress.observeCampaignProgress().first().finaleReached)
        progress.submitCampaignAnswer(campaign, 1, "карта", index)
        val passed = model.await { it.recommendedLevel == 2 }
        assertEquals(2, passed.levels[0].stars)
        assertEquals(1, passed.summary?.passedLevels)
        // An optional star before the finale must not send Continue backwards.
        progress.submitCampaignAnswer(campaign, 1, "кот", index)
        val beforeFinale = model.await { it.levels[0].stars == 3 }
        assertEquals(2, beforeFinale.recommendedLevel)
        withContext(Dispatchers.Main) { model.onSelectLevel("картотека"); model.onGameOpened() }
        progress.submitCampaignAnswer(campaign, 2, "крот", index)
        val finale = model.await { it.finaleReached }
        assertEquals(1, finale.recommendedLevel)
        assertTrue(finale.levels.last().crowned)
        assertFalse(requireNotNull(finale.summary).isMaster)
        progress.submitCampaignAnswer(campaign, 1, "крот", index)
        progress.submitCampaignAnswer(campaign, 1, "река", index)
        val fullFirst = model.await { it.levels[0].stars == 5 }
        assertEquals(2, fullFirst.recommendedLevel)
        assertTrue(fullFirst.levels[0].unlocked)
        withContext(Dispatchers.Main) { model.onSelectLevel("картотека") }
        assertEquals(1, model.uiState.value.openLevel)
        withContext(Dispatchers.Main) { model.onGameOpened() }
        progress.submitCampaignAnswer(campaign, 2, "торт", index)
        val master = model.await { it.summary?.isMaster == true }
        assertEquals(7, master.summary?.stars)
        assertTrue(master.levels.all { it.unlocked })
    }

}
