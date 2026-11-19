package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import com.example.sostavslova.data.jsonstore.Preferences
import com.example.sostavslova.data.jsonstore.edit
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignIdentity
import com.example.sostavslova.core.CampaignLevel
import com.example.sostavslova.core.CampaignTimeSnapshot
import com.example.sostavslova.core.CampaignTimeStatistics
import com.example.sostavslova.core.CampaignTimeSummary
import com.example.sostavslova.core.LevelTimeMeasurement
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
 * Ошибки диска и JSON передаются вызывающему коду: нули вместо повреждённых
 * данных не выдаются. Повторять неудачную запись следует с тем же sequence.
 * Этот слой не запускает таймер и не вычисляет/меняет игровой прогресс.
 */
interface PlayTimeStore {
    suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime
    suspend fun appendSegment(campaign: Campaign, levelNumber: Int, sequence: Long, activeMs: Long, starsAfter: Int): LevelPlayTime
}

class PlayTimeRepository(private val dataStore: DataStore<Preferences>) : PlayTimeStore {
    private val json = Json { encodeDefaults = true }
    private data class DecodedSnapshot(
        val campaign: Campaign,
        val preferences: Preferences,
        val records: Map<Int, LevelPlayTime>,
    )
    // Только последний снимок. Новые данные или параметры кампании требуют валидации.
    @Volatile private var decodedSnapshot: DecodedSnapshot? = null

    fun observe(campaign: Campaign): Flow<Map<Int, LevelPlayTime>> {
        identity(campaign)
        return dataStore.data.map { preferences ->
            val cached = decodedSnapshot
            if (cached?.campaign == campaign && cached.preferences == preferences) cached.records
            else campaign.levels.associate { level -> level.number to read(preferences, campaign, level) }.also {
                decodedSnapshot = DecodedSnapshot(campaign, preferences, it)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    suspend fun snapshot(campaign: Campaign): Map<Int, LevelPlayTime> = observe(campaign).first()

    /** Чтение статистики не выполняет edit/initialize и не создаёт записей. */
    fun observeStatistics(campaign: Campaign): Flow<CampaignTimeSummary> = observe(campaign)
        .map { records -> statistics(campaign, records) }.flowOn(Dispatchers.Default)

    internal fun statistics(campaign: Campaign, records: Map<Int, LevelPlayTime>): CampaignTimeSummary =
        CampaignTimeStatistics.aggregate(campaign, CampaignTimeSnapshot(identity(campaign),
            campaign.levels.associate { level ->
                val time = records.getValue(level.number)
                level.number to LevelTimeMeasurement(level.word, time.cumulativeActiveMs, time.lastSequence > 0,
                    time.timeToRMs, time.timeToFullMs)
            }))

    /**
     * Обязателен до первого сегмента. starsBeforeTracking — реальные звёзды из
     * сохранения на момент подключения таймера. Повторное открытие не меняет замеры.
     * Уже достигнутые R/M помечаются accounted с null, а не рекордом 0 мс.
     */
    override suspend fun initialize(campaign: Campaign, levelNumber: Int, starsBeforeTracking: Int): LevelPlayTime {
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
            } else if (starsBeforeTracking > previous.lastStars) {
                // Например, процесс погиб между записью прогресса и checkpoint времени.
                // При восстановлении неизвестно время достижения: порог учитываем без замера.
                preferences[key(campaign, level)] = json.encodeToString(previous.copy(
                    lastStars = starsBeforeTracking,
                    rAccounted = previous.rAccounted || starsBeforeTracking >= level.requiredStars,
                    fullAccounted = previous.fullAccounted || starsBeforeTracking >= level.maxStars,
                ))
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
    override suspend fun appendSegment(
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

    /** Явный сброс только файла времени, включая прежние кампании. */
    suspend fun clearTimeStatistics() {
        decodedSnapshot = null
        dataStore.edit { it.clear() }
    }

    /** Идентичность по словам уровней: SHA файла и порядок уровней не участвуют. */
    private fun identity(campaign: Campaign): String {
        require(campaign.levels.map { it.number }.distinct().size == campaign.levels.size)
        return CampaignIdentity.of(campaign)
    }

    private fun level(campaign: Campaign, number: Int): CampaignLevel {
        identity(campaign)
        return requireNotNull(campaign.levels.singleOrNull { it.number == number }) { "Unknown level" }
    }

    /** Новый ключ адресуется словом уровня, а не его номером: перестановка не теряет время. */
    private fun key(campaign: Campaign, level: CampaignLevel): Preferences.Key<String> {
        require(level.number > 0 && level.word.isNotBlank())
        require(level.requiredStars in 1..level.maxStars)
        return stringPreferencesKey("playTimeV1:${identity(campaign)}:${level.word}")
    }

    /**
     * Прежний формат: `playTimeV1:<sha-файла>:<номер>:<слово>`. Читаем его без записи,
     * пока для слова нет нового ключа. Если подходящих записей несколько и они разные,
     * не выбираем наугад: уровень считается неизмеренным, старые ключи не удаляются.
     */
    private fun legacyRaw(preferences: Preferences, level: CampaignLevel): String? {
        val suffix = ":${level.word}"
        return preferences.asMap().entries.asSequence()
            .filter { (key, _) ->
                key.name.startsWith("playTimeV1:") && key.name.endsWith(suffix) &&
                    !key.name.startsWith("playTimeV1:${CampaignIdentity.PREFIX}")
            }
            .map { it.value as String }.toSet().singleOrNull()
    }

    private fun read(preferences: Preferences, campaign: Campaign, level: CampaignLevel): LevelPlayTime {
        val raw = preferences[key(campaign, level)] ?: legacyRaw(preferences, level) ?: return LevelPlayTime()
        val value = json.decodeFromString<LevelPlayTime>(raw)
        // Отсутствующие поля старых DTO имеют defaults; запись без initialized невалидна.
        require(value.version == 1 && value.initialized) { "Invalid timing schema" }
        require(value.cumulativeActiveMs >= 0L && value.lastSequence >= 0L)
        require(value.lastStars in 0..level.maxStars)
        require(value.timeToRMs == null || value.timeToRMs in 0L..value.cumulativeActiveMs)
        require(value.timeToFullMs == null || value.timeToFullMs in 0L..value.cumulativeActiveMs)
        require(value.timeToRMs == null || value.timeToFullMs == null || value.timeToRMs <= value.timeToFullMs)
        require(value.lastSequence != 0L || (value.cumulativeActiveMs == 0L && value.timeToRMs == null && value.timeToFullMs == null))
        // Пороги и число заданий могли измениться. Флаги не отнимают уже учтённые
        // достижения и не превращают старый замер в новый.
        return value.copy(
            rAccounted = value.rAccounted || value.lastStars >= level.requiredStars,
            fullAccounted = value.fullAccounted || value.lastStars >= level.maxStars,
        )
    }
}
