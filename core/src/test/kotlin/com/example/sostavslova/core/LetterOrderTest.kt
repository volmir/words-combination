package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LetterOrderTest {
    @Test fun naturalOrderUsesEveryOriginalIndex() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LetterOrder.natural("ёлочка"))
        assertEquals(emptyList(), LetterOrder.natural(""))
        assertEquals(listOf(0), LetterOrder.natural("я"))
    }

    @Test fun shuffleIsDeterministicAndPreservesEveryIndex() {
        for (word in listOf("", "я", "аа", "молоко", "электростанция")) {
            val previous = LetterOrder.natural(word)
            for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 42L, Long.MAX_VALUE)) {
                val shuffled = LetterOrder.shuffled(word, seed, previous)
                assertEquals(previous, shuffled.sorted())
                assertEquals(shuffled, LetterOrder.shuffled(word, seed, previous))
                if (word.length > 1) assertNotEquals(previous, shuffled)
            }
        }
    }

    @Test fun matchingPreviousIsRotatedEvenWhenLettersRepeat() {
        val word = "аааа"
        val candidate = LetterOrder.shuffled(word, 42L, emptyList())
        assertEquals(candidate.drop(1) + candidate.first(), LetterOrder.shuffled(word, 42L, candidate))
    }

    @Test fun consecutiveShufflesAlwaysChangeOrder() {
        for (word in listOf("аа", "молоко")) {
            var previous = LetterOrder.natural(word)
            repeat(100) { seed ->
                val shuffled = LetterOrder.shuffled(word, seed.toLong(), previous)
                assertNotEquals(previous, shuffled)
                assertEquals(LetterOrder.natural(word), shuffled.sorted())
                previous = shuffled
            }
        }
    }
}
