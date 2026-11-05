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

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var random: kotlin.random.Random = kotlin.random.Random.Default
        set(value) {
            field = value
            wordRepository.random = value
        }

    val wordRepository = WordRepository(readWords = { readAsset("words.txt") }, random = random)
    val levelsRepository = LevelsRepository(readLevels = { readAsset("levels.json") })
    val definitionsRepository: DefinitionsRepository = SqliteDefinitionsRepository(appContext)

    private val progressDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
            produceFile = { appContext.preferencesDataStoreFile("progress") },
        )
    }
    val progressRepository: ProgressRepository by lazy { ProgressRepository(progressDataStore) }

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
