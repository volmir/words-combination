package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileUsageTest {
    private val word = "молоко"
    // о, о, м, о, л, к
    private val order = listOf(5, 1, 0, 3, 2, 4)

    @Test fun repeatedLettersOccupyFirstTilesInDisplayOrder() {
        assertEquals(listOf(true, true, false, false, true, false), TileUsage.usedMask(word, order, "оло"))
        assertTrue(TileUsage.canTake(word, order, "оло"))
        assertEquals(listOf(true, true, true, true, true, true), TileUsage.usedMask(word, order, "молоко"))
        assertFalse(TileUsage.canTake(word, order, "молоко"))
    }

    @Test fun excessiveInputIsCappedPerLetterAndDoesNotHideFreeTiles() {
        assertEquals(listOf(true, true, false, true, false, false), TileUsage.usedMask(word, order, "оооооооооя"))
        assertTrue(TileUsage.canTake(word, order, "оооооооооя"))
        assertEquals(List(6) { true }, TileUsage.usedMask(word, order, "молокомолокояя"))
        assertFalse(TileUsage.canTake(word, order, "молокомолокояя"))
    }

    @Test fun emptyInputLeavesAllTilesFree() {
        assertEquals(List(6) { false }, TileUsage.usedMask(word, order, ""))
        assertTrue(TileUsage.canTake(word, order, ""))
        assertEquals(emptyList(), TileUsage.usedMask("", emptyList(), "слово"))
        assertFalse(TileUsage.canTake("", emptyList(), ""))
    }

    @Test fun normalizationHandlesYoAndCaseInWordAndInput() {
        val order = listOf(1, 2, 0, 3)
        assertEquals(listOf(true, true, false, false), TileUsage.usedMask("ЁеЕж", order, "еЁ"))
        assertEquals(listOf(true, true, true, true), TileUsage.usedMask("ЁеЕж", order, "ЕёеЖ"))
        assertFalse(TileUsage.canTake("ЁеЕж", order, "ЕёеЖ"))
        assertEquals(listOf(false, false, false, true), TileUsage.usedMask("ЁеЕж", order, " Ж-1x "))
    }
}
