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
    fun slotsInfoForEmptyPattern() {
        assertEquals(HintText.SlotsInfo(0, emptyList()), HintText.slotsInfo(""))
    }

    @Test
    fun slotsInfoWithoutOpenedLetters() {
        assertEquals(HintText.SlotsInfo(9, emptyList()), HintText.slotsInfo("_________"))
    }

    @Test
    fun slotsInfoWithOneOpenedLetterUsesOneBasedPositionAndUppercase() {
        assertEquals(HintText.SlotsInfo(3, listOf(HintText.OpenSlot(2, 'Б'))), HintText.slotsInfo("_б_"))
        assertEquals(HintText.SlotsInfo(21, listOf(HintText.OpenSlot(21, 'Я'))),
            HintText.slotsInfo("_".repeat(20) + "я"))
    }

    @Test
    fun slotsInfoWithSeveralOpenedLettersPreservesOrder() {
        assertEquals(HintText.SlotsInfo(9, listOf(HintText.OpenSlot(1, 'П'), HintText.OpenSlot(4, 'О'))),
            HintText.slotsInfo("п__о_____"))
        assertEquals(HintText.SlotsInfo(7, listOf(
            HintText.OpenSlot(1, 'П'), HintText.OpenSlot(4, 'О'), HintText.OpenSlot(7, 'Я'),
        )), HintText.slotsInfo("п__о__я"))
    }

    @Test
    fun slotsInfoIncludesReadyT5Ending() {
        assertEquals(HintText.SlotsInfo(7, listOf(HintText.OpenSlot(6, 'К'), HintText.OpenSlot(7, 'А'))),
            HintText.slotsInfo("_____ка"))
    }

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
