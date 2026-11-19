package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetterLayoutTest {
    @Test fun balancedRowsKeepCountAndSpreadLettersEvenlyForThreeToFifteen() {
        for (count in 3..15) {
            for (maxPerRow in 1..15) {
                val rows = balancedRows(count, maxPerRow)
                val context = "count=$count, maxPerRow=$maxPerRow, rows=$rows"
                assertEquals(count, rows.sum(), context)
                assertTrue(rows.all { it in 1..maxPerRow }, context)
                assertTrue(rows.max() - rows.min() <= 1, context)
            }
        }
    }

    @Test fun thirteenLettersBecomeSevenAndSixInsteadOfEightAndFive() {
        assertEquals(listOf(7, 6), balancedRows(13, 7))
        assertEquals(listOf(7, 6), balancedRows(13, 8))
        assertEquals(listOf(8, 7), balancedRows(15, 8))
        assertEquals(listOf(6, 5), balancedRows(11, 8))
        assertEquals(listOf(3), balancedRows(3, 15))
    }

    @Test fun emptyCountHasNoRows() {
        assertEquals(emptyList(), balancedRows(0, 7))
    }
}
