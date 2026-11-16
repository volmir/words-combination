package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Только время; null у пройденного порога означает отсутствие достоверного замера. */
@Serializable
data class LevelPlayTime(
    val cumulativeActiveMs: Long = 0L,
    val timeToRMs: Long? = null,
    val timeToFullMs: Long? = null,
    val initialized: Boolean = false,
    val rAccounted: Boolean = false,
    val fullAccounted: Boolean = false,
    val lastSequence: Long = 0L,
    val lastStars: Int = 0,
    val version: Int = 1,
)

/**
 * Отдельный DataStore, принадлежащий AppContainer. edit сериализует операции даже
 * разных экземпляров репозитория над одним DataStore и возвращается после записи.
 * Ошибки диска, protobuf и JSON передаются вызывающему коду: нули вместо повреждённых
 * данных не выдаются. Повторять неудачную запись следует с тем же sequence.
 * Этот слой не запускает таймер и не вычисляет/меняет игровой прогресс.
 */
class PlayTimeRepository(private val dataStore: DataStore<Preferences>) {
    private val json = Json { encodeDefaults = true }

    fun observe(campaign: Campaign): Flow<Map<Int, LevelPlayTime>> {
        identity(campaign)
        return dataStore.data.map { preferences ->
            campaign.levels.associate { level -> level.number to read(preferences, campaign, level) }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    suspend fun snapshot(campaign: Campaign): Map<Int, LevelPlayTime> = observe(campaign).first()

    /**
     * Обязателен до первого сегмента. starsBeforeTracking — реальные звёзды из
     * сохранения на момент подключения таймера. Повторное открытие не меняет baseline.
     * Уже достигнутые R/M помечаются accounted с null, а не рекордом 0 мс.
     */
    suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime {
        val level = level(campaign, levelNumber)
        require(starsBeforeTracking in 0..level.maxStars)
        val saved = dataStore.edit { preferences ->
            val previous = read(preferences, campaign, level)
            if (!previous.initialized) {
                val initial = LevelPlayTime(
                    initialized = true,
                    rAccounted = starsBeforeTracking >= level.requiredStars,
                    fullAccounted = starsBeforeTracking >= level.maxStars,
                    lastStars = starsBeforeTracking,
                )
                preferences[key(campaign, level)] = json.encodeToString(initial)
            }
        }
        return read(saved, campaign, level)
    }

    /**
     * Атомарно добавляет один сегмент и фиксирует новые пороги в конце сегмента.
     * sequence: 1, 2, ... в пределах уровня и SHA кампании, сохранён на диске.
     * Повтор sequence <= lastSequence ничего не меняет; пропуск номера запрещён.
     * Вызывающий код сериализует сегменты и повторяет исходные аргументы при сбое.
     * starsAfter — фактический результат :core, а не команда изменить звёзды.
     * Метод suspend завершается только после долговечной записи; отдельный awaitSaved
     * ProgressRepository к этому независимому файлу не относится.
     */
    suspend fun appendSegment(
        campaign: Campaign,
        levelNumber: Int,
        sequence: Long,
        activeMs: Long,
        starsAfter: Int,
    ): LevelPlayTime {
        val level = level(campaign, levelNumber)
        require(sequence > 0L && activeMs >= 0L)
        require(starsAfter in 0..level.maxStars)
        val saved = dataStore.edit { preferences ->
            val previous = read(preferences, campaign, level)
            check(previous.initialized) { "Initialize timing with existing stars first" }
            if (sequence > previous.lastSequence) {
                require(sequence == Math.addExact(previous.lastSequence, 1L)) { "Out-of-order segment" }
                require(starsAfter >= previous.lastStars) { "Stars cannot decrease within a timing record" }
                val total = Math.addExact(previous.cumulativeActiveMs, activeMs)
                val reachedR = starsAfter >= level.requiredStars
                val reachedFull = starsAfter >= level.maxStars
                val updated = previous.copy(
                    cumulativeActiveMs = total,
                    timeToRMs = if (!previous.rAccounted && reachedR) total else previous.timeToRMs,
                    timeToFullMs = if (!previous.fullAccounted && reachedFull) total else previous.timeToFullMs,
                    rAccounted = previous.rAccounted || reachedR,
                    fullAccounted = previous.fullAccounted || reachedFull,
                    lastSequence = sequence,
                    lastStars = starsAfter,
                )
                preferences[key(campaign, level)] = json.encodeToString(updated)
            }
        }
        return read(saved, campaign, level)
    }

    /** Явный сброс только файла времени, включая прежние кампании. В UI не подключён. */
    suspend fun clearTimeStatistics() {
        dataStore.edit { it.clear() }
    }

    private fun identity(campaign: Campaign): String {
        val sha = campaign.contentSha256
        require(sha != null && sha.matches(Regex("[0-9a-f]{64}"))) { "Verified campaign SHA-256 required" }
        require(campaign.levels.isNotEmpty() && campaign.levels.map { it.number }.distinct().size == campaign.levels.size)
        return sha
    }

    private fun level(campaign: Campaign, number: Int): CampaignLevel {
        identity(campaign)
        return requireNotNull(campaign.levels.singleOrNull { it.number == number }) { "Unknown level" }
    }

    private fun key(campaign: Campaign, level: CampaignLevel): Preferences.Key<String> {
        require(level.number > 0 && level.word.isNotBlank())
        require(level.requiredStars in 1..level.maxStars)
        return stringPreferencesKey("playTimeV1:${identity(campaign)}:${level.number}:${level.word}")
    }

    private fun read(preferences: Preferences, campaign: Campaign, level: CampaignLevel): LevelPlayTime {
        val raw = preferences[key(campaign, level)] ?: return LevelPlayTime()
        val value = json.decodeFromString<LevelPlayTime>(raw)
        // Отсутствующие поля старых DTO имеют defaults; запись без initialized невалидна.
        require(value.version == 1 && value.initialized) { "Invalid timing schema" }
        require(value.cumulativeActiveMs >= 0L && value.lastSequence >= 0L)
        require(value.lastStars in 0..level.maxStars)
        require(value.rAccounted == (value.lastStars >= level.requiredStars))
        require(value.fullAccounted == (value.lastStars >= level.maxStars))
        require(value.timeToRMs == null || (value.rAccounted && value.timeToRMs in 0L..value.cumulativeActiveMs))
        require(value.timeToFullMs == null || (value.fullAccounted && value.timeToFullMs in 0L..value.cumulativeActiveMs))
        require(value.timeToRMs == null || value.timeToFullMs == null || value.timeToRMs <= value.timeToFullMs)
        require(value.lastSequence != 0L || (value.cumulativeActiveMs == 0L && value.timeToRMs == null && value.timeToFullMs == null))
        return value
    }
}
