package com.example.sostavslova.core

import java.text.Normalizer
import java.util.Locale

/** Только текст подсказки: исходное толкование и его метаданные не изменяются. */
object HintText {
    const val VERSION = "hint-text-v1"
    const val MAX_LENGTH = 140
    private val link = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private val latinParentheses = Regex("\\([^()]*[A-Za-z][^()]*\\)")
    private val spaces = Regex("\\s+")
    private val label = Regex(
        "^(?:зоол|бот|биол|кул|кулин|разг|перен|шутл|книжн|устар|спец|собир|истор|поэт|прост|" +
            "неодобр|пренебр|ирон|ласк|обл|диал|тех|мед|хим|физ|мат|воен|жарг)\\.\\s*[,;]?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val template = Regex(
        "^(?:то же,?\\s+что(?![А-Яа-яЁё])|(?:действие|свойство|состояние|качество)\\s+по значению(?![А-Яа-яЁё]))",
        RegexOption.IGNORE_CASE,
    )

    fun clean(text: String): String? {
        // Проверяем исходный текст: очистка не должна прятать HTML/URL в удаляемой части.
        if (text.isBlank() || text.length > 4000 || '<' in text || '>' in text || link.containsMatchIn(text)) return null
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)
        result = latinParentheses.replace(result, " ").replace(spaces, " ").trim()
        repeat(4) { result = result.replaceFirst(label, "") }
        while (template.containsMatchIn(result)) {
            result = result.substringAfter(';', "").trim()
            if (result.isEmpty()) return null
            repeat(4) { result = result.replaceFirst(label, "") }
        }
        if (result.isEmpty()) return null
        result = result.replaceFirstChar { it.titlecase(Locale.ROOT) }
        if (result.length > MAX_LENGTH) {
            val boundary = result.lastIndexOf(' ', MAX_LENGTH - 1)
            if (boundary <= 0) return null
            result = result.substring(0, boundary).trimEnd(' ', ',', ';', ':', '.', '!', '?', '—', '–', '-') + "…"
        }
        return result
    }
}
