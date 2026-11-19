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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatsUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val statistics: CampaignStatistics? = null,
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(StatsUiState())
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val campaign = levels.getCampaign()
                if (campaign == null) {
                    mutableState.value = StatsUiState(loading = false)
                    return@launch
                }
                // Поиск по словарю выполняется один раз при открытии экрана, не на старте приложения.
                val basis = withContext(computationDispatcher) {
                    CampaignStatisticsAssembler.prepare(campaign, words.requireIndex())
                }
                combine(time.observe(campaign), progress.observeCampaignProgress()) { records, saved ->
                    statsUiState(campaign, basis, time.statistics(campaign, records), records, saved, stageNames)
                }.flowOn(computationDispatcher).collect { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = StatsUiState(loading = false, error = true)
            }
        }
    }
}
