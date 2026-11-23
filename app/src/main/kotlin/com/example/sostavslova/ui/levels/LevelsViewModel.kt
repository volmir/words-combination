package com.example.sostavslova.ui.levels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sostavslova.R
import com.example.sostavslova.core.AchievementRules
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignSummary
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.LevelAchievement
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.WelcomeState
import com.example.sostavslova.data.LevelRouteRules
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LevelUiState(
    val index: Int, val word: String, val stars: Int, val unlocked: Boolean,
    val maxStars: Int = 3, val requiredStars: Int = 1, val stage: Int = 0,
    val recommended: Boolean = false, val crowned: Boolean = false,
    val achievements: Set<LevelAchievement> = emptySet(),
    val achievementsAvailable: Set<LevelAchievement> = emptySet(),
)

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
    /** Приветствие показывается один раз и только после появления настоящих карточек. */
    val showWelcome: Boolean = false,
    val welcomePresented: Boolean = false,
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
    private var welcomeResolved = false

    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, loadError = false) }
        loadJob = viewModelScope.launch {
            try {
                // Кампания обязательна: при отсутствии или повреждении показываем ошибку загрузки,
                // а не старый режим отдельных уровней.
                val campaign = requireNotNull(levels.getCampaign()) { "Campaign asset is required" }
                val index = words.requireIndex()
                progress.migrateCampaign(campaign, index)
                // Один поиск на уровень при загрузке карты; обновления прогресса используют кэш.
                val available = withContext(Dispatchers.Default) {
                    campaign.levels.associate { level ->
                        level.number to AchievementRules.available(
                            GameState.start(level.word, index), level.requiredStars, level.maxStars)
                    }
                }
                progress.observeCampaignProgress().collect { saved ->
                    val recommended = CampaignRules.continueLevel(campaign, saved).number
                    val items = campaign.levels.map { level ->
                        LevelUiState(level.number, level.word, CampaignRules.stars(level, saved),
                            LevelRouteRules.isUnlocked(level.number, campaign, saved), level.maxStars,
                            level.requiredStars, level.stage, recommended == level.number,
                            saved.finaleReached && level.number == campaign.levels.last().number,
                            achievements = saved.levels[level.word]?.achievements.orEmpty(),
                            achievementsAvailable = available.getValue(level.number))
                    }
                    val showWelcome = if (items.isNotEmpty()) resolveWelcomeAfterReady() else false
                    mutableState.update { it.copy(loading = false, levels = items, stages = campaign.stages,
                        summary = CampaignRules.summary(campaign, saved), recommendedLevel = recommended,
                        finaleReached = saved.finaleReached, showWelcome = it.showWelcome || showWelcome) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, loadError = true) } }
        }
    }

    /**
     * Вызывается экраном после фактической композиции диалога, не по нажатию.
     * Ошибка записи не должна превращать уже загруженную карту в ошибку.
     */
    fun onWelcomeShown() {
        val state = mutableState.value
        if (!state.showWelcome || state.welcomePresented || state.loading || state.loadError ||
            state.levels.isEmpty()) return
        mutableState.update { it.copy(welcomePresented = true) }
        viewModelScope.launch {
            try {
                progress.markWelcomeDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Следующий запуск повторит показ, если DONE не успел сохраниться.
            }
        }
    }

    fun dismissWelcome() { mutableState.update { it.copy(showWelcome = false) } }

    private suspend fun resolveWelcomeAfterReady(): Boolean {
        if (welcomeResolved) return false
        welcomeResolved = true
        val resolved = try {
            progress.resolveWelcomeState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WelcomeState.UNKNOWN
        }
        return resolved == WelcomeState.SHOW
    }

    fun onSelectLevel(word: String) {
        val state = uiState.value
        if (!state.canSelect) return
        val item = state.levels.firstOrNull { it.word == word } ?: return
        // Переход не создаёт игру и ничего не сохраняет. Маршрут перепроверяет доступ.
        if (item.unlocked) mutableState.update { it.copy(openLevel = item.index, message = 0, lockedRequirement = null) }
        else {
            val previous = state.levels.firstOrNull { it.index == item.index - 1 } ?: return
            mutableState.update { it.copy(lockedRequirement = previous.index to previous.requiredStars) }
        }
    }

    fun dismissLockedRequirement() { mutableState.update { it.copy(lockedRequirement = null) } }

    fun onGameOpened() { mutableState.update { it.copy(openLevel = null) } }
}
