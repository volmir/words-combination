package com.example.sostavslova.data

import com.example.sostavslova.core.Level
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
) {
    private val mutex = Mutex()
    private var cached: List<Level>? = null

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
