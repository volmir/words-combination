package com.example.sostavslova.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Проверяет экспорт локальной БД тем же обработчиком, который использует игра. */
object DefinitionHintDatasetAudit {
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private class Metrics {
        var useful = 0
        var masked = 0
        var length = 0L

        fun record(hint: String?) {
            if (hint == null) return
            useful++
            if (DefinitionHint.MASK in hint) masked++
            length += hint.length
        }

        fun json(words: Int, definitions: Int): JSONObject = JSONObject()
            .put("with_useful_hints", useful).put("without_useful_hint", words - useful)
            .put("useful_share", if (definitions == 0) 0.0 else useful.toDouble() / definitions)
            .put("masked_hints", masked)
            .put("masked_share", if (useful == 0) 0.0 else masked.toDouble() / useful)
            .put("average_length", if (useful == 0) 0.0 else length.toDouble() / useful)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Нужны входной и выходной JSONL" }
        val output = File(args[1])
        output.parentFile?.mkdirs()
        var words = 0
        var definitions = 0
        val before = Metrics()
        val after = Metrics()
        var gained = 0
        var lost = 0
        output.bufferedWriter().use { writer ->
            File(args[0]).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    val row = JSONObject(line)
                    if (index == 0) {
                        require(row.getString("schema") == "definition-hint-audit-v1")
                        require(row.getString("masker_version") == DefinitionHint.VERSION)
                        writer.appendLine(row.put("cleaner_version", HintText.VERSION).toString())
                    } else {
                        val word = row.getString("word")
                        val forms = row.getJSONArray("forms").strings()
                        val related = row.getJSONArray("related").strings()
                        val texts = row.getJSONArray("definitions").strings()
                        val oldHint = texts.firstNotNullOfOrNull { DefinitionHint.prepareUncleaned(word, it, forms, related) }
                        val hint = texts.firstNotNullOfOrNull { DefinitionHint.prepare(word, it, forms, related) }
                        words++
                        if (texts.isNotEmpty()) definitions++
                        before.record(oldHint)
                        after.record(hint)
                        if (oldHint == null && hint != null) gained++
                        if (oldHint != null && hint == null) lost++
                        writer.appendLine(JSONObject().put("word", word).put("hint", hint ?: JSONObject.NULL)
                            .put("hint_before", oldHint ?: JSONObject.NULL)
                            .put("has_definition", texts.isNotEmpty()).toString())
                    }
                }
            }
        }
        // Сохраняем прежние верхнеуровневые счётчики для потребителей отчёта.
        val summary = after.json(words, definitions).put("words", words).put("with_definitions", definitions)
            .put("before", before.json(words, definitions)).put("after", after.json(words, definitions))
            .put("gained_useful_hints", gained).put("lost_useful_hints", lost)
            .put("masker_version", DefinitionHint.VERSION).put("cleaner_version", HintText.VERSION)
        File(output.path + ".summary.json").writeText(summary.toString(2) + "\n")
        println(summary.toString(2))
    }
}
