package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelRulesTest {
    private val levels = TestData.levels

    @Test
    fun thirtyLevelsAllWithCyrillicLetters() {
        assertEquals(30, levels.size)
        for (l in levels) assertTrue(Alphabet.indexOf(l.letter) >= 0, "Уровень ${l.word}: буква '${l.letter}' не из алфавита")
    }

    @Test
    fun everyLevelIsReachable() {
        for (l in levels) {
            val all = TestData.index.findBuildable(l.word).filter { it.length >= 3 }
            assertTrue(TestData.index.contains(l.word), "нет слова уровня ${l.word}")
            assertTrue(all.size >= l.words, "${l.word}: доступно ${all.size}, нужно ${l.words}")
            val first = all.count { it[0] == l.letter }
            assertTrue(first >= l.letterCount, "${l.word}: на «${l.letter}» ${first} из ${l.letterCount}")
        }
    }

    @Test
    fun evaluateAndStarsNeverDecrease() {
        val l = Level(1, "торт", answers = 2, words = 3, letter = 'т', letterCount = 2)
        assertEquals(LevelProgress(), LevelRules.evaluate(l, emptyList()))
        val p1 = LevelRules.evaluate(l, listOf("тор", "тот"))
        assertEquals(LevelProgress(completed = true, wordsTask = false, lettersTask = true), p1)
        val merged = p1 merge LevelRules.evaluate(l, emptyList())
        assertEquals(2, merged.stars)
        assertEquals(0, LevelRules.remainingToComplete(l, listOf("тор", "тот")))
        assertEquals(1, LevelRules.remainingToComplete(l, listOf("тор")))
    }

    @Test
    fun unlockSequentially() {
        val p = mutableMapOf<String, LevelProgress>()
        assertTrue(LevelRules.isUnlocked(levels, p, 0))
        assertFalse(LevelRules.isUnlocked(levels, p, 1))
        p[levels[0].word] = LevelProgress(completed = true)
        assertTrue(LevelRules.isUnlocked(levels, p, 1))
        assertFalse(LevelRules.isUnlocked(levels, p, 2))
        assertFalse(LevelRules.isUnlocked(levels, p, 999))
    }
}
