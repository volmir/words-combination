package com.example.sostavslova.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertNull(s.hintWord())
    }

    @Test
    fun hintIsNeverAnAlreadyFoundWord() {
        val s = start().submit("рот").state
        repeat(50) { assertTrue(s.hintWord(Random(it)) in listOf("тор", "тот")) }
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
    fun restoredAnswersAreFilteredToValidOnes() {
        val s = GameState.start("торт", index, restoredFound = listOf("рот", "рот", "нос"))
        assertEquals(listOf("рот"), s.found)
    }

    @Test
    fun wordWithNoBuildableWordsIsNotFinished() {
        val s = GameState.start("ааа", index)
        assertTrue(s.hasNoWords)
        assertFalse(s.isFinished)
    }
}
