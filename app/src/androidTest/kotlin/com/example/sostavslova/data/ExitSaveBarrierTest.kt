package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.sostavslova.core.Level
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExitSaveBarrierTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            gate?.await()
            if (fail) throw IOException("disk")
            return transform(data.value).also { data.value = it }
        }
    }
    private val level = Level(1, "торт", 2, 3, 'т', 2)

    @Test fun barrierWaitsForPreparationAndForAcceptedWrite() = runTest {
        val store = Store()
        val repository = ProgressRepository(store, StandardTestDispatcher(testScheduler))
        val preparation = CompletableDeferred<Unit>()
        val write = launch { preparation.await(); repository.saveLevelResult(level, listOf("тор")) }
        repository.trackOperation(write)
        var saved = false
        launch { repository.awaitSaved(); saved = true }
        runCurrent(); assertFalse(saved)
        store.gate = CompletableDeferred()
        preparation.complete(Unit); runCurrent(); assertFalse(saved)
        store.gate?.complete(Unit); runCurrent()
        assertTrue(saved)
        assertEquals(listOf("тор"), repository.levelAnswers("торт"))
    }

    @Test fun failedTransactionCanBeRetriedWithoutLosingAnswer() = runTest {
        val store = Store()
        val repository = ProgressRepository(store, StandardTestDispatcher(testScheduler))
        store.fail = true
        assertTrue(runCatching { repository.saveLevelResult(level, listOf("тор")) }.isFailure)
        assertTrue(runCatching { repository.awaitSaved() }.isFailure)
        store.fail = false
        repository.awaitSaved()
        repository.awaitSaved()
        assertEquals(listOf("тор"), repository.levelAnswers("торт"))
    }

    @Test fun cancellingExitWaitDoesNotCancelWrite() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val repository = ProgressRepository(store, StandardTestDispatcher(testScheduler))
        val write = launch { repository.saveLevelResult(level, listOf("тор")) }
        repository.trackOperation(write)
        val exit = launch { repository.awaitSaved() }
        runCurrent()
        exit.cancelAndJoin()
        assertTrue(write.isActive)
        store.gate?.complete(Unit); runCurrent()
        repository.awaitSaved()
        assertEquals(listOf("тор"), repository.levelAnswers("торт"))
    }
}
