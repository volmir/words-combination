package com.example.sostavslova.core

/**
 * Русский алфавит игры: 32 буквы. Буква «ё» всегда сворачивается в «е»
 * (в оригинале ё и е были разными буквами, из-за чего «ёж» и «еж» не совпадали).
 */
object Alphabet {
    const val LETTERS = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    const val SIZE = 32
    const val MIN_WORD_LENGTH = 3
    const val MAX_WORD_LENGTH = 30

    private val indexByChar: Map<Char, Int> = LETTERS.withIndex().associate { (i, c) -> c to i }

    /** Индекс буквы 0..31 или -1, если символ не входит в алфавит. */
    fun indexOf(c: Char): Int = indexByChar[c] ?: -1

    /**
     * Приводит ввод пользователя к каноническому виду: нижний регистр, ё -> е,
     * все символы кроме русских букв (цифры, дефисы, латиница, пробелы) отбрасываются.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            val c = if (ch == 'ё') 'е' else ch
            if (indexByChar.containsKey(c)) sb.append(c)
        }
        return sb.toString()
    }
}

/** Результат проверки слова, введённого для начала игры или поиска. */
sealed class WordInput {
    data class Ok(val word: String) : WordInput()
    object Empty : WordInput()
    object TooShort : WordInput()
    object TooLong : WordInput()
}

fun parseWordInput(raw: String): WordInput {
    val word = Alphabet.normalize(raw)
    return when {
        word.isEmpty() -> WordInput.Empty
        word.length < Alphabet.MIN_WORD_LENGTH -> WordInput.TooShort
        word.length > Alphabet.MAX_WORD_LENGTH -> WordInput.TooLong
        else -> WordInput.Ok(word)
    }
}
