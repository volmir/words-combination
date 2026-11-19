package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStateTest {
    private val index = WordIndex(listOf("рот", "тор", "торт", "тот", "трон", "нос"))
    private fun start() = GameState.start("торт", index)

    @Test
    fun startCollectsBuildableWordsFrom3Letters() {
        // из «торт»: рот, тор, тот. «трон»/«нос» невозможны
        assertEquals(listOf("рот", "тор", "тот"), start().allWords)
    }

    @Test
    fun submitFlow() {
        var s = start()
        var sub = s.submit(" РОТ ")
        assertEquals(AnswerResult.CORRECT, sub.result); s = sub.state
        sub = s.submit("рот")
        assertEquals(AnswerResult.ALREADY_FOUND, sub.result)
        assertEquals(AnswerResult.NOT_FOUND, s.submit("нос").result)
        assertEquals(AnswerResult.SAME_AS_SOURCE, s.submit("торт").result)
        assertEquals(AnswerResult.TOO_SHORT, s.submit("то").result)
        assertEquals(AnswerResult.EMPTY, s.submit("123").result)
        assertEquals(1, s.foundCount)
        assertFalse(s.isFinished)
    }

    @Test
    fun finishesWhenAllFound() {
        var s = start()
        for (w in listOf("рот", "тор", "тот")) s = s.submit(w).state
        assertTrue(s.isFinished)
    }

    @Test
    fun statisticsByLength() {
        val s = start().submit("рот").state
        val st = s.statistics()
        assertEquals(1, st.rows.size)
        assertEquals(LengthStat(3, 1, 3), st.rows[0])
        assertEquals(33, st.totalPercent)
        assertEquals(33, st.rows[0].percent)
    }

    @Test
    fun percentDoesNotRoundUpTo100() {
        assertEquals(99, LengthStat(3, 199, 200).percent)
        assertEquals(0, LengthStat(3, 1, 200).percent)
        assertEquals(100, LengthStat(3, 200, 200).percent)
        assertEquals(0, LengthStat(3, 0, 0).percent)
        assertEquals(99, Statistics(listOf(LengthStat(3, 199, 200))).totalPercent)
        assertEquals(0, Statistics(listOf(LengthStat(3, 1, 200))).totalPercent)
        assertEquals(100, Statistics(listOf(LengthStat(3, 200, 200))).totalPercent)
        assertEquals(0, Statistics(emptyList()).totalPercent)
    }

    @Test
    fun restoredAnswersAreFilteredToValidOnes() {
        val s = GameState.start("торт", index, restoredFound = listOf("рот", "рот", "нос"))
        assertEquals(listOf("рот"), s.found)
    }

    @Test
    fun restoringSnapshotReusesAnswersAndReplacesFoundWithValidDistinctWords() {
        val original = start().submit("рот").state
        val restored = original.restoreFound(listOf("тор", "нос", "тор", "торт"))
        assertEquals(listOf("тор"), restored.found)
        assertTrue(original.allWords === restored.allWords)
        assertEquals(listOf("рот"), original.found)
        assertEquals(emptyList(), restored.restoreFound(emptyList()).found)
    }

    @Test
    fun wordWithNoBuildableWordsIsNotFinished() {
        val s = GameState.start("ааа", index)
        assertTrue(s.hasNoWords)
        assertFalse(s.isFinished)
    }

    @Test
    fun countedFoundCountHandlesEmptyShortOnlyAndMixedFoundWords() {
        val allWords = listOf("рот", "тор", "крот", "торт", "колея", "елка")
        assertEquals(0, GameState("тест", allWords, emptyList()).countedFoundCount(4))
        assertEquals(0, GameState("тест", allWords, listOf("рот", "тор")).countedFoundCount(4))

        val mixed = GameState("тест", allWords, listOf("рот", "тор", "крот", "торт", "колея"))
        assertEquals(3, mixed.countedFoundCount(4))
        assertEquals(1, mixed.countedFoundCount(5))
        assertEquals(5, mixed.countedFoundCount(3))

        val unnormalized = GameState("тест", allWords, listOf(" кот ", " ЁЛКА ", "елка"))
        assertEquals(1, unnormalized.countedFoundCount(4))
    }
}
