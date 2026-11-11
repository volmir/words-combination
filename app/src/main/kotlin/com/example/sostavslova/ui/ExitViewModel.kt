package com.example.sostavslova.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExitUiState(
    val dialog: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
    val finish: Boolean = false,
    val backDeadline: Long? = null,
)

class ExitViewModel(
    private val handle: SavedStateHandle,
    private val awaitSaved: suspend () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val state = MutableStateFlow(ExitUiState(dialog = handle["exit.dialog"] ?: false))
    val uiState = state.asStateFlow()
    private var request: Job? = null
    private var generation = 0

    init { if (state.value.dialog) save(exit = false) }

    fun open() {
        if (state.value.dialog || state.value.busy) return
        handle["exit.dialog"] = true
        state.value = ExitUiState(dialog = true)
        save(exit = false)
    }

    fun cancel() {
        generation++
        request?.cancel()
        handle["exit.dialog"] = false
        state.value = ExitUiState()
    }

    fun resetBack() { state.value = state.value.copy(backDeadline = null) }

    fun backOnMap() {
        if (state.value.busy) return
        val deadline = state.value.backDeadline
        if (deadline != null && now() < deadline) {
            handle["exit.dialog"] = true
            state.value = state.value.copy(dialog = true, backDeadline = null)
            save(exit = true)
        } else state.value = state.value.copy(backDeadline = now() + 2_000)
    }

    fun confirm() { if (!state.value.busy) save(exit = true) }

    private fun save(exit: Boolean) {
        val token = ++generation
        state.value = state.value.copy(busy = true, saved = false, error = false, finish = false)
        request = viewModelScope.launch {
            try {
                awaitSaved()
                if (token == generation) state.value = state.value.copy(busy = false, saved = true, finish = exit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) {
                    handle["exit.dialog"] = true
                    state.value = state.value.copy(dialog = true, busy = false, saved = false, error = true)
                }
            }
        }
    }
}
