package com.example.sostavslova.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignStatistics
import com.example.sostavslova.core.CampaignStatisticsAssembler
import com.example.sostavslova.core.CampaignStatisticsBasis
import com.example.sostavslova.core.CampaignTimeSummary
import com.example.sostavslova.data.LevelPlayTime
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.time.PlayTimeTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

/**
 * Собирает один снимок экрана из уже загруженных данных. Историческая пометка FIX-04
 * остаётся здесь: это доказательство сохранения, а не новое правило времени.
 */
internal fun statsUiState(
    campaign: Campaign,
    basis: CampaignStatisticsBasis,
    time: CampaignTimeSummary,
    records: Map<Int, LevelPlayTime>,
    progress: CampaignProgress,
    stageNames: Map<Int, String>,
): StatsUiState {
    val matchingProgress = progress.takeIf { it.campaignId == CampaignIdentity.of(campaign) } ?: CampaignProgress()
    val historical = campaign.levels.filter { level ->
        val record = records[level.number] ?: LevelPlayTime()
        // До учёта времени сохранение не фиксировало каждый заход: используем только известные следы.
        (!record.initialized && (matchingProgress.levels.containsKey(level.word) ||
            matchingProgress.lastVisitedLevel == level.number)) ||
            (record.rAccounted && record.timeToRMs == null) ||
            (record.lastSequence == 0L && record.lastStars > 0)
    }.map { it.number }.toSet()
    val pending = records.filter { it.value.initialized && it.value.lastSequence == 0L }.keys - historical
    return StatsUiState(loading = false, statistics = CampaignStatisticsAssembler.assemble(
        campaign, matchingProgress, basis, time, historical, pending, stageNames,
    ))
}

class StatsViewModel(
    private val levels: LevelsRepository,
    private val time: PlayTimeRepository,
    private val progress: ProgressRepository,
    private val words: WordRepository,
    private val stageNames: Map<Int, String>,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val playTimeTracker: PlayTimeTracker? = null,
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
                combine(time.observe(campaign), progress.observeCampaignProgress()) { records, saved ->
                    statsUiState(campaign, basis, time.statistics(campaign, records), records, saved, stageNames)
                }.flowOn(computationDispatcher).collect { next ->
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
                playTimeTracker?.awaitSaved()
                progress.resetCampaignProgress()
                time.clearTimeStatistics()
                playTimeTracker?.resetAll()
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
