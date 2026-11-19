package com.example.sostavslova.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.BuildConfig
import com.example.sostavslova.data.RiddlesRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.JsonRiddlesRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.data.readInstallEvidence
import com.example.sostavslova.data.jsonstore.LegacyPreferencesMigration
import com.example.sostavslova.time.PlayTimeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(
    context: Context,
    readCampaign: (suspend () -> String?)? = null,
    words: WordRepository? = null,
    riddles: RiddlesRepository? = null,
    progressFile: (() -> File)? = null,
) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun categoryName(resource: Int): String = appContext.getString(resource)

    var random: kotlin.random.Random = kotlin.random.Random.Default

    val wordRepository = words ?: WordRepository(readWords = { readAsset("words.txt") })
    val levelsRepository = LevelsRepository(
        readCampaign = readCampaign ?: {
            // Check presence separately: an existing but unreadable/damaged v2 must never become v1.
            if ("campaign.json" in appContext.assets.list("").orEmpty()) readAsset("campaign.json") else null
        },
        expectedCampaignSha256 = if (readCampaign == null) BuildConfig.CAMPAIGN_SHA256.takeIf { it.isNotBlank() } else null,
    )
    val riddlesRepository: RiddlesRepository = riddles ?: JsonRiddlesRepository(
        readAsset = { appContext.assets.open("owned_riddles.json").use { it.readBytes() } },
    )

    private val storeDirectory = File(appContext.filesDir, "datastore")
    private val oldProgressFile = File(storeDirectory, "progress.preferences_pb")
    private val progressFileProvider = progressFile ?: { File(storeDirectory, "progress.json") }
    private val migrateOldProgress = progressFile == null && !progressFileProvider().exists()

    // Снимок до первого edit: отсутствие файла не доказывает новую установку,
    // но его наличие подтверждает локальное состояние старого запуска.
    private val hadLocalStateAtStartup = try {
        progressFileProvider().exists() || (progressFile == null && oldProgressFile.exists())
    } catch (_: Exception) {
        true
    }

    private val progressDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
            migrations = if (migrateOldProgress) listOf(LegacyPreferencesMigration(oldProgressFile, true)) else emptyList(),
            produceFile = { progressFileProvider().also { it.parentFile?.mkdirs() } },
        )
    }
    val progressRepository: ProgressRepository by lazy {
        ProgressRepository(
            dataStore = progressDataStore,
            installEvidence = appContext::readInstallEvidence,
            hadLocalStateAtStartup = hadLocalStateAtStartup,
            onCampaignCommitted = { playTimeTracker.checkpointProgress(it) },
        )
    }

    // Независимый файл: записи времени не участвуют в writeMutex/awaitSaved прогресса.
    val playTimeRepository: PlayTimeRepository by lazy {
        PlayTimeRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
                migrations = listOf(LegacyPreferencesMigration(
                    File(storeDirectory, "play_time.preferences_pb"),
                    !File(storeDirectory, "play_time.json").exists(),
                )),
                produceFile = { File(storeDirectory, "play_time.json").also { it.parentFile?.mkdirs() } },
            ),
        )
    }

    val playTimeTracker: PlayTimeTracker by lazy {
        PlayTimeTracker(playTimeRepository, applicationScope, SystemClock::elapsedRealtime) { failure ->
            Log.e("PlayTimeTracker", "level=${failure.levelNumber} operation=${failure.operation} " +
                "pendingMs=${failure.pendingActiveMs} error=${failure.errorType}")
        }
    }

    suspend fun awaitSaved() {
        playTimeTracker.setExiting(true)
        progressRepository.awaitSaved()
        playTimeTracker.awaitSaved()
    }

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
