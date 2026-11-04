package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RussianPluralTest {
    @Test
    fun wordsForms() {
        val expected = mapOf(
            0 to "слов", 1 to "слово", 2 to "слова", 3 to "слова", 4 to "слова", 5 to "слов",
            11 to "слов", 12 to "слов", 14 to "слов", 21 to "слово", 22 to "слова", 25 to "слов",
            101 to "слово", 102 to "слова", 111 to "слов", 112 to "слов", 121 to "слово", 394 to "слова",
        )
        for ((n, form) in expected) assertEquals(form, RussianPlural.words(n), "n=$n")
    }

    @Test
    fun countWords() {
        assertEquals("3 слова", RussianPlural.countWords(3))
    }
}
