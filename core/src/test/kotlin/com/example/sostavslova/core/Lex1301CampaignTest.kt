package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LEX13-01: «вероника» заменена на «ровесник» в T3 уровня 13, T5 не изменён. */
class Lex1301CampaignTest {
    private val campaign = requireNotNull(TestData.campaign) { "В data/manifest.json нет campaign_source" }
    private val level = campaign.levels.single { it.number == 13 }
    private val answers = TestData.index.findBuildable(level.word)

    @Test
    fun rovesnikIsTheT3TargetAndVeronikaStaysAnOrdinaryAnswer() {
        assertEquals(20103, TestData.index.size)
        assertTrue(TestData.index.contains("вероника"))
        assertTrue(TestData.index.contains("ровесник"))
        assertEquals("складирование", level.word)

        val t3 = level.tasks.filterIsInstance<CampaignTask.T3>().single()
        assertEquals("ровесник", t3.hintTarget)
        assertEquals(8, t3.minLength)
        assertEquals(8, t3.hintTarget.length)

        val game = GameState(level.word, answers)
        assertEquals(AnswerResult.CORRECT, game.submit("ровесник", TestData.index).result)
        assertEquals(AnswerResult.CORRECT, game.submit("вероника", TestData.index).result)
    }

    @Test
    fun t3StillAcceptsEveryOtherLongAnswer() {
        val t3 = level.tasks.filterIsInstance<CampaignTask.T3>().single()
        val other = answers.first { it.length >= t3.minLength && it != t3.hintTarget }
        val submission = TaskRules.submit(CurrentCampaignLevel(level, GameState(level.word, answers)),
            other, campaign.countMinLength, TestData.index)
        assertEquals(AnswerResult.CORRECT, submission.result)
        assertTrue(t3.id in submission.evaluation.newlyCompletedTaskIds)
    }

    @Test
    fun t5ConfigurationIsUnchangedAndRemainsReachable() {
        val t5 = level.tasks.filterIsInstance<CampaignTask.T5>().single()
        assertEquals("ние", t5.ending)
        assertEquals(6, t5.minLength)
        assertEquals(1, level.tasks.count { it.id == "l13-t5" })

        val accepted = answers.filter { it.length >= t5.minLength && it.endsWith(t5.ending) }.toSet()
        assertTrue(accepted.isNotEmpty())
        assertFalse("вероника" in accepted)

        val answer = accepted.first()
        val submission = TaskRules.submit(CurrentCampaignLevel(level, GameState(level.word, answers)),
            answer, campaign.countMinLength, TestData.index)
        assertEquals(AnswerResult.CORRECT, submission.result)
        assertTrue(t5.id in submission.evaluation.newlyCompletedTaskIds)
    }
}
