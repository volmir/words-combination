package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressRepositoryTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: ProgressRepository
    private val level = Level(1, "торт", answers = 2, words = 3, letter = 'т', letterCount = 2)
    private val game = GameState("торт", listOf("рот", "тор", "тот"), listOf("рот"))

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "progress-test-${UUID.randomUUID()}").apply { mkdirs() }
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "progress.preferences_pb")
        }
        repository = ProgressRepository(store)
    }

    @After
    fun tearDown() = runTest {
        scope.cancel()
        scope.coroutineContext.job.join()
        directory.deleteRecursively()
    }

    @Test
    fun emptyStoreHasNoCurrentGameOrLevelProgress() = runTest {
        assertNull(repository.observeCurrentGame().first())
        assertEquals(emptyMap<String, LevelProgress>(), repository.observeLevelProgress().first())
        assertEquals(emptyList<String>(), repository.levelAnswers("торт"))
    }

    @Test
    fun currentGameAndLevelAnswersSurviveReopeningDataStore() = runTest {
        repository.saveGame(game)
        repository.saveLevelResult(level, listOf("тор", "тот"))
        scope.cancel()
        scope.coroutineContext.job.join()
        openStore()
        assertEquals(CurrentGame(game.word, game.found), repository.observeCurrentGame().first())
        assertEquals(listOf("тор", "тот"), repository.levelAnswers(" ТОРТ "))
        assertEquals(2, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun starsNeverDecreaseAndAnswersAccumulateWithoutDuplicates() = runTest {
        repository.saveLevelResult(level, listOf("тор", "тот"))
        val earned = repository.observeLevelProgress().first().getValue("торт")
        repository.saveLevelResult(level, emptyList())
        assertEquals(earned, repository.observeLevelProgress().first().getValue("торт"))
        repository.saveLevelResult(level, listOf("тор", "рот"))
        assertEquals(listOf("тор", "тот", "рот"), repository.levelAnswers("торт"))
        assertEquals(3, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun clearingCurrentGameLeavesBothLevelJsonValuesUntouched() = runTest {
        repository.saveGame(game)
        repository.saveLevelResult(level, listOf("тор", "тот"))
        val before = store.data.first()
        repository.clearCurrentGame()
        val after = store.data.first()
        assertNull(repository.observeCurrentGame().first())
        for (key in listOf("levelAnswers", "levelProgress")) {
            assertEquals(before[stringPreferencesKey(key)], after[stringPreferencesKey(key)])
        }
        assertEquals(listOf("тор", "тот"), repository.levelAnswers("торт"))
        assertEquals(2, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun parallelSavesMergeAnswersAndStarsAtomically() = runTest {
        coroutineScope {
            repeat(12) { i ->
                launch { repository.saveLevelResult(level, listOf(listOf("рот", "тор", "тот")[i % 3])) }
            }
        }
        assertEquals(setOf("рот", "тор", "тот"), repository.levelAnswers("торт").toSet())
        assertEquals(3, repository.observeLevelProgress().first().getValue("торт").stars)
    }

    @Test
    fun malformedCurrentGameJsonDoesNotEraseLevelProgress() = runTest {
        repository.saveLevelResult(level, listOf("тор", "тот"))
        store.edit { it[stringPreferencesKey("currentGame")] = "broken JSON" }
        assertNull(repository.observeCurrentGame().first())
        assertEquals(2, repository.observeLevelProgress().first().getValue("торт").stars)
        assertEquals(listOf("тор", "тот"), repository.levelAnswers("торт"))
    }
}
