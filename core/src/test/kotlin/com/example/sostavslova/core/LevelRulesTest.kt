package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
        val campaign = TestData.campaign
        if (campaign != null) {
            val errors = ProductionCampaignValidator.validate(campaign, TestData.campaignProfile)
            assertTrue(errors.isEmpty(), errors.joinToString())
            for (level in campaign.levels) {
                assertTrue(TestData.index.contains(level.word), "нет исходника v2 ${level.word}")
                val game = GameState.start(level.word, TestData.index)
                val all = CurrentCampaignLevel(level, game.copy(found = game.allWords))
                val evaluated = TaskRules.evaluate(all, campaign.countMinLength)
                assertEquals(level.maxStars, evaluated.stars, "Недостижимые задания v2 ${level.word}")
            }
            return
        }
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
    }

}
