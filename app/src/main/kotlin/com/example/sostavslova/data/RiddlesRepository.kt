package com.example.sostavslova.data

import com.example.sostavslova.BuildConfig
import com.example.sostavslova.core.Alphabet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

fun interface RiddlesRepository {
    /** Missing or invalid mandatory content fails loading; never substitutes another clue. */
    suspend fun textFor(word: String): String
}

/** Reads the verified UTF-8 asset directly. No installation or app-private cache. */
class JsonRiddlesRepository(
    private val readAsset: () -> ByteArray,
    private val expectedSha256: String = BuildConfig.RIDDLES_SHA256,
) : RiddlesRepository {
    private val mutex = Mutex()
    private var cached: Map<String, String>? = null

    override suspend fun textFor(word: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val texts = cached ?: parse(readAsset()).also { cached = it }
            requireNotNull(texts[Alphabet.normalize(word)]) { "Missing approved riddle" }
        }
    }

    private fun parse(bytes: ByteArray): Map<String, String> {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(expectedSha256.isNotEmpty() && sha == expectedSha256) { "Riddle asset SHA mismatch" }
        val raw = bytes.decodeToString(throwOnInvalidSequence = true)
        val value = Json.parseToJsonElement(raw).jsonObject
        require(value.keys == setOf("schema", "version", "license", "riddles"))
        require(value.getValue("schema").jsonPrimitive.content == "owned-riddles")
        require(value.getValue("version").toString() == "1")
        require(value.getValue("license").jsonPrimitive.content == "CC0-1.0")
        val texts = value.getValue("riddles").jsonObject
        require(texts.keys == setOf("работа", "утро", "отель", "десерт", "проблема", "билет",
            "ковер", "весна", "стадион", "банк", "артист"))
        require(texts.all { (key, text) -> key == Alphabet.normalize(key) && text.jsonPrimitive.isString &&
            text.jsonPrimitive.content.isNotBlank() })
        // Exact canonical serialization also rejects duplicate JSON fields.
        val canonical = JsonObject(value.toSortedMap().mapValues { (key, element) ->
            if (key == "riddles") JsonObject(texts.toSortedMap()) else element
        }).toString() + "\n"
        require(raw == canonical) { "Noncanonical riddle JSON" }
        return texts.mapValues { it.value.jsonPrimitive.content }
    }
}
