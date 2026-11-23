package com.example.sostavslova.core

/** Итоги подтверждённого ответа; событие показа не выводится из сохранения при открытии. */
data class LevelCompleteSummary(
    val levelNumber: Int,
    val word: String,
    val stars: Int,
    val maxStars: Int,
    val achievements: Set<LevelAchievement>,
    val availableAchievements: Set<LevelAchievement>,
    val newAchievements: Set<LevelAchievement>,
    val foundWords: Int,
    val totalWords: Int,
    val hintsUsed: Int,
    val hasNextLevel: Boolean,
) {
    val allStars: Boolean get() = stars == maxStars

    companion object {
        /** Последний уровень представляет существующий финальный диалог кампании. */
        fun build(
            current: CurrentCampaignLevel,
            saved: CampaignLevelProgress,
            totalLevels: Int,
            levelPassedNow: Boolean,
            newAchievements: Set<LevelAchievement> = emptySet(),
        ): LevelCompleteSummary? {
            if (!levelPassedNow || current.level.number >= totalLevels) return null
            val level = current.level
            return LevelCompleteSummary(
                levelNumber = level.number,
                word = level.word,
                stars = level.tasks.count { it.id in saved.completedTaskIds },
                maxStars = level.maxStars,
                achievements = saved.achievements,
                availableAchievements = AchievementRules.available(current.game, level.requiredStars, level.maxStars) + saved.achievements,
                newAchievements = newAchievements.intersect(saved.achievements),
                foundWords = current.game.found.size,
                totalWords = current.game.allWords.size,
                hintsUsed = saved.hints.shownSteps,
                hasNextLevel = level.number < totalLevels,
            )
        }
    }
}
