package com.example.sostavslova.core

/** Необязательный индекс узнаваемости; порядок строк соответствует words.txt. */
class WordFamiliarity private constructor(private val tiers: Map<String, Int>) {
    fun tier(word: String): Int? = tiers[word]

    companion object {
        fun fromText(words: List<String>, text: String): WordFamiliarity {
            val rows = if (text.isEmpty()) emptyList() else text.lineSequence().toList().let {
                if (it.last().isEmpty()) it.dropLast(1) else it
            }
            require(rows.size == words.size) { "Число оценок узнаваемости не совпадает со словарём" }
            val tiers = rows.map { row ->
                require(row.length == 1 && row[0] in '0'..'3') { "Некорректная оценка узнаваемости" }
                row[0].digitToInt()
            }
            return WordFamiliarity(words.zip(tiers).toMap())
        }
    }
}
