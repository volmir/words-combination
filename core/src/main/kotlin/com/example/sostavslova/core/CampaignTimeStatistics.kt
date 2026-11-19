package com.example.sostavslova.core

import java.math.BigInteger

/** Снимок только времени. Идентичность кампании и слово не позволяют смешать разные наборы. */
data class CampaignTimeSnapshot(val campaignId: String, val levels: Map<Int, LevelTimeMeasurement>)

data class LevelTimeMeasurement(
    val word: String,
    val activeMs: Long = 0,
    /** Есть хотя бы один сохранённый сегмент, включая настоящий сегмент 0 мс. */
    val measured: Boolean = false,
    val timeToRMs: Long? = null,
    val timeToFullMs: Long? = null,
)

data class LevelTimeStatistics(
    val levelNumber: Int,
    val stageNumber: Int,
    /** null — измерений нет, 0 — измеренный нулевой интервал. */
    val screenActiveMs: Long?,
    val timeToRMs: Long?,
    val timeToFullMs: Long?,
)

/** Отдельная сумма достоверных milestone, не замена времени на экране. */
data class MilestoneTimeSummary(val measuredLevelCount: Int, val totalMs: BigInteger)

data class StageTimeStatistics(
    val stageNumber: Int,
    val levels: List<LevelTimeStatistics>,
    val screenActiveMs: BigInteger,
    val toR: MilestoneTimeSummary,
    val toFull: MilestoneTimeSummary,
)

data class CampaignTimeSummary(
    val levels: List<LevelTimeStatistics>,
    val stages: List<StageTimeStatistics>,
    val screenActiveMs: BigInteger,
    val measuredLevelCount: Int,
    val meanLevelActiveMs: Double?,
    val medianLevelActiveMs: Double?,
    /** Минимум/максимум суммарного активного времени уровня, а не отдельного захода. */
    val fastestLevel: LevelTimeStatistics?,
    val slowestLevel: LevelTimeStatistics?,
    val toR: MilestoneTimeSummary,
    val toFull: MilestoneTimeSummary,
)

/** Чистая функция: не читает часы, прогресс или хранилище, ничего не записывает. */
object CampaignTimeStatistics {
    fun aggregate(campaign: Campaign, snapshot: CampaignTimeSnapshot): CampaignTimeSummary {
        require(snapshot.campaignId == CampaignIdentity.of(campaign)) { "Different campaign timing snapshot" }
        require(campaign.levels.map { it.number }.distinct().size == campaign.levels.size)
        require(campaign.stages.map { it.number }.distinct().size == campaign.stages.size)
        require(snapshot.levels.keys.all { number -> campaign.levels.any { it.number == number } })
        val levels = campaign.levels.map { level ->
            val stage = campaign.stages.single { it.number == level.stage }
            require(level.number in stage.firstLevel..stage.lastLevel)
            val time = snapshot.levels[level.number]
            if (time != null) {
                require(time.word == level.word)
                require(time.activeMs >= 0)
                require(time.measured || (time.activeMs == 0L && time.timeToRMs == null && time.timeToFullMs == null))
                require(time.timeToRMs == null || time.timeToRMs in 0..time.activeMs)
                require(time.timeToFullMs == null || time.timeToFullMs in 0..time.activeMs)
                require(time.timeToRMs == null || time.timeToFullMs == null || time.timeToRMs <= time.timeToFullMs)
            }
            LevelTimeStatistics(level.number, level.stage, time?.takeIf { it.measured }?.activeMs,
                time?.timeToRMs, time?.timeToFullMs)
        }
        val measured = levels.filter { it.screenActiveMs != null }
        val sorted = measured.map { requireNotNull(it.screenActiveMs) }.sorted()
        val total = sum(sorted)
        val median = if (sorted.isEmpty()) null else {
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[middle].toDouble()
            else (BigInteger.valueOf(sorted[middle - 1]) + BigInteger.valueOf(sorted[middle])).toDouble() / 2
        }
        return CampaignTimeSummary(
            levels = levels,
            stages = campaign.stages.map { stage ->
                val items = levels.filter { it.stageNumber == stage.number }
                StageTimeStatistics(stage.number, items, sum(items.mapNotNull { it.screenActiveMs }),
                    milestone(items.mapNotNull { it.timeToRMs }), milestone(items.mapNotNull { it.timeToFullMs }))
            },
            screenActiveMs = total,
            measuredLevelCount = measured.size,
            meanLevelActiveMs = if (measured.isEmpty()) null else total.toDouble() / measured.size,
            medianLevelActiveMs = median,
            fastestLevel = measured.minByOrNull { requireNotNull(it.screenActiveMs) },
            slowestLevel = measured.maxByOrNull { requireNotNull(it.screenActiveMs) },
            toR = milestone(levels.mapNotNull { it.timeToRMs }),
            toFull = milestone(levels.mapNotNull { it.timeToFullMs }),
        )
    }

    // Даже сумма нескольких Long.MAX_VALUE остаётся точной и не становится отрицательной.
    private fun sum(values: List<Long>): BigInteger = values.fold(BigInteger.ZERO) { total, ms -> total + BigInteger.valueOf(ms) }
    private fun milestone(values: List<Long>) = MilestoneTimeSummary(values.size, sum(values))
}
