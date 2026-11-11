package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.AnswerResult
import com.example.sostavslova.core.Campaign
import com.example.sostavslova.core.CampaignLevelProgress
import com.example.sostavslova.core.CampaignProgress
import com.example.sostavslova.core.CampaignRules
import com.example.sostavslova.core.CampaignTask
import com.example.sostavslova.core.CurrentCampaignLevel
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.HintResult
import com.example.sostavslova.core.HintRules
import com.example.sostavslova.core.HintWordData
import com.example.sostavslova.core.KnownWords
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.LevelRules
import com.example.sostavslova.core.TaskRules
import com.example.sostavslova.core.TaskSubmission
import com.example.sostavslova.core.TaskType
import com.example.sostavslova.core.WordIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class CurrentGame(val word: String, val found: List<String> = emptyList(), val version: Int = 1)

data class LevelSessionSnapshot(val found: List<String>, val progress: LevelProgress)

/** Переходы из той же транзакции, что и ответ; восстановление UI их не повторяет. */
data class CampaignAnswerUpdate(
    val submission: TaskSubmission,
    val openedNextLevel: Boolean,
    val reachedFinale: Boolean,
)

class ProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val writeMutex = Mutex()
    private val operations = mutableSetOf<Job>()
    private val failedWrites = mutableListOf<suspend () -> Unit>()

    /** Регистрируется сразу при принятии UI-события, до фоновой подготовки записи. */
    fun trackOperation(job: Job) {
        synchronized(operations) { operations.add(job) }
        job.invokeOnCompletion { synchronized(operations) { operations.remove(job) } }
    }

    private suspend fun <T> committedWrite(block: suspend () -> T): T =
        withContext(NonCancellable + computationDispatcher) {
            writeMutex.withLock {
                try { block() }
                catch (error: Exception) {
                    // Транзакции повторяются с исходными аргументами. Ответы объединяются,
                    // а расход подсказки защищён expectedShownSteps (SAVE-01).
                    failedWrites.add { block(); Unit }
                    throw error
                }
            }
        }

    /** Барьер выхода: подготовка событий, принятые записи, повтор ошибок и DataStore. */
    suspend fun awaitSaved() {
        while (true) {
            val pending = synchronized(operations) { operations.toList() }
            if (pending.isEmpty()) break
            pending.forEach { it.join() }
        }
        withContext(computationDispatcher) {
            writeMutex.withLock {
                while (failedWrites.isNotEmpty()) {
                    failedWrites.first().invoke()
                    failedWrites.removeAt(0)
                }
                // Даже при отсутствии изменений проверяет доступность хранилища.
                dataStore.edit { }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val currentGameKey = stringPreferencesKey("currentGame")
    private val levelProgressKey = stringPreferencesKey("levelProgress")
    private val levelAnswersKey = stringPreferencesKey("levelAnswers")
    private val legacyLevelsKey = stringPreferencesKey("levelSessionV1")
    private val campaignProgressKey = stringPreferencesKey("campaignProgressV2")

    /** Один снимок для уровня, доступа, подсказок и итогов CampaignRules.summary. */
    fun observeCampaignProgress(): Flow<CampaignProgress> = dataStore.data
        .map { readCampaign(it) }
        .distinctUntilChanged().flowOn(computationDispatcher)

    /**
     * Вызывать после загрузки настоящей кампании v2 и нового словаря, до событий v2.
     * Чтение v1 и временный адаптер уровней не запускают миграцию. Повторный вызов
     * возвращает уже записанный снимок без пересчёта и без изменения ключей v1.
     */
    suspend fun migrateCampaign(campaign: Campaign, index: WordIndex): CampaignProgress {
        require(campaign.version == 2 && campaign.levels.isNotEmpty()) { "Real v2 campaign is required" }
        require(campaign.levels.map { it.number } == (1..campaign.levels.size).toList())
        val sources = campaign.levels.map { Alphabet.normalize(it.word) }
        require(sources == campaign.levels.map { it.word } && sources.distinct().size == sources.size) {
            "Campaign source words must be canonical and unambiguous"
        }
        currentCoroutineContext().ensureActive()
        return committedWrite {
            val committed = dataStore.edit { preferences ->
                if (preferences[campaignProgressKey] != null) {
                    // Проверка также запрещает перезаписывать будущую версию или битый v2.
                    readCampaign(preferences)
                    return@edit
                }
                // В отличие от старых наблюдателей, здесь нет fallback на пустые данные.
                validateLegacyPayloads(preferences)
                val transitional = readLegacyLevels(preferences)
                val progress = transitional.progress + decodeLegacy<Map<String, StoredLevelProgress>>(
                    preferences[levelProgressKey], emptyMap(),
                )
                val answers = transitional.answers + decodeLegacy<Map<String, List<String>>>(
                    preferences[levelAnswersKey], emptyMap(),
                )
                val current = decodeLegacy<CurrentGame?>(preferences[currentGameKey], null)
                if (progress.values.any { it.version != 1 } || (current != null && current.version != 1)) {
                    throw SerializationException("Unsupported legacy progress version")
                }
                val completedWords = progress.filterValues { it.completed }.keys
                    .map(Alphabet::normalize).toSet()
                val groupedAnswers = answers.entries.groupBy { Alphabet.normalize(it.key) }
                val recordedWords = (progress.keys + answers.keys).map(Alphabet::normalize).toSet()
                val currentWord = current?.word?.let(Alphabet::normalize)
                val migrated = campaign.levels.mapNotNull { level ->
                    val word = level.word
                    if (!LegacyCampaignV1.contains(word)) return@mapNotNull null
                    // currentGame не содержит режима. Без отдельной записи реального
                    // уровня нельзя отличить его от свободной игры на том же слове.
                    val currentFound = if (word == currentWord && word in recordedWords) {
                        current?.found.orEmpty()
                    } else emptyList()
                    val restored = groupedAnswers[word].orEmpty().flatMap { it.value } + currentFound
                    if (restored.isEmpty()) return@mapNotNull null
                    val game = GameState.start(word, index, restored.map(Alphabet::normalize))
                    if (game.found.isEmpty()) return@mapNotNull null
                    val evaluated = TaskRules.evaluate(CurrentCampaignLevel(level, game), campaign.countMinLength)
                    word to CampaignLevelProgress(
                        found = game.found,
                        completedTaskIds = evaluated.state.completedTaskIds,
                        solvedRiddleTargets = level.tasks.filterIsInstance<CampaignTask.T4>()
                            .flatMap { it.targets }.filter { it in game.found }.toSet(),
                    )
                }.toMap()
                val saved = CampaignProgress(
                    levels = migrated,
                    highestUnlockedLevel = LegacyCampaignV1.highestUnlocked(completedWords)
                        .coerceAtMost(campaign.levels.size),
                    // Старый completed не означает финал v2. Здесь нет recordLevel:
                    // пересчёт не расширяет старую границу и не запускает цепочку открытий.
                )
                preferences[campaignProgressKey] = json.encodeToString(CampaignProgressDto.fromCore(saved))
                preferences.remove(legacyLevelsKey)
                preferences.remove(currentGameKey)
                preferences.remove(levelProgressKey)
                preferences.remove(levelAnswersKey)
            }
            readCampaign(committed)
        }
    }

    /** Возвращает подтверждённый результат; UI не должен отдельно сохранять звёзды/доступ. */
    suspend fun submitCampaignAnswer(
        campaign: Campaign,
        levelNumber: Int,
        raw: String,
        index: WordIndex,
        knownWords: KnownWords? = null,
    ): TaskSubmission = submitCampaignAnswerWithTransitions(campaign, levelNumber, raw, index, knownWords).submission

    suspend fun submitCampaignAnswerWithTransitions(
        campaign: Campaign,
        levelNumber: Int,
        raw: String,
        index: WordIndex,
        knownWords: KnownWords? = null,
    ): CampaignAnswerUpdate = editCampaign { saved ->
        val current = openCampaignLevel(campaign, saved, levelNumber, index)
        val submitted = TaskRules.submit(current, raw, campaign.countMinLength, index, knownWords)
        val next = if (submitted.result == AnswerResult.CORRECT) {
            CampaignRules.recordLevel(campaign, saved, submitted.evaluation.state)
        } else saved
        next to CampaignAnswerUpdate(submitted,
            openedNextLevel = next.highestUnlockedLevel > saved.highestUnlockedLevel,
            reachedFinale = next.finaleReached && !saved.finaleReached)
    }

    /**
     * expectedShownSteps берётся из наблюдаемого снимка при нажатии. Повтор того же
     * события (в том числе после перезапуска) возвращает null без нового расхода.
     * Отдельное нажатие после подтверждения использует новый shownSteps.
     */
    suspend fun requestCampaignHint(
        campaign: Campaign,
        levelNumber: Int,
        expectedShownSteps: Int,
        index: WordIndex,
        words: Map<String, HintWordData> = emptyMap(),
    ): HintResult? = editCampaign { saved ->
        val current = openCampaignLevel(campaign, saved, levelNumber, index)
        if (current.hints.shownSteps != expectedShownSteps) saved to null
        else {
            val hint = HintRules.next(current, words, campaign.countMinLength)
            CampaignRules.recordLevel(campaign, saved, hint.state) to hint
        }
    }

    /** Посещение не изменяет границу открытия и не подменяет правило «Продолжить». */
    suspend fun visitCampaignLevel(campaign: Campaign, levelNumber: Int) = editCampaign { saved ->
        require(CampaignRules.isUnlocked(campaign, saved, levelNumber)) { "Level is locked or unknown" }
        saved.copy(lastVisitedLevel = levelNumber) to Unit
    }

    /** true разрешает показ только после успешной записи; повторный показ не нужен. */
    suspend fun markCampaignExplanationSeen(type: TaskType): Boolean = editCampaign { saved ->
        saved.copy(seenExplanations = saved.seenExplanations + type) to (type !in saved.seenExplanations)
    }

    /** Подтверждение закрытого диалога. При открытии экрана этот метод не вызывается. */
    suspend fun consumeCampaignFinaleDialog(): Boolean = editCampaign { saved ->
        val presentation = CampaignRules.consumeFinaleDialog(saved)
        presentation.state to presentation.showDialog
    }

    private fun openCampaignLevel(
        campaign: Campaign, saved: CampaignProgress, levelNumber: Int, index: WordIndex,
    ): CurrentCampaignLevel = requireNotNull(CampaignRules.openLevel(campaign, saved, levelNumber, index)) {
        "Level is locked or unknown"
    }

    private fun readCampaign(preferences: Preferences): CampaignProgress {
        val raw = preferences[campaignProgressKey] ?: return CampaignProgress()
        // Повреждённый или будущий формат не превращаем в пустое сохранение на записи.
        return json.decodeFromString<CampaignProgressDto>(raw).toCore()
    }

    private suspend fun <T> editCampaign(
        change: (CampaignProgress) -> Pair<CampaignProgress, T>,
    ): T {
        currentCoroutineContext().ensureActive()
        // После принятия события навигация не отменяет запись. Ошибки DataStore
        // передаются вызывающему коду; результат публикуется только после edit.
        return committedWrite {
            var result: Pair<CampaignProgress, T>? = null
            dataStore.edit { preferences ->
                check(preferences[campaignProgressKey] != null || !hasLegacyProgress(preferences)) {
                    "Migrate legacy progress with the real v2 campaign before writing campaign events"
                }
                val saved = readCampaign(preferences)
                val changed = change(saved)
                if (changed.first != saved) {
                    preferences[campaignProgressKey] = json.encodeToString(CampaignProgressDto.fromCore(changed.first))
                }
                result = changed
            }
            checkNotNull(result).second
        }
    }

    @Serializable
    private data class LegacyLevels(
        val answers: Map<String, List<String>> = emptyMap(),
        val progress: Map<String, StoredLevelProgress> = emptyMap(),
        val version: Int = 1,
    )

    /** Переходная v1: старые ключи читаются один раз, свободная игра не переносится. */
    suspend fun migrateLegacyLevels(levels: List<Level>) = committedWrite {
        dataStore.edit { preferences ->
            if (preferences[legacyLevelsKey] != null) return@edit
            validateLegacyPayloads(preferences)
            val rawAnswers = decodeLegacy<Map<String, List<String>>>(preferences[levelAnswersKey], emptyMap())
            val rawStars = decodeLegacy<Map<String, StoredLevelProgress>>(preferences[levelProgressKey], emptyMap())
            val current = decodeLegacy<CurrentGame?>(preferences[currentGameKey], null)
            require(rawStars.values.all { it.version == 1 } && (current == null || current.version == 1))
            val answers = rawAnswers.entries.groupBy { Alphabet.normalize(it.key) }
                .mapValues { (_, entries) -> entries.flatMap { it.value }.map(Alphabet::normalize).distinct() }.toMutableMap()
            val stars = rawStars.entries.groupBy { Alphabet.normalize(it.key) }.mapValues { (_, entries) ->
                StoredLevelProgress.fromCore(entries.fold(LevelProgress()) { result, entry -> result merge entry.value.toCore() })
            }
            // Слово из currentGame переносится только при отдельной записи реального уровня.
            for (level in levels) {
                if (current != null && Alphabet.normalize(current.word) == level.word &&
                    (level.word in answers || level.word in stars)) {
                    answers[level.word] = (answers[level.word].orEmpty() + current.found).map(Alphabet::normalize).distinct()
                }
            }
            preferences[legacyLevelsKey] = json.encodeToString(LegacyLevels(answers, stars))
            preferences.remove(currentGameKey)
            preferences.remove(levelAnswersKey)
            preferences.remove(levelProgressKey)
        }
        Unit
    }

    private fun readLegacyLevels(preferences: Preferences): LegacyLevels =
        preferences[legacyLevelsKey]?.let { raw ->
            json.decodeFromString<LegacyLevels>(raw).also { require(it.version == 1) { "Unsupported v1 session version" } }
        } ?: LegacyLevels()

    fun observeLevelProgress(): Flow<Map<String, LevelProgress>> = dataStore.data
        .map { readLegacyLevels(it).progress.mapValues { entry -> entry.value.toCore() } }
        .distinctUntilChanged().flowOn(computationDispatcher)

    /** Ответы и звёзды одного уровня из одной подтверждённой транзакции v1. */
    fun observeLevelSession(word: String): Flow<LevelSessionSnapshot> = dataStore.data
        .map { preferences ->
            val saved = readLegacyLevels(preferences)
            val source = Alphabet.normalize(word)
            LevelSessionSnapshot(saved.answers[source].orEmpty(),
                saved.progress[source]?.toCore() ?: LevelProgress())
        }.distinctUntilChanged().flowOn(computationDispatcher)

    suspend fun clearAll() = committedWrite {
        dataStore.edit { preferences ->
            preferences.remove(currentGameKey)
            preferences.remove(levelProgressKey)
            preferences.remove(levelAnswersKey)
            preferences.remove(legacyLevelsKey)
            preferences.remove(campaignProgressKey)
        }
        Unit
    }

    /** Звёзды и ответы v1 сохраняются одной транзакцией, которую выход с экрана не отменяет. */
    suspend fun saveLevelResult(level: Level, found: List<String>) = committedWrite {
        dataStore.edit { preferences ->
            val saved = readLegacyLevels(preferences)
            val restored = (saved.answers[level.word].orEmpty() + found).distinct()
            val previous = saved.progress[level.word]?.toCore() ?: LevelProgress()
            val merged = previous merge LevelRules.evaluate(level, restored)
            preferences[legacyLevelsKey] = json.encodeToString(saved.copy(
                answers = saved.answers + (level.word to restored),
                progress = saved.progress + (level.word to StoredLevelProgress.fromCore(merged)),
            ))
        }
        Unit
    }

    suspend fun levelAnswers(word: String): List<String> = withContext(computationDispatcher) {
        readLegacyLevels(dataStore.data.first()).answers[Alphabet.normalize(word)].orEmpty()
    }

    private inline fun <reified T> decodeLegacy(raw: String?, absent: T): T =
        if (raw == null) absent else json.decodeFromString<T>(raw)
            ?: throw SerializationException("Null legacy payload")

    /** JSON-декодер допускает некоторые преобразования скаляров (например, "true"
     * в Boolean). При миграции это не повод уничтожать исходный повреждённый payload. */
    private fun validateLegacyPayloads(preferences: Preferences) {
        fun readObject(key: Preferences.Key<String>): JsonObject? =
            preferences[key]?.let { json.parseToJsonElement(it).jsonObject }
        fun strings(value: JsonElement) {
            value.jsonArray.forEach {
                if (it !is JsonPrimitive || !it.isString) throw SerializationException("Expected legacy string")
            }
        }
        fun version(entry: JsonObject) {
            entry["version"]?.jsonPrimitive?.let {
                if (it.isString || it.intOrNull == null) throw SerializationException("Expected legacy version number")
            }
        }
        readObject(currentGameKey)?.let { entry ->
            val word = entry["word"]
            if (word !is JsonPrimitive || !word.isString) throw SerializationException("Expected legacy source word")
            entry["found"]?.let(::strings)
            version(entry)
        }
        readObject(levelAnswersKey)?.values?.forEach(::strings)
        readObject(levelProgressKey)?.values?.forEach { value ->
            val entry = value.jsonObject
            for (key in listOf("completed", "wordsTask", "lettersTask")) {
                entry[key]?.jsonPrimitive?.let {
                    if (it.isString || it.booleanOrNull == null) throw SerializationException("Expected legacy flag")
                }
            }
            version(entry)
        }
    }

    private fun hasLegacyProgress(preferences: Preferences): Boolean =
        preferences[currentGameKey] != null || preferences[levelProgressKey] != null ||
            preferences[levelAnswersKey] != null || preferences[legacyLevelsKey] != null

    // DTO сохраняет :core независимым от Android и kotlinx-serialization.
    @Serializable
    private data class StoredLevelProgress(
        val completed: Boolean = false,
        val wordsTask: Boolean = false,
        val lettersTask: Boolean = false,
        val version: Int = 1,
    ) {
        fun toCore() = LevelProgress(completed, wordsTask, lettersTask)

        companion object {
            fun fromCore(progress: LevelProgress) = StoredLevelProgress(
                progress.completed, progress.wordsTask, progress.lettersTask,
            )
        }
    }
}
