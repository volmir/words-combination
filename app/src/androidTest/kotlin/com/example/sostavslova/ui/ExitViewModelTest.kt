package com.example.sostavslova.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExitViewModelTest {
    private val models = mutableListOf<ExitViewModel>()
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun TestScope.model(handle: SavedStateHandle = SavedStateHandle(),
        save: suspend () -> Unit = {}): ExitViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ExitViewModel(handle, save, { testScheduler.currentTime }).also { models.add(it) }
    }

    @Test fun secondBackWithinTwoSecondsWaitsForSave() = runTest {
        val write = CompletableDeferred<Unit>()
        val vm = model { write.await() }
        vm.backOnMap()
        advanceTimeBy(1_999)
        vm.backOnMap(); runCurrent()
        assertTrue(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.finish)
        write.complete(Unit); runCurrent()
        assertTrue(vm.uiState.value.finish)
    }

    @Test fun exactDeadlineAndExpiredWindowRequireAnotherPress() = runTest {
        val vm = model()
        vm.backOnMap(); advanceTimeBy(2_000); vm.backOnMap(); runCurrent()
        assertFalse(vm.uiState.value.finish)
        advanceTimeBy(2_001); vm.backOnMap(); runCurrent()
        assertFalse(vm.uiState.value.finish)
        vm.resetBack(); vm.backOnMap(); runCurrent()
        assertFalse(vm.uiState.value.finish)
    }

    @Test fun busyDisablesRepeatedConfirmationAndCancellationPreventsFinish() = runTest {
        var calls = 0
        val write = CompletableDeferred<Unit>()
        val vm = model { calls++; withContext(NonCancellable) { write.await() } }
        vm.open(); runCurrent()
        vm.confirm(); vm.confirm(); runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.busy)
        vm.cancel(); write.complete(Unit); runCurrent()
        assertFalse(vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.finish)
    }

    @Test fun failureAllowsRetryAndNeverPromisesSavedProgress() = runTest {
        var fails = true
        val vm = model { if (fails) throw IOException("disk") }
        vm.open(); runCurrent()
        assertTrue(vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.busy)
        vm.confirm(); runCurrent()
        assertFalse(vm.uiState.value.finish)
        fails = false
        vm.confirm(); runCurrent()
        assertTrue(vm.uiState.value.saved)
        assertTrue(vm.uiState.value.finish)
    }

    @Test fun cancellingConfirmedExitSuppressesLateNonCancellableCompletion() = runTest {
        val write = CompletableDeferred<Unit>()
        var calls = 0
        val vm = model {
            calls++
            if (calls > 1) withContext(NonCancellable) { write.await() }
        }
        vm.open(); runCurrent()
        assertTrue(vm.uiState.value.saved)
        vm.confirm(); runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.cancel(); runCurrent()
        write.complete(Unit); runCurrent()
        assertFalse(vm.uiState.value.finish)
        assertFalse(vm.uiState.value.dialog)
    }

    @Test fun confirmationChecksStorageAgainAfterSavedMessage() = runTest {
        var fail = false
        val vm = model { if (fail) throw IOException("disk") }
        vm.open(); runCurrent()
        assertTrue(vm.uiState.value.saved)
        fail = true
        vm.confirm(); runCurrent()
        assertFalse(vm.uiState.value.finish)
        assertFalse(vm.uiState.value.saved)
        assertTrue(vm.uiState.value.error)
        fail = false
        vm.confirm(); runCurrent()
        assertTrue(vm.uiState.value.finish)
    }

    @Test fun restoredDialogChecksStorageAgainAndDoesNotRestoreBackWindow() = runTest {
        val write = CompletableDeferred<Unit>()
        val vm = model(SavedStateHandle(mapOf("exit.dialog" to true))) { write.await() }
        runCurrent()
        assertTrue(vm.uiState.value.dialog)
        assertTrue(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.saved)
        assertNull(vm.uiState.value.backDeadline)
        write.complete(Unit); runCurrent()
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.finish)
    }
}
