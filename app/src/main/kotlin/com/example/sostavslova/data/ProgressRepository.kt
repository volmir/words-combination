package com.example.sostavslova.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sostavslova.core.Alphabet
import com.example.sostavslova.core.GameState
import com.example.sostavslova.core.Level
import com.example.sostavslova.core.LevelProgress
import com.example.sostavslova.core.LevelRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

@Serializable
data class CurrentGame(val word: String, val found: List<String>)

class ProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val currentGameKey = stringPreferencesKey("currentGame")
    private val levelProgressKey = stringPreferencesKey("levelProgress")
    private val levelAnswersKey = stringPreferencesKey("levelAnswers")

    fun observeCurrentGame(): Flow<CurrentGame?> = dataStore.data
        .map { decode<CurrentGame?>(it[currentGameKey], null) }
        .distinctUntilChanged().flowOn(computationDispatcher)

    fun observeLevelProgress(): Flow<Map<String, LevelProgress>> = dataStore.data
        .map { preferences ->
            decode<Map<String, StoredLevelProgress>>(preferences[levelProgressKey], emptyMap())
                .mapValues { it.value.toCore() }
        }
        .distinctUntilChanged().flowOn(computationDispatcher)

    suspend fun saveGame(state: GameState) = withContext(computationDispatcher) {
        val saved = CurrentGame(state.word, state.found.toList())
        dataStore.edit { it[currentGameKey] = json.encodeToString(saved) }
        Unit
    }

    suspend fun clearCurrentGame() {
        dataStore.edit { it.remove(currentGameKey) }
    }

    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.remove(currentGameKey)
            preferences.remove(levelProgressKey)
            preferences.remove(levelAnswersKey)
        }
    }

    /** Звёзды и ответы обновляются одной транзакцией, включая параллельные сохранения. */
    suspend fun saveLevelResult(level: Level, found: List<String>) = withContext(computationDispatcher) {
        dataStore.edit { preferences ->
            val answers = decode<Map<String, List<String>>>(preferences[levelAnswersKey], emptyMap()).toMutableMap()
            val restored = (answers[level.word].orEmpty() + found).distinct()
            answers[level.word] = restored

            val progress = decode<Map<String, StoredLevelProgress>>(preferences[levelProgressKey], emptyMap()).toMutableMap()
            val previous = progress[level.word]?.toCore() ?: LevelProgress()
            val merged = previous merge LevelRules.evaluate(level, restored)
            progress[level.word] = StoredLevelProgress.fromCore(merged)

            preferences[levelProgressKey] = json.encodeToString(progress.toMap())
            preferences[levelAnswersKey] = json.encodeToString(answers.toMap())
        }
        Unit
    }

    suspend fun levelAnswers(word: String): List<String> = withContext(computationDispatcher) {
        val preferences = dataStore.data.first()
        decode<Map<String, List<String>>>(preferences[levelAnswersKey], emptyMap())
            .get(Alphabet.normalize(word)).orEmpty().toList()
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        if (raw == null) fallback else try {
            json.decodeFromString<T>(raw)
        } catch (_: SerializationException) {
            fallback
        }

    // DTO сохраняет :core независимым от Android и kotlinx-serialization.
    @Serializable
    private data class StoredLevelProgress(
        val completed: Boolean = false,
        val wordsTask: Boolean = false,
        val lettersTask: Boolean = false,
    ) {
        fun toCore() = LevelProgress(completed, wordsTask, lettersTask)

        companion object {
            fun fromCore(progress: LevelProgress) = StoredLevelProgress(
                progress.completed, progress.wordsTask, progress.lettersTask,
            )
        }
    }
}
