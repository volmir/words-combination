package com.example.sostavslova.data

import com.example.sostavslova.core.Level
import com.example.sostavslova.core.Campaign
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

class LevelsRepository(
    private val readLevels: suspend () -> String,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readCampaign: suspend () -> String? = { null },
) {
    private val mutex = Mutex()
    private var cached: List<Level>? = null
    private var campaignLoaded = false
    private var cachedCampaign: Campaign? = null

    /** null means v2 is absent in this transitional build. Read/parse/validation failures propagate. */
    suspend fun getCampaign(): Campaign? = mutex.withLock {
        if (campaignLoaded) return@withLock cachedCampaign
        val text = withContext(ioDispatcher) { readCampaign() }
        val campaign = withContext(computationDispatcher) { text?.let(CampaignJson::decode) }
        cachedCampaign = campaign
        campaignLoaded = true
        campaign
    }

    /** Original v1 data and rules remain independent of v2; this is not an error fallback. */
    suspend fun getLevels(): List<Level> = mutex.withLock {
        cached ?: run {
            val text = withContext(ioDispatcher) { readLevels() }
            withContext(computationDispatcher) {
                val array = JSONArray(text)
                List(array.length()) { i ->
                    val entry = array.getJSONObject(i)
                    Level(
                        index = entry.getInt("index"),
                        word = entry.getString("word"),
                        answers = entry.getInt("answers"),
                        words = entry.getInt("words"),
                        letter = entry.getString("letter").single(),
                        letterCount = entry.getInt("letterCount"),
                    )
                }
            }.also { cached = it }
        }
    }
}
