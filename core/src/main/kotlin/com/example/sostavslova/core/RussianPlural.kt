package com.example.sostavslova.core

import kotlin.math.abs

/**
 * Склонение существительных после числительных.
 *
 * ИСПРАВЛЕНИЕ ОШИБКИ ОРИГИНАЛА: в PHP-версии Vocabulary::getNameWords() список «слова» не содержал
 * чисел 2, 3, 4 (получалось «найдено 3 слов»), а для чисел больше 161 правило не работало вообще.
 */
object RussianPlural {
    fun form(n: Int, one: String, few: String, many: String): String {
        val mod100 = abs(n) % 100
        val mod10 = mod100 % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    fun words(n: Int): String = form(n, "слово", "слова", "слов")
    fun letters(n: Int): String = form(n, "буква", "буквы", "букв")

    /** «3 слова», «21 слово», «5 слов». */
    fun countWords(n: Int): String = "$n ${words(n)}"
}
