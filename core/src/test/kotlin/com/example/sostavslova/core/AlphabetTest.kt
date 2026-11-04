package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AlphabetTest {
    @Test
    fun normalizeFoldsYoAndDropsForeignChars() {
        assertEquals("порталеж", Alphabet.normalize("ПортАл ЁЖ-1 abc"))
        assertEquals("еж", Alphabet.normalize("ёж"))
    }

    @Test
    fun alphabetHas32LettersWithoutYo() {
        assertEquals(32, Alphabet.LETTERS.length)
        assertEquals(-1, Alphabet.indexOf('ё'))
        assertEquals(-1, Alphabet.indexOf('c')) // латинская c - не буква игры
        assertEquals(17, Alphabet.indexOf('с')) // кириллическая с
    }

    @Test
    fun parseWordInputValidatesLength() {
        assertIs<WordInput.Empty>(parseWordInput("   123 "))
        assertIs<WordInput.TooShort>(parseWordInput("до"))
        assertIs<WordInput.TooLong>(parseWordInput("а".repeat(31)))
        assertEquals("портал", (parseWordInput(" Портал ") as WordInput.Ok).word)
    }
}
