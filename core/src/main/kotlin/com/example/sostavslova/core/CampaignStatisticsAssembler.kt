package com.example.sostavslova.core

data class LevelStatsRow(
    val number: Int,
    val word: String,
    val stageName: String,
    val stars: Int,
    val maxStars: Int,
    val requiredStars: Int,
    val found: Int,
    val total: Int,
    val hintsShown: Int,
    val byLength: List<LengthStat>,
    /** Уже полученные и достижимые на текущем словаре достижения этого уровня. */
    val achievements: Set<LevelAchievement> = emptySet(),
    val achievementsAvailable: Set<LevelAchievement> = emptySet(),
) {
    val passed: Boolean get() = stars >= requiredStars

    /** Тот же процент, что в остальных таблицах :core, без отдельной формулы округления. */
    val percent: Int get() = Statistics(byLength).totalPercent
}

data class StageStatsRows(
    val number: Int,
    val name: String,
    val firstLevel: Int,
    val lastLevel: Int,
    val levels: List<LevelStatsRow>,
)

data class CampaignStatistics(
    val stars: Int,
    val maxStars: Int,
    val passedLevels: Int,
    val totalLevels: Int,
    val foundWords: Int,
    val totalWords: Int,
    val hintsShown: Int,
    val stages: List<StageStatsRows>,
    val levels: List<LevelStatsRow>,
    val achievementsEarned: Int = 0,
    val achievementsTotal: Int = 0,
)

/** Слова уровня, найденные один раз при открытии экрана: поиск по словарю не повторяется. */
class CampaignStatisticsBasis internal constructor(
    internal val levels: Map<Int, LevelWords>,
) {
    internal data class LevelWords(val number: Int, val word: String, val allWords: List<String>)
}

/**
 * Чистая сборка статистики. [prepare] один раз перебирает словарь для каждого уровня,
 * [assemble] только восстанавливает найденные слова из сохранения через GameState.
 * Ничего не читает с диска/часов и ничего не записывает.
 */
object CampaignStatisticsAssembler {
    fun prepare(campaign: Campaign, index: WordIndex): CampaignStatisticsBasis {
        validate(campaign)
        val levels = campaign.levels.associate { level ->
            val words = GameState.start(level.word, index).allWords
            level.number to CampaignStatisticsBasis.LevelWords(level.number, level.word, words)
        }
        return CampaignStatisticsBasis(levels)
    }

    fun assemble(
        campaign: Campaign,
        progress: CampaignProgress,
        index: WordIndex,
        stageNames: Map<Int, String> = emptyMap(),
    ): CampaignStatistics = assemble(campaign, progress, prepare(campaign, index), stageNames)

    fun assemble(
        campaign: Campaign,
        progress: CampaignProgress,
        basis: CampaignStatisticsBasis,
        stageNames: Map<Int, String> = emptyMap(),
    ): CampaignStatistics {
        validate(campaign)
        val numbers = campaign.levels.map { it.number }
        require(basis.levels.keys == numbers.toSet()) { "Statistics basis does not match campaign levels" }
        val rows = campaign.levels.map { level ->
            val saved = progress.levels[level.word] ?: CampaignLevelProgress()
            val words = requireNotNull(basis.levels[level.number])
            require(words.word == level.word) { "Statistics basis belongs to another source word" }
            // GameState сам отбрасывает несоставимые/повторные слова и считает длины.
            val game = GameState(level.word, words.allWords).restoreFound(saved.found.map(Alphabet::normalize))
            val statistics = game.statistics()
            LevelStatsRow(
                number = level.number,
                word = level.word,
                stageName = stageNames[level.stage] ?: level.stage.toString(),
                stars = CampaignRules.stars(level, progress),
                maxStars = level.maxStars,
                requiredStars = level.requiredStars,
                found = statistics.totalFound,
                total = statistics.totalWords,
                hintsShown = saved.hints.shownSteps,
                byLength = statistics.rows,
                achievements = saved.achievements,
                achievementsAvailable = AchievementRules.available(game, level.requiredStars, level.maxStars),
            )
        }
        val summary = CampaignRules.summary(campaign, progress)
        val stages = campaign.stages.map { stage ->
            StageStatsRows(stage.number, stageNames[stage.number] ?: stage.number.toString(),
                stage.firstLevel, stage.lastLevel, rows.filter { it.number in stage.firstLevel..stage.lastLevel })
        }
        return CampaignStatistics(
            stars = summary.stars,
            maxStars = campaign.maxStars,
            passedLevels = summary.passedLevels,
            totalLevels = campaign.levels.size,
            foundWords = rows.sumOf { it.found },
            totalWords = rows.sumOf { it.total },
            hintsShown = summary.shownHintSteps,
            stages = stages,
            levels = rows,
            achievementsEarned = rows.sumOf { it.achievements.size },
            achievementsTotal = rows.sumOf { it.achievementsAvailable.size },
        )
    }

    private fun validate(campaign: Campaign) {
        require(campaign.levels.isNotEmpty()) { "Campaign must contain levels" }
        require(campaign.levels.map { it.number }.distinct().size == campaign.levels.size) {
            "Duplicate campaign level number"
        }
        require(campaign.stages.map { it.number }.distinct().size == campaign.stages.size) {
            "Duplicate campaign stage number"
        }
        campaign.levels.forEach { level ->
            require(level.maxStars >= level.requiredStars && level.requiredStars > 0) { "Invalid star thresholds" }
            require(campaign.stages.any { level.number in it.firstLevel..it.lastLevel }) {
                "Level ${level.number} is outside every stage"
            }
        }
    }
}
