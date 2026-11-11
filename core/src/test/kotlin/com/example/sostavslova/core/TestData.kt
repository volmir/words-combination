package com.example.sostavslova.core

import org.json.JSONArray
import org.json.JSONObject

object TestData {
    private fun text(name: String): String =
        checkNotNull(TestData::class.java.classLoader.getResourceAsStream(name)) { "нет ресурса $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    val wordsText: String by lazy { text("words.txt") }
    val index: WordIndex by lazy { WordIndex.fromText(wordsText) }
    val manifest: JSONObject by lazy { JSONObject(text("manifest.json")) }
    val golden: JSONObject by lazy { JSONObject(text("golden_search.json")) }
    val campaign: Campaign? by lazy {
        if (manifest.has("campaign_source")) CampaignDatasetAudit.decode(JSONObject(text("campaign.json"))) else null
    }
    val currentSourceWords: Set<String> get() = campaign?.levels?.map { it.word }?.toSet()
        ?: levels.map { it.word }.toSet()

    val levels: List<Level> by lazy {
        // Immutable v1 fixture tests legacy rules; v2 may replace its sources.
        val arr = JSONArray(text("legacy_levels_v1.json"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Level(
                index = o.getInt("index"),
                word = o.getString("word"),
                answers = o.getInt("answers"),
                words = o.getInt("words"),
                letter = o.getString("letter")[0],
                letterCount = o.getInt("letterCount"),
            )
        }
    }
}
