package com.example.sostavslova.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.data.DefinitionsRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.SqliteDefinitionsRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.data.readInstallEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(
    context: Context,
    readCampaign: (suspend () -> String?)? = null,
    words: WordRepository? = null,
    definitions: DefinitionsRepository? = null,
    progressFile: (() -> File)? = null,
) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun categoryName(resource: Int): String = appContext.getString(resource)

    var random: kotlin.random.Random = kotlin.random.Random.Default

    val wordRepository = words ?: WordRepository(readWords = { readAsset("words.txt") })
    val levelsRepository = LevelsRepository(
        readLevels = { readAsset("levels.json") },
        readCampaign = readCampaign ?: {
            // Check presence separately: an existing but unreadable/damaged v2 must never become v1.
            if ("campaign.json" in appContext.assets.list("").orEmpty()) readAsset("campaign.json") else null
        },
        expectedCampaignSha256 = if (readCampaign == null) BuildConfig.CAMPAIGN_SHA256.takeIf { it.isNotBlank() } else null,
    )
    val definitionsRepository: DefinitionsRepository = definitions ?: SqliteDefinitionsRepository(appContext)

    private val progressFileProvider = progressFile ?: { appContext.preferencesDataStoreFile("progress") }

    // Снимок до первого edit: отсутствие файла не доказывает новую установку,
    // но его наличие подтверждает локальное состояние старого запуска.
    private val hadLocalStateAtStartup = try {
        progressFileProvider().exists()
    } catch (_: Exception) {
        true
    }

    private val progressDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
            produceFile = progressFileProvider,
        )
    }
    val progressRepository: ProgressRepository by lazy {
        ProgressRepository(
            dataStore = progressDataStore,
            installEvidence = appContext::readInstallEvidence,
            hadLocalStateAtStartup = hadLocalStateAtStartup,
        )
    }

    // Независимый файл: записи времени не участвуют в writeMutex/awaitSaved прогресса.
    val playTimeRepository: PlayTimeRepository by lazy {
        PlayTimeRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
                produceFile = { appContext.preferencesDataStoreFile("play_time") },
            ),
        )
    }

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
