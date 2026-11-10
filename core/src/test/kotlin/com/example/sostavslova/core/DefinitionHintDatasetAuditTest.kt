package com.example.sostavslova.core

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DefinitionHintDatasetAuditTest {
    @Test
    fun comparesCoverageMasksAndLengthsWithoutExternalDump() {
        val directory = Files.createTempDirectory("hint-audit").toFile()
        try {
            val input = directory.resolve("input.jsonl")
            val output = directory.resolve("output.jsonl")
            fun row(word: String, vararg texts: String) = JSONObject().put("word", word)
                .put("forms", JSONArray()).put("related", JSONArray()).put("definitions", JSONArray(texts))
            input.writeText(listOf(
                JSONObject().put("schema", "definition-hint-audit-v1").put("masker_version", DefinitionHint.VERSION),
                row("зонт", "Действие по значению гл. укрывать; предмет для защиты от дождя."),
                row("дерево", "Дерево служит условным примером."),
                row("образец", "Свойство по значению прил. условный."),
                row("пример"),
            ).joinToString("\n"))
            DefinitionHintDatasetAudit.main(arrayOf(input.path, output.path))
            val summary = JSONObject(directory.resolve("output.jsonl.summary.json").readText())
            val before = summary.getJSONObject("before")
            val after = summary.getJSONObject("after")
            assertEquals(4, summary.getInt("words"))
            assertEquals(3, summary.getInt("with_definitions"))
            assertEquals(2, before.getInt("with_useful_hints"))
            assertEquals(2, after.getInt("with_useful_hints"))
            assertEquals(1, summary.getInt("gained_useful_hints"))
            assertEquals(1, summary.getInt("lost_useful_hints"))
            assertEquals(0.5, after.getDouble("masked_share"))
            assertEquals(2.0 / 3, after.getDouble("useful_share"))
            assertEquals(("Предмет для защиты от дождя.".length + "*** служит условным примером.".length) / 2.0,
                after.getDouble("average_length"))
            val rows = output.readLines().drop(1).map(::JSONObject)
            assertFalse(rows.first().isNull("hint"))
            assertEquals(true, rows.first().isNull("hint_before"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyDatasetHasFiniteZeroMetrics() {
        val directory = Files.createTempDirectory("hint-audit-empty").toFile()
        try {
            val input = directory.resolve("input.jsonl")
            val output = directory.resolve("output.jsonl")
            input.writeText(JSONObject().put("schema", "definition-hint-audit-v1")
                .put("masker_version", DefinitionHint.VERSION).toString())
            DefinitionHintDatasetAudit.main(arrayOf(input.path, output.path))
            val summary = JSONObject(directory.resolve("output.jsonl.summary.json").readText())
            for (stage in listOf("before", "after")) {
                for (metric in listOf("useful_share", "masked_share", "average_length")) {
                    assertEquals(0.0, summary.getJSONObject(stage).getDouble(metric))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
