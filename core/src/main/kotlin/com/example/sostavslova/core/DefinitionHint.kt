package com.example.sostavslova.core

import java.text.Normalizer
import java.util.Locale

/** Подготовка подсказки; полное толкование для экрана значения не изменяется. */
object DefinitionHint {
    const val VERSION = "core-definition-hint-v2"
    const val MASK = "***"
    private val russianToken = Regex("[А-Яа-яЁё]+(?:[\u0300\u0301][А-Яа-яЁё]*)*")
    private val relativePronoun = Regex("котор(?:ый|ая|ое|ые|ого|ой|ых|ому|ым|ыми|ую)")
    private val labelPrefix = Regex("^(?:(?:разг|перен|книжн|устар|спец|собир|истор|поэт|прост)\\.\\s*[,;]?\\s*)+")
    // Явная Unicode-граница: обычная \b на JVM 21 не учитывает кириллицу.
    private val wordEnd = "(?![\\p{L}\\p{N}_])"
    private val reference = Regex("^(?:то же,? что$wordEnd|см\\.|(?:уменьш|ласк|увелич)\\.|форма$wordEnd|действие по значению|женск\\.)")
    private val link = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private val stopWords = setOf("для", "при", "или", "это", "его", "еще", "как", "что", "так", "тот", "эта", "эти",
        "тем", "чем", "под", "над", "без", "между", "около", "возле", "через", "после", "перед", "также", "обычно",
        "один", "одна", "одно", "одного", "либо", "того", "этого", "когда", "если", "чтобы", "свой", "своей",
        "разг", "перен", "спец", "собир", "истор", "поэт", "книжн")

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace("\u0300", "").replace("\u0301", "").lowercase(Locale.ROOT).replace('ё', 'е')

    /** Эвристика дополняет наблюдаемые формы и родство, а не заменяет морфологический словарь. */
    fun sharesRoot(left: String, right: String): Boolean {
        return sharesNormalizedRoot(normalize(left), normalize(right))
    }

    private fun sharesNormalizedRoot(a: String, b: String): Boolean {
        val shorter = minOf(a.length, b.length)
        if (shorter < 3) return a.isNotEmpty() && a == b
        var common = 0
        while (common < shorter && a[common] == b[common]) common++
        return common >= 4 || common.toDouble() / shorter >= 0.7
    }

    fun prepare(answer: String, text: String, forms: Collection<String> = emptyList(),
                related: Collection<String> = emptyList()): String? {
        val cleaned = HintText.clean(text) ?: return null
        return prepareUncleaned(answer, cleaned, forms, related)
    }

    /** Снимок прежнего поведения для сравнения в офлайн-аудите. Игра использует prepare. */
    internal fun prepareUncleaned(answer: String, text: String, forms: Collection<String> = emptyList(),
                                  related: Collection<String> = emptyList()): String? {
        if (text.isBlank() || text.length > 4000 || '<' in text || '>' in text || link.containsMatchIn(text)) return null
        val displayText = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        val normalized = normalize(displayText)
        if (reference.containsMatchIn(normalized.replace(labelPrefix, ""))) return null
        val family = (forms + related + answer).map(::normalize).filter { it.isNotEmpty() }.toSet()
        var maskedCount = 0
        val result = russianToken.replace(displayText) { match ->
            val token = normalize(match.value)
            val exact = token in family
            val root = token !in stopWords && !relativePronoun.matches(token) && family.any { sharesNormalizedRoot(it, token) }
            if (exact || root) {
                maskedCount++
                MASK
            } else match.value
        }
        val remaining = russianToken.findAll(result).map { normalize(it.value) }
            .filter { it.length >= 3 && it !in stopWords && !relativePronoun.matches(it) }.toList()
        if (remaining.size < 2 || remaining.sumOf { it.length } < 12 || maskedCount > remaining.size) return null
        return result
    }
}
