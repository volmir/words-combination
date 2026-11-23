package com.example.sostavslova.core

/** Фиксированный порядок показа достижений уровня. */
enum class LevelAchievement {
    NO_HINTS,
    EXPERT,
    COLLECTOR,
    EXPLORER,
    LONG_WORD,
    LONG_WORD_MASTER,
    GIANT,
    STRAIGHT_A,
}

/**
 * Достижения уровня: только чтение ответов, звёзд и подсказок. Все пороги вынесены
 * сюда, чтобы их можно было изменить после плейтеста без правки правил.
 */
object AchievementRules {
    const val EXPERT_NUMERATOR = 1
    const val EXPERT_DENOMINATOR = 3
    const val COLLECTOR_NUMERATOR = 1
    const val COLLECTOR_DENOMINATOR = 2
    const val EXPLORER_NUMERATOR = 2
    const val EXPLORER_DENOMINATOR = 3
    const val LONG_WORD_LENGTH = 7
    const val LONG_WORD_COUNT = 1
    const val LONG_WORD_MASTER_COUNT = 3
    const val GIANT_LENGTH = 9
    const val GIANT_COUNT = 1

    /**
     * Множество достижений, которые должен иметь уровень после ответа. Ранее полученные
     * [alreadyEarned] сохраняются независимо от текущих условий; новые выдаются по правилам.
     * [hintsUsed] — устойчивый признак показывавшейся ступени; по умолчанию выводится из
     * старого снимка, где отдельного поля ещё не было.
     */
    fun evaluate(
        level: CampaignLevel,
        game: GameState,
        completedTaskIds: Set<String>,
        hints: HintState,
        alreadyEarned: Set<LevelAchievement>,
        hintsUsed: Boolean = hints.shownSteps > 0 || hints.paidUnits > 0,
    ): Set<LevelAchievement> {
        val all = admissibleWords(game)
        val found = game.found.map(Alphabet::normalize).toSet().intersect(all)
        val total = all.size
        val stars = level.tasks.count { it.id in completedTaskIds }
        val earned = mutableSetOf<LevelAchievement>()
        if (total > 0 && stars >= level.requiredStars && !hintsUsed) earned += LevelAchievement.NO_HINTS
        if (meetsShare(found.size, total, EXPERT_NUMERATOR, EXPERT_DENOMINATOR)) earned += LevelAchievement.EXPERT
        if (meetsShare(found.size, total, COLLECTOR_NUMERATOR, COLLECTOR_DENOMINATOR)) earned += LevelAchievement.COLLECTOR
        if (meetsShare(found.size, total, EXPLORER_NUMERATOR, EXPLORER_DENOMINATOR)) earned += LevelAchievement.EXPLORER
        val longWords = found.count { it.length >= LONG_WORD_LENGTH }
        if (longWords >= LONG_WORD_COUNT) earned += LevelAchievement.LONG_WORD
        if (longWords >= LONG_WORD_MASTER_COUNT) earned += LevelAchievement.LONG_WORD_MASTER
        if (found.count { it.length >= GIANT_LENGTH } >= GIANT_COUNT) earned += LevelAchievement.GIANT
        if (level.requiredStars < level.maxStars && stars >= level.maxStars) earned += LevelAchievement.STRAIGHT_A
        return LevelAchievement.entries.filterTo(LinkedHashSet()) { it in earned || it in alreadyEarned }
    }

    /** Достижения, достижимые на уровне по текущему словарю и порогу R < M. */
    fun available(game: GameState, requiredStars: Int, maxStars: Int): Set<LevelAchievement> {
        val all = admissibleWords(game)
        val result = mutableSetOf<LevelAchievement>()
        if (all.isNotEmpty()) result += setOf(
            LevelAchievement.NO_HINTS, LevelAchievement.EXPERT,
            LevelAchievement.COLLECTOR, LevelAchievement.EXPLORER,
        )
        val longWords = all.count { it.length >= LONG_WORD_LENGTH }
        if (longWords >= LONG_WORD_COUNT) result += LevelAchievement.LONG_WORD
        if (longWords >= LONG_WORD_MASTER_COUNT) result += LevelAchievement.LONG_WORD_MASTER
        if (all.count { it.length >= GIANT_LENGTH } >= GIANT_COUNT) result += LevelAchievement.GIANT
        if (requiredStars < maxStars) result += LevelAchievement.STRAIGHT_A
        return LevelAchievement.entries.filterTo(LinkedHashSet()) { it in result }
    }

    /** found * denominator >= total * numerator, включая total == 0 => false. */
    internal fun meetsShare(found: Int, total: Int, numerator: Int, denominator: Int): Boolean =
        denominator > 0 && total > 0 && found.toLong() * denominator >= total.toLong() * numerator

    private fun admissibleWords(game: GameState): Set<String> = game.allWords
        .map(Alphabet::normalize).filter { it.length >= Alphabet.MIN_WORD_LENGTH }.toSet()
}
