package com.example.sostavslova.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.sostavslova.data.DefinitionsRepository
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.SqliteDefinitionsRepository
import com.example.sostavslova.data.WordRepository
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
    )
    val definitionsRepository: DefinitionsRepository = definitions ?: SqliteDefinitionsRepository(appContext)

    private val progressDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
            produceFile = progressFile ?: { appContext.preferencesDataStoreFile("progress") },
        )
    }
    val progressRepository: ProgressRepository by lazy { ProgressRepository(progressDataStore) }

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
