package com.example.sostavslova.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignTimeSummary
import com.example.sostavslova.data.LevelPlayTime
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

data class TimeUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val campaign: Campaign? = null,
    val summary: CampaignTimeSummary? = null,
    val historicalLevels: Set<Int> = emptySet(),
    val pendingLevels: Set<Int> = emptySet(),
)

/** Presentation evidence only: never initialize timing or migrate/write progress here. */
internal fun timeUiState(
    campaign: Campaign,
    summary: CampaignTimeSummary,
    records: Map<Int, LevelPlayTime>,
    progress: CampaignProgress,
): TimeUiState {
    val matchingProgress = progress.takeIf { it.campaignId == campaign.contentSha256 }
    val historical = campaign.levels.filter { level ->
        val record = records.getValue(level.number)
        // Before timing existed, the save did not record every visit. Use only known evidence.
        (!record.initialized && (matchingProgress?.levels?.containsKey(level.word) == true ||
            matchingProgress?.lastVisitedLevel == level.number)) ||
            (record.rAccounted && record.timeToRMs == null) ||
            (record.lastSequence == 0L && record.lastStars > 0)
    }.map { it.number }.toSet()
    return TimeUiState(loading = false, campaign = campaign, summary = summary,
        historicalLevels = historical,
        pendingLevels = records.filter { it.value.initialized && it.value.lastSequence == 0L }.keys - historical)
}

class TimeViewModel(
    levels: LevelsRepository,
    time: PlayTimeRepository,
    progress: ProgressRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TimeUiState())
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val campaign = levels.getCampaign()
                if (campaign == null) mutableState.value = TimeUiState(loading = false)
                else combine(time.observe(campaign), progress.observeCampaignProgress()) { records, saved ->
                    timeUiState(campaign, time.statistics(campaign, records), records, saved)
                }.flowOn(Dispatchers.Default).collect { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = TimeUiState(loading = false, error = true)
            }
        }
    }
}
