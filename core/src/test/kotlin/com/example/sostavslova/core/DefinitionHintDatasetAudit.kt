package com.example.sostavslova.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Проверяет экспорт локальной БД тем же обработчиком, который использует игра. */
object DefinitionHintDatasetAudit {
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Нужны входной и выходной JSONL" }
        val output = File(args[1])
        output.parentFile?.mkdirs()
        var words = 0
        var definitions = 0
        var useful = 0
        var masked = 0
        output.bufferedWriter().use { writer ->
            File(args[0]).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    val row = JSONObject(line)
                    if (index == 0) {
                        require(row.getString("schema") == "definition-hint-audit-v1")
                        require(row.getString("masker_version") == DefinitionHint.VERSION)
                        writer.appendLine(row.toString())
                    } else {
                        val word = row.getString("word")
                        val forms = row.getJSONArray("forms").strings()
                        val related = row.getJSONArray("related").strings()
                        val texts = row.getJSONArray("definitions").strings()
                        val hint = texts.firstNotNullOfOrNull { DefinitionHint.prepare(word, it, forms, related) }
                        words++
                        if (texts.isNotEmpty()) definitions++
                        if (hint != null) {
                            useful++
                            if (DefinitionHint.MASK in hint) masked++
                        }
                        writer.appendLine(JSONObject().put("word", word).put("hint", hint ?: JSONObject.NULL)
                            .put("has_definition", texts.isNotEmpty()).toString())
                    }
                }
            }
        }
        val summary = JSONObject().put("words", words).put("with_definitions", definitions)
            .put("with_useful_hints", useful).put("masked_hints", masked)
            .put("without_useful_hint", words - useful).put("masker_version", DefinitionHint.VERSION)
        File(output.path + ".summary.json").writeText(summary.toString(2) + "\n")
        println(summary.toString(2))
    }
}
