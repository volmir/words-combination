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

    val levels: List<Level> by lazy {
        val arr = JSONArray(text("levels.json"))
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
