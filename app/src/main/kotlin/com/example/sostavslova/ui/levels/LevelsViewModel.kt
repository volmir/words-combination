package com.example.sostavslova.ui.levels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignSummary
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.data.LevelRouteRules
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LevelUiState(
    val index: Int, val word: String, val progress: LevelProgress, val unlocked: Boolean,
    val campaignStars: Int? = null, val maxStars: Int = 3,
    val requiredStars: Int = 1, val stage: Int = 0, val recommended: Boolean = false,
    val crowned: Boolean = false,
) {
    val stars: Int get() = campaignStars ?: progress.stars

    constructor(level: Level, progress: LevelProgress, unlocked: Boolean) :
        this(level.index, level.word, progress, unlocked)
}

data class LevelsUiState(
    val loading: Boolean = true,
    val summary: CampaignSummary? = null,
    val stages: List<Stage> = emptyList(),
    val recommendedLevel: Int? = null,
    val finaleReached: Boolean = false,
    val lockedRequirement: Pair<Int, Int>? = null,
    val loadError: Boolean = false,
    val levels: List<LevelUiState> = emptyList(),
    val openLevel: Int? = null,
    @param:StringRes val message: Int = 0,
) {
    val canSelect: Boolean get() = !loading && !loadError && openLevel == null
}

class LevelsViewModel(
    private val levels: LevelsRepository,
    private val progress: ProgressRepository,
    private val words: WordRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LevelsUiState())
    val uiState = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        loadJob = viewModelScope.launch {
            try {
                val campaign = levels.getCampaign()
                if (campaign != null) {
                    progress.migrateCampaign(campaign, words.requireIndex())
                    progress.observeCampaignProgress().collect { saved ->
                        val recommended = CampaignRules.continueLevel(campaign, saved).number
                        val items = campaign.levels.map { level ->
                            LevelUiState(level.number, level.word, LevelProgress(),
                                LevelRouteRules.isUnlocked(level.number, campaign, saved, emptyList(), emptyMap()),
                                CampaignRules.stars(level, saved), level.maxStars, level.requiredStars, level.stage,
                                recommended == level.number, saved.finaleReached && level.number == campaign.levels.last().number)
                        }
                        mutableState.update { it.copy(loading = false, levels = items, stages = campaign.stages,
                            summary = CampaignRules.summary(campaign, saved), recommendedLevel = recommended,
                            finaleReached = saved.finaleReached) }
                    }
                } else {
                    val all = levels.getLevels()
                    progress.migrateLegacyLevels(all)
                    progress.observeLevelProgress().collect { saved ->
                        val items = all.map { level ->
                            LevelUiState(level, saved[level.word] ?: LevelProgress(),
                                LevelRouteRules.isUnlocked(level.index, null, CampaignProgress(), all, saved))
                        }
                        mutableState.update { it.copy(loading = false, levels = items) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, loadError = true) } }
        }
    }

    fun onSelectLevel(word: String) {
        val state = uiState.value
        if (!state.canSelect) return
        val item = state.levels.firstOrNull { it.word == word } ?: return
        // Переход не создаёт игру и ничего не сохраняет. Маршрут перепроверяет доступ.
        if (item.unlocked) mutableState.update { it.copy(openLevel = item.index, message = 0, lockedRequirement = null) }
        else if (item.campaignStars != null) {
            val previous = state.levels.firstOrNull { it.index == item.index - 1 } ?: return
            mutableState.update { it.copy(lockedRequirement = previous.index to previous.requiredStars) }
        } else mutableState.update { it.copy(message = R.string.level_closed) }
    }

    fun dismissLockedRequirement() { mutableState.update { it.copy(lockedRequirement = null) } }

    fun onGameOpened() { mutableState.update { it.copy(openLevel = null) } }
}
