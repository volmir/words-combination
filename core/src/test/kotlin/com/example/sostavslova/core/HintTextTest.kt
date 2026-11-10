package com.example.sostavslova.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HintTextTest {
    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array ->
        (0 until array.length()).map { array.getString(it) }
    } ?: emptyList()

    @Test
    fun sharedSyntheticVectors() {
        val vectors = JSONArray(File("../tools/hint_vectors.json").readText())
        for (index in 0 until vectors.length()) {
            val row = vectors.getJSONObject(index)
            val id = row.getString("id")
            assertEquals(row.optionalText("clean"), HintText.clean(row.getString("text")), "$id: clean")
            val hint = DefinitionHint.prepare(row.getString("answer"), row.getString("text"),
                row.strings("forms"), row.strings("related"))
            assertEquals(row.optionalText("result"), hint, "$id: prepare")
            if (hint != null) assertTrue(hint.length <= 140, id)
        }
    }
}
