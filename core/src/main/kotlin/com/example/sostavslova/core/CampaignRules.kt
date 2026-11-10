package com.example.sostavslova.core

/** Сохранение одного уровня; ключ в [CampaignProgress.levels] — CampaignLevel.word. */
data class CampaignLevelProgress(
    val found: List<String> = emptyList(),
    val completedTaskIds: Set<String> = emptySet(),
    val hints: HintState = HintState(),
)

/**
 * Сохраняемый снимок v2. Граница доступа и оба признака финала не выводятся заново
 * из звёзд. Клиент атомарно сохраняет возвращённый снимок и использует его дальше.
 */
data class CampaignProgress(
    val levels: Map<String, CampaignLevelProgress> = emptyMap(),
    val highestUnlockedLevel: Int = 1,
    val finaleReached: Boolean = false,
    val finaleShown: Boolean = false,
)

data class CampaignSummary(
    val stars: Int,
    val maxStars: Int,
    val passedLevels: Int,
    val totalLevels: Int,
    val foundWords: Int,
) {
    /** Для производственной кампании максимум равен 100; финал сам по себе недостаточен. */
    val isMaster: Boolean get() = maxStars > 0 && stars == maxStars
}

data class FinalePresentation(val state: CampaignProgress, val showDialog: Boolean)

/**
 * Правила для валидированной непустой кампании с номерами 1..N и её сохранений.
 * Номера в API начинаются с 1. Словарь нужен только при открытии конкретной игры;
 * доступ, выбор «Продолжить» и итоги не перебирают словарь.
 */
object CampaignRules {
    fun stars(level: CampaignLevel, progress: CampaignProgress): Int {
        val completed = progress.levels[level.word]?.completedTaskIds.orEmpty()
        return level.tasks.count { it.id in completed }
    }

    /** Прохождение по любым R заданиям отдельно от полного набора M и доступа. */
    fun isPassed(level: CampaignLevel, progress: CampaignProgress): Boolean =
        stars(level, progress) >= level.requiredStars

    fun isUnlocked(campaign: Campaign, progress: CampaignProgress, levelNumber: Int): Boolean =
        levelNumber in 1..campaign.levels.size && levelNumber <= progress.highestUnlockedLevel

    /** Одна проверка для карточки и прямого маршрута; закрытая/неизвестная игра не создаётся. */
    fun openLevel(
        campaign: Campaign,
        progress: CampaignProgress,
        levelNumber: Int,
        index: WordIndex,
    ): CurrentCampaignLevel? {
        if (!isUnlocked(campaign, progress, levelNumber)) return null
        val level = campaign.levels[levelNumber - 1]
        val saved = progress.levels[level.word] ?: CampaignLevelProgress()
        val game = GameState.start(level.word, index, normalizedFound(saved.found))
        return CurrentCampaignLevel(level, game, saved.completedTaskIds.intersect(taskIds(level)), saved.hints)
    }

    /**
     * После TaskRules.submit/evaluate (и восстановления игры) объединяет ответы и
     * звёзды с сохранением. Повторное или неполное состояние не отнимает прогресс.
     * Закрытый маршрут и состояние чужого слова отвергаются также на записи.
     */
    fun recordLevel(
        campaign: Campaign,
        progress: CampaignProgress,
        current: CurrentCampaignLevel,
    ): CampaignProgress {
        val level = current.level
        require(isUnlocked(campaign, progress, level.number)) { "Level is locked or unknown" }
        require(campaign.levels[level.number - 1] == level) { "Level does not belong to campaign" }
        require(current.game.word == level.word) { "Game belongs to another source word" }
        val saved = progress.levels[level.word] ?: CampaignLevelProgress()
        val merged = current.copy(
            game = current.game.copy(found = normalizedFound(saved.found + current.game.found)),
            completedTaskIds = (saved.completedTaskIds + current.completedTaskIds).intersect(taskIds(level)),
        )
        val evaluated = TaskRules.evaluate(merged, campaign.countMinLength).state
        val passed = evaluated.completedTaskIds.size >= level.requiredStars
        val last = level.number == campaign.levels.size
        // Только прохождение текущей границы открывает ровно один следующий уровень.
        val frontier = if (passed && !last && level.number == progress.highestUnlockedLevel) {
            level.number + 1
        } else {
            progress.highestUnlockedLevel
        }
        return progress.copy(
            levels = progress.levels + (level.word to CampaignLevelProgress(
                found = evaluated.game.found,
                completedTaskIds = evaluated.completedTaskIds,
                // Неполный/старый снимок игры не возвращает уже потраченный бюджет.
                hints = if (evaluated.hints.shownSteps > saved.hints.shownSteps ||
                    (evaluated.hints.shownSteps == saved.hints.shownSteps &&
                        (evaluated.hints.ladder.target != null || saved.hints.ladder.target?.word in evaluated.game.found))
                ) evaluated.hints else saved.hints,
            )),
            highestUnlockedLevel = frontier,
            finaleReached = progress.finaleReached || (last && passed),
        )
    }

    /** До финала пропускает пройденные по R, после финала ищет недобор до M. */
    fun continueLevel(campaign: Campaign, progress: CampaignProgress): CampaignLevel {
        val open = campaign.levels.filter { isUnlocked(campaign, progress, it.number) }
        return open.firstOrNull { level ->
            val target = if (progress.finaleReached) level.maxStars else level.requiredStars
            stars(level, progress) < target
        } ?: open.last()
    }

    /**
     * Сохранить state при получении showDialog=true, затем показать диалог.
     * Повторное открытие, добор и пересчёт сами по себе не потребляют показ.
     */
    fun consumeFinaleDialog(progress: CampaignProgress): FinalePresentation {
        val show = progress.finaleReached && !progress.finaleShown
        return FinalePresentation(if (show) progress.copy(finaleShown = true) else progress, show)
    }

    /** Только текущие слова уровней и их ID; одинаковый ответ на двух уровнях считается дважды. */
    fun summary(campaign: Campaign, progress: CampaignProgress): CampaignSummary = CampaignSummary(
        stars = campaign.levels.sumOf { stars(it, progress) },
        maxStars = campaign.maxStars,
        passedLevels = campaign.levels.count { isPassed(it, progress) },
        totalLevels = campaign.levels.size,
        foundWords = campaign.levels.sumOf { normalizedFound(progress.levels[it.word]?.found.orEmpty()).size },
    )

    /** Вызывать только по явному пользовательскому сбросу, заменяя всё сохранение кампании. */
    fun reset(): CampaignProgress = CampaignProgress()

    private fun taskIds(level: CampaignLevel): Set<String> = level.tasks.map { it.id }.toSet()

    private fun normalizedFound(found: List<String>): List<String> = found.map(Alphabet::normalize).distinct()
}
