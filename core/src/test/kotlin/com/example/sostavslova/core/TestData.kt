package com.example.sostavslova.core

import java.security.MessageDigest
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
        if (manifest.has("campaign_source")) {
            val campaignText = text("campaign.json")
            val sha = MessageDigest.getInstance("SHA-256").digest(campaignText.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            CampaignDatasetAudit.decode(JSONObject(campaignText))
                .copy(contentSha256 = sha)
        } else null
    }
    val campaignProfile: String? by lazy {
        manifest.optJSONObject("campaign_source")?.optString("profile")?.takeIf { it.isNotBlank() }
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
