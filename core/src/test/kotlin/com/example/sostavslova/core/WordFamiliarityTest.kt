package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WordFamiliarityTest {
    @Test fun parsesTiersInDictionaryOrderAndUnknownWordHasNoTier() {
        val familiarity = WordFamiliarity.fromText(listOf("кот", "крот", "колея", "станция"), "0\n1\n2\n3\n")
        assertEquals(listOf(0, 1, 2, 3), listOf("кот", "крот", "колея", "станция").map(familiarity::tier))
        assertNull(familiarity.tier("несуществующее"))
        assertEquals(2, WordFamiliarity.fromText(listOf("кот"), "2").tier("кот"))
        assertEquals(3, WordFamiliarity.fromText(listOf("кот"), "3\r\n").tier("кот"))
    }

    @Test fun rejectsWrongLengthAndMalformedRows() {
        for (text in listOf("", "1\n2\n", "4", "-1", "12", "x", "1\n\n")) {
            assertFailsWith<IllegalArgumentException>(text) {
                WordFamiliarity.fromText(listOf("кот"), text)
            }
        }
        assertNull(WordFamiliarity.fromText(emptyList(), "").tier("кот"))
    }
}
