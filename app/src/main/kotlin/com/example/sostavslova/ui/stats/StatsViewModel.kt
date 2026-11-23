package com.example.sostavslova.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignStatistics
import com.example.sostavslova.core.CampaignStatisticsAssembler
import com.example.sostavslova.core.CampaignStatisticsBasis
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatsUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val statistics: CampaignStatistics? = null,
    val resetConfirmVisible: Boolean = false,
    val resetting: Boolean = false,
    val resetError: Boolean = false,
    val resetCompleted: Boolean = false,
)

internal fun statsUiState(
    campaign: Campaign,
    basis: CampaignStatisticsBasis,
    progress: CampaignProgress,
    stageNames: Map<Int, String>,
): StatsUiState {
    val matchingProgress = progress.takeIf { it.campaignId == CampaignIdentity.of(campaign) } ?: CampaignProgress()
    return StatsUiState(loading = false, statistics = CampaignStatisticsAssembler.assemble(
        campaign, matchingProgress, basis, stageNames,
    ))
}

class StatsViewModel(
    private val levels: LevelsRepository,
    private val progress: ProgressRepository,
    private val words: WordRepository,
    private val stageNames: Map<Int, String>,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StatsUiState())
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val campaign = levels.getCampaign()
                if (campaign == null) {
                    mutableState.update { it.copy(loading = false) }
                    return@launch
                }
                // Поиск по словарю выполняется один раз при открытии экрана, не на старте приложения.
                val basis = withContext(computationDispatcher) {
                    CampaignStatisticsAssembler.prepare(campaign, words.requireIndex())
                }
                progress.observeCampaignProgress()
                    .map { saved -> statsUiState(campaign, basis, saved, stageNames) }
                    .flowOn(computationDispatcher).collect { next ->
                        mutableState.update { current ->
                            next.copy(
                                resetConfirmVisible = current.resetConfirmVisible,
                                resetting = current.resetting,
                                resetError = current.resetError,
                                resetCompleted = current.resetCompleted,
                            )
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun onRequestReset() {
        mutableState.update { current ->
            if (current.loading || current.error || current.statistics == null ||
                current.resetting || current.resetCompleted) current
            else current.copy(resetConfirmVisible = true, resetError = false)
        }
    }

    fun onDismissReset() {
        mutableState.update { current ->
            if (current.resetting) current
            else current.copy(resetConfirmVisible = false, resetError = false)
        }
    }

    fun onConfirmReset() {
        val current = mutableState.value
        if (!current.resetConfirmVisible || current.resetting || current.resetCompleted) return
        mutableState.update { it.copy(resetting = true, resetError = false) }
        viewModelScope.launch {
            try {
                progress.awaitSaved()
                progress.resetCampaignProgress()
                mutableState.update {
                    it.copy(
                        resetConfirmVisible = false,
                        resetting = false,
                        resetError = false,
                        resetCompleted = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(resetting = false, resetError = true) }
            }
        }
    }
}
